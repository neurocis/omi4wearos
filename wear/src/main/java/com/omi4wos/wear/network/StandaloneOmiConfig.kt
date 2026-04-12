package com.omi4wos.wear.network

import android.content.Context
import android.content.SharedPreferences
import com.omi4wos.wear.BuildConfig

/**
 * Persists Firebase credentials for the standalone build.
 *
 * On first access the SharedPreferences are seeded from the BuildConfig values that
 * were injected at compile time from local.properties:
 *
 *   OMI_FIREBASE_WEB_API_KEY=AIzaSy...
 *   OMI_FIREBASE_TOKEN=eyJ...
 *   OMI_FIREBASE_REFRESH_TOKEN=AMf...
 *
 * Token refresh updates only the idToken + refreshToken in SharedPreferences so the
 * new tokens survive process restarts without requiring a rebuild.
 */
class StandaloneOmiConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Credentials(
        val firebaseWebApiKey: String = "",
        val idToken: String = "",
        val refreshToken: String = "",
        val tokenExpiresAtSecs: Long = 0L
    ) {
        val isConfigured: Boolean
            get() = firebaseWebApiKey.isNotBlank() && refreshToken.isNotBlank()
    }

    fun load(): Credentials {
        // Seed from BuildConfig on first run (SharedPrefs empty)
        if (!prefs.contains(KEY_ID_TOKEN) && BuildConfig.OMI_FIREBASE_TOKEN.isNotBlank()) {
            prefs.edit()
                .putString(KEY_API_KEY,       BuildConfig.OMI_FIREBASE_WEB_API_KEY)
                .putString(KEY_ID_TOKEN,      BuildConfig.OMI_FIREBASE_TOKEN)
                .putString(KEY_REFRESH_TOKEN, BuildConfig.OMI_FIREBASE_REFRESH_TOKEN)
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() / 1000L + 3600L)
                .apply()
        }
        return Credentials(
            firebaseWebApiKey  = prefs.getString(KEY_API_KEY, BuildConfig.OMI_FIREBASE_WEB_API_KEY) ?: "",
            idToken            = prefs.getString(KEY_ID_TOKEN, BuildConfig.OMI_FIREBASE_TOKEN) ?: "",
            refreshToken       = prefs.getString(KEY_REFRESH_TOKEN, BuildConfig.OMI_FIREBASE_REFRESH_TOKEN) ?: "",
            tokenExpiresAtSecs = prefs.getLong(KEY_EXPIRES_AT, 0L)
        )
    }

    /** Called by [StandaloneOmiApiClient] after a successful token refresh. */
    fun saveRefreshedToken(idToken: String, refreshToken: String, expiresAtSecs: Long) {
        prefs.edit()
            .putString(KEY_ID_TOKEN,      idToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT,      expiresAtSecs)
            .apply()
    }

    /** Called by [com.omi4wos.wear.setup.SetupServer] when user submits the web form. */
    fun saveFromSetup(
        firebaseWebApiKey: String,
        idToken: String,
        refreshToken: String,
        tokenExpiresAtSecs: Long
    ) {
        prefs.edit()
            .putString(KEY_API_KEY,       firebaseWebApiKey)
            .putString(KEY_ID_TOKEN,      idToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT,      tokenExpiresAtSecs)
            .apply()
    }

    companion object {
        private const val PREFS_NAME        = "standalone_omi_config"
        private const val KEY_API_KEY       = "firebase_web_api_key"
        private const val KEY_ID_TOKEN      = "id_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT    = "token_expires_at_secs"
    }
}
