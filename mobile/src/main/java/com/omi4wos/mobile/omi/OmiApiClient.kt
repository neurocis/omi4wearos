package com.omi4wos.mobile.omi

import android.util.Log
import com.omi4wos.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for the Omi API.
 * Uploads transcripts as conversations via:
 *   POST /v2/integrations/{app_id}/user/conversations?uid={user_id}
 */
class OmiApiClient {

    companion object {
        private const val TAG = "OmiApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        )
        .build()

    /**
     * Upload a conversation transcript to Omi.
     *
     * @param apiKey The Omi API key (sk_...)
     * @param appId The Omi app ID
     * @param userId The Omi user ID
     * @param text The transcript text
     * @param startedAt ISO 8601 timestamp when the conversation started
     * @param finishedAt ISO 8601 timestamp when the conversation ended
     * @return true if upload succeeded (HTTP 200-299)
     */
    suspend fun uploadConversation(
        apiKey: String,
        appId: String,
        userId: String,
        text: String,
        startedAt: String,
        finishedAt: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${Constants.OMI_BASE_URL}" +
                    String.format(Constants.OMI_CONVERSATIONS_PATH, appId, userId)

            val jsonBody = JSONObject().apply {
                put("text", text)
                put("started_at", startedAt)
                put("finished_at", finishedAt)
                put("text_source", "audio_transcript")
                put("text_source_spec", "wearos_watch")
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            Log.d(TAG, "Uploading to Omi: $url")

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            val body = response.body?.string()

            if (success) {
                Log.i(TAG, "Upload successful: ${response.code}")
            } else {
                Log.w(TAG, "Upload failed: ${response.code} - $body")
            }

            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception", e)
            false
        }
    }

    /**
     * Test the Omi API connection with the given credentials.
     * @return Pair of (success, message)
     */
    suspend fun testConnection(
        apiKey: String,
        appId: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = "${Constants.OMI_BASE_URL}/v2/integrations/$appId/conversations"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            val body = response.body?.string()
            response.close()

            if (success) {
                Pair(true, "Connection successful")
            } else {
                Pair(false, "HTTP ${response.code}: $body")
            }
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }
}
