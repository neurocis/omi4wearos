package com.omi4wos.wear.service

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.omi4wos.shared.AudioChunk
import com.omi4wos.shared.Constants
import com.omi4wos.shared.DataLayerPaths
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Sends audio chunks from the watch to the phone companion app
 * via the Wear Data Layer MessageClient API.
 */
class DataLayerSender(private val context: Context) {

    companion object {
        private const val TAG = "DataLayerSender"
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    private var phoneNodeId: String? = null
    var isConnected: Boolean = false
        private set

    /**
     * Discover the connected phone node that has the companion app installed.
     */
    suspend fun checkConnectivity() {
        try {
            val capabilityInfo = capabilityClient.getCapability(
                DataLayerPaths.CAPABILITY_PHONE_APP,
                CapabilityClient.FILTER_REACHABLE
            ).await()

            val bestNode = pickBestNode(capabilityInfo.nodes)
            phoneNodeId = bestNode?.id
            isConnected = bestNode != null

            if (isConnected) {
                Log.i(TAG, "Phone connected: ${bestNode?.displayName} (${bestNode?.id})")
            } else {
                // Fallback: try to find any connected node
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    phoneNodeId = nodes.first().id
                    isConnected = true
                    Log.i(TAG, "Phone found via node list: ${nodes.first().displayName}")
                } else {
                    Log.w(TAG, "No phone connected")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check connectivity", e)
            isConnected = false
        }
    }

    /**
     * Send an audio chunk to the phone via MessageClient.
     * Uses a compact binary format for efficiency.
     * Returns true if successfully transmitted, false if connection is unavailable or fails.
     */
    suspend fun sendAudioChunk(chunk: AudioChunk): Boolean {
        val nodeId = phoneNodeId
        if (nodeId == null) {
            // Try to reconnect
            checkConnectivity()
            if (phoneNodeId == null) {
                Log.w(TAG, "Cannot send chunk: no phone connected")
                return false
            }
        }

        try {
            val payload = serializeChunk(chunk)

            // Split into smaller messages if needed
            if (payload.size <= Constants.MAX_DATA_LAYER_PAYLOAD) {
                messageClient.sendMessage(
                    phoneNodeId!!,
                    DataLayerPaths.AUDIO_SPEECH_PATH,
                    payload
                ).await()
            } else {
                // Split large payloads
                var offset = 0
                var partIndex = 0
                while (offset < payload.size) {
                    val end = minOf(offset + Constants.MAX_DATA_LAYER_PAYLOAD, payload.size)
                    val part = payload.copyOfRange(offset, end)
                    messageClient.sendMessage(
                        phoneNodeId!!,
                        "${DataLayerPaths.AUDIO_SPEECH_PATH}/$partIndex",
                        part
                    ).await()
                    offset = end
                    partIndex++
                }
            }

            Log.d(TAG, "Sent chunk: seg=${chunk.segmentId} idx=${chunk.chunkIndex} " +
                    "size=${payload.size} final=${chunk.isFinal}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk", e)
            // Mark as disconnected, will retry on next send
            isConnected = false
            phoneNodeId = null
            return false
        }
    }

    /**
     * Send a control/status message to the phone.
     */
    suspend fun sendControlMessage(command: String, extras: Map<String, String> = emptyMap()) {
        val nodeId = phoneNodeId ?: return

        try {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeUTF(command)
            dos.writeInt(extras.size)
            extras.forEach { (key, value) ->
                dos.writeUTF(key)
                dos.writeUTF(value)
            }
            dos.flush()

            messageClient.sendMessage(
                nodeId,
                DataLayerPaths.AUDIO_CONTROL_PATH,
                baos.toByteArray()
            ).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send control message: $command", e)
        }
    }

    /**
     * Serialize an AudioChunk to a compact binary format.
     * Format:
     *   - segmentId: UTF string
     *   - chunkIndex: int
     *   - timestampMs: long
     *   - durationMs: long
     *   - confidence: float
     *   - isFinal: boolean
     *   - audioDataLength: int
     *   - audioData: bytes
     */
    private fun serializeChunk(chunk: AudioChunk): ByteArray {
        val baos = ByteArrayOutputStream(chunk.audioData.size + 64)
        val dos = DataOutputStream(baos)
        dos.writeUTF(chunk.segmentId)
        dos.writeInt(chunk.chunkIndex)
        dos.writeLong(chunk.timestampMs)
        dos.writeLong(chunk.durationMs)
        dos.writeFloat(chunk.speechConfidence)
        dos.writeBoolean(chunk.isFinal)
        dos.writeInt(chunk.audioData.size)
        dos.write(chunk.audioData)
        dos.flush()
        return baos.toByteArray()
    }

    /**
     * Pick the best node — prefer nearby nodes.
     */
    private fun pickBestNode(nodes: Set<Node>): Node? {
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }
}
