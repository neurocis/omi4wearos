package com.omi4wos.wear.audio

import android.content.Context
import android.util.Log
import com.omi4wos.shared.Constants
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YAMNet TFLite speech classifier.
 * Classifies 0.975s audio windows (15600 samples at 16kHz) into 521 AudioSet classes.
 * We only care about class index 0 = "Speech".
 *
 * YAMNet model outputs:
 *   - output[0]: float[1][N][521] - class scores per frame
 *   - output[1]: float[1][N][1024] - embeddings
 *   - output[2]: float[1][N] - log-mel spectrogram (intermediate)
 *
 * We use output[0] and average across frames for the speech score.
 */
class SpeechClassifier(context: Context) {

    companion object {
        private const val TAG = "SpeechClassifier"
        private const val MODEL_FILE = "yamnet.tflite"
    }

    data class ClassificationResult(
        val isSpeech: Boolean,
        val confidence: Float,
        val topClassName: String = "",
        val inferenceTimeMs: Long = 0
    )

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    init {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(model, options)
            isInitialized = true
            Log.i(TAG, "YAMNet model loaded successfully")

            // Log model input/output shapes
            val inputTensor = interpreter!!.getInputTensor(0)
            Log.d(TAG, "Input shape: ${inputTensor.shape().contentToString()}, " +
                    "type: ${inputTensor.dataType()}")
            for (i in 0 until interpreter!!.outputTensorCount) {
                val outputTensor = interpreter!!.getOutputTensor(i)
                Log.d(TAG, "Output[$i] shape: ${outputTensor.shape().contentToString()}, " +
                        "type: ${outputTensor.dataType()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load YAMNet model", e)
            isInitialized = false
        }
    }

    /**
     * Classify an audio window for speech presence.
     * @param audioFloat Float array of 15600 samples normalized to [-1.0, 1.0]
     * @return ClassificationResult with speech detection and confidence
     */
    fun classify(audioFloat: FloatArray): ClassificationResult {
        if (!isInitialized || interpreter == null) {
            return ClassificationResult(isSpeech = false, confidence = 0f)
        }

        val startTime = System.currentTimeMillis()

        try {
            // Prepare input: YAMNet expects a 1D float array of 15600 samples
            val inputBuffer = ByteBuffer.allocateDirect(
                Constants.YAMNET_INPUT_SAMPLES * 4
            ).apply {
                order(ByteOrder.nativeOrder())
            }
            for (i in 0 until minOf(audioFloat.size, Constants.YAMNET_INPUT_SAMPLES)) {
                inputBuffer.putFloat(audioFloat[i])
            }
            // Pad with zeros if needed
            for (i in audioFloat.size until Constants.YAMNET_INPUT_SAMPLES) {
                inputBuffer.putFloat(0f)
            }
            inputBuffer.rewind()

            // Run inference
            // YAMNet outputs multiple tensors; we need output[0] for class scores
            val outputScores = Array(1) { FloatArray(Constants.YAMNET_NUM_CLASSES) }

            // For multi-output models, use runForMultipleInputsOutputs
            val outputMap = HashMap<Int, Any>()
            outputMap[0] = outputScores

            // YAMNet has 3 outputs; allocate placeholders for outputs we don't need
            val numOutputs = interpreter!!.outputTensorCount
            for (i in 1 until numOutputs) {
                val shape = interpreter!!.getOutputTensor(i).shape()
                val size = shape.fold(1) { acc, v -> acc * v }
                outputMap[i] = Array(shape[0]) { FloatArray(if (shape.size > 1) shape.last() else size) }
            }

            interpreter!!.runForMultipleInputsOutputs(
                arrayOf(inputBuffer),
                outputMap
            )

            // Extract speech score (class index 0 = "Speech" in AudioSet)
            val speechScore = outputScores[0][Constants.YAMNET_SPEECH_CLASS_INDEX]
            val isSpeech = speechScore > Constants.SPEECH_CONFIDENCE_THRESHOLD

            val inferenceTime = System.currentTimeMillis() - startTime

            if (isSpeech) {
                Log.d(TAG, "Speech detected: confidence=$speechScore (${inferenceTime}ms)")
            }

            return ClassificationResult(
                isSpeech = isSpeech,
                confidence = speechScore,
                topClassName = if (isSpeech) "Speech" else findTopClass(outputScores[0]),
                inferenceTimeMs = inferenceTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            return ClassificationResult(isSpeech = false, confidence = 0f)
        }
    }

    /**
     * Find the top scoring class name (for debug logging).
     */
    private fun findTopClass(scores: FloatArray): String {
        var maxIdx = 0
        var maxScore = scores[0]
        for (i in 1 until scores.size) {
            if (scores[i] > maxScore) {
                maxScore = scores[i]
                maxIdx = i
            }
        }
        return "class_$maxIdx"
    }

    /**
     * Release model resources.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
        Log.i(TAG, "YAMNet model released")
    }
}
