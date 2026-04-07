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

    private var wakeLock: PowerManager.WakeLock? = null

    // Speech segment tracking
    private var speechStartTimeMs: Long = 0L
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private var currentSegmentId: String = ""
    private var chunkIndex = 0
    private var isInSpeechSegment = false

    // Thresholds for hysteresis
    private val speechOnsetFrames = 2 // Need 2 consecutive speech frames to trigger
    private val speechOffsetFrames = 5 // Need 5 consecutive silence frames to end

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioRecorder = AudioRecorder()
        speechClassifier = SpeechClassifier(this)
        circularBuffer = CircularAudioBuffer(Constants.CIRCULAR_BUFFER_SAMPLES)
        opusEncoder = OpusEncoder()
        dataLayerSender = DataLayerSender(this)
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

        // Check phone connectivity
        serviceScope.launch {
            dataLayerSender.checkConnectivity()
            _isPhoneConnected.value = dataLayerSender.isConnected
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
     * classifies with YAMNet, and handles speech segment extraction.
     */
    private suspend fun classificationLoop() {
        val windowSamples = Constants.YAMNET_INPUT_SAMPLES // 15600 samples = 0.975s
        val windowBuffer = ShortArray(windowSamples)
        val floatBuffer = FloatArray(windowSamples)

        while (serviceScope.isActive) {
            // Wait until we have enough samples
            if (circularBuffer.availableSamples() < windowSamples) {
                delay(50)
                continue
            }

            // Read the latest window from circular buffer
            circularBuffer.readLatest(windowBuffer, windowSamples)

            // Convert to float [-1.0, 1.0] for YAMNet
            for (i in windowBuffer.indices) {
                floatBuffer[i] = windowBuffer[i] / 32768.0f
            }

            // Check loudness
            val rmsDb = calculateRmsDb(windowBuffer)
            if (rmsDb < Constants.LOUDNESS_THRESHOLD_DB) {
                handleSilenceFrame()
                delay(Constants.CLASSIFICATION_INTERVAL_MS)
                continue
            }

            // Classify with YAMNet
            val result = speechClassifier.classify(floatBuffer)

            if (result.isSpeech) {
                handleSpeechFrame(result.confidence)
            } else {
                handleSilenceFrame()
            }

            delay(Constants.CLASSIFICATION_INTERVAL_MS)
        }
    }

    private fun handleSpeechFrame(confidence: Float) {
        consecutiveSpeechFrames++
        consecutiveSilenceFrames = 0

        if (!isInSpeechSegment && consecutiveSpeechFrames >= speechOnsetFrames) {
            // Start new speech segment
            isInSpeechSegment = true
            speechStartTimeMs = System.currentTimeMillis()
            currentSegmentId = UUID.randomUUID().toString().take(8)
            chunkIndex = 0
            _isSpeechDetected.value = true

            updateNotification("Speech detected")
            Log.d(TAG, "Speech segment started: $currentSegmentId (conf=$confidence)")

            // Extract pre-roll audio from circular buffer
            extractAndSendPreRoll()
        }

        if (isInSpeechSegment) {
            // Extract and send current audio chunk
            extractAndSendChunk(confidence, isFinal = false)
        }
    }

    private fun handleSilenceFrame() {
        consecutiveSilenceFrames++
        consecutiveSpeechFrames = 0

        if (isInSpeechSegment && consecutiveSilenceFrames >= speechOffsetFrames) {
            // End speech segment
            val duration = System.currentTimeMillis() - speechStartTimeMs

            if (duration >= Constants.MIN_SPEECH_DURATION_MS) {
                // Send final chunk marker
                extractAndSendChunk(0f, isFinal = true)
                Log.d(TAG, "Speech segment ended: $currentSegmentId (${duration}ms)")
            } else {
                Log.d(TAG, "Speech segment too short, discarding: ${duration}ms")
            }

            isInSpeechSegment = false
            _isSpeechDetected.value = false
            updateNotification("Monitoring for speech…")
        }
    }

    private fun extractAndSendPreRoll() {
        val preRollSamples = (Constants.SAMPLE_RATE * Constants.PRE_ROLL_SECONDS).toInt()
        val preRollBuffer = ShortArray(preRollSamples)
        circularBuffer.readLatest(preRollBuffer, preRollSamples)

        val encoded = opusEncoder.encode(preRollBuffer)
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
            serviceScope.launch {
                dataLayerSender.sendAudioChunk(chunk)
            }
        }
    }

    private fun extractAndSendChunk(confidence: Float, isFinal: Boolean) {
        val chunkSamples = Constants.YAMNET_INPUT_SAMPLES
        val chunkBuffer = ShortArray(chunkSamples)
        circularBuffer.readLatest(chunkBuffer, chunkSamples)

        val encoded = opusEncoder.encode(chunkBuffer)
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
            serviceScope.launch {
                dataLayerSender.sendAudioChunk(chunk)
            }
        }

        // Check max segment duration
        val elapsed = System.currentTimeMillis() - speechStartTimeMs
        if (elapsed > Constants.MAX_SPEECH_SEGMENT_SECONDS * 1000L) {
            Log.d(TAG, "Max segment duration reached, forcing end")
            handleSilenceFrame()
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
