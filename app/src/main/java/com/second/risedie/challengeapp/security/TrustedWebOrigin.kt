package com.second.risedie.challengeapp.security

import com.second.risedie.challengeapp.BuildConfig
import org.json.JSONArray
import java.net.URI
import java.util.Locale

/** Single allow-list policy for WebView navigation and native authenticated HTTP calls. */
object TrustedWebOrigin {
    fun canonicalOrigin(
        rawUrl: String?,
        allowedHostsJson: String = BuildConfig.APP_ALLOWED_HOSTS_JSON,
    ): String? {
        val value = rawUrl?.trim().orEmpty()
        if (value.isBlank()) return null

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null

        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host.isBlank() || host !in allowedHosts(allowedHostsJson)) return null

        val port = uri.port
        if (port != -1 && port != 443) return null

        return "https://$host"
    }

    fun isTrustedUrl(
        rawUrl: String?,
        allowedHostsJson: String = BuildConfig.APP_ALLOWED_HOSTS_JSON,
    ): Boolean = canonicalOrigin(rawUrl, allowedHostsJson) != null

    internal fun allowedHosts(rawJson: String): Set<String> = runCatching {
        val array = JSONArray(rawJson)
        buildSet {
            for (index in 0 until array.length()) {
                val host = array.optString(index).trim().lowercase(Locale.ROOT).trimEnd('.')
                if (host.isNotBlank()) add(host)
            }
        }
    }.getOrDefault(emptySet())
}
