package com.omi4wos.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omi4wos.shared.AudioChunk
import com.omi4wos.shared.Constants
import com.omi4wos.shared.DataLayerPaths
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service for continuous audio recording, speech classification,
 * and forwarding speech segments to the phone companion app.
 *
 * Each completed speech segment triggers a debounced 30-second upload window
 * so consecutive segments land in one Omi conversation. An hourly fallback
 * sync catches any pending chunks after reconnection.
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"

        const val ACTION_START = "com.omi4wos.wear.ACTION_START"
        const val ACTION_STOP = "com.omi4wos.wear.ACTION_STOP"
        const val ACTION_FORCE_SYNC = "com.omi4wos.wear.ACTION_FORCE_SYNC"

        // Observable state for UI
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _isSpeechDetected = MutableStateFlow(false)
        val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected

    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var classificationJob: Job? = null

    /**
     * Decouples Opus encoding from the classification loop.
     * The classification coroutine sends raw PCM here and immediately returns to sleep.
     * A separate Dispatchers.IO coroutine drains the channel, blocking on MediaCodec
     * without ever touching the classification thread.
     */
    private data class EncodeRequest(
        val pcmData: ShortArray,
        val timestampMs: Long,
        val durationMs: Long,
        val speechConfidence: Float,
        val chunkIndex: Int,
        val segmentId: String,
        val isFinal: Boolean
    )
    private val encodeChannel = Channel<EncodeRequest>(capacity = Channel.UNLIMITED)
    private var encodeJob: Job? = null

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var speechClassifier: SpeechClassifier
    private lateinit var circularBuffer: CircularAudioBuffer
    private lateinit var opusEncoder: OpusEncoder
    private lateinit var dataLayerSender: DataLayerSender
    private lateinit var chunkRepository: ChunkRepository
    private lateinit var prefs: SharedPreferences

    private var wakeLock: PowerManager.WakeLock? = null

    // Speech segment tracking
    private var speechStartTimeMs: Long = 0L
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private var currentSegmentId: String = ""
    private var chunkIndex = 0
    @Volatile private var isInSpeechSegment = false

    // Thresholds for hysteresis.
    // 6 × 960ms ≈ 5.76 s of continuous silence required to end a segment.
    // This comfortably covers natural breath pauses (0.5–2 s) and brief thinking
    // pauses mid-sentence without fragmenting a conversation into many short clips.
    private val speechOffsetFrames = 6

    // In Realtime mode: wait this long after the last segment ends before syncing.
    // Consecutive 60-second segments (from MAX_SPEECH_SEGMENT_SECONDS) all land in
    // the same performSync() call and therefore in one Omi conversation.
    private val REALTIME_SYNC_IDLE_MS = 30_000L
    private var realtimeSyncJob: Job? = null

    // Dynamic Conversation Tracking
    private var lastValidSpeechEndTimeMs: Long = 0L

    // Notification deduplication — avoid nm.notify() when text hasn't changed
    private var lastNotificationText: String = ""

    // Hourly sync tracking (persisted across service restarts)
    private var lastSyncTimeMs: Long = 0L

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
        prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        lastSyncTimeMs = prefs.getLong(Constants.PREF_LAST_SYNC_TIME, 0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> {
                // START_STICKY restart after OS kill — resume recording if it was active before.
                if (prefs.getBoolean(Constants.PREF_RECORDING_ENABLED, false)) {
                    Log.i(TAG, "Restarted by OS (intent=null) — resuming recording")
                    startCapture()
                } else {
                    stopSelf()
                }
            }
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
            ACTION_FORCE_SYNC -> {
                val wasRecording = _isRecording.value
                if (!wasRecording) {
                    startForeground(
                        Constants.WEAR_NOTIFICATION_ID,
                        createNotification("Syncing to phone…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                }
                serviceScope.launch {
                    Log.i(TAG, "ACTION_FORCE_SYNC — syncing immediately")
                    performSync()
                    if (!wasRecording) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (_isRecording.value) return

        Log.i(TAG, "Starting audio capture")
        prefs.edit().putBoolean(Constants.PREF_RECORDING_ENABLED, true).apply()

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
            circularBuffer.write(samples)
        }

        _isRecording.value = true

        // Notify phone that recording has started
        notifyPhoneRecordingState(true)

        // Connectivity loop: checks phone presence every 2 min and updates UI state.
        // Also serves as a hourly fallback sync — catches any pending chunks that the
        // per-segment realtimeSyncJob might have missed (e.g. after a BT reconnect).
        serviceScope.launch {
            while (isActive) {
                dataLayerSender.checkConnectivity()
                val connected = dataLayerSender.isConnected

                if (connected) {
                    val elapsed = System.currentTimeMillis() - lastSyncTimeMs
                    if (lastSyncTimeMs == 0L || elapsed >= Constants.HOURLY_SYNC_INTERVAL_MS) {
                        Log.i(TAG, "Hourly fallback sync triggered")
                        performSync()
                        lastSyncTimeMs = System.currentTimeMillis()
                        prefs.edit().putLong(Constants.PREF_LAST_SYNC_TIME, lastSyncTimeMs).apply()
                    }
                }
                delay(Constants.CONNECTIVITY_POLL_INTERVAL_MS)
            }
        }

        // Start encoder pipeline (Dispatchers.IO — blocks on MediaCodec without touching classification thread)
        encodeJob = serviceScope.launch(Dispatchers.IO) {
            encoderLoop()
        }

        // Start classification loop
        classificationJob = serviceScope.launch {
            classificationLoop()
        }
    }

    private fun stopCapture() {
        Log.i(TAG, "Stopping audio capture")
        prefs.edit().putBoolean(Constants.PREF_RECORDING_ENABLED, false).apply()

        classificationJob?.cancel()
        classificationJob = null

        audioRecorder.stop()

        // Close the encode channel — encoderLoop() drains remaining items then releases the encoder
        encodeChannel.close()
        encodeJob = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        _isRecording.value = false
        _isSpeechDetected.value = false
        isInSpeechSegment = false

        stopForeground(STOP_FOREGROUND_REMOVE)

        // Notify the phone that recording has stopped, then stop the service.
        // stopSelf() must be called from inside this coroutine — calling it before
        // the coroutine completes triggers onDestroy() → serviceScope.cancel(), which
        // would kill the notification before the network call could finish.
        serviceScope.launch {
            notifyPhoneRecordingState(false)
            stopSelf()
        }
    }

    /**
     * Drains the encodeChannel on Dispatchers.IO. Each request was queued by the
     * classification loop in ~1ms; the ~360ms MediaCodec block happens here, completely
     * off the classification thread. The encoder is released when the channel closes.
     */
    private suspend fun encoderLoop() {
        try {
            for (request in encodeChannel) {
                android.os.Trace.beginSection("vad:opus_encode")
                val encoded = if (request.pcmData.isNotEmpty()) opusEncoder.encode(request.pcmData) else null
                android.os.Trace.endSection()

                if (encoded != null || request.isFinal) {
                    val chunk = AudioChunk(
                        audioData = encoded ?: ByteArray(0),
                        timestampMs = request.timestampMs,
                        durationMs = request.durationMs,
                        speechConfidence = request.speechConfidence,
                        chunkIndex = request.chunkIndex,
                        segmentId = request.segmentId,
                        isFinal = request.isFinal
                    )
                    android.os.Trace.beginSection("vad:chunk_save")
                    chunkRepository.saveChunk(chunk)
                    android.os.Trace.endSection()

                    if (request.isFinal) {
                        // Debounced upload: wait 30 s after the last segment ends so that
                        // consecutive 60-s max-segments land in one Omi conversation.
                        realtimeSyncJob?.cancel()
                        realtimeSyncJob = serviceScope.launch {
                            delay(REALTIME_SYNC_IDLE_MS)
                            Log.d(TAG, "Realtime idle sync: uploading accumulated segments")
                            performSync()
                            lastSyncTimeMs = System.currentTimeMillis()
                            prefs.edit().putLong(Constants.PREF_LAST_SYNC_TIME, lastSyncTimeMs).apply()
                        }
                    }
                }
            }
        } finally {
            opusEncoder.release()
        }
    }

    /**
     * Main classification loop: reads audio windows from the circular buffer,
     * classifies with Silero VAD, and handles speech segment extraction.
     * Slows to half-rate after 5 minutes of silence to reduce CPU usage.
     */
    private suspend fun classificationLoop() {
        val windowSamples = Constants.WEBRTC_INPUT_SAMPLES
        val windowBuffer = ShortArray(windowSamples)
        var lastSpeechDetectedMs = System.currentTimeMillis()

        while (serviceScope.isActive) {
            if (circularBuffer.availableSamples() < windowSamples) {
                delay(50)
                continue
            }

            android.os.Trace.beginSection("vad:buffer_read")
            circularBuffer.readLatest(windowBuffer, windowSamples)
            android.os.Trace.endSection()

            android.os.Trace.beginSection("vad:loudness_gate")
            val loud = isLoudEnough(windowBuffer)
            android.os.Trace.endSection()

            if (!loud) {
                handleSilenceFrame()
                delay(classificationInterval(lastSpeechDetectedMs))
                continue
            }

            android.os.Trace.beginSection("vad:classify")
            val result = speechClassifier.classify(windowBuffer)
            android.os.Trace.endSection()

            if (result.isSpeech) {
                lastSpeechDetectedMs = System.currentTimeMillis()
                android.os.Trace.beginSection("vad:speech_frame")
                handleSpeechFrame(result.confidence)
                android.os.Trace.endSection()
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
        val currentOnsetFrames = 1 // Always 1 — pre-roll handles false-positive risk; 2-frame idle guard was clipping sentence openers

        if (!isInSpeechSegment && consecutiveSpeechFrames >= currentOnsetFrames) {
            isInSpeechSegment = true
            speechStartTimeMs = System.currentTimeMillis()
            currentSegmentId = java.util.UUID.randomUUID().toString().take(8)
            chunkIndex = 0
            _isSpeechDetected.value = true

            updateNotification("Speech detected")
            Log.d(TAG, "Speech segment started: $currentSegmentId (conf=$confidence)")

            val preRollSamples = (Constants.SAMPLE_RATE * Constants.PRE_ROLL_SECONDS).toInt()
            circularBuffer.rewindReadPos(preRollSamples)
            android.os.Trace.beginSection("vad:preroll")
            extractAndSendPreRoll()
            android.os.Trace.endSection()
        }

        if (isInSpeechSegment) {
            android.os.Trace.beginSection("vad:extract_chunk")
            extractAndSendChunk(confidence, isFinal = false)
            android.os.Trace.endSection()

            val elapsed = System.currentTimeMillis() - speechStartTimeMs
            if (elapsed > Constants.MAX_SPEECH_SEGMENT_SECONDS * 1000L) {
                Log.d(TAG, "Max segment duration reached, forcing end")
                android.os.Trace.beginSection("vad:extract_final")
                extractAndSendChunk(0f, isFinal = true)
                android.os.Trace.endSection()
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
            val duration = System.currentTimeMillis() - speechStartTimeMs

            isInSpeechSegment = false
            _isSpeechDetected.value = false
            updateNotification("Monitoring for speech…")

            val timeSinceLastSpeech = System.currentTimeMillis() - lastValidSpeechEndTimeMs
            val isConversationActive = timeSinceLastSpeech < Constants.CONVERSATION_TIMEOUT_MS
            val currentMinDuration = if (isConversationActive) Constants.MIN_SPEECH_DURATION_ACTIVE_MS else Constants.MIN_SPEECH_DURATION_IDLE_MS

            if (duration >= currentMinDuration) {
                extractAndSendChunk(0f, isFinal = true)
                Log.d(TAG, "Speech segment ended: $currentSegmentId (${duration}ms) - State: ${if(isConversationActive) "ACTIVE" else "IDLE"}")
                lastValidSpeechEndTimeMs = System.currentTimeMillis()
            } else {
                Log.d(TAG, "Speech segment too short, discarding: ${duration}ms < ${currentMinDuration}ms")
            }
            speechClassifier.resetState()
        }
    }

    /**
     * Sends a SYNC_START control message (with syncId + battery), transfers all pending
     * chunks to the phone, then sends SYNC_END so the phone knows to flush its batch buffer.
     * The syncId lets the phone group all segments from this transfer into one upload.
     */
    private suspend fun performSync() {
        // Close any in-progress speech segment so its final chunk is saved before we
        // read the pending-chunk list. Without this, batch sync during continuous speech
        // sends only isFinal=false chunks and the phone never completes a segment.
        if (isInSpeechSegment) {
            Log.i(TAG, "Closing in-progress segment before sync: $currentSegmentId")
            extractAndSendChunk(0f, isFinal = true)
            isInSpeechSegment = false
            _isSpeechDetected.value = false
            updateNotification("Monitoring for speech…")
            lastValidSpeechEndTimeMs = System.currentTimeMillis()
        }

        val syncId = java.util.UUID.randomUUID().toString().take(8)
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val extras = buildMap {
            put(DataLayerPaths.KEY_SYNC_ID, syncId)
            if (battery >= 0) put(DataLayerPaths.KEY_BATTERY_LEVEL, battery.toString())
        }
        dataLayerSender.sendControlMessage(DataLayerPaths.CMD_SYNC_START, extras)
        Log.i(TAG, "Sync start: $syncId battery=$battery%")

        syncPendingChunks()

        dataLayerSender.sendControlMessage(DataLayerPaths.CMD_SYNC_END, mapOf(DataLayerPaths.KEY_SYNC_ID to syncId))
        Log.i(TAG, "Sync end: $syncId")
    }

    private suspend fun syncPendingChunks() {
        val files = chunkRepository.getPendingChunkFiles()
        if (files.isEmpty()) return

        for (file in files) {
            val chunk = chunkRepository.readChunk(file)
            if (chunk != null) {
                val success = dataLayerSender.sendAudioChunk(chunk)
                if (success) {
                    chunkRepository.deleteChunkFile(file)
                } else {
                    Log.w(TAG, "Sync aborted, connection lost during transmission")
                    break
                }
            } else {
                chunkRepository.deleteChunkFile(file)
            }
        }
    }

    /**
     * Sends the current recording state to the phone so the UI reflects watch-side changes.
     * Called on startCapture() and stopCapture().
     */
    private fun notifyPhoneRecordingState(recording: Boolean) {
        serviceScope.launch {
            dataLayerSender.checkConnectivity()
            if (dataLayerSender.isConnected) {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val extras = buildMap {
                    put(DataLayerPaths.KEY_IS_RECORDING, recording.toString())
                    if (battery >= 0) put(DataLayerPaths.KEY_BATTERY_LEVEL, battery.toString())
                }
                dataLayerSender.sendControlMessage(DataLayerPaths.CMD_STATUS_RESPONSE, extras)
                Log.d(TAG, "Notified phone: isRecording=$recording battery=$battery%")
            }
        }
    }

    private fun extractAndSendPreRoll() {
        val unreadBuffer = circularBuffer.consumeUnread()
        if (unreadBuffer.isEmpty()) return
        encodeChannel.trySend(EncodeRequest(
            pcmData = unreadBuffer,
            timestampMs = speechStartTimeMs - (Constants.PRE_ROLL_SECONDS * 1000).toLong(),
            durationMs = (Constants.PRE_ROLL_SECONDS * 1000).toLong(),
            speechConfidence = 0f,
            chunkIndex = chunkIndex++,
            segmentId = currentSegmentId,
            isFinal = false
        ))
    }

    private fun extractAndSendChunk(confidence: Float, isFinal: Boolean) {
        val unreadBuffer = circularBuffer.consumeUnread()
        if (unreadBuffer.isEmpty() && !isFinal) return
        // Queue raw PCM — encode + save happen in encoderLoop() on Dispatchers.IO,
        // so this call returns in ~1ms and never blocks the classification thread.
        encodeChannel.trySend(EncodeRequest(
            pcmData = unreadBuffer,
            timestampMs = System.currentTimeMillis(),
            durationMs = Constants.CLASSIFICATION_INTERVAL_MS,
            speechConfidence = confidence,
            chunkIndex = chunkIndex++,
            segmentId = currentSegmentId,
            isFinal = isFinal
        ))
    }

    /**
     * Integer energy gate — equivalent to the old LOUDNESS_THRESHOLD_DB = 52.0 check but
     * without any floating-point division, sqrt, or log10. Runs on every classification
     * cycle so eliminating the float math meaningfully reduces CPU work during silence.
     */
    private fun isLoudEnough(samples: ShortArray): Boolean {
        var sum = 0L
        for (s in samples) sum += s.toLong() * s
        return (sum / samples.size) > Constants.LOUDNESS_THRESHOLD_SQ
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
        if (text == lastNotificationText) return
        lastNotificationText = text
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
