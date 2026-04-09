package com.omi4wos.wear.audio

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD via OnnxRuntime 1.14.1 (downgraded from 1.20 which SIGBUS on armeabi-v7a).
 * Model loaded via file path (mmap) rather than byte array to minimise alignment exposure.
 */
class SpeechClassifier(private val context: Context) {

    companion object {
        private const val TAG = "SpeechClassifier"
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val FRAME_SAMPLES = 512       // 32ms at 16kHz
        private const val SAMPLE_RATE = 16000L
        private const val NUM_LAYERS = 2
        private const val HIDDEN_SIZE = 64
        private const val SPEECH_THRESHOLD = 0.5f
        private const val MIN_SPEECH_FRAMES = 4     // out of 30 per 960ms window
    }

    data class ClassificationResult(
        val isSpeech: Boolean,
        val confidence: Float,
        val topClassName: String = "",
        val inferenceTimeMs: Long = 0
    )

    private var session: OrtSession? = null
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var h = FloatArray(NUM_LAYERS * HIDDEN_SIZE)
    private var c = FloatArray(NUM_LAYERS * HIDDEN_SIZE)
    private var isInitialized = false

    init {
        try {
            val modelFile = extractModelIfNeeded()
            session = ortEnv.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            isInitialized = true
            Log.i(TAG, "Silero VAD initialized (OnnxRuntime 1.14.1, file-path load)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Silero VAD", e)
        }
    }

    private fun extractModelIfNeeded(): File {
        val dst = File(context.filesDir, MODEL_ASSET)
        if (!dst.exists()) {
            context.assets.open(MODEL_ASSET).use { it.copyTo(dst.outputStream()) }
            Log.i(TAG, "Silero model extracted: ${dst.length()} bytes")
        }
        return dst
    }

    private fun inferFrame(audio: FloatArray, frameOffset: Int): Float {
        val localSession = session ?: return 0f
        val frame = FloatArray(FRAME_SAMPLES) { i -> audio[frameOffset + i] }

        val audioTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(frame), longArrayOf(1, FRAME_SAMPLES.toLong()))
        val srTensor    = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf(1))
        val hTensor     = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(h.copyOf()), longArrayOf(NUM_LAYERS.toLong(), 1, HIDDEN_SIZE.toLong()))
        val cTensor     = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(c.copyOf()), longArrayOf(NUM_LAYERS.toLong(), 1, HIDDEN_SIZE.toLong()))

        return try {
            val result = localSession.run(mapOf("input" to audioTensor, "sr" to srTensor, "h" to hTensor, "c" to cTensor))

            @Suppress("UNCHECKED_CAST")
            val prob = (result[0].value as Array<FloatArray>)[0][0]

            @Suppress("UNCHECKED_CAST")
            val hn = result[1].value as Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            val cn = result[2].value as Array<Array<FloatArray>>
            var idx = 0
            for (layer in 0 until NUM_LAYERS) for (k in 0 until HIDDEN_SIZE) {
                h[idx] = hn[layer][0][k]; c[idx] = cn[layer][0][k]; idx++
            }

            result.close()
            prob
        } finally {
            audioTensor.close(); srTensor.close(); hTensor.close(); cTensor.close()
        }
    }

    fun classify(audioShort: ShortArray): ClassificationResult {
        if (!isInitialized) return ClassificationResult(isSpeech = false, confidence = 0f)

        val startTime = System.currentTimeMillis()
        val audioFloat = FloatArray(audioShort.size) { i -> audioShort[i] / 32768f }

        var speechFrames = 0
        var sumProb = 0f
        val numFrames = audioShort.size / FRAME_SAMPLES

        for (i in 0 until numFrames) {
            val prob = inferFrame(audioFloat, i * FRAME_SAMPLES)
            if (prob >= SPEECH_THRESHOLD) speechFrames++
            sumProb += prob
        }

        val confidence = sumProb / numFrames
        val isSpeech = speechFrames >= MIN_SPEECH_FRAMES
        val inferenceTime = System.currentTimeMillis() - startTime

        if (isSpeech) {
            Log.d(TAG, "Speech: frames=$speechFrames/$numFrames conf=${"%.2f".format(confidence)} (${inferenceTime}ms)")
        } else if (speechFrames > 0) {
            Log.d(TAG, "Rejected: frames=$speechFrames/$numFrames below gate (${inferenceTime}ms)")
        }

        return ClassificationResult(isSpeech = isSpeech, confidence = confidence, topClassName = "Speech", inferenceTimeMs = inferenceTime)
    }

    fun resetState() {
        h = FloatArray(NUM_LAYERS * HIDDEN_SIZE)
        c = FloatArray(NUM_LAYERS * HIDDEN_SIZE)
    }

    fun close() {
        session?.close()
        session = null
        isInitialized = false
        Log.i(TAG, "Silero VAD released")
    }
}
