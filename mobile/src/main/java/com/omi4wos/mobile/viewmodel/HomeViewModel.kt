package com.omi4wos.mobile.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.omi4wos.mobile.data.TranscriptEntity
import com.omi4wos.mobile.data.TranscriptRepository
import com.omi4wos.mobile.service.AudioReceiverService
import com.omi4wos.mobile.service.TranscriptionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class HomeUiState(
    val watchConnected: Boolean = false,
    val isRecording: Boolean = false,
    val totalSegments: Int = 0,
    val totalTranscripts: Int = 0,
    val totalUploaded: Int = 0,
    val recentTranscripts: List<TranscriptEntity> = emptyList()
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "HomeViewModel" }

    private val repository = TranscriptRepository.getInstance(application)
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
        
        // Auto-retry failed uploads silently when the app is opened
        retryPendingUploads()
    }

    private fun checkWatchConnectivity() {
        viewModelScope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                Log.i(TAG, "Connected nodes from phone: ${nodes.map { it.displayName }}")
                
                // Completely sync the native NodeClient truth into our local states
                val isActuallyConnected = nodes.isNotEmpty()
                AudioReceiverService.setWatchConnected(isActuallyConnected)
                _uiState.value = _uiState.value.copy(watchConnected = isActuallyConnected)
                
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
        // Observe watch connection and segment count
        viewModelScope.launch {
            AudioReceiverService.watchConnected.collect { connected ->
                _uiState.value = _uiState.value.copy(watchConnected = connected)
            }
        }

        viewModelScope.launch {
            AudioReceiverService.segmentsReceived.collect { count ->
                _uiState.value = _uiState.value.copy(totalSegments = count)
            }
        }

        viewModelScope.launch {
            AudioReceiverService.isReceivingAudio.collect { receiving ->
                _uiState.value = _uiState.value.copy(isRecording = receiving)
            }
        }

        // Observe transcript database
        repository.getRecentTranscripts(20)
            .onEach { transcripts ->
                _uiState.value = _uiState.value.copy(
                    recentTranscripts = transcripts
                )
            }
            .launchIn(viewModelScope)

        repository.getTotalCount()
            .onEach { count ->
                _uiState.value = _uiState.value.copy(totalTranscripts = count)
            }
            .launchIn(viewModelScope)

        repository.getUploadedCount()
            .onEach { count ->
                _uiState.value = _uiState.value.copy(totalUploaded = count)
            }
            .launchIn(viewModelScope)
    }

    fun retryPendingUploads() {
        viewModelScope.launch {
            val pending = repository.getPendingUploads()
            if (pending.isEmpty()) return@launch

            val config = com.omi4wos.mobile.omi.OmiConfig(getApplication())
            val apiClient = com.omi4wos.mobile.omi.OmiApiClient()
            val cacheDir = java.io.File(getApplication<Application>().cacheDir, "speech_audio")

            for (entity in pending) {
                val uploadName = "recording_fs320_${entity.timestamp / 1000}.bin"
                val binFile = java.io.File(cacheDir, uploadName)

                if (binFile.exists()) {
                    val token = apiClient.getValidFirebaseToken(config)
                    if (token != null) {
                        try {
                            val result = apiClient.uploadAudioSync(token, binFile, uploadName)
                            if (result != null) {
                                repository.markUploaded(entity.id)
                                binFile.delete()
                                // Update text
                                repository.insert(entity.copy(
                                    uploadedToOmi = true,
                                    text = "[Uploaded directly to Omi Cloud: $uploadName]"
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Retry failed for ${entity.id}", e)
                        }
                    } else {
                        Log.e(TAG, "Cannot retry, valid token unavailable")
                    }
                } else {
                    Log.w(TAG, "File missing for retry: $uploadName")
                }
            }
        }
    }
}
