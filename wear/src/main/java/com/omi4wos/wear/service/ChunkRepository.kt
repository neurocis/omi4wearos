package com.omi4wos.wear.service

import android.content.Context
import android.util.Log
import com.omi4wos.shared.AudioChunk
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Local filesystem repository for Store-and-Forward caching.
 * Persists Omi Opus bursts natively on the Watch disk to guarantee 
 * survival across temporary disconnections, app closes, or prolonged OS reboots.
 */
class ChunkRepository(context: Context) {

    companion object {
        private const val TAG = "ChunkRepository"
        // Prevent catastrophic watch out-of-storage by strictly capping offline audio to 500MB
        private const val MAX_DIRECTORY_SIZE_BYTES = 500L * 1024L * 1024L 
    }

    private val directory: File = File(context.filesDir, "pending_chunks")

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    /**
     * Serializes and writes a native chunk directly to disk.
     */
    fun saveChunk(chunk: AudioChunk) {
        try {
            enforceStorageQuota()

            // Construct chronological filename: chunk_timestamp_segmentId_index.dat
            val filename = "chunk_${chunk.timestampMs}_${chunk.segmentId}_${chunk.chunkIndex}.dat"
            val file = File(directory, filename)

            DataOutputStream(FileOutputStream(file)).use { dos ->
                dos.writeUTF(chunk.segmentId)
                dos.writeInt(chunk.chunkIndex)
                dos.writeLong(chunk.timestampMs)
                dos.writeLong(chunk.durationMs)
                dos.writeFloat(chunk.speechConfidence)
                dos.writeBoolean(chunk.isFinal)
                dos.writeInt(chunk.audioData.size)
                dos.write(chunk.audioData)
            }
            Log.v(TAG, "Cached to disk: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache chunk to disk", e)
        }
    }

    /**
     * Returns chronologically sorted queued chunk files.
     */
    fun getPendingChunkFiles(): List<File> {
        val files = directory.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }
            ?: return emptyList()
        // File sorting by name works perfectly chronologically because we prefixed with timestampMs
        return files.sortedBy { it.name }
    }

    /**
     * Inflates a specific native file back into an Opus AudioChunk object.
     */
    fun readChunk(file: File): AudioChunk? {
        if (!file.exists()) return null
        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                val segmentId = dis.readUTF()
                val chunkIndex = dis.readInt()
                val timestampMs = dis.readLong()
                val durationMs = dis.readLong()
                val speechConfidence = dis.readFloat()
                val isFinal = dis.readBoolean()
                val dataSize = dis.readInt()
                val audioData = ByteArray(dataSize)
                dis.readFully(audioData)

                AudioChunk(
                    segmentId = segmentId,
                    chunkIndex = chunkIndex,
                    timestampMs = timestampMs,
                    durationMs = durationMs,
                    speechConfidence = speechConfidence,
                    isFinal = isFinal,
                    audioData = audioData
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cached chunk: ${file.name}", e)
            null
        }
    }

    /**
     * Purges successfully transmitted chunks natively off the disk.
     */
    fun deleteChunkFile(file: File) {
        if (file.exists()) {
            file.delete()
            Log.v(TAG, "Purged synced chunk: ${file.name}")
        }
    }

    /**
     * Natively manages bounded directory scaling, automatically rotating the oldest bounds off disk.
     */
    private fun enforceStorageQuota() {
        var currentSize = calculateDirectorySize()
        if (currentSize > MAX_DIRECTORY_SIZE_BYTES) {
            val files = getPendingChunkFiles()
            for (file in files) {
                val size = file.length()
                if (file.delete()) {
                    currentSize -= size
                    Log.w(TAG, "Storage quota exceeded. Dropped oldest chunk: ${file.name}")
                }
                if (currentSize <= MAX_DIRECTORY_SIZE_BYTES * 0.9) { // Clear until 90% full to prevent rapid flapping
                    break
                }
            }
        }
    }

    private fun calculateDirectorySize(): Long {
        return directory.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
