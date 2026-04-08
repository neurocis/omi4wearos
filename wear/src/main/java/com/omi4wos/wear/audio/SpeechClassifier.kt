package com.omi4wos.wear.audio

import android.content.Context
import android.util.Log
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WebRTC Voice Activity Detection (VAD) classifier.
 * Extremely lightweight native GMM engine evaluating audio in 20ms algorithmic burst frames.
 */
class SpeechClassifier(context: Context) {

    companion object {
        private const val TAG = "SpeechClassifier"
    }

    data class ClassificationResult(
        val isSpeech: Boolean,
        val confidence: Float,
        val topClassName: String = "",
        val inferenceTimeMs: Long = 0
    )

    private var vad: VadWebRTC? = null
    private var isInitialized = false

    init {
        try {
            vad = VadWebRTC(
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_320, // 20ms chunks
                mode = Mode.VERY_AGGRESSIVE, // Suppress strict background hums
                silenceDurationMs = 300,
                speechDurationMs = 50
            )
            isInitialized = true
            Log.i(TAG, "VadWebRTC initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VadWebRTC", e)
            isInitialized = false
        }
    }

    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
        return bytes
    }

    /**
     * Classify a batched audio window for speech presence natively.
     * Evaluates the identical 960ms chunk seamlessly by dissecting into 48 inline frames.
     */
    fun classify(audioShort: ShortArray): ClassificationResult {
        if (!isInitialized || vad == null) {
            return ClassificationResult(isSpeech = false, confidence = 0f)
        }

        val startTime = System.currentTimeMillis()
        var speechFrames = 0
        var totalFrames = 0

        try {
            val audioBytes = shortArrayToByteArray(audioShort)
            var offset = 0
            val chunkSize = 640 // 320 samples = 640 bytes (16-bit PCM = 2 bytes per sample)

            val localVad = vad ?: return ClassificationResult(isSpeech = false, confidence = 0f)

            // Iterate the 960ms native hardware buffer via 20ms WebRTC bursts
            while (offset + chunkSize <= audioBytes.size) {
                val frame = audioBytes.copyOfRange(offset, offset + chunkSize)
                if (localVad.isSpeech(frame)) {
                    speechFrames++
                }
                totalFrames++
                offset += chunkSize
            }

            val confidence = if (totalFrames > 0) speechFrames.toFloat() / totalFrames else 0f
            val isSpeech = speechFrames > 2 // Trigger if at least 3 frames (60ms total) contained voice

            val inferenceTime = System.currentTimeMillis() - startTime

            if (isSpeech) {
                Log.d(TAG, "Speech detected: confidence=$confidence (${inferenceTime}ms)")
            }

            return ClassificationResult(
                isSpeech = isSpeech,
                confidence = confidence,
                topClassName = "Speech",
                inferenceTimeMs = inferenceTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            return ClassificationResult(isSpeech = false, confidence = 0f)
        }
    }

    /**
     * Release model resources.
     */
    fun close() {
        vad?.close()
        vad = null
        isInitialized = false
        Log.i(TAG, "VadWebRTC released")
    }
}
