package com.omi4wos.wear.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.omi4wos.shared.Constants

/**
 * Wraps Android AudioRecord for continuous 16kHz PCM16 mono recording.
 * Audio samples are delivered via a callback on a dedicated recording thread.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false

    private val sampleRate = Constants.SAMPLE_RATE
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Start recording audio. The callback is invoked on the recording thread
     * with each buffer of PCM16 samples.
     */
    fun start(onAudioData: (ShortArray) -> Unit) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize")
            return
        }

        // Use 2x min buffer for headroom
        val bufferSize = minBufferSize * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            // Maximize CPU sleep cycle by drastically expanding hardware read buffer
            // Matches exactly to the 0.975s YAMNet constraint (15600 samples)
            val readSizeSamples = Constants.YAMNET_INPUT_SAMPLES

            recordingThread = Thread({
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                )

                val buffer = ShortArray(readSizeSamples)
                Log.i(TAG, "Recording started: ${sampleRate}Hz, buffer=$readSizeSamples samples")

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        // Deliver a copy to avoid buffer reuse issues
                        val copy = buffer.copyOf(read)
                        onAudioData(copy)
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        break
                    }
                }

                Log.i(TAG, "Recording thread exiting")
            }, "AudioRecordThread").apply {
                isDaemon = true
                start()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "No RECORD_AUDIO permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    /**
     * Stop recording and release resources.
     */
    fun stop() {
        isRecording = false

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord stop error", e)
        }

        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.release()
        audioRecord = null

        Log.i(TAG, "Recording stopped")
    }
}
