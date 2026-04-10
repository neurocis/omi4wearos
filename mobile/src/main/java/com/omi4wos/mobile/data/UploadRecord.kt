package com.omi4wos.mobile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing one audio segment uploaded to Omi Cloud.
 * Multiple records may share the same syncId (they were transferred in one batch).
 */
@Entity(
    tableName = "uploads",
    indices = [Index(value = ["segment_id"], unique = true)]
)
data class UploadRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "segment_id")
    val segmentId: String,

    /** Batch sync session that delivered this segment. All records with the same
     *  syncId were transferred together and appear as one entry in the UI. */
    @ColumnInfo(name = "sync_id")
    val syncId: String = "",

    /** Human-readable status line stored at upload time. */
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
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "audio_size_bytes")
    val audioSizeBytes: Long = 0,

    @ColumnInfo(name = "watch_battery_level")
    val watchBatteryLevel: Int = -1
)
