package com.omi4wos.mobile.data

/**
 * Aggregated view of one sync session shown as a single card in the UI.
 *
 * @param syncId       Identifies the batch (empty string = legacy records with no syncId)
 * @param syncTime     Wall-clock time the sync completed (max created_at in the group)
 * @param earliestMs   Earliest audio start timestamp in the batch
 * @param latestMs     Latest audio end timestamp in the batch
 * @param totalBytes   Sum of all audio_size_bytes in the batch
 * @param batteryLevel Watch battery at time of sync (-1 = unknown)
 * @param segmentCount Number of audio segments in the batch
 * @param uploadedCount Number of segments successfully uploaded
 * @param failedCount  Number of segments that failed (uploadedToOmi = false)
 */
data class SyncSummary(
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
