package com.omi4wos.mobile.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
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
import java.util.concurrent.ConcurrentHashMap

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
        
        fun setWatchConnected(connected: Boolean) {
            _watchConnected.value = connected
        }

        private val _audioChunkFlow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
        val audioChunkFlow: SharedFlow<AudioChunk> = _audioChunkFlow

        private val _segmentsReceived = MutableStateFlow(0)
        val segmentsReceived: StateFlow<Int> = _segmentsReceived

        private val _isReceivingAudio = MutableStateFlow(false)
        val isReceivingAudio: StateFlow<Boolean> = _isReceivingAudio

        // Shared segment tracking — used by both WearableListenerService and direct listener
        private val activeSegments = ConcurrentHashMap<String, MutableList<AudioChunk>>()
        private val companionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Process an incoming message from either WearableListenerService or a direct
         * MessageClient listener. Called from both paths so logic stays in one place.
         */
        fun processMessage(context: Context, path: String, data: ByteArray) {
            _watchConnected.value = true
            Log.d(TAG, "processMessage: path=$path size=${data.size}")
            when {
                path.startsWith(DataLayerPaths.AUDIO_SPEECH_PATH) -> handleAudioData(context, data)
                path == DataLayerPaths.AUDIO_CONTROL_PATH -> handleControlData(data)
            }
        }

        private fun handleAudioData(context: Context, data: ByteArray) {
            try {
                val chunk = deserializeChunk(data)

                val segmentChunks = activeSegments.getOrPut(chunk.segmentId) { mutableListOf() }
                
                // Deduplicate: Some watches fire both WearableListenerService and MessageClient listeners!
                if (segmentChunks.any { it.chunkIndex == chunk.chunkIndex }) {
                    return
                }
                
                segmentChunks.add(chunk)

                if (!chunk.isFinal) {
                    _isReceivingAudio.value = true
                }

                companionScope.launch { _audioChunkFlow.emit(chunk) }

                if (chunk.isFinal) {
                    _isReceivingAudio.value = false
                    val completeSegment = activeSegments.remove(chunk.segmentId)
                    if (completeSegment != null && completeSegment.isNotEmpty()) {
                        _segmentsReceived.value++
                        Log.i(TAG, "Complete segment: ${chunk.segmentId} (${completeSegment.size} chunks)")
                        triggerTranscription(context, chunk.segmentId, completeSegment)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process audio message", e)
            }
        }

        private fun handleControlData(data: ByteArray) {
            try {
                val dis = DataInputStream(ByteArrayInputStream(data))
                val command = dis.readUTF()
                Log.d(TAG, "Control message: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse control message", e)
            }
        }

        private fun triggerTranscription(context: Context, segmentId: String, chunks: List<AudioChunk>) {
            val totalSize = chunks.sumOf { it.audioData.size }
            val combinedAudio = ByteArray(totalSize)
            var offset = 0
            for (chunk in chunks.sortedBy { it.chunkIndex }) {
                System.arraycopy(chunk.audioData, 0, combinedAudio, offset, chunk.audioData.size)
                offset += chunk.audioData.size
            }
            val startTime = chunks.minOf { it.timestampMs }
            val endTime = chunks.maxOf { it.timestampMs + it.durationMs }
            val validChunks = chunks.filter { it.speechConfidence > 0 }
            val avgConfidence = if (validChunks.isNotEmpty()) {
                validChunks.map { it.speechConfidence }.average().toFloat()
            } else {
                0f
            }

            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_TRANSCRIBE
                putExtra(TranscriptionService.EXTRA_SEGMENT_ID, segmentId)
                putExtra(TranscriptionService.EXTRA_AUDIO_DATA, combinedAudio)
                putExtra(TranscriptionService.EXTRA_START_TIME, startTime)
                putExtra(TranscriptionService.EXTRA_END_TIME, endTime)
                putExtra(TranscriptionService.EXTRA_CONFIDENCE, avgConfidence)
            }
            context.startService(intent)
        }

        private fun deserializeChunk(data: ByteArray): AudioChunk {
            val dis = DataInputStream(ByteArrayInputStream(data))
            return AudioChunk(
                segmentId = dis.readUTF(),
                chunkIndex = dis.readInt(),
                timestampMs = dis.readLong(),
                durationMs = dis.readLong(),
                speechConfidence = dis.readFloat(),
                isFinal = dis.readBoolean(),
                audioData = ByteArray(dis.readInt()).also { dis.readFully(it) }
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onPeerConnected(peer: Node) {
        Log.i(TAG, "Watch connected: ${peer.displayName} (${peer.id})")
        _watchConnected.value = true
    }

    override fun onPeerDisconnected(peer: Node) {
        Log.i(TAG, "Watch disconnected: ${peer.displayName} (${peer.id})")
        _watchConnected.value = false
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        processMessage(this, messageEvent.path, messageEvent.data)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
