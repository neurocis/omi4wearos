package com.omi4wos.mobile.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

data class HomeUiState(
    val watchConnected: Boolean = false,
    val isRecording: Boolean = false,
    val totalSegments: Int = 0,
    val totalTranscripts: Int = 0,
    val totalUploaded: Int = 0,
    val recentTranscripts: List<TranscriptEntity> = emptyList()
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscriptRepository.getInstance(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        observeState()
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
            TranscriptionService.isTranscribing.collect { transcribing ->
                _uiState.value = _uiState.value.copy(isRecording = transcribing)
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
}
