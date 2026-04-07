package com.omi4wos.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Repository for transcript data operations.
 * Provides a clean API over the Room DAO.
 */
class TranscriptRepository private constructor(context: Context) {

    private val dao: TranscriptDao = TranscriptDatabase.getInstance(context).transcriptDao()

    companion object {
        @Volatile
        private var INSTANCE: TranscriptRepository? = null

        fun getInstance(context: Context): TranscriptRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = TranscriptRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun insert(transcript: TranscriptEntity): Long {
        return dao.insert(transcript)
    }

    fun getRecentTranscripts(limit: Int = 50): Flow<List<TranscriptEntity>> {
        return dao.getRecentTranscripts(limit)
    }

    fun getAllTranscripts(): Flow<List<TranscriptEntity>> {
        return dao.getAllTranscripts()
    }

    suspend fun getPendingUploads(): List<TranscriptEntity> {
        return dao.getPendingUploads()
    }

    suspend fun markUploaded(id: Long) {
        dao.markUploaded(id)
    }

    fun getTotalCount(): Flow<Int> {
        return dao.getTotalCount()
    }

    fun getUploadedCount(): Flow<Int> {
        return dao.getUploadedCount()
    }

    suspend fun deleteOlderThan(beforeTimestamp: Long) {
        dao.deleteOlderThan(beforeTimestamp)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    suspend fun getBySegmentId(segmentId: String): TranscriptEntity? {
        return dao.getBySegmentId(segmentId)
    }
}
