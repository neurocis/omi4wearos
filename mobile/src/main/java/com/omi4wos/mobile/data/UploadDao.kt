package com.omi4wos.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: UploadRecord): Long

    @Query("SELECT * FROM uploads WHERE uploaded_to_omi = 0 ORDER BY timestamp ASC")
    suspend fun getPendingUploads(): List<UploadRecord>

    @Query("UPDATE uploads SET uploaded_to_omi = 1 WHERE id = :id")
    suspend fun markUploaded(id: Long)

    @Query("SELECT COUNT(*) FROM uploads")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM uploads WHERE uploaded_to_omi = 1")
    fun getUploadedCount(): Flow<Int>

    @Query("DELETE FROM uploads WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    @Query("DELETE FROM uploads")
    suspend fun deleteAll()

    @Query("SELECT * FROM uploads WHERE segment_id = :segmentId LIMIT 1")
    suspend fun getBySegmentId(segmentId: String): UploadRecord?

    /**
     * Returns one row per sync session, ordered by most recent first.
     * Groups by sync_id when non-empty; each empty sync_id is its own row.
     */
    @Query("""
        SELECT
            CASE WHEN sync_id = '' THEN segment_id ELSE sync_id END AS syncId,
            MAX(created_at)              AS syncTime,
            MIN(timestamp)               AS earliestMs,
            MAX(end_timestamp)           AS latestMs,
            SUM(audio_size_bytes)        AS totalBytes,
            MAX(watch_battery_level)     AS batteryLevel,
            COUNT(*)                     AS segmentCount,
            SUM(CASE WHEN uploaded_to_omi = 1 THEN 1 ELSE 0 END) AS uploadedCount,
            SUM(CASE WHEN uploaded_to_omi = 0 THEN 1 ELSE 0 END) AS failedCount
        FROM uploads
        GROUP BY CASE WHEN sync_id = '' THEN segment_id ELSE sync_id END
        ORDER BY syncTime DESC
        LIMIT :limit
    """)
    fun getRecentSyncSummaries(limit: Int = 20): Flow<List<SyncSummaryRow>>
}

/** Raw Room result for the grouped query — mapped to [SyncSummary] in the repository. */
data class SyncSummaryRow(
    val syncId: String,
    val syncTime: Long,
    val earliestMs: Long,
    val latestMs: Long,
    val totalBytes: Long,
    val batteryLevel: Int,
    val segmentCount: Int,
    val uploadedCount: Int,
    val failedCount: Int
)
