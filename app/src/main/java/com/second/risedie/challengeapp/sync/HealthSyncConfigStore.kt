package com.second.risedie.challengeapp.sync

import android.content.Context

data class HealthSyncConfig(
    val token: String,
    val apiBase: String,
    val sourceId: Long,
)

class HealthSyncConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(token: String, apiBase: String, sourceId: Long) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_API_BASE, apiBase.trimEnd('/'))
            .putLong(KEY_SOURCE_ID, sourceId)
            .apply()
    }

    fun load(): HealthSyncConfig? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val apiBase = prefs.getString(KEY_API_BASE, null)?.trimEnd('/')?.takeIf { it.startsWith("https://") } ?: return null
        val sourceId = prefs.getLong(KEY_SOURCE_ID, 0L).takeIf { it > 0L } ?: return null
        return HealthSyncConfig(token, apiBase, sourceId)
    }

    companion object {
        const val PREFS = "grafit_native_health_sync"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_SOURCE_ID = "source_id"
    }
}
