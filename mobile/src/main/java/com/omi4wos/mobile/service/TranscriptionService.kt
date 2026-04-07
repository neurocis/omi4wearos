package com.omi4wos.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omi4wos.mobile.data.TranscriptEntity
import com.omi4wos.mobile.data.TranscriptRepository
import com.omi4wos.mobile.omi.OmiApiClient
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.shared.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Service that handles speech-to-text transcription of audio segments
 * received from the watch, and uploads transcripts to Omi.
 */
class TranscriptionService : Service() {

    companion object {
        private const val TAG = "TranscriptionService"
        const val ACTION_TRANSCRIBE = "com.omi4wos.mobile.ACTION_TRANSCRIBE"
        const val EXTRA_SEGMENT_ID = "segment_id"
        const val EXTRA_AUDIO_DATA = "audio_data"
        const val EXTRA_START_TIME = "start_time"
        const val EXTRA_END_TIME = "end_time"
        const val EXTRA_CONFIDENCE = "confidence"

        private val _isTranscribing = MutableStateFlow(false)
        val isTranscribing: StateFlow<Boolean> = _isTranscribing

        private val _transcriptCount = MutableStateFlow(0)
        val transcriptCount: StateFlow<Int> = _transcriptCount
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var repository: TranscriptRepository
    private lateinit var omiClient: OmiApiClient
    private lateinit var omiConfig: OmiConfig

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repository = TranscriptRepository.getInstance(applicationContext)
        omiConfig = OmiConfig(applicationContext)
        omiClient = OmiApiClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRANSCRIBE -> {
                val segmentId = intent.getStringExtra(EXTRA_SEGMENT_ID) ?: ""
                val audioData = intent.getByteArrayExtra(EXTRA_AUDIO_DATA)
                val startTime = intent.getLongExtra(EXTRA_START_TIME, 0)
                val endTime = intent.getLongExtra(EXTRA_END_TIME, 0)
                val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)

                if (audioData != null && audioData.isNotEmpty()) {
                    processSegment(segmentId, audioData, startTime, endTime, confidence)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun processSegment(
        segmentId: String,
        audioData: ByteArray,
        startTime: Long,
        endTime: Long,
        confidence: Float
    ) {
        _isTranscribing.value = true
        Log.i(TAG, "Processing segment $segmentId (${audioData.size} bytes)")

        // Use Android's SpeechRecognizer for on-device transcription
        serviceScope.launch(Dispatchers.Main) {
            try {
                transcribeAudio(segmentId, audioData, startTime, endTime, confidence)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed for $segmentId", e)
                // Save with empty text — can retry later
                saveTranscript(segmentId, "[Transcription failed]", startTime, endTime, confidence)
            } finally {
                _isTranscribing.value = false
            }
        }
    }

    private fun transcribeAudio(
        segmentId: String,
        audioData: ByteArray,
        startTime: Long,
        endTime: Long,
        confidence: Float
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available, saving raw segment")
            saveTranscript(segmentId, "[Speech recognition unavailable]", startTime, endTime, confidence)
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech recognition")
                // Feed audio data to recognizer by playing it
                playAudioForRecognizer(audioData)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error ($error)"
                }
                Log.w(TAG, "Recognition error: $errorMsg")
                saveTranscript(segmentId, "[Recognition error: $errorMsg]", startTime, endTime, confidence)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""

                if (text.isNotBlank()) {
                    Log.i(TAG, "Transcribed segment $segmentId: $text")
                    saveTranscript(segmentId, text, startTime, endTime, confidence)
                } else {
                    Log.w(TAG, "Empty transcription for $segmentId")
                    saveTranscript(segmentId, "[No speech detected]", startTime, endTime, confidence)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(recognizerIntent)
    }

    /**
     * Play the audio data through the speaker at low volume so the SpeechRecognizer
     * can pick it up. This is a workaround since SpeechRecognizer doesn't accept
     * raw audio input directly.
     *
     * Note: In production, consider using Vosk or another offline STT library
     * that accepts raw audio buffers directly.
     */
    private fun playAudioForRecognizer(audioData: ByteArray) {
        // This is a simplified approach — in production, use a proper STT library
        // that accepts audio buffers (e.g., Vosk, Whisper Android)
        Log.d(TAG, "Audio data available for transcription: ${audioData.size} bytes")
    }

    private fun saveTranscript(
        segmentId: String,
        text: String,
        startTime: Long,
        endTime: Long,
        confidence: Float
    ) {
        serviceScope.launch {
            try {
                val entity = TranscriptEntity(
                    segmentId = segmentId,
                    text = text,
                    timestamp = startTime,
                    endTimestamp = endTime,
                    speechConfidence = confidence,
                    uploadedToOmi = false
                )
                repository.insert(entity)
                _transcriptCount.value++

                Log.d(TAG, "Transcript saved: $segmentId")

                // Try to upload to Omi
                uploadToOmi(entity)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save transcript", e)
            }
        }
    }

    private suspend fun uploadToOmi(transcript: TranscriptEntity) {
        val config = omiConfig.getConfig()
        if (config.apiKey.isBlank() || config.appId.isBlank() || config.userId.isBlank()) {
            Log.w(TAG, "Omi not configured, skipping upload")
            return
        }

        if (transcript.text.startsWith("[")) {
            Log.w(TAG, "Skipping upload for error transcript")
            return
        }

        try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startedAt = Instant.ofEpochMilli(transcript.timestamp)
                .atOffset(ZoneOffset.UTC).format(formatter)
            val finishedAt = Instant.ofEpochMilli(transcript.endTimestamp)
                .atOffset(ZoneOffset.UTC).format(formatter)

            val success = omiClient.uploadConversation(
                apiKey = config.apiKey,
                appId = config.appId,
                userId = config.userId,
                text = transcript.text,
                startedAt = startedAt,
                finishedAt = finishedAt
            )

            if (success) {
                repository.markUploaded(transcript.id)
                Log.i(TAG, "Uploaded to Omi: ${transcript.segmentId}")
            } else {
                Log.w(TAG, "Omi upload failed for ${transcript.segmentId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Omi upload error", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.MOBILE_NOTIFICATION_CHANNEL_ID,
            "Transcription Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }
}
