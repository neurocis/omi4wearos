package com.omi4wos.mobile.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.omi4wos.shared.AudioChunk
import com.omi4wos.shared.DataLayerPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * WearableListenerService that receives audio chunks from the watch
 * via the Wear Data Layer MessageClient API.
 *
 * Deserializes incoming messages and forwards them to the TranscriptionService.
 */
class AudioReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "AudioReceiverService"

        // Observable state
        private val _watchConnected = MutableStateFlow(false)
        val watchConnected: StateFlow<Boolean> = _watchConnected

        private val _audioChunkFlow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
        val audioChunkFlow: SharedFlow<AudioChunk> = _audioChunkFlow

        private val _segmentsReceived = MutableStateFlow(0)
        val segmentsReceived: StateFlow<Int> = _segmentsReceived
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Track active speech segments
    private val activeSegments = mutableMapOf<String, MutableList<AudioChunk>>()

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        Log.d(TAG, "Message received: path=$path, size=${messageEvent.data.size}")

        _watchConnected.value = true

        when {
            path.startsWith(DataLayerPaths.AUDIO_SPEECH_PATH) -> {
                handleAudioMessage(messageEvent.data)
            }
            path == DataLayerPaths.AUDIO_CONTROL_PATH -> {
                handleControlMessage(messageEvent.data)
            }
        }
    }

    private fun handleAudioMessage(data: ByteArray) {
        try {
            val chunk = deserializeChunk(data)

            Log.d(TAG, "Audio chunk: seg=${chunk.segmentId} idx=${chunk.chunkIndex} " +
                    "final=${chunk.isFinal} size=${chunk.audioData.size}")

            // Accumulate chunks by segment
            val segmentChunks = activeSegments.getOrPut(chunk.segmentId) { mutableListOf() }
            segmentChunks.add(chunk)

            // Emit for real-time processing
            serviceScope.launch {
                _audioChunkFlow.emit(chunk)
            }

            if (chunk.isFinal) {
                // Complete segment received — trigger transcription
                val completeSegment = activeSegments.remove(chunk.segmentId)
                if (completeSegment != null && completeSegment.isNotEmpty()) {
                    _segmentsReceived.value++
                    Log.i(TAG, "Complete segment: ${chunk.segmentId} " +
                            "(${completeSegment.size} chunks)")
                    triggerTranscription(chunk.segmentId, completeSegment)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process audio message", e)
        }
    }

    private fun handleControlMessage(data: ByteArray) {
        try {
            val dis = DataInputStream(ByteArrayInputStream(data))
            val command = dis.readUTF()
            val extrasCount = dis.readInt()
            val extras = mutableMapOf<String, String>()
            repeat(extrasCount) {
                extras[dis.readUTF()] = dis.readUTF()
            }
            Log.d(TAG, "Control message: $command, extras=$extras")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse control message", e)
        }
    }

    /**
     * Trigger the TranscriptionService to transcribe a complete speech segment.
     */
    private fun triggerTranscription(segmentId: String, chunks: List<AudioChunk>) {
        // Combine all audio data from the segment
        val totalSize = chunks.sumOf { it.audioData.size }
        val combinedAudio = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks.sortedBy { it.chunkIndex }) {
            System.arraycopy(chunk.audioData, 0, combinedAudio, offset, chunk.audioData.size)
            offset += chunk.audioData.size
        }

        val startTime = chunks.minOf { it.timestampMs }
        val endTime = chunks.maxOf { it.timestampMs + it.durationMs }
        val avgConfidence = chunks.filter { it.speechConfidence > 0 }
            .map { it.speechConfidence }
            .average()
            .toFloat()

        // Start TranscriptionService with segment data
        val intent = Intent(this, TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_TRANSCRIBE
            putExtra(TranscriptionService.EXTRA_SEGMENT_ID, segmentId)
            putExtra(TranscriptionService.EXTRA_AUDIO_DATA, combinedAudio)
            putExtra(TranscriptionService.EXTRA_START_TIME, startTime)
            putExtra(TranscriptionService.EXTRA_END_TIME, endTime)
            putExtra(TranscriptionService.EXTRA_CONFIDENCE, avgConfidence)
        }
        startService(intent)
    }

    /**
     * Deserialize an AudioChunk from the compact binary format.
     */
    private fun deserializeChunk(data: ByteArray): AudioChunk {
        val dis = DataInputStream(ByteArrayInputStream(data))
        val segmentId = dis.readUTF()
        val chunkIndex = dis.readInt()
        val timestampMs = dis.readLong()
        val durationMs = dis.readLong()
        val confidence = dis.readFloat()
        val isFinal = dis.readBoolean()
        val audioLength = dis.readInt()
        val audioData = ByteArray(audioLength)
        dis.readFully(audioData)

        return AudioChunk(
            audioData = audioData,
            timestampMs = timestampMs,
            durationMs = durationMs,
            speechConfidence = confidence,
            chunkIndex = chunkIndex,
            segmentId = segmentId,
            isFinal = isFinal
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
