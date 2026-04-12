package com.omi4wos.wear.network

import android.content.Context
import android.util.Log
import com.omi4wos.shared.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads speech sessions directly to api.omi.me using Firebase bearer tokens.
 *
 * Sessions: chunks separated by ≤ SESSION_GAP_MS are grouped together and uploaded
 * as a single binary file (raw concatenated Opus frames, 320 samples/frame at 16 kHz).
 *
 * Token refresh: if the idToken expires within 60 seconds, [ensureValidToken] silently
 * exchanges the refreshToken via the Firebase Secure Token Service before uploading.
 */
class StandaloneOmiApiClient(private val context: Context) {

    private val config = StandaloneOmiConfig(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads [chunks] to Omi Cloud, grouped into sessions by time.
     * All HTTP work is dispatched to [Dispatchers.IO].
     * Returns true only if every session uploaded successfully.
     */
    suspend fun uploadChunks(chunks: List<AudioChunk>): Boolean = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext true

        var creds = ensureValidToken() ?: return@withContext false
        val sessions = groupIntoSessions(chunks)

        var allOk = true
        for (session in sessions) {
            val bytes = session
                .filter { it.audioData.isNotEmpty() }
                .map { it.audioData }
                .reduceOrNull { acc, b -> acc + b }
                ?: continue

            val timestampSecs = session.first().timestampMs / 1000L
            val result = uploadSession(creds, bytes, timestampSecs)

            if (result == UploadResult.TOKEN_EXPIRED) {
                // idToken was stale despite expiry check — force a refresh and retry once
                Log.i(TAG, "Got 401, forcing token refresh and retrying…")
                val refreshed = refreshToken(creds)
                if (refreshed == null) {
                    allOk = false
                    continue
                }
                creds = refreshed
                if (uploadSession(creds, bytes, timestampSecs) != UploadResult.SUCCESS) allOk = false
            } else if (result != UploadResult.SUCCESS) {
                allOk = false
            }
        }
        allOk
    }

    private enum class UploadResult { SUCCESS, TOKEN_EXPIRED, FAILED }

    private fun uploadSession(
        creds: StandaloneOmiConfig.Credentials,
        audioBytes: ByteArray,
        timestampSecs: Long
    ): UploadResult {
        val fileName = "recording_fs320_${timestampSecs}.bin"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio_file",
                fileName,
                audioBytes.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.omi.me/v2/sync-local-files")
            .addHeader("Authorization", "Bearer ${creds.idToken}")
            .post(body)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        Log.i(TAG, "Upload success: $fileName (${audioBytes.size} bytes)")
                        UploadResult.SUCCESS
                    }
                    response.code == 401 -> {
                        Log.w(TAG, "Upload 401 (token expired): $fileName")
                        UploadResult.TOKEN_EXPIRED
                    }
                    else -> {
                        Log.w(TAG, "Upload failed ${response.code}: $fileName")
                        UploadResult.FAILED
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Upload IO error: $fileName", e)
            UploadResult.FAILED
        }
    }

    /**
     * Groups [chunks] into sessions. A new session begins when consecutive chunks
     * are separated by more than [SESSION_GAP_MS] (5 minutes).
     */
    private fun groupIntoSessions(chunks: List<AudioChunk>): List<List<AudioChunk>> {
        val sorted = chunks.sortedBy { it.timestampMs }
        val sessions = mutableListOf<MutableList<AudioChunk>>()
        var current = mutableListOf<AudioChunk>()
        sessions.add(current)

        for (chunk in sorted) {
            if (current.isEmpty()) {
                current.add(chunk)
            } else {
                val gap = chunk.timestampMs - current.last().timestampMs
                if (gap > SESSION_GAP_MS) {
                    current = mutableListOf()
                    sessions.add(current)
                }
                current.add(chunk)
            }
        }
        return sessions
    }

    /**
     * Returns valid credentials, refreshing the idToken first if it expires within 60 seconds.
     */
    private fun ensureValidToken(): StandaloneOmiConfig.Credentials? {
        val creds = config.load()
        if (!creds.isConfigured) {
            Log.w(TAG, "Not configured — skipping upload. Run Setup from the watch app.")
            return null
        }
        val nowSecs = System.currentTimeMillis() / 1000L
        return if (creds.tokenExpiresAtSecs - nowSecs < 60) refreshToken(creds) else creds
    }

    private fun refreshToken(creds: StandaloneOmiConfig.Credentials): StandaloneOmiConfig.Credentials? {
        val body = "grant_type=refresh_token&refresh_token=${creds.refreshToken}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url("https://securetoken.googleapis.com/v1/token?key=${creds.firebaseWebApiKey}")
            .post(body)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return null
                val json = JSONObject(text)
                val newIdToken  = json.optString("id_token").takeIf { it.isNotBlank() } ?: return null
                val newRefresh  = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: creds.refreshToken
                val expiresIn   = json.optLong("expires_in", 3600L)
                val expiresAtSecs = System.currentTimeMillis() / 1000L + expiresIn
                config.saveRefreshedToken(newIdToken, newRefresh, expiresAtSecs)
                Log.i(TAG, "Token refreshed (expires in ${expiresIn}s)")
                creds.copy(
                    idToken            = newIdToken,
                    refreshToken       = newRefresh,
                    tokenExpiresAtSecs = expiresAtSecs
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            null
        }
    }

    companion object {
        private const val TAG             = "StandaloneOmiApiClient"
        private const val SESSION_GAP_MS  = 5 * 60 * 1000L // 5 minutes → new Omi conversation
    }
}
