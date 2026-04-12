package com.omi4wos.wear.network

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists Firebase credentials and the Firebase Web API key for the standalone build.
 * Everything is user-configured at setup time — nothing is hardcoded.
 *
 * Users obtain their Firebase Web API Key by:
 *   1. Opening app.omi.me in a browser
 *   2. Opening DevTools (F12) → Network tab
 *   3. Filtering for "googleapis.com" requests
 *   4. Copying the `key=` query parameter value
 */
class StandaloneOmiConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Credentials(
        val firebaseWebApiKey: String = "",
        val idToken: String = "",
        val refreshToken: String = "",
        val userId: String = "",
        val tokenExpiresAtSecs: Long = 0L
    ) {
        /** True when the user has completed setup and a refresh token is available. */
        val isConfigured: Boolean
            get() = firebaseWebApiKey.isNotBlank() && refreshToken.isNotBlank() && userId.isNotBlank()
    }

    fun load(): Credentials = Credentials(
        firebaseWebApiKey  = prefs.getString(KEY_API_KEY, "") ?: "",
        idToken            = prefs.getString(KEY_ID_TOKEN, "") ?: "",
        refreshToken       = prefs.getString(KEY_REFRESH_TOKEN, "") ?: "",
        userId             = prefs.getString(KEY_USER_ID, "") ?: "",
        tokenExpiresAtSecs = prefs.getLong(KEY_EXPIRES_AT, 0L)
    )

    fun save(creds: Credentials) {
        prefs.edit()
            .putString(KEY_API_KEY,       creds.firebaseWebApiKey)
            .putString(KEY_ID_TOKEN,      creds.idToken)
            .putString(KEY_REFRESH_TOKEN, creds.refreshToken)
            .putString(KEY_USER_ID,       creds.userId)
            .putLong(KEY_EXPIRES_AT,      creds.tokenExpiresAtSecs)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME        = "standalone_omi_config"
        private const val KEY_API_KEY       = "firebase_web_api_key"
        private const val KEY_ID_TOKEN      = "id_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID       = "user_id"
        private const val KEY_EXPIRES_AT    = "token_expires_at_secs"
    }
}
