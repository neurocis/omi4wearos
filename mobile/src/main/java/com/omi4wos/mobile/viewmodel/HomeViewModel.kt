package com.omi4wos.mobile.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.omi4wos.mobile.data.SyncSummary
import com.omi4wos.mobile.data.UploadRepository
import com.omi4wos.mobile.omi.OmiApiClient
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.service.AudioReceiverService
import com.omi4wos.mobile.service.AudioUploadService
import com.omi4wos.shared.Constants
import com.omi4wos.shared.DataLayerPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

data class HomeUiState(
    val watchConnected: Boolean = false,
    val isReceivingAudio: Boolean = false,
    val watchRecordingEnabled: Boolean = false,
    val watchBatteryLevel: Int = -1,
    val totalUploads: Int = 0,
    val uploadFailures: Int = 0,
    val recentSyncs: List<SyncSummary> = emptyList(),
    val streamMode: String = Constants.STREAM_MODE_BATCH,
    val batchIntervalMinutes: Int = Constants.DEFAULT_BATCH_INTERVAL_MINUTES
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "HomeViewModel" }

    private val repository = UploadRepository.getInstance(application)
    private val omiConfig = OmiConfig(application)
    private val messageClient = Wearable.getMessageClient(application)
    private val nodeClient = Wearable.getNodeClient(application)

    // Direct listener — catches messages when WearableListenerService is not triggered (Samsung)
    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        Log.d(TAG, "Direct message received: ${event.path} size=${event.data.size}")
        AudioReceiverService.processMessage(getApplication(), event.path, event.data)
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        messageClient.addListener(messageListener)
        checkWatchConnectivity()
        observeState()
        queryWatchRecordingState()
        retryPendingUploads()
    }

    private fun checkWatchConnectivity() {
        viewModelScope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                val connected = nodes.isNotEmpty()
                AudioReceiverService.setWatchConnected(connected)
                _uiState.value = _uiState.value.copy(watchConnected = connected)
            } catch (e: Exception) {
                Log.e(TAG, "Node check failed", e)
            }
        }
    }

    override fun onCleared() {
        messageClient.removeListener(messageListener)
        super.onCleared()
    }

    private fun observeState() {
        viewModelScope.launch {
            AudioReceiverService.watchConnected.collect { connected ->
                _uiState.value = _uiState.value.copy(watchConnected = connected)
            }
        }
        viewModelScope.launch {
            AudioReceiverService.isReceivingAudio.collect { receiving ->
                _uiState.value = _uiState.value.copy(isReceivingAudio = receiving)
            }
        }
        viewModelScope.launch {
            AudioReceiverService.watchBatteryLevel.collect { level ->
                _uiState.value = _uiState.value.copy(watchBatteryLevel = level)
            }
        }
        viewModelScope.launch {
            AudioReceiverService.watchRecordingEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(watchRecordingEnabled = enabled)
            }
        }

        repository.getRecentSyncSummaries(20)
            .onEach { syncs ->
                _uiState.value = _uiState.value.copy(recentSyncs = syncs)
            }
            .launchIn(viewModelScope)

        combine(
            repository.getTotalCount(),
            repository.getUploadedCount()
        ) { total, uploaded ->
            Pair(total, (total - uploaded).coerceAtLeast(0))
        }.onEach { (total, failures) ->
            _uiState.value = _uiState.value.copy(
                totalUploads = total,
                uploadFailures = failures
            )
        }.launchIn(viewModelScope)

        omiConfig.observeConfig()
            .onEach { config ->
                _uiState.value = _uiState.value.copy(
                    streamMode = config.streamMode,
                    batchIntervalMinutes = config.batchIntervalMinutes
                )
            }
            .launchIn(viewModelScope)
    }

    fun retryPendingUploads() {
        viewModelScope.launch {
            val pending = repository.getPendingUploads()
            if (pending.isEmpty()) return@launch

            val config = OmiConfig(getApplication())
            val apiClient = OmiApiClient()
            val cacheDir = File(getApplication<Application>().cacheDir, "speech_audio")

            for (record in pending) {
                val uploadName = "recording_fs320_${record.timestamp / 1000}.bin"
                val binFile = File(cacheDir, uploadName)

                if (binFile.exists()) {
                    val token = apiClient.getValidFirebaseToken(config)
                    if (token != null) {
                        try {
                            val result = apiClient.uploadAudioSync(token, binFile, uploadName)
                            if (result != null) {
                                repository.markUploaded(record.id)
                                binFile.delete()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Retry failed for ${record.id}", e)
                        }
                    } else {
                        Log.e(TAG, "Cannot retry — no valid token")
                    }
                } else {
                    Log.w(TAG, "File missing for retry: $uploadName")
                }
            }
        }
    }

    /** Sends a force-sync command to the watch — triggers immediate upload of all pending chunks. */
    fun forceSyncWatch() {
        sendWatchCommand(DataLayerPaths.CMD_FORCE_SYNC)
    }

    fun setStreamMode(mode: String) {
        viewModelScope.launch {
            val config = omiConfig.getConfig()
            omiConfig.saveConfig(config.copy(streamMode = mode))
            val cmd = if (mode == Constants.STREAM_MODE_REALTIME) {
                "${DataLayerPaths.CMD_SET_STREAM_MODE}:$mode"
            } else {
                "${DataLayerPaths.CMD_SET_STREAM_MODE}:$mode:${config.batchIntervalMinutes}"
            }
            sendWatchCommand(cmd)
        }
    }

    fun setBatchIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            val config = omiConfig.getConfig()
            omiConfig.saveConfig(config.copy(batchIntervalMinutes = minutes))
            sendWatchCommand("${DataLayerPaths.CMD_SET_STREAM_MODE}:${Constants.STREAM_MODE_BATCH}:$minutes")
        }
    }

    fun startWatchRecording() {
        _uiState.value = _uiState.value.copy(watchRecordingEnabled = true)
        sendWatchCommand(DataLayerPaths.CMD_START_RECORDING)
    }

    fun stopWatchRecording() {
        _uiState.value = _uiState.value.copy(watchRecordingEnabled = false)
        sendWatchCommand(DataLayerPaths.CMD_STOP_RECORDING)
    }

    private fun queryWatchRecordingState() {
        sendWatchCommand(DataLayerPaths.CMD_STATUS_REQUEST)
    }

    private fun sendWatchCommand(command: String) {
        viewModelScope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                val watch = nodes.firstOrNull()
                    ?: run { Log.w(TAG, "No watch connected, cannot send: $command"); return@launch }
                messageClient.sendMessage(
                    watch.id,
                    DataLayerPaths.AUDIO_CONTROL_PATH,
                    command.toByteArray(Charsets.UTF_8)
                ).await()
                Log.i(TAG, "Sent '$command' to ${watch.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send '$command'", e)
            }
        }
    }
}
