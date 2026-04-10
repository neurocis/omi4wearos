package com.omi4wos.wear.service

import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.omi4wos.shared.DataLayerPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * WearableListenerService that receives control commands from the phone companion app.
 *
 * Handles:
 *  - CMD_START_RECORDING  → starts AudioCaptureService
 *  - CMD_STOP_RECORDING   → stops AudioCaptureService
 *  - CMD_FORCE_SYNC       → triggers immediate sync via ACTION_FORCE_SYNC (does NOT reset hourly timer)
 *  - CMD_STATUS_REQUEST   → replies with current recording state to phone
 *  - CMD_SET_STREAM_MODE  → updates stream mode + batch interval on the watch
 */
class WatchCommandReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "WatchCommandReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != DataLayerPaths.AUDIO_CONTROL_PATH) return

        val command = try {
            String(messageEvent.data, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode command", e)
            return
        }

        Log.i(TAG, "Received command from phone: $command")

        when (command) {
            DataLayerPaths.CMD_START_RECORDING -> {
                startForegroundService(
                    Intent(this, AudioCaptureService::class.java)
                        .apply { action = AudioCaptureService.ACTION_START }
                )
            }
            DataLayerPaths.CMD_STOP_RECORDING -> {
                startService(
                    Intent(this, AudioCaptureService::class.java)
                        .apply { action = AudioCaptureService.ACTION_STOP }
                )
            }
            DataLayerPaths.CMD_FORCE_SYNC -> {
                // Route through AudioCaptureService so syncPendingChunks() runs on the
                // service's scope with the existing DataLayerSender instance.
                // If the service isn't running, start it (it will sync on first connect anyway).
                startForegroundService(
                    Intent(this, AudioCaptureService::class.java)
                        .apply { action = AudioCaptureService.ACTION_FORCE_SYNC }
                )
            }
            DataLayerPaths.CMD_STATUS_REQUEST -> {
                // Reply with the current recording state so the phone UI stays in sync
                val isRecording = AudioCaptureService.isRecording.value
                scope.launch { sendStatusResponse(messageEvent.sourceNodeId, isRecording) }
            }
            else -> {
                // SET_STREAM_MODE:realtime  or  SET_STREAM_MODE:batch:60
                if (command.startsWith(DataLayerPaths.CMD_SET_STREAM_MODE + ":")) {
                    val parts = command.split(":")
                    val mode = parts.getOrNull(1) ?: return
                    val intervalMinutes = parts.getOrNull(2)?.toIntOrNull()
                    startService(
                        Intent(this, AudioCaptureService::class.java).apply {
                            action = AudioCaptureService.ACTION_SET_STREAM_MODE
                            putExtra(AudioCaptureService.EXTRA_STREAM_MODE, mode)
                            if (intervalMinutes != null) {
                                putExtra(AudioCaptureService.EXTRA_BATCH_INTERVAL_MINUTES, intervalMinutes)
                            }
                        }
                    )
                } else {
                    Log.w(TAG, "Unknown command: $command")
                }
            }
        }
    }

    private suspend fun sendStatusResponse(targetNodeId: String, isRecording: Boolean) {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeUTF(DataLayerPaths.CMD_STATUS_RESPONSE)
            val extraCount = if (battery >= 0) 2 else 1
            dos.writeInt(extraCount)
            dos.writeUTF(DataLayerPaths.KEY_IS_RECORDING)
            dos.writeUTF(isRecording.toString())
            if (battery >= 0) {
                dos.writeUTF(DataLayerPaths.KEY_BATTERY_LEVEL)
                dos.writeUTF(battery.toString())
            }
            dos.flush()

            Wearable.getMessageClient(this).sendMessage(
                targetNodeId,
                DataLayerPaths.AUDIO_CONTROL_PATH,
                baos.toByteArray()
            ).await()
            Log.d(TAG, "Sent status response: isRecording=$isRecording to $targetNodeId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status response", e)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
