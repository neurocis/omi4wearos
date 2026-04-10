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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Receives assembled Opus audio segments from [AudioReceiverService], writes them to
 * Limitless-compatible .bin files, and uploads them directly to Omi Cloud.
 */
class AudioUploadService : Service() {

    companion object {
        private const val TAG = "AudioUploadService"

        const val ACTION_UPLOAD      = "com.omi4wos.mobile.ACTION_UPLOAD"
        const val EXTRA_SEGMENT_ID   = "segment_id"
        const val EXTRA_SYNC_ID      = "sync_id"
        const val EXTRA_AUDIO_DATA   = "audio_data"
        const val EXTRA_START_TIME   = "start_time"
        const val EXTRA_END_TIME     = "end_time"
        const val EXTRA_CONFIDENCE   = "confidence"
        const val EXTRA_BATTERY_LEVEL    = "battery_level"
        const val EXTRA_AUDIO_SIZE_BYTES = "audio_size_bytes"

        private val _isUploading = MutableStateFlow(false)
        val isUploading: StateFlow<Boolean> = _isUploading

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
        if (intent?.action == ACTION_UPLOAD) {
            val segmentId    = intent.getStringExtra(EXTRA_SEGMENT_ID) ?: ""
            val syncId       = intent.getStringExtra(EXTRA_SYNC_ID) ?: ""
            val audioData    = intent.getByteArrayExtra(EXTRA_AUDIO_DATA)
            val startTime    = intent.getLongExtra(EXTRA_START_TIME, 0)
            val endTime      = intent.getLongExtra(EXTRA_END_TIME, 0)
            val confidence   = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)
            val batteryLevel = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
            val audioSizeBytes = intent.getLongExtra(EXTRA_AUDIO_SIZE_BYTES, 0L)

            if (audioData != null && audioData.isNotEmpty()) {
                uploadSegment(segmentId, syncId, audioData, startTime, endTime, confidence, batteryLevel, audioSizeBytes)
            }
        }
        return START_NOT_STICKY
    }

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
