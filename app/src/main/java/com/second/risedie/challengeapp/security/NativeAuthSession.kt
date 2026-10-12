package com.second.risedie.challengeapp.security

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the long-lived refresh credential outside WebView storage.
 * JavaScript receives only the short-lived access token returned after a native refresh.
 */
class NativeAuthSession(context: Context) {
    private val credentials = SecureCredentialStore(context.applicationContext)

    fun storeRefreshToken(token: String?): Boolean {
        val normalized = token?.trim().orEmpty()
        if (normalized.isBlank()) return false
        return runCatching {
            credentials.writeRefreshToken(normalized)
            true
        }.getOrDefault(false)
    }

    fun clear() {
        credentials.clearSessionTokens()
    }

    fun refresh(apiBase: String?): String {
        val origin = TrustedWebOrigin.canonicalOrigin(apiBase)
            ?: return result(false, null, "untrusted_origin")
        val refreshToken = credentials.readRefreshToken()
            ?: return result(false, null, "refresh_token_missing")

        val connection = runCatching {
            (URL("$origin/api/v1/auth/refresh").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { output ->
                    val body = JSONObject().put("refresh_token", refreshToken).toString()
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
        }.getOrElse { return result(false, null, "network_error") }

        return try {
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")

            if (status !in 200..299) {
                if (status == 401 || status == 403 || status == 422) clear()
                return result(false, null, if (status == 429 || status >= 500) "retryable" else "refresh_rejected")
            }

            val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
            val accessToken = data?.optString("token")?.trim().orEmpty()
            val rotatedRefreshToken = data?.optString("refresh_token")?.trim().orEmpty()
            if (accessToken.isBlank() || rotatedRefreshToken.isBlank()) {
                clear()
                return result(false, null, "invalid_response")
            }

            credentials.writeAccessToken(accessToken)
            credentials.writeRefreshToken(rotatedRefreshToken)
            result(true, accessToken, null)
        } catch (_: Throwable) {
            result(false, null, "network_error")
        } finally {
            connection.disconnect()
        }
    }

    private fun result(ok: Boolean, token: String?, reason: String?): String = JSONObject()
        .put("ok", ok)
        .put("token", token)
        .put("reason", reason)
        .toString()
}
