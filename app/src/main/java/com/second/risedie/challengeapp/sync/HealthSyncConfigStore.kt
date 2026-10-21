package com.second.risedie.challengeapp.sync

import android.content.Context
import com.second.risedie.challengeapp.security.NativeAuthSession
import com.second.risedie.challengeapp.security.SecureCredentialStore
import com.second.risedie.challengeapp.security.TrustedWebOrigin

data class HealthSyncConfig(
    val token: String,
    val apiBase: String,
    val sourceId: Long,
    val sessionGeneration: Long = NativeAuthSession.sessionGeneration(),
)

class HealthSyncConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val credentials = SecureCredentialStore(appContext)

    fun save(token: String, apiBase: String, sourceId: Long) {
        val generation = NativeAuthSession.sessionGeneration()
        val trustedApiBase = TrustedWebOrigin.canonicalOrigin(apiBase)
            ?: throw IllegalArgumentException("Untrusted native API origin")
        require(token.isNotBlank()) { "Missing native API token" }
        require(sourceId > 0L) { "Invalid activity source" }

        NativeAuthSession.inSession(generation) {
            check(credentials.readAccessToken() == token) { "Native credentials changed" }
            prefs.edit()
                .remove(KEY_TOKEN)
                .putString(KEY_API_BASE, trustedApiBase)
                .putLong(KEY_SOURCE_ID, sourceId)
                .commit()
        }
    }

    fun load(): HealthSyncConfig? {
        val generation = NativeAuthSession.sessionGeneration()
        return runCatching { NativeAuthSession.inSession(generation) {
            val token = credentials.readAccessToken()?.takeIf { it.isNotBlank() } ?: return@inSession null
            val apiBase = TrustedWebOrigin.canonicalOrigin(prefs.getString(KEY_API_BASE, null)) ?: return@inSession null
            val sourceId = prefs.getLong(KEY_SOURCE_ID, 0L).takeIf { it > 0L } ?: return@inSession null
            HealthSyncConfig(token, apiBase, sourceId, generation)
        } }.getOrNull()
    }

    companion object {
        const val PREFS = "grafit_native_health_sync"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_SOURCE_ID = "source_id"
    }
}
