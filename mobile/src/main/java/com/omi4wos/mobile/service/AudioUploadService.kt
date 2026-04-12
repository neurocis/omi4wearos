package com.omi4wos.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.omi4wos.mobile.data.UploadRecord
import com.omi4wos.mobile.data.UploadRepository
import com.omi4wos.mobile.omi.OmiApiClient
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.shared.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives assembled Opus audio segments from [AudioReceiverService], writes them to
 * Limitless-compatible .bin files, and uploads them to Omi Cloud.
 *
 * Segments are buffered in memory (keyed by syncId). When the watch sends CMD_SYNC_END
 * (translated into ACTION_FLUSH_SYNC), all buffered segments for that syncId are grouped
 * into temporal sessions (gaps > SESSION_GAP_MS = separate upload) and each session is
 * sent as one multipart POST. This prevents conversation fragmentation on Omi's backend
 * and ensures segments from a single sync window land in one conversation.
 */
class AudioUploadService : Service() {

    companion object {
        private const val TAG = "AudioUploadService"

        const val ACTION_UPLOAD      = "com.omi4wos.mobile.ACTION_UPLOAD"
        const val ACTION_FLUSH_SYNC  = "com.omi4wos.mobile.ACTION_FLUSH_SYNC"
        const val EXTRA_SEGMENT_ID   = "segment_id"
        const val EXTRA_SYNC_ID      = "sync_id"
        const val EXTRA_AUDIO_DATA   = "audio_data"
        const val EXTRA_START_TIME   = "start_time"
        const val EXTRA_END_TIME     = "end_time"
        const val EXTRA_CONFIDENCE   = "confidence"
        const val EXTRA_BATTERY_LEVEL    = "battery_level"
        const val EXTRA_AUDIO_SIZE_BYTES = "audio_size_bytes"

        // Segments whose start time is more than this many ms after the previous segment's
        // end time are treated as a separate conversation and get their own upload.
        private const val SESSION_GAP_MS = 5 * 60 * 1000L // 5 minutes

        // Placeholder key for segments that arrive before CMD_SYNC_START is processed.
        // The Wear Data Layer does not guarantee cross-path ordering, so audio chunks on
        // /audio/speech/ can be fully assembled before the SYNC_START control message on
        // /audio/control/ is handled. We buffer them here and absorb into the real
        // syncId when flushBatch() is called.
        private const val PENDING_SYNC_KEY = "__pending__"

        private val _isUploading = MutableStateFlow(false)
        val isUploading: StateFlow<Boolean> = _isUploading

        // In-memory buffer for batch mode. Lives in the companion so it survives
        // service restarts between ACTION_UPLOAD and ACTION_FLUSH_SYNC intents.
        private val pendingBatchSegments =
            ConcurrentHashMap<String, MutableList<PendingSegment>>()

        private data class PendingSegment(
            val segmentId: String,
            val syncId: String,
            val audioData: ByteArray,
            val startTime: Long,
            val endTime: Long,
            val confidence: Float,
            val batteryLevel: Int,
            val audioSizeBytes: Long
        )

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024L      -> "$bytes B"
            bytes < 1_048_576L -> "${"%.1f".format(bytes / 1024.0)} KB"
            else               -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: UploadRepository
    private lateinit var omiClient: OmiApiClient
    private lateinit var omiConfig: OmiConfig

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repository = UploadRepository.getInstance(applicationContext)
        omiConfig = OmiConfig(applicationContext)
        omiClient = OmiApiClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPLOAD -> {
                val segmentId    = intent.getStringExtra(EXTRA_SEGMENT_ID) ?: ""
                val syncId       = intent.getStringExtra(EXTRA_SYNC_ID) ?: ""
                val audioData    = intent.getByteArrayExtra(EXTRA_AUDIO_DATA)
                val startTime    = intent.getLongExtra(EXTRA_START_TIME, 0)
                val endTime      = intent.getLongExtra(EXTRA_END_TIME, 0)
                val confidence   = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)
                val batteryLevel = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                val audioSizeBytes = intent.getLongExtra(EXTRA_AUDIO_SIZE_BYTES, 0L)

                if (audioData != null && audioData.isNotEmpty()) {
                    // Buffer even when syncId is empty: the Wear Data Layer does not
                    // guarantee that CMD_SYNC_START (control path) arrives before the
                    // first audio chunk (speech path). Segments with no syncId are
                    // held under PENDING_SYNC_KEY and merged into the real syncId
                    // inside flushBatch() when CMD_SYNC_END is processed.
                    val effectiveSyncId = syncId.ifEmpty { PENDING_SYNC_KEY }
                    bufferSegment(PendingSegment(segmentId, effectiveSyncId, audioData, startTime, endTime, confidence, batteryLevel, audioSizeBytes))
                }
            }
            ACTION_FLUSH_SYNC -> {
                val syncId = intent.getStringExtra(EXTRA_SYNC_ID) ?: ""
                if (syncId.isNotEmpty()) {
                    serviceScope.launch { flushBatch(syncId) }
                }
            }
        }
        return START_NOT_STICKY
    }

    // -----------------------------------------------------------------------
    // Realtime path — single segment, single upload (unchanged behaviour)
    // -----------------------------------------------------------------------

    private fun uploadSegment(
        segmentId: String,
        syncId: String,
        audioData: ByteArray,
        startTime: Long,
        endTime: Long,
        confidence: Float,
        batteryLevel: Int,
        audioSizeBytes: Long
    ) {
        _isUploading.value = true
        Log.i(TAG, "Uploading segment $segmentId (${audioData.size} bytes) syncId=$syncId")

        serviceScope.launch {
            try {
                val timestampSec = startTime / 1000
                val uploadName = "recording_fs320_$timestampSec.bin"

                val cachePath = File(cacheDir, "speech_audio")
                if (!cachePath.exists()) cachePath.mkdirs()
                val binFile = File(cachePath, uploadName)

                withContext(Dispatchers.IO) {
                    FileOutputStream(binFile).use { it.write(audioData) }
                }

                val token = omiClient.getValidFirebaseToken(omiConfig)
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "No valid Firebase token — cannot upload to Omi Cloud")
                    saveRecord(segmentId, syncId, "[No valid Firebase Token]", startTime, endTime, confidence, audioSizeBytes, batteryLevel, uploaded = false)
                    return@launch
                }

                val result = omiClient.uploadAudioSync(token, binFile, uploadName)

                if (result != null) {
                    val timeFmt = SimpleDateFormat("hh:mma", Locale.getDefault())
                    val dateFmt = SimpleDateFormat("MM/dd/yy hh:mma", Locale.getDefault())
                    val batteryStr = if (batteryLevel >= 0) "$batteryLevel%" else "?%"
                    val text = "${timeFmt.format(Date())}  |  Watch Battery: $batteryStr\n" +
                               "Uploaded to Omi Cloud: ${formatSize(audioSizeBytes)}\n" +
                               "Spanning ${dateFmt.format(Date(startTime))} to ${dateFmt.format(Date(endTime))}"
                    saveRecord(segmentId, syncId, text, startTime, endTime, confidence, audioSizeBytes, batteryLevel, uploaded = true)
                    binFile.delete()
                } else {
                    saveRecord(segmentId, syncId, "[Omi Cloud Upload Failed]", startTime, endTime, confidence, audioSizeBytes, batteryLevel, uploaded = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed for $segmentId", e)
                saveRecord(segmentId, syncId, "[Upload error: ${e.message}]", startTime, endTime, confidence, audioSizeBytes, batteryLevel, uploaded = false)
            } finally {
                _isUploading.value = false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Batch path — buffer during session, flush when CMD_SYNC_END arrives
    // -----------------------------------------------------------------------

    private fun bufferSegment(segment: PendingSegment) {
        pendingBatchSegments
            .getOrPut(segment.syncId) { Collections.synchronizedList(mutableListOf()) }
            .add(segment)
        Log.d(TAG, "Buffered segment ${segment.segmentId} for batch syncId=${segment.syncId} " +
                   "(total=${pendingBatchSegments[segment.syncId]?.size})")
    }

    private suspend fun flushBatch(syncId: String) {
        // Generous delay to let all in-flight ACTION_UPLOAD coroutines finish buffering.
        // CMD_SYNC_END is sent by the watch after the last chunk, but coroutine scheduling
        // and Binder delivery can mean the last segment arrives just after the flush starts.
        delay(2_000)

        // Absorb any segments that raced ahead of CMD_SYNC_START (arrived with empty syncId).
        // There is at most one active batch sync at a time, so all pending segments belong here.
        val racing = pendingBatchSegments.remove(PENDING_SYNC_KEY)
        if (!racing.isNullOrEmpty()) {
            Log.i(TAG, "Absorbing ${racing.size} pre-SYNC_START segment(s) into syncId=$syncId")
            pendingBatchSegments
                .getOrPut(syncId) { Collections.synchronizedList(mutableListOf()) }
                .addAll(racing)
        }

        val segments = pendingBatchSegments.remove(syncId)

        // Absorb segments orphaned by previously failed syncs (watch cancelled mid-sync before
        // CMD_SYNC_END was sent, leaving segments buffered under a syncId that will never flush).
        val orphaned = mutableListOf<PendingSegment>()
        for (key in pendingBatchSegments.keys.toList()) {
            pendingBatchSegments.remove(key)?.let { orphaned.addAll(it) }
        }
        if (orphaned.isNotEmpty()) {
            Log.w(TAG, "Absorbing ${orphaned.size} orphaned segment(s) from ${orphaned.map { it.syncId }.distinct()} into flush for $syncId")
        }

        val allSegments = (segments ?: emptyList()) + orphaned
        if (allSegments.isEmpty()) {
            Log.w(TAG, "Flush requested for syncId=$syncId but no buffered segments found")
            return
        }

        val sorted   = allSegments.sortedBy { it.startTime }
        val sessions = groupIntoSessions(sorted)
        val totalBytes = sorted.sumOf { it.audioSizeBytes }
        Log.i(TAG, "Flushing batch syncId=$syncId: ${sorted.size} segment(s) → " +
                   "${sessions.size} session(s), ${formatSize(totalBytes)} total")

        val token = omiClient.getValidFirebaseToken(omiConfig)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No valid Firebase token — cannot batch upload to Omi Cloud")
            for (seg in allSegments) {
                saveRecord(seg.segmentId, syncId, "[No valid Firebase Token]",
                    seg.startTime, seg.endTime, seg.confidence, seg.audioSizeBytes, seg.batteryLevel, uploaded = false)
            }
            return
        }

        _isUploading.value = true
        try {
            for ((idx, session) in sessions.withIndex()) {
                uploadSession(session, token, syncId, sessionNum = idx + 1, totalSessions = sessions.size)
            }
        } finally {
            _isUploading.value = false
        }
    }

    /**
     * Groups a chronologically sorted segment list into continuous recording sessions.
     * A new session begins when the gap between one segment's end and the next's start
     * exceeds SESSION_GAP_MS — these are genuinely separate conversations.
     */
    private fun groupIntoSessions(sorted: List<PendingSegment>): List<List<PendingSegment>> {
        if (sorted.isEmpty()) return emptyList()
        val sessions = mutableListOf<MutableList<PendingSegment>>()
        var current  = mutableListOf(sorted[0])
        var prevEnd  = sorted[0].endTime

        for (seg in sorted.drop(1)) {
            if (seg.startTime - prevEnd > SESSION_GAP_MS) {
                sessions.add(current)
                current = mutableListOf()
            }
            current.add(seg)
            prevEnd = maxOf(prevEnd, seg.endTime)
        }
        if (current.isNotEmpty()) sessions.add(current)
        return sessions
    }

    /**
     * Concatenates all segments in the session into a single .bin file and uploads it
     * as one request. The Omi API creates one conversation per file, so sending N files
     * in a multipart request still produces N conversations. A single concatenated file
     * guarantees one conversation per session regardless of API behaviour.
     */
    private suspend fun uploadSession(
        segments: List<PendingSegment>,
        token: String,
        syncId: String,
        sessionNum: Int,
        totalSessions: Int
    ) {
        val label = if (totalSessions > 1) " (session $sessionNum/$totalSessions)" else ""
        val sorted = segments.sortedBy { it.startTime }

        // Concatenate all segment audio into one file
        val totalAudioSize = sorted.sumOf { it.audioData.size }
        val combined = ByteArray(totalAudioSize)
        var offset = 0
        for (seg in sorted) {
            System.arraycopy(seg.audioData, 0, combined, offset, seg.audioData.size)
            offset += seg.audioData.size
        }

        val uploadName = "recording_fs320_${sorted.first().startTime / 1000}.bin"
        val cachePath  = File(cacheDir, "speech_audio")
        if (!cachePath.exists()) cachePath.mkdirs()
        val binFile = File(cachePath, uploadName)
        withContext(Dispatchers.IO) {
            FileOutputStream(binFile).use { it.write(combined) }
        }

        val totalBytes = sorted.sumOf { it.audioSizeBytes }
        Log.i(TAG, "Session upload$label: ${sorted.size} segment(s) → 1 file, ${formatSize(totalBytes)}")

        try {
            val result = omiClient.uploadAudioSync(token, binFile, uploadName)

            if (result != null) {
                val timeFmt   = SimpleDateFormat("hh:mma", Locale.getDefault())
                val dateFmt   = SimpleDateFormat("MM/dd/yy hh:mma", Locale.getDefault())
                val avgBattery = sorted.map { it.batteryLevel }.filter { it >= 0 }
                    .let { if (it.isNotEmpty()) it.average().toInt() else -1 }
                val batteryStr = if (avgBattery >= 0) "$avgBattery%" else "?%"

                for (seg in sorted) {
                    val text = "${timeFmt.format(Date())}  |  Watch Battery: $batteryStr\n" +
                               "Uploaded to Omi Cloud: ${formatSize(seg.audioSizeBytes)}\n" +
                               "Spanning ${dateFmt.format(Date(seg.startTime))} to ${dateFmt.format(Date(seg.endTime))}"
                    saveRecord(seg.segmentId, syncId, text, seg.startTime, seg.endTime,
                        seg.confidence, seg.audioSizeBytes, seg.batteryLevel, uploaded = true)
                }
                binFile.delete()
            } else {
                for (seg in sorted) {
                    saveRecord(seg.segmentId, syncId, "[Upload Failed]",
                        seg.startTime, seg.endTime, seg.confidence, seg.audioSizeBytes, seg.batteryLevel, uploaded = false)
                }
                // Keep binFile so retry can re-attempt the upload later.
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session upload failed$label", e)
            // Keep binFile so retry can re-attempt the upload later.
            for (seg in sorted) {
                saveRecord(seg.segmentId, syncId, "[Upload error: ${e.message}]",
                    seg.startTime, seg.endTime, seg.confidence, seg.audioSizeBytes, seg.batteryLevel, uploaded = false)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private suspend fun saveRecord(
        segmentId: String,
        syncId: String,
        text: String,
        startTime: Long,
        endTime: Long,
        confidence: Float,
        audioSizeBytes: Long,
        batteryLevel: Int,
        uploaded: Boolean
    ) {
        try {
            repository.insert(
                UploadRecord(
                    segmentId = segmentId,
                    syncId = syncId,
                    text = text,
                    timestamp = startTime,
                    endTimestamp = endTime,
                    speechConfidence = confidence,
                    uploadedToOmi = uploaded,
                    audioSizeBytes = audioSizeBytes,
                    watchBatteryLevel = batteryLevel
                )
            )
            Log.d(TAG, "Record saved: $segmentId uploaded=$uploaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save record", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.MOBILE_NOTIFICATION_CHANNEL_ID,
            "Audio Upload Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
