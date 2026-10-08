package com.second.risedie.challengeapp.sync

import android.content.Context
import com.second.risedie.challengeapp.security.SecureCredentialStore
import com.second.risedie.challengeapp.security.TrustedWebOrigin

data class HealthSyncConfig(
    val token: String,
    val apiBase: String,
    val sourceId: Long,
)

class HealthSyncConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val credentials = SecureCredentialStore(appContext)

    fun save(token: String, apiBase: String, sourceId: Long) {
        val trustedApiBase = TrustedWebOrigin.canonicalOrigin(apiBase)
            ?: throw IllegalArgumentException("Untrusted native API origin")
        require(token.isNotBlank()) { "Missing native API token" }
        require(sourceId > 0L) { "Invalid activity source" }

        credentials.writeAccessToken(token)
        prefs.edit()
            .remove(KEY_TOKEN)
            .putString(KEY_API_BASE, trustedApiBase)
            .putLong(KEY_SOURCE_ID, sourceId)
            .apply()
    }

    fun load(): HealthSyncConfig? {
        val token = credentials.readAccessToken()?.takeIf { it.isNotBlank() } ?: return null
        val apiBase = TrustedWebOrigin.canonicalOrigin(prefs.getString(KEY_API_BASE, null)) ?: return null
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
