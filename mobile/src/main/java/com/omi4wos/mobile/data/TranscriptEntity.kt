package com.omi4wos.mobile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a speech transcript.
 * Stores transcribed text from watch audio segments along with metadata.
 */
@Entity(
    tableName = "transcripts",
    indices = [Index(value = ["segment_id"], unique = true)]
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "segment_id")
    val segmentId: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long,

    @ColumnInfo(name = "speech_confidence")
    val speechConfidence: Float,

    @ColumnInfo(name = "uploaded_to_omi")
    val uploadedToOmi: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
