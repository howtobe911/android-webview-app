package com.second.risedie.challengeapp.security

import android.content.Context
import android.webkit.CookieManager
import com.second.risedie.challengeapp.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Existing device login/refresh, with credentials kept out of the JavaScript response. */
class NativeAuthSession(context: Context) {
    private val credentials = SecureCredentialStore(context.applicationContext)
    private val origin = requireNotNull(TrustedWebOrigin.canonicalOrigin(BuildConfig.APP_WEB_URL))

    fun authenticate(operation: String?, payloadJson: String?): String = synchronized(sessionLock) {
        if (operation !in setOf("login", "register")) return@synchronized result(false, "invalid_operation")
        val input = runCatching { JSONObject(payloadJson.orEmpty()) }.getOrNull()
            ?: return@synchronized result(false, "invalid_payload")
        // A capability with a fixed endpoint and a narrow payload, never a raw-token setter.
        val fields = setOf("login", "password", "nickname", "email", "personal_data_consent", "terms_accepted",
            "legal_version", "install_uuid", "device_name", "device_model", "platform", "app_version", "os_version", "push_token")
        val body = JSONObject()
        fields.forEach { if (input.has(it)) body.put(it, input.get(it)) }
        val reply = exchange(operation!!, body) ?: return@synchronized result(false, "network_error")
        val status = reply.first
        val payload = reply.second
        if (status in 200..299) {
            if (!acceptCredentials(payload)) return@synchronized result(false, "invalid_response")
            credentials.clearPendingActivityBatch()
        }
        // Both success and error bodies are sanitized before crossing the bridge.
        payload.optJSONObject("data")?.remove("token")
        payload.optJSONObject("data")?.remove("refresh_token")
        JSONObject().put("ok", status in 200..299).put("status", status).put("payload", payload).toString()
    }

    fun refresh(failedToken: String? = null): String = synchronized(sessionLock) {
        val current = credentials.readAccessToken()
        if (failedToken != null && current != null && current != failedToken) {
            installWebCookie(current)
            return@synchronized result(true, null)
        }
        val refreshToken = credentials.readRefreshToken()
            ?: return@synchronized result(false, "refresh_token_missing")
        val reply = exchange("refresh", JSONObject().put("refresh_token", refreshToken))
            ?: return@synchronized result(false, "network_error")
        val status = reply.first
        if (status !in 200..299) {
            if (status == 401 || status == 403 || status == 422) clear()
            return@synchronized result(false, if (status == 429 || status >= 500) "retryable" else "refresh_rejected")
        }
        if (!acceptCredentials(reply.second)) {
            clear()
            return@synchronized result(false, "invalid_response")
        }
        result(true, null)
    }

    /** Invoked natively before the first document loads, never by a JS token setter. */
    fun restoreWebSession() = synchronized(sessionLock) {
        if (credentials.readRefreshToken() != null) refresh()
        val access = credentials.readAccessToken()
        if (access != null) installWebCookie(access) else clearWebCookie()
    }

    fun clear() = synchronized(sessionLock) {
        credentials.clearSessionTokens()
        clearWebCookie()
    }

    private fun acceptCredentials(payload: JSONObject): Boolean {
        val data = payload.optJSONObject("data") ?: return false
        val access = data.optString("token").trim()
        val refresh = data.optString("refresh_token").trim()
        if (access.isBlank() || refresh.isBlank()) return false
        credentials.writeSessionTokens(access, refresh)
        installWebCookie(access)
        data.put("authenticated", true)
        return true
    }

    private fun installWebCookie(access: String) {
        val cookies = CookieManager.getInstance()
        for (host in TrustedWebOrigin.allowedHosts(BuildConfig.APP_ALLOWED_HOSTS_JSON)) {
            val origin = "https://$host"
            // Session cookie: restored from secure native refresh at application startup.
            cookies.setCookie(origin, "__Host-grafit_session=$access; Path=/; Secure; HttpOnly; SameSite=Strict")
            cookies.setCookie(origin, "grafit_token=; Path=/; Max-Age=0; Secure; SameSite=Lax")
            // Android must never retain a browser refresh cookie from an older web login.
            cookies.setCookie(origin, "__Secure-grafit_refresh=; Path=/api/v1/auth/refresh; Max-Age=0; Secure; HttpOnly; SameSite=Strict")
        }
    }

    private fun clearWebCookie() {
        val cookies = CookieManager.getInstance()
        for (host in TrustedWebOrigin.allowedHosts(BuildConfig.APP_ALLOWED_HOSTS_JSON)) {
            val origin = "https://$host"
            cookies.setCookie(origin, "__Host-grafit_session=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict")
            cookies.setCookie(origin, "__Secure-grafit_refresh=; Path=/api/v1/auth/refresh; Max-Age=0; Secure; HttpOnly; SameSite=Strict")
            cookies.setCookie(origin, "grafit_token=; Path=/; Max-Age=0; Secure; SameSite=Lax")
        }
    }

    private fun exchange(operation: String, body: JSONObject): Pair<Int, JSONObject>? {
        val connection = (URL("$origin/api/v1/auth/$operation").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            status to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun result(ok: Boolean, reason: String?): String = JSONObject()
        .put("ok", ok).put("reason", reason).toString()

    companion object {
        // Login, refresh and logout from WebView/background clients cannot rotate concurrently.
        private val sessionLock = Any()
    }
}
