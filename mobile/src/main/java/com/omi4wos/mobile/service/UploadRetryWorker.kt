package com.omi4wos.mobile.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic background worker that retries any failed Omi Cloud uploads.
 * Scheduled every 15 minutes when a network connection is available.
 * Uses [runUploadRetry] so retry behaviour is identical to the manual button.
 */
class UploadRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "omi4wos_upload_retry"
        private const val TAG = "UploadRetryWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Auto-retry started")
        return try {
            runUploadRetry(applicationContext)
            Log.i(TAG, "Auto-retry finished")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-retry threw unexpectedly", e)
            Result.retry()
        }
    }
}
