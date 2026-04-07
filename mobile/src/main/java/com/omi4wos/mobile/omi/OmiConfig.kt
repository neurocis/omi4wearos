package com.omi4wos.mobile.omi

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omi4wos.shared.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "omi_settings")

/**
 * Manages Omi API configuration stored in DataStore preferences.
 */
class OmiConfig(private val context: Context) {

    data class Config(
        val apiKey: String = "",
        val appId: String = "",
        val userId: String = ""
    ) {
        val isConfigured: Boolean
            get() = apiKey.isNotBlank() && appId.isNotBlank() && userId.isNotBlank()
    }

    companion object {
        private val KEY_API_KEY = stringPreferencesKey(Constants.PREF_OMI_API_KEY)
        private val KEY_APP_ID = stringPreferencesKey(Constants.PREF_OMI_APP_ID)
        private val KEY_USER_ID = stringPreferencesKey(Constants.PREF_OMI_USER_ID)
    }

    /**
     * Get the current Omi configuration.
     */
    suspend fun getConfig(): Config {
        return context.dataStore.data.map { prefs ->
            Config(
                apiKey = prefs[KEY_API_KEY] ?: "",
                appId = prefs[KEY_APP_ID] ?: "",
                userId = prefs[KEY_USER_ID] ?: ""
            )
        }.first()
    }

    /**
     * Save the Omi configuration.
     */
    suspend fun saveConfig(config: Config) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_KEY] = config.apiKey
            prefs[KEY_APP_ID] = config.appId
            prefs[KEY_USER_ID] = config.userId
        }
    }

    /**
     * Observe configuration changes as a Flow.
     */
    fun observeConfig() = context.dataStore.data.map { prefs ->
        Config(
            apiKey = prefs[KEY_API_KEY] ?: "",
            appId = prefs[KEY_APP_ID] ?: "",
            userId = prefs[KEY_USER_ID] ?: ""
        )
    }

    /**
     * Clear all configuration.
     */
    suspend fun clearConfig() {
        context.dataStore.edit { it.clear() }
    }
}
