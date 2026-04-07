package com.omi4wos.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for transcript database operations.
 */
@Dao
interface TranscriptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcript: TranscriptEntity): Long

    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTranscripts(limit: Int = 50): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC")
    fun getAllTranscripts(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE uploaded_to_omi = 0 ORDER BY timestamp ASC")
    suspend fun getPendingUploads(): List<TranscriptEntity>

    @Query("UPDATE transcripts SET uploaded_to_omi = 1 WHERE id = :id")
    suspend fun markUploaded(id: Long)

    @Query("SELECT COUNT(*) FROM transcripts")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transcripts WHERE uploaded_to_omi = 1")
    fun getUploadedCount(): Flow<Int>

    @Query("DELETE FROM transcripts WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()

    @Query("SELECT * FROM transcripts WHERE segment_id = :segmentId LIMIT 1")
    suspend fun getBySegmentId(segmentId: String): TranscriptEntity?
}
