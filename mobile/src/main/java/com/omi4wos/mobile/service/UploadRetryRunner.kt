package com.omi4wos.mobile.service

import android.content.Context
import android.util.Log
import com.omi4wos.mobile.data.UploadRepository
import com.omi4wos.mobile.omi.OmiApiClient
import com.omi4wos.mobile.omi.OmiConfig
import java.io.File

/**
 * Shared retry logic used by both [UploadRetryWorker] (background) and
 * [com.omi4wos.mobile.viewmodel.HomeViewModel] (foreground/manual).
 *
 * Groups failed records by syncId — batch records share one .bin file named
 * after the earliest segment's timestamp. Realtime records each own their file.
 * Records whose cache file has been evicted are dismissed (marked uploaded) since
 * the audio data is unrecoverable.
 *
 * Returns true if at least one upload succeeded.
 */
suspend fun runUploadRetry(context: Context): Boolean {
    val tag = "UploadRetryRunner"
    val repository = UploadRepository.getInstance(context)
    val apiClient = OmiApiClient()
    val config = OmiConfig(context)
    val cacheDir = File(context.cacheDir, "speech_audio")

    val pending = repository.getPendingUploads()
    if (pending.isEmpty()) return false

    Log.i(tag, "Retrying ${pending.size} failed upload(s)")

    // Group by syncId so batch records that share one .bin file are handled together.
    // Realtime records have empty syncId — treat each as its own group.
    val grouped = pending.groupBy { record ->
        if (record.syncId.isEmpty()) "solo_${record.id}" else record.syncId
    }

    var anySucceeded = false

    for ((_, records) in grouped) {
        val earliest = records.minByOrNull { it.timestamp } ?: continue
        val uploadName = "recording_fs320_${earliest.timestamp / 1000}.bin"
        val binFile = File(cacheDir, uploadName)

        if (binFile.exists()) {
            val token = apiClient.getValidFirebaseToken(config)
            if (token == null) {
                Log.e(tag, "Cannot retry — no valid Firebase token")
                return anySucceeded  // No point continuing without auth
            }
            try {
                val result = apiClient.uploadAudioSync(token, binFile, uploadName)
                if (result != null) {
                    for (record in records) repository.markUploaded(record.id)
                    binFile.delete()
                    anySucceeded = true
                    Log.i(tag, "Retry succeeded for ${records.size} record(s) syncId=${earliest.syncId}")
                } else {
                    Log.w(tag, "Retry upload returned null for $uploadName")
                }
            } catch (e: Exception) {
                Log.e(tag, "Retry failed for syncId=${earliest.syncId}", e)
            }
        } else {
            // File evicted from cache — audio unrecoverable; dismiss so it stops counting.
            Log.w(tag, "Cache file missing: $uploadName — dismissing ${records.size} record(s)")
            for (record in records) repository.markUploaded(record.id)
        }
    }

    return anySucceeded
}
