package com.omi4wos.wear.network

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last [MAX_ENTRIES] upload attempts so the Settings screen can
 * display a live upload log without requiring logcat access.
 */
class UploadLog(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(
        val timestampMs: Long,
        val bytes: Int,
        val sessions: Int,
        val success: Boolean
    )

    fun add(bytes: Int, sessions: Int, success: Boolean) {
        val entries = getEntries().toMutableList()
        entries.add(0, Entry(System.currentTimeMillis(), bytes, sessions, success))
        if (entries.size > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size).clear()

        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("ts", e.timestampMs)
                put("bytes", e.bytes)
                put("sessions", e.sessions)
                put("ok", e.success)
            })
        }
        prefs.edit().putString(KEY_LOG, arr.toString()).apply()
    }

    fun getEntries(): List<Entry> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getLong("ts"), o.getInt("bytes"), o.getInt("sessions"), o.getBoolean("ok"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "standalone_upload_log"
        private const val KEY_LOG    = "log"
        private const val MAX_ENTRIES = 20
    }
}
