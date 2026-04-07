package com.omi4wos.wear.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe circular buffer for PCM16 audio samples.
 * Supports writing from the recording thread and reading from the classification thread.
 *
 * The buffer stores the most recent N samples. When full, old samples are overwritten.
 * readLatest() returns the most recent requested number of samples without consuming them.
 */
class CircularAudioBuffer(private val capacity: Int) {

    private val buffer = ShortArray(capacity)
    private var writePos = 0
    private var totalWritten = 0L
    private val lock = ReentrantLock()

    /**
     * Write samples into the circular buffer.
     * Called from the recording thread.
     */
    fun write(samples: ShortArray) {
        lock.withLock {
            for (sample in samples) {
                buffer[writePos % capacity] = sample
                writePos = (writePos + 1) % capacity
                totalWritten++
            }
        }
    }

    /**
     * Read the most recent [count] samples into [dest].
     * Does NOT consume the samples — they remain available for subsequent reads.
     * Returns the actual number of samples copied (may be less than [count] if buffer isn't full yet).
     */
    fun readLatest(dest: ShortArray, count: Int): Int {
        lock.withLock {
            val available = minOf(count, totalWritten.toInt(), capacity)
            if (available == 0) return 0

            // Calculate start position for reading
            var readPos = (writePos - available + capacity) % capacity

            for (i in 0 until available) {
                dest[i] = buffer[readPos]
                readPos = (readPos + 1) % capacity
            }

            // Zero-fill remainder if dest is larger than available
            for (i in available until dest.size) {
                dest[i] = 0
            }

            return available
        }
    }

    /**
     * Returns the number of samples currently available in the buffer.
     */
    fun availableSamples(): Int {
        lock.withLock {
            return minOf(totalWritten.toInt(), capacity)
        }
    }

    /**
     * Clear the buffer.
     */
    fun clear() {
        lock.withLock {
            buffer.fill(0)
            writePos = 0
            totalWritten = 0
        }
    }
}
