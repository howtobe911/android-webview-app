package com.second.risedie.challengeapp.sync

import android.content.Context
import com.second.risedie.challengeapp.health.ServerSyncWindow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import com.second.risedie.challengeapp.security.PlayIntegrityTokenProvider
import com.second.risedie.challengeapp.security.TrustedWebOrigin
import com.second.risedie.challengeapp.security.SecureCredentialStore
import com.second.risedie.challengeapp.security.NativeAuthSession
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HealthSyncApiClient(
    context: Context,
    private val logger: HealthSyncLogger,
) {
    private val credentials = SecureCredentialStore(context.applicationContext)
    private val auth = NativeAuthSession(context.applicationContext)
    private val playIntegrity = PlayIntegrityTokenProvider(context.applicationContext)
    @Volatile private var lastHttpCode: Int? = null
    @Volatile private var sourceSyncHttpCode: Int? = null

    fun lastHttpCode(): Int? = lastHttpCode
    fun sourceSyncHttpCode(): Int? = sourceSyncHttpCode
    fun resetSourceSyncHttpCode() { sourceSyncHttpCode = null }
    private var sessionGeneration = NativeAuthSession.sessionGeneration()

    fun fetchSyncWindow(config: HealthSyncConfig, sessionId: String): ServerSyncWindow {
        sessionGeneration = config.sessionGeneration
        logger.info(sessionId, COMPONENT, "sync_window_request")
        val response = getJson("${config.apiBase}/api/v1/me/activity/sync-window", config.token, sessionId, "sync_window")
        val data = response.optJSONObject("data") ?: response
        val serverDay = data.optString("server_day")
        val windowFrom = data.optString("window_from_utc")
        val windowTo = data.optString("window_to_utc")
        val endsAt = data.optString("server_day_ends_at_utc")
        require(serverDay.isNotBlank() && windowFrom.isNotBlank() && windowTo.isNotBlank() && endsAt.isNotBlank()) {
            "Incomplete server sync window"
        }
        val window = ServerSyncWindow(
            serverDay = serverDay,
            serverTimezone = data.optString("server_timezone", "UTC"),
            windowFromUtc = parseServerInstant(windowFrom),
            windowToUtc = parseServerInstant(windowTo),
            serverDayEndsAtUtc = parseServerInstant(endsAt),
        )
        logger.info(sessionId, COMPONENT, "sync_window_response", JSONObject()
            .put("server_day", window.serverDay)
            .put("server_timezone", window.serverTimezone)
            .put("window_from_utc", window.windowFromUtc.toString())
            .put("window_to_utc", window.windowToUtc.toString()))
        return window
    }

    private fun parseServerInstant(value: String): Instant =
        OffsetDateTime.parse(value).toInstant()

    fun fetchDetailRequests(config: HealthSyncConfig, serverDay: String, sessionId: String): JSONArray {
        val response = getJson(
            "${config.apiBase}/api/v1/me/activity/detail-requests?activity_date=$serverDay",
            config.token,
            sessionId,
            "detail_requests",
        )
        return response.optJSONObject("data")?.optJSONArray("requests") ?: JSONArray()
    }

    fun postSourceSync(config: HealthSyncConfig, body: JSONObject, serverDay: String, sessionId: String): JSONObject {
        ActivityBatchEnvelope.prepare(body)
        val pending = ActivityBatchEnvelope.pending(credentials.readPendingActivityBatch(), config.apiBase, config.sourceId)
        if (pending != null) {
            val response = sendSourceSync(config, pending, pending.optString("server_day", serverDay), sessionId)
            if (pending.optString("idempotency_key") == body.optString("idempotency_key")) return response
        }
        // Commit the frozen payload/key before the first network attempt, including nonce acquisition.
        check(sessionGeneration == NativeAuthSession.sessionGeneration()) { "Native session changed" }
        NativeAuthSession.inSession(sessionGeneration) { credentials.writePendingActivityBatch(ActivityBatchEnvelope.stored(config.apiBase, config.sourceId, body)) }
        return sendSourceSync(config, body, serverDay, sessionId)
    }

    fun retryPendingSourceSync(config: HealthSyncConfig, sessionId: String) {
        val pending = ActivityBatchEnvelope.pending(credentials.readPendingActivityBatch(), config.apiBase, config.sourceId) ?: return
        sendSourceSync(config, pending, pending.optString("server_day"), sessionId)
    }

    private fun sendSourceSync(config: HealthSyncConfig, body: JSONObject, serverDay: String, sessionId: String): JSONObject {
        attachPayloadSecurityIfNeeded(config, body, serverDay, sessionId)
        val response = postJsonForResponse(
            "${config.apiBase}/api/v1/me/sources/sync", config.token, body, sessionId, "source_sync",
        )
        if (!response.optBoolean("success") || response.optJSONObject("data")?.optLong("batch_id", 0L)?.let { it > 0L } != true) {
            throw java.io.IOException("Missing source-sync acknowledgement")
        }
        // A lost 202 or process death leaves the same envelope for the next attempt.
        if (sessionGeneration == NativeAuthSession.sessionGeneration()) NativeAuthSession.inSession(sessionGeneration) { credentials.clearPendingActivityBatch() }
        return response
    }

    fun postDayClose(config: HealthSyncConfig, body: JSONObject, sessionId: String): JSONObject =
        postJsonForResponse(
            "${config.apiBase}/api/v1/me/activity/health-connect/day-close-sync",
            config.token,
            body,
            sessionId,
            "day_close",
        )

    private fun attachPayloadSecurityIfNeeded(
        config: HealthSyncConfig,
        body: JSONObject,
        serverDay: String,
        sessionId: String,
    ) {
        // Every source-sync payload, including the aggregate snapshot, is bound to a
        // one-time nonce and the exact canonical request body. Detail windows are not
        // a privileged security path anymore.
        listOf("nonce", "payload_signature", "signature", "play_integrity_token", "play_integrity").forEach(body::remove)
        val nonceResponse = postJsonForResponse(
            "${config.apiBase}/api/v1/me/activity/payload-nonce",
            config.token,
            JSONObject().put("activity_date", serverDay),
            sessionId,
            "payload_nonce",
        )
        val data = nonceResponse.optJSONObject("data") ?: throw java.io.IOException("Missing payload nonce")
        val nonce = data.optString("nonce")
        val signingKey = data.optString("payload_signing_key")
        if (nonce.isBlank() || signingKey.isBlank()) throw java.io.IOException("Incomplete payload nonce")
        body.put("nonce", nonce)
        body.put("payload_signature", hmacSha256Hex(canonicalActivityPayload(body, data), signingKey))

        val integrityToken = runCatching { playIntegrity.tokenForNonce(nonce) }
            .onFailure {
                logger.warn(
                    sessionId,
                    COMPONENT,
                    "play_integrity_request_failed_non_blocking",
                    JSONObject().put("kind", body.optString("kind")),
                    it,
                )
            }
            .getOrNull()
        if (!integrityToken.isNullOrBlank()) {
            body.put("play_integrity_token", integrityToken)
        }

        logger.info(
            sessionId,
            COMPONENT,
            "payload_security_attached",
            JSONObject()
                .put("kind", body.optString("kind"))
                .put("play_integrity_token_attached", !integrityToken.isNullOrBlank()),
        )
    }

    private fun getJson(url: String, token: String, sessionId: String, operation: String): JSONObject {
        val connection = openConnection(url, token, "GET")
        return execute(connection, null, sessionId, operation)
    }

    private fun postJsonForResponse(
        url: String,
        token: String,
        body: JSONObject,
        sessionId: String,
        operation: String,
    ): JSONObject {
        val connection = openConnection(url, token, "POST").apply { doOutput = true }
        val fields = JSONObject()
            .put("operation", operation)
            .put("kind", body.optString("kind"))
            .put("records_count", body.optJSONArray("records")?.length() ?: 0)
            .put("payload_bytes", body.toString().toByteArray(Charsets.UTF_8).size)
        logger.info(sessionId, COMPONENT, "http_request", fields)
        logger.info(sessionId, COMPONENT, "http_upload_started", fields)
        return execute(connection, body, sessionId, operation)
    }

    private fun openConnection(url: String, token: String, method: String): HttpURLConnection {
        check(sessionGeneration == NativeAuthSession.sessionGeneration()) { "Native session changed" }
        require(TrustedWebOrigin.isTrustedUrl(url)) { "Refusing request to untrusted API origin" }
        val accessToken = NativeAuthSession.inSession(sessionGeneration) { credentials.readAccessToken() }
            ?: throw IllegalStateException("Native session is missing")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            if (method == "POST") setRequestProperty("Content-Type", "application/json")
        }
    }

    private fun execute(
        connection: HttpURLConnection,
        body: JSONObject?,
        sessionId: String,
        operation: String,
        canRefresh: Boolean = true,
    ): JSONObject {
        val sentToken = connection.getRequestProperty("Authorization")?.removePrefix("Bearer ")
        val started = System.nanoTime()
        try {
            check(sessionGeneration == NativeAuthSession.sessionGeneration()) { "Native session changed" }
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = connection.responseCode
            if (code == 401 && canRefresh) {
                val refreshed = JSONObject(auth.refresh(sentToken, sessionGeneration)).optBoolean("ok")
                if (refreshed) {
                    val retry = openConnection(connection.url.toString(), "", connection.requestMethod)
                    if (body != null) retry.doOutput = true
                    return execute(retry, body, sessionId, operation, canRefresh = false)
                }
            }
            lastHttpCode = code
            if (operation == "source_sync") sourceSyncHttpCode = code
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()
            val responseFields = JSONObject()
                .put("operation", operation)
                .put("http_code", code)
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000)
            logger.info(sessionId, COMPONENT, "http_response", responseFields)
            if (body != null) logger.info(sessionId, COMPONENT, "http_upload_completed", responseFields)
            if (code !in 200..299) {
                // A definitive ingress rejection cannot be repaired by retrying a frozen body.
                // Keep uncertain deliveries, authentication failures and retryable server errors.
                if (operation == "source_sync" && code in setOf(400, 404, 409, 413, 422)) {
                    if (sessionGeneration == NativeAuthSession.sessionGeneration()) NativeAuthSession.inSession(sessionGeneration) { credentials.clearPendingActivityBatch() }
                }
                if (code in setOf(408, 425, 429) || code >= 500) {
                    throw java.io.IOException("$operation temporarily failed with HTTP $code")
                }
                throw IllegalStateException("$operation failed with HTTP $code")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (error: Throwable) {
            logger.error(sessionId, COMPONENT, "http_failed", error, JSONObject().put("operation", operation))
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun canonicalActivityPayload(body: JSONObject, nonceData: JSONObject): String = JSONObject()
        .put("user_id", nonceData.optLong("user_id", 0L))
        .put("source_id", body.optLong("source_id", 0L))
        .put("kind", if (body.has("kind")) body.optString("kind") else JSONObject.NULL)
        .put("activity_date", if (body.has("server_day")) body.optString("server_day") else if (body.has("activity_date")) body.optString("activity_date") else JSONObject.NULL)
        .put("timezone", if (body.has("timezone")) body.optString("timezone") else JSONObject.NULL)
        .put("generated_at", if (body.has("generated_at")) body.optString("generated_at") else JSONObject.NULL)
        .put("nonce", if (body.has("nonce")) body.optString("nonce") else JSONObject.NULL)
        .put("app_version", if (body.has("app_version")) body.optString("app_version") else JSONObject.NULL)
        .put("records", body.optJSONArray("records") ?: JSONArray())
        .toString()

    private fun hmacSha256Hex(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val COMPONENT = "api_client"
    }
}
