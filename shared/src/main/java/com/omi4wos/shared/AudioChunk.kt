package com.omi4wos.shared

import java.io.Serializable

/**
 * Represents a chunk of speech audio to be sent from watch to phone.
 * Audio is Opus-encoded speech detected by YAMNet on the watch.
 */
data class AudioChunk(
    /** Opus-encoded audio bytes */
    val audioData: ByteArray,
    /** Timestamp when this speech segment started (epoch millis) */
    val timestampMs: Long,
    /** Duration of this chunk in milliseconds */
    val durationMs: Long,
    /** YAMNet speech confidence score (0.0 - 1.0) */
    val speechConfidence: Float,
    /** Sequential chunk index within a speech segment */
    val chunkIndex: Int = 0,
    /** Unique segment ID grouping chunks from the same speech event */
    val segmentId: String = "",
    /** Whether this is the last chunk in the segment */
    val isFinal: Boolean = false
) : Serializable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return timestampMs == other.timestampMs &&
                durationMs == other.durationMs &&
                speechConfidence == other.speechConfidence &&
                chunkIndex == other.chunkIndex &&
                segmentId == other.segmentId &&
                isFinal == other.isFinal &&
                audioData.contentEquals(other.audioData)
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + speechConfidence.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + segmentId.hashCode()
        result = 31 * result + isFinal.hashCode()
        return result
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
