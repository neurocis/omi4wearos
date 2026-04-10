package com.omi4wos.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for upload record operations.
 */
class UploadRepository private constructor(context: Context) {

    private val dao: UploadDao = UploadDatabase.getInstance(context).uploadDao()

    companion object {
        @Volatile
        private var INSTANCE: UploadRepository? = null

        fun getInstance(context: Context): UploadRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = UploadRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun insert(record: UploadRecord): Long = dao.insert(record)

    suspend fun getPendingUploads(): List<UploadRecord> = dao.getPendingUploads()

    suspend fun markUploaded(id: Long) = dao.markUploaded(id)

    fun getTotalCount(): Flow<Int> = dao.getTotalCount()

    fun getUploadedCount(): Flow<Int> = dao.getUploadedCount()

    fun getRecentSyncSummaries(limit: Int = 20): Flow<List<SyncSummary>> =
        dao.getRecentSyncSummaries(limit).map { rows ->
            rows.map { r ->
                SyncSummary(
                    syncId = r.syncId,
                    syncTime = r.syncTime,
                    earliestMs = r.earliestMs,
                    latestMs = r.latestMs,
                    totalBytes = r.totalBytes,
                    batteryLevel = r.batteryLevel,
                    segmentCount = r.segmentCount,
                    uploadedCount = r.uploadedCount,
                    failedCount = r.failedCount
                )
            }
        }

    suspend fun getBySegmentId(segmentId: String): UploadRecord? = dao.getBySegmentId(segmentId)

    suspend fun deleteOlderThan(beforeTimestamp: Long) = dao.deleteOlderThan(beforeTimestamp)

    suspend fun deleteAll() = dao.deleteAll()
}
