package com.omi4wos.mobile.omi

import android.util.Log
import com.omi4wos.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
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
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
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

    /**
     * Upload an aggregated .bin Opus file directly to the /v2/sync-local-files endpoint.
     * This mimics the Limitless Pendant payload.
     *
     * @param firebaseToken Google Auth token matching the user's web session
     * @param binFile The raw .bin file constructed on phone
     * @param uploadName The required Limitless naming convention e.g. recording_fs320_12345.bin
     * @return Raw JSON response containing job_id or immediate memories, null on failure.
     */
    suspend fun uploadAudioSync(
        firebaseToken: String,
        binFile: File,
        uploadName: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.omi.me/v2/sync-local-files"
            
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "files",
                    uploadName,
                    binFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $firebaseToken")
                .post(requestBody)
                .build()

            Log.d(TAG, "Uploading raw .bin to Omi: $uploadName (${binFile.length()} bytes)")

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            val body = response.body?.string()
            val statusCode = response.code
            response.close()

            if (success) {
                Log.i(TAG, "Audio sync accepted ($statusCode): $body")
                body
            } else {
                Log.w(TAG, "Audio sync failed ($statusCode): $body")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio sync exception", e)
            null
        }
    }

    /**
     * Upload multiple .bin files in a single multipart POST to /v2/sync-local-files.
     * Used in batch mode so all segments from one sync session become one Omi job,
     * preventing the backend from fragmenting them into separate conversations.
     *
     * @param firebaseToken Valid Firebase ID token
     * @param files List of (File, uploadName) pairs — all files are added as "files" parts
     * @return Raw JSON response string, or null on failure
     */
    suspend fun uploadAudioBatch(
        firebaseToken: String,
        files: List<Pair<File, String>>
    ): String? = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext null
        try {
            val url = "https://api.omi.me/v2/sync-local-files"

            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            for ((file, uploadName) in files) {
                builder.addFormDataPart(
                    "files",
                    uploadName,
                    file.asRequestBody("application/octet-stream".toMediaType())
                )
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $firebaseToken")
                .post(builder.build())
                .build()

            Log.d(TAG, "Batch uploading ${files.size} .bin file(s) to Omi")

            val response = client.newCall(request).execute()
            val success  = response.isSuccessful
            val body     = response.body?.string()
            val code     = response.code
            response.close()

            if (success) {
                Log.i(TAG, "Batch audio sync accepted ($code): $body")
                body
            } else {
                Log.w(TAG, "Batch audio sync failed ($code): $body")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Batch audio sync exception", e)
            null
        }
    }

    /**
     * Refreshes the Firebase token if expired using the refresh token and web API key.
     * Returns the valid Firebase ID Token.
     */
    suspend fun getValidFirebaseToken(
        omiConfig: OmiConfig
    ): String? = withContext(Dispatchers.IO) {
        val config = omiConfig.getConfig()
        val nowSecs = System.currentTimeMillis() / 1000

        // If the token is valid (with 60s buffer), use it
        if (config.firebaseToken.isNotBlank() && config.firebaseTokenExpiresAt - 60 > nowSecs) {
            return@withContext config.firebaseToken
        }

        // Token expired or no expiry set. Try to refresh if we have credentials
        if (config.firebaseRefreshToken.isNotBlank() && config.firebaseWebApiKey.isNotBlank()) {
            Log.i(TAG, "Firebase token expired or expiring soon. Requesting refresh...")
            try {
                val url = "https://securetoken.googleapis.com/v1/token?key=${config.firebaseWebApiKey}"
                val jsonBody = JSONObject().apply {
                    put("grant_type", "refresh_token")
                    put("refresh_token", config.firebaseRefreshToken)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                val bodyStr = response.body?.string()
                response.close()

                if (success && bodyStr != null) {
                    val respObj = JSONObject(bodyStr)
                    val newIdToken = respObj.getString("id_token")
                    val newRefreshToken = respObj.getString("refresh_token")
                    val expiresIn = respObj.getLong("expires_in")
                    
                    val newExpiresAt = nowSecs + expiresIn

                    omiConfig.saveConfig(
                        config.copy(
                            firebaseToken = newIdToken,
                            firebaseRefreshToken = newRefreshToken,
                            firebaseTokenExpiresAt = newExpiresAt
                        )
                    )
                    Log.i(TAG, "Successfully refreshed Firebase token.")
                    return@withContext newIdToken
                } else {
                    Log.w(TAG, "Token refresh failed: $bodyStr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh exception", e)
            }
        } else if (config.firebaseToken.isNotBlank() && config.firebaseTokenExpiresAt == 0L) {
             // Fallback for first run where user just pasted the ID token manually.
             // Assume they literally just grabbed it, so set expiry to 1 hr from now.
             Log.i(TAG, "No expiry found. Seeding cache to 1 hour from now for manual token.")
             omiConfig.saveConfig(
                 config.copy(firebaseTokenExpiresAt = nowSecs + 3600)
             )
             return@withContext config.firebaseToken
        }

        return@withContext if (config.firebaseToken.isNotBlank()) config.firebaseToken else null
    }
}
