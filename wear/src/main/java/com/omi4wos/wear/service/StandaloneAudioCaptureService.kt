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
import com.omi4wos.wear.network.StandaloneOmiApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone foreground service — records audio, classifies speech with Silero VAD,
 * encodes to Opus, and uploads directly to api.omi.me without a companion phone app.
 *
 * The VAD/encoding pipeline is identical to [AudioCaptureService] (v2.0):
 *   - Async [Channel] decouples Opus encoding (Dispatchers.IO) from the classification loop
 *   - 30-second debounce groups consecutive segments into one Omi conversation
 *   - [withContext(NonCancellable)] ensures every upload attempt completes
 *   - [ChunkRepository] store-and-forward: chunks survive upload failures and are retried
 *
 * The only difference from the companion build is the sync path:
 *   Companion: DataLayerSender → phone → AudioUploadService → Omi
 *   Standalone: StandaloneOmiApiClient.uploadChunks() → Omi (direct)
 */
class StandaloneAudioCaptureService : Service() {

    companion object {
        private const val TAG = "StandaloneAudioCapture"

        const val ACTION_START = "com.omi4wos.wear.ACTION_START"
        const val ACTION_STOP  = "com.omi4wos.wear.ACTION_STOP"

        // Observable state consumed by the UI (HomeScreen)
        private val _isRecording      = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _isSpeechDetected = MutableStateFlow(false)
        val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var classificationJob: Job? = null

    /**
     * Decouples Opus encoding from the VAD classification loop.
     * The classification coroutine queues raw PCM here (~1 ms) and returns to sleep.
     * A separate Dispatchers.IO coroutine drains the channel, blocking on MediaCodec.
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

    private lateinit var audioRecorder:    AudioRecorder
    private lateinit var speechClassifier: SpeechClassifier
    private lateinit var circularBuffer:   CircularAudioBuffer
    private lateinit var opusEncoder:      OpusEncoder
    private lateinit var omiApiClient:     StandaloneOmiApiClient
    private lateinit var chunkRepository:  ChunkRepository
    private lateinit var prefs:            SharedPreferences

    private var wakeLock: PowerManager.WakeLock? = null

    // Speech segment state
    private var speechStartTimeMs:       Long    = 0L
    private var consecutiveSpeechFrames: Int     = 0
    private var consecutiveSilenceFrames: Int    = 0
    private var currentSegmentId:        String  = ""
    private var chunkIndex:              Int     = 0
    @Volatile private var isInSpeechSegment = false

    // 6 × 960ms ≈ 5.76 s of continuous silence to end a segment
    private val speechOffsetFrames  = 6
    // Wait 30 s of idle after last segment before uploading so back-to-back segments
    // land in one Omi conversation
    private val REALTIME_SYNC_IDLE_MS = 30_000L
    private var realtimeSyncJob: Job? = null

    private var lastValidSpeechEndTimeMs: Long   = 0L
    private var lastNotificationText:     String = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioRecorder    = AudioRecorder()
        speechClassifier = SpeechClassifier(this)
        circularBuffer   = CircularAudioBuffer(Constants.CIRCULAR_BUFFER_SAMPLES)
        opusEncoder      = OpusEncoder()
        omiApiClient     = StandaloneOmiApiClient(this)
        chunkRepository  = ChunkRepository(this)
        prefs            = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> {
                // START_STICKY restart — resume if was recording before OS kill
                if (prefs.getBoolean(Constants.PREF_RECORDING_ENABLED, false)) {
                    Log.i(TAG, "Restarted by OS (intent=null) — resuming recording")
                    startCapture()
                } else {
                    stopSelf()
                }
            }
            ACTION_START -> startCapture()
            ACTION_STOP  -> stopCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        speechClassifier.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Capture control ───────────────────────────────────────────────────────

    private fun startCapture() {
        if (_isRecording.value) return
        Log.i(TAG, "Starting capture (standalone)")
        prefs.edit().putBoolean(Constants.PREF_RECORDING_ENABLED, true).apply()

        startForeground(
            Constants.WEAR_NOTIFICATION_ID,
            createNotification("Monitoring for speech…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omi4wos:standalone_capture")
            .apply { acquire() }

        audioRecorder.start { samples -> circularBuffer.write(samples) }
        _isRecording.value = true

        encodeJob         = serviceScope.launch(Dispatchers.IO) { encoderLoop() }
        classificationJob = serviceScope.launch { classificationLoop() }
    }

    private fun stopCapture() {
        Log.i(TAG, "Stopping capture")
        prefs.edit().putBoolean(Constants.PREF_RECORDING_ENABLED, false).apply()

        classificationJob?.cancel()
        classificationJob = null
        audioRecorder.stop()
        encodeChannel.close()
        encodeJob = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        _isRecording.value      = false
        _isSpeechDetected.value = false
        isInSpeechSegment       = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.launch { stopSelf() }
    }

    // ── Encoder pipeline ──────────────────────────────────────────────────────

    /**
     * Drains [encodeChannel] on Dispatchers.IO.
     * After each isFinal chunk, arms a debounced upload: 30 s of idle → [uploadPendingChunks].
     * The upload runs inside [NonCancellable] so a new segment arriving mid-upload
     * cannot cancel the in-progress HTTP request.
     */
    private suspend fun encoderLoop() {
        try {
            for (request in encodeChannel) {
                val encoded = if (request.pcmData.isNotEmpty()) opusEncoder.encode(request.pcmData) else null

                if (encoded != null || request.isFinal) {
                    val chunk = AudioChunk(
                        audioData        = encoded ?: ByteArray(0),
                        timestampMs      = request.timestampMs,
                        durationMs       = request.durationMs,
                        speechConfidence = request.speechConfidence,
                        chunkIndex       = request.chunkIndex,
                        segmentId        = request.segmentId,
                        isFinal          = request.isFinal
                    )
                    chunkRepository.saveChunk(chunk)

                    if (request.isFinal) {
                        realtimeSyncJob?.cancel()
                        realtimeSyncJob = serviceScope.launch {
                            delay(REALTIME_SYNC_IDLE_MS) // reset on every new segment
                            withContext(NonCancellable + Dispatchers.IO) {
                                Log.d(TAG, "Idle timeout — uploading segments to Omi")
                                uploadPendingChunks()
                            }
                        }
                    }
                }
            }
        } finally {
            opusEncoder.release()
        }
    }

    /**
     * Reads all pending chunks from [ChunkRepository], uploads via [StandaloneOmiApiClient],
     * and deletes the files on success. Failures leave files on disk for the next retry.
     */
    private suspend fun uploadPendingChunks() {
        // Close any open segment first so its final chunk lands in this upload batch
        if (isInSpeechSegment) {
            Log.i(TAG, "Closing open segment before upload: $currentSegmentId")
            extractAndSendChunk(0f, isFinal = true)
            isInSpeechSegment       = false
            _isSpeechDetected.value = false
            updateNotification("Monitoring for speech…")
            lastValidSpeechEndTimeMs = System.currentTimeMillis()
        }

        val files = chunkRepository.getPendingChunkFiles()
        if (files.isEmpty()) return

        val chunks = files.mapNotNull { chunkRepository.readChunk(it) }
        Log.i(TAG, "Uploading ${chunks.size} chunks in ${files.size} files")

        updateNotification("Uploading to Omi…")
        val success = omiApiClient.uploadChunks(chunks)

        if (success) {
            files.forEach { chunkRepository.deleteChunkFile(it) }
            Log.i(TAG, "Upload complete — ${files.size} chunk files deleted")
        } else {
            Log.w(TAG, "Upload failed — chunks retained on disk for retry")
        }
        updateNotification("Monitoring for speech…")
    }

    // ── Classification loop ───────────────────────────────────────────────────

    private suspend fun classificationLoop() {
        val windowSamples    = Constants.WEBRTC_INPUT_SAMPLES
        val windowBuffer     = ShortArray(windowSamples)
        var lastSpeechTimeMs = System.currentTimeMillis()

        while (serviceScope.isActive) {
            if (circularBuffer.availableSamples() < windowSamples) {
                delay(50)
                continue
            }

            circularBuffer.readLatest(windowBuffer, windowSamples)

            if (!isLoudEnough(windowBuffer)) {
                handleSilenceFrame()
                delay(classificationInterval(lastSpeechTimeMs))
                continue
            }

            val result = speechClassifier.classify(windowBuffer)
            if (result.isSpeech) {
                lastSpeechTimeMs = System.currentTimeMillis()
                handleSpeechFrame(result.confidence)
            } else {
                handleSilenceFrame()
            }
            delay(classificationInterval(lastSpeechTimeMs))
        }
    }

    /** Doubles poll interval to 3 s after 30 s of silence to reduce CPU load. */
    private fun classificationInterval(lastSpeechTimeMs: Long): Long {
        val idleMs = System.currentTimeMillis() - lastSpeechTimeMs
        return if (idleMs > Constants.IDLE_SLOWDOWN_AFTER_MS)
            Constants.IDLE_CLASSIFICATION_INTERVAL_MS
        else
            Constants.CLASSIFICATION_INTERVAL_MS
    }

    private fun handleSpeechFrame(confidence: Float) {
        consecutiveSpeechFrames++
        consecutiveSilenceFrames = 0

        if (!isInSpeechSegment && consecutiveSpeechFrames >= 1) {
            isInSpeechSegment       = true
            speechStartTimeMs       = System.currentTimeMillis()
            currentSegmentId        = java.util.UUID.randomUUID().toString().take(8)
            chunkIndex              = 0
            _isSpeechDetected.value = true
            updateNotification("Speech detected")
            Log.d(TAG, "Segment started: $currentSegmentId (conf=$confidence)")

            val preRollSamples = (Constants.SAMPLE_RATE * Constants.PRE_ROLL_SECONDS).toInt()
            circularBuffer.rewindReadPos(preRollSamples)
            extractAndSendPreRoll()
        }

        if (isInSpeechSegment) {
            extractAndSendChunk(confidence, isFinal = false)

            val elapsed = System.currentTimeMillis() - speechStartTimeMs
            if (elapsed > Constants.MAX_SPEECH_SEGMENT_SECONDS * 1000L) {
                Log.d(TAG, "Max segment duration reached — forcing end")
                extractAndSendChunk(0f, isFinal = true)
                isInSpeechSegment       = false
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

            isInSpeechSegment       = false
            _isSpeechDetected.value = false
            updateNotification("Monitoring for speech…")

            val timeSinceLastSpeech  = System.currentTimeMillis() - lastValidSpeechEndTimeMs
            val isConversationActive = timeSinceLastSpeech < Constants.CONVERSATION_TIMEOUT_MS
            val minDuration = if (isConversationActive)
                Constants.MIN_SPEECH_DURATION_ACTIVE_MS
            else
                Constants.MIN_SPEECH_DURATION_IDLE_MS

            if (duration >= minDuration) {
                extractAndSendChunk(0f, isFinal = true)
                Log.d(TAG, "Segment ended: $currentSegmentId (${duration}ms) — ${if (isConversationActive) "ACTIVE" else "IDLE"}")
                lastValidSpeechEndTimeMs = System.currentTimeMillis()
            } else {
                Log.d(TAG, "Segment too short, discarding: ${duration}ms < ${minDuration}ms")
            }
            speechClassifier.resetState()
        }
    }

    // ── Chunk extraction helpers ──────────────────────────────────────────────

    private fun extractAndSendPreRoll() {
        val unread = circularBuffer.consumeUnread()
        if (unread.isEmpty()) return
        encodeChannel.trySend(
            EncodeRequest(
                pcmData          = unread,
                timestampMs      = speechStartTimeMs - (Constants.PRE_ROLL_SECONDS * 1000).toLong(),
                durationMs       = (Constants.PRE_ROLL_SECONDS * 1000).toLong(),
                speechConfidence = 0f,
                chunkIndex       = chunkIndex++,
                segmentId        = currentSegmentId,
                isFinal          = false
            )
        )
    }

    private fun extractAndSendChunk(confidence: Float, isFinal: Boolean) {
        val unread = circularBuffer.consumeUnread()
        if (unread.isEmpty() && !isFinal) return
        encodeChannel.trySend(
            EncodeRequest(
                pcmData          = unread,
                timestampMs      = System.currentTimeMillis(),
                durationMs       = Constants.CLASSIFICATION_INTERVAL_MS,
                speechConfidence = confidence,
                chunkIndex       = chunkIndex++,
                segmentId        = currentSegmentId,
                isFinal          = isFinal
            )
        )
    }

    // ── Loudness gate ─────────────────────────────────────────────────────────

    /** Integer energy gate — avoids float math (sqrt, log10) on every classification cycle. */
    private fun isLoudEnough(samples: ShortArray): Boolean {
        var sum = 0L
        for (s in samples) sum += s.toLong() * s
        return (sum / samples.size) > Constants.LOUDNESS_THRESHOLD_SQ
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.WEAR_NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.WEAR_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java)
            .notify(Constants.WEAR_NOTIFICATION_ID, createNotification(text))
    }
}
