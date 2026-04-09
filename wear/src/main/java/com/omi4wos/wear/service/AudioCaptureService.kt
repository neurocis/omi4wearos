package com.omi4wos.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omi4wos.shared.AudioChunk
import com.omi4wos.shared.Constants
import com.omi4wos.wear.MainActivity
import com.omi4wos.wear.R
import com.omi4wos.wear.audio.AudioRecorder
import com.omi4wos.wear.audio.CircularAudioBuffer
import com.omi4wos.wear.audio.OpusEncoder
import com.omi4wos.wear.audio.SpeechClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service for continuous audio recording, speech classification,
 * and forwarding speech segments to the phone companion app.
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"

        const val ACTION_START = "com.omi4wos.wear.ACTION_START"
        const val ACTION_STOP = "com.omi4wos.wear.ACTION_STOP"

        // Observable state for UI
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _isSpeechDetected = MutableStateFlow(false)
        val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected

        private val _isPhoneConnected = MutableStateFlow(false)
        val isPhoneConnected: StateFlow<Boolean> = _isPhoneConnected
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var classificationJob: Job? = null

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var speechClassifier: SpeechClassifier
    private lateinit var circularBuffer: CircularAudioBuffer
    private lateinit var opusEncoder: OpusEncoder
    private lateinit var dataLayerSender: DataLayerSender
    private lateinit var chunkRepository: ChunkRepository

    private var wakeLock: PowerManager.WakeLock? = null

    // Speech segment tracking
    private var speechStartTimeMs: Long = 0L
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private var currentSegmentId: String = ""
    private var chunkIndex = 0
    private var isInSpeechSegment = false


    // Thresholds for hysteresis
    private val speechOffsetFrames = 3 // Need 3 consecutive silence frames to end (Silero windows ~1.2s each)

    // Dynamic Conversation Tracking
    private var lastValidSpeechEndTimeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioRecorder = AudioRecorder()
        speechClassifier = SpeechClassifier(this)
        circularBuffer = CircularAudioBuffer(Constants.CIRCULAR_BUFFER_SAMPLES)
        opusEncoder = OpusEncoder()
        dataLayerSender = DataLayerSender(this)
        chunkRepository = ChunkRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (_isRecording.value) return

        Log.i(TAG, "Starting audio capture")

        // Start foreground with notification
        val notification = createNotification("Monitoring for speech…")
        startForeground(
            Constants.WEAR_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        // Acquire wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "omi4wos:audio_capture"
        ).apply { acquire() }

        // Start audio recording
        audioRecorder.start { samples ->
            // Called on the recording thread with each buffer of samples
            circularBuffer.write(samples)
        }

        _isRecording.value = true

        // Check phone connectivity and setup background synchronizer
        serviceScope.launch {
            while (isActive) {
                dataLayerSender.checkConnectivity()
                val connected = dataLayerSender.isConnected
                _isPhoneConnected.value = connected
                if (connected) {
                    syncPendingChunks()
                }
                delay(Constants.CONNECTIVITY_POLL_INTERVAL_MS)
            }
        }

        // Start classification loop
        classificationJob = serviceScope.launch {
            classificationLoop()
        }
    }

    private fun stopCapture() {
        Log.i(TAG, "Stopping audio capture")

        classificationJob?.cancel()
        classificationJob = null

        audioRecorder.stop()
        opusEncoder.release()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        _isRecording.value = false
        _isSpeechDetected.value = false
        isInSpeechSegment = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Main classification loop: reads audio windows from the circular buffer,
     * classifies with WebRTC VAD, and handles speech segment extraction.
     * Slows to half-rate after 5 minutes of silence to reduce CPU usage.
     */
    private suspend fun classificationLoop() {
        val windowSamples = Constants.WEBRTC_INPUT_SAMPLES
        val windowBuffer = ShortArray(windowSamples)
        var lastSpeechDetectedMs = System.currentTimeMillis()

        while (serviceScope.isActive) {
            // Wait until we have enough samples
            if (circularBuffer.availableSamples() < windowSamples) {
                delay(50)
                continue
            }

            // Read the latest window from circular buffer
            circularBuffer.readLatest(windowBuffer, windowSamples)

            // Check loudness
            val rmsDb = calculateRmsDb(windowBuffer)
            if (rmsDb < Constants.LOUDNESS_THRESHOLD_DB) {
                handleSilenceFrame()
                val interval = classificationInterval(lastSpeechDetectedMs)
                delay(interval)
                continue
            }

            // Classify with WebRTC VAD
            val result = speechClassifier.classify(windowBuffer)

            if (result.isSpeech) {
                lastSpeechDetectedMs = System.currentTimeMillis()
                handleSpeechFrame(result.confidence)
            } else {
                handleSilenceFrame()
            }

            delay(classificationInterval(lastSpeechDetectedMs))
        }
    }

    /**
     * Returns the classification poll interval. Doubles to 1920ms after 5 minutes of inactivity
     * to reduce CPU wake cycles during extended silence.
     */
    private fun classificationInterval(lastSpeechDetectedMs: Long): Long {
        val idleMs = System.currentTimeMillis() - lastSpeechDetectedMs
        return if (idleMs > Constants.IDLE_SLOWDOWN_AFTER_MS) {
            Constants.IDLE_CLASSIFICATION_INTERVAL_MS
        } else {
            Constants.CLASSIFICATION_INTERVAL_MS
        }
    }

    private fun handleSpeechFrame(confidence: Float) {
        consecutiveSpeechFrames++
        consecutiveSilenceFrames = 0

        val timeSinceLastSpeech = System.currentTimeMillis() - lastValidSpeechEndTimeMs
        val isConversationActive = timeSinceLastSpeech < Constants.CONVERSATION_TIMEOUT_MS
        val currentOnsetFrames = if (isConversationActive) 1 else 2 // Silero is accurate — 1 positive window in ACTIVE, 2 in IDLE

        if (!isInSpeechSegment && consecutiveSpeechFrames >= currentOnsetFrames) {
            // Start new speech segment
            isInSpeechSegment = true
            speechStartTimeMs = System.currentTimeMillis()
            currentSegmentId = java.util.UUID.randomUUID().toString().take(8)
            chunkIndex = 0
            _isSpeechDetected.value = true

            updateNotification("Speech detected")
            Log.d(TAG, "Speech segment started: $currentSegmentId (conf=$confidence)")

            // Rewind the encoding pointer to capture pre-roll, then send it
            val preRollSamples = (Constants.SAMPLE_RATE * Constants.PRE_ROLL_SECONDS).toInt()
            circularBuffer.rewindReadPos(preRollSamples)
            extractAndSendPreRoll()
        }

        if (isInSpeechSegment) {
            // Extract and send current newly captured audio chunks
            extractAndSendChunk(confidence, isFinal = false)

            // Check if we need to force end due to maximum recording length
            val elapsed = System.currentTimeMillis() - speechStartTimeMs
            if (elapsed > Constants.MAX_SPEECH_SEGMENT_SECONDS * 1000L) {
                Log.d(TAG, "Max segment duration reached, forcing end")
                extractAndSendChunk(0f, isFinal = true)
                isInSpeechSegment = false
                _isSpeechDetected.value = false
                updateNotification("Monitoring for speech…")
            }
        }
    }

    private fun handleSilenceFrame() {
        consecutiveSilenceFrames++
        consecutiveSpeechFrames = 0

        if (isInSpeechSegment && consecutiveSilenceFrames >= speechOffsetFrames) {
            // End speech segment
            val duration = System.currentTimeMillis() - speechStartTimeMs

            isInSpeechSegment = false
            _isSpeechDetected.value = false
            updateNotification("Monitoring for speech…")

            val timeSinceLastSpeech = System.currentTimeMillis() - lastValidSpeechEndTimeMs
            val isConversationActive = timeSinceLastSpeech < Constants.CONVERSATION_TIMEOUT_MS
            val currentMinDuration = if (isConversationActive) Constants.MIN_SPEECH_DURATION_ACTIVE_MS else Constants.MIN_SPEECH_DURATION_IDLE_MS

            if (duration >= currentMinDuration) {
                // Send final chunk marker
                extractAndSendChunk(0f, isFinal = true)
                Log.d(TAG, "Speech segment ended: $currentSegmentId (${duration}ms) - State: ${if(isConversationActive) "ACTIVE" else "IDLE"}")
                lastValidSpeechEndTimeMs = System.currentTimeMillis()
            } else {
                Log.d(TAG, "Speech segment too short, discarding: ${duration}ms < ${currentMinDuration}ms")
            }
            // Reset Silero LSTM state so residual context from this segment doesn't
            // bleed into the next detection window
            speechClassifier.resetState()
        }
    }

    private fun syncPendingChunks() {
        val files = chunkRepository.getPendingChunkFiles()
        if (files.isEmpty()) return

        serviceScope.launch {
            for (file in files) {
                val chunk = chunkRepository.readChunk(file)
                if (chunk != null) {
                    val success = dataLayerSender.sendAudioChunk(chunk)
                    if (success) {
                        chunkRepository.deleteChunkFile(file)
                    } else {
                        // Connection failed or dropped mid-sync, gracefully abort to retry later
                        Log.w(TAG, "Sync aborted, connection lost during transmission")
                        break
                    }
                } else {
                    // File was corrupted or unreadable, discard
                    chunkRepository.deleteChunkFile(file)
                }
            }
        }
    }

    private fun extractAndSendPreRoll() {
        val unreadBuffer = circularBuffer.consumeUnread()
        if (unreadBuffer.isEmpty()) return

        val encoded = opusEncoder.encode(unreadBuffer)
        if (encoded != null) {
            val chunk = AudioChunk(
                audioData = encoded,
                timestampMs = speechStartTimeMs - (Constants.PRE_ROLL_SECONDS * 1000).toLong(),
                durationMs = (Constants.PRE_ROLL_SECONDS * 1000).toLong(),
                speechConfidence = 0f,
                chunkIndex = chunkIndex++,
                segmentId = currentSegmentId,
                isFinal = false
            )
            chunkRepository.saveChunk(chunk)
        }
    }

    private fun extractAndSendChunk(confidence: Float, isFinal: Boolean) {
        val unreadBuffer = circularBuffer.consumeUnread()
        if (unreadBuffer.isEmpty() && !isFinal) return

        val encoded = if (unreadBuffer.isNotEmpty()) opusEncoder.encode(unreadBuffer) else null
        if (encoded != null || isFinal) {
            val chunk = AudioChunk(
                audioData = encoded ?: ByteArray(0),
                timestampMs = System.currentTimeMillis(),
                durationMs = Constants.CLASSIFICATION_INTERVAL_MS,
                speechConfidence = confidence,
                chunkIndex = chunkIndex++,
                segmentId = currentSegmentId,
                isFinal = isFinal
            )
            chunkRepository.saveChunk(chunk)
            
            // Blast the absolute payload completely across BLE natively on sentence completion
            if (isFinal) {
                syncPendingChunks()
            }
        }
    }

    private fun calculateRmsDb(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        val rms = Math.sqrt(sum / samples.size)
        return if (rms > 0) 20.0 * Math.log10(rms) + 90.0 else 0.0 // dB SPL approximation
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.WEAR_NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.WEAR_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(Constants.WEAR_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopCapture()
        speechClassifier.close()
        serviceScope.cancel()
        super.onDestroy()
    }
}
