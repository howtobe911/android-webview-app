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
        val response = getJson("${config.apiBase}/api/v1/me/activity/sync-window?source_id=${config.sourceId}", config.token, sessionId, "sync_window")
        val data = response.optJSONObject("data") ?: response
        val serverDay = data.optString("server_day")
        val windowFrom = data.optString("window_from_utc")
        val windowTo = data.optString("window_to_utc")
        val endsAt = data.optString("server_day_ends_at_utc")
        require(serverDay.isNotBlank() && windowFrom.isNotBlank() && windowTo.isNotBlank() && endsAt.isNotBlank()) {
            "Incomplete server sync window"
        }
        val sealedToken = data.optString("health_connect_window_token")
        require(sealedToken.isNotBlank()) { "Backend did not issue a replay-safe Health Connect window" }
        val window = ServerSyncWindow(
            serverDay = serverDay,
            serverTimezone = data.optString("server_timezone", "UTC"),
            windowFromUtc = parseServerInstant(windowFrom),
            windowToUtc = parseServerInstant(windowTo),
            serverDayEndsAtUtc = parseServerInstant(endsAt),
            healthConnectWindowToken = sealedToken,
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

    fun fetchDetailRequests(config: HealthSyncConfig, sessionId: String): JSONArray {
        // Pending requests may target previous UTC days. Never filter to today.
        val response = getJson(
            "${config.apiBase}/api/v1/me/activity/detail-requests?limit=3&source_id=${config.sourceId}",
            config.token,
            sessionId,
            "detail_requests",
        )
        return response.optJSONObject("data")?.optJSONArray("requests") ?: JSONArray()
    }

    fun fetchSyncStatus(config: HealthSyncConfig, batchId: Long, sessionId: String): String =
        getJson("${config.apiBase}/api/v1/me/sources/sync-status/$batchId", config.token, sessionId, "sync_status")
            .optJSONObject("data")?.optString("processing_status", "processing") ?: "processing"

    fun reportDetailProgress(config: HealthSyncConfig, requestId: Long, state: String, errorCode: String?, sessionId: String) {
        // Optional diagnostics must never prevent delivery of actual evidence.
        runCatching {
            postJsonForResponse("${config.apiBase}/api/v1/me/activity/detail-requests/$requestId/report",
                config.token, JSONObject().put("state", state)
                    .put("error_code", errorCode ?: JSONObject.NULL)
                    .put("app_version", com.second.risedie.challengeapp.BuildConfig.VERSION_NAME),
                sessionId, "detail_progress")
        }.onFailure { logger.warn(sessionId, COMPONENT, "detail_progress_not_delivered", error = it) }
    }

    fun postSourceSync(config: HealthSyncConfig, body: JSONObject, serverDay: String, sessionId: String): JSONObject {
        ActivityBatchEnvelope.prepare(body)
        check(sessionGeneration == NativeAuthSession.sessionGeneration()) { "Native session changed" }
        val items = readOutbox(config)
        val key = body.optString("idempotency_key")
        var item = items.firstOrNull { it.optJSONObject("body")?.optString("idempotency_key") == key }
        if (item == null) {
            item = JSONObject()
                .put("body", JSONObject(body.toString()))
                .put("server_day", serverDay)
                .put("batch_id", 0L)
            items.add(item)
            writeOutbox(config, items) // Before the first nonce or HTTP request.
        }
        val existingBatchId = item.optLong("batch_id", 0L)
        if (existingBatchId > 0L) {
            return JSONObject().put("success", true)
                .put("data", JSONObject().put("batch_id", existingBatchId))
                .put("meta", JSONObject().put("queued", true))
        }
        try {
            val response = sendSourceSync(config, item.getJSONObject("body"), serverDay, sessionId)
            item.put("batch_id", response.optJSONObject("data")!!.optLong("batch_id"))
            writeOutbox(config, items) // 202 is queue admission, not processed.
            return response
        } catch (error: TerminalSourceSyncRejection) {
            items.remove(item)
            writeOutbox(config, items)
            logger.warn(sessionId, COMPONENT, "discarded_terminal_pending_batch", error = error)
            throw error
        }
    }

    fun retryPendingSourceSync(config: HealthSyncConfig, sessionId: String) {
        sessionGeneration = config.sessionGeneration
        val items = readOutbox(config)
        for (item in items.sortedBy { if (it.optString("endpoint") == "day_close") 0 else 1 }
            .take(MAX_OUTBOX_REPLAY_PER_RUN)) {
            val body = item.optJSONObject("body") ?: continue
            val batchId = item.optLong("batch_id", 0L)
            try {
                if (item.optString("endpoint") == "day_close") {
                    val close = sendDayClose(config, body, sessionId)
                    val status = close.optJSONObject("data")?.optString("status", "") ?: ""
                    if (status == "settlement_open") rememberDayCloseAcknowledgement(config, body)
                    else logger.warn(sessionId, COMPONENT, "day_close_outbox_not_accepted", JSONObject().put("status", status))
                    items.remove(item)
                    writeOutbox(config, items)
                    continue
                }
                if (batchId > 0L) {
                    val processingStatus = fetchSyncStatus(config, batchId, sessionId)
                    when (processingStatus) {
                        "processed", "detail_pending" -> {
                            items.remove(item)
                            writeOutbox(config, items)
                        }
                        "rejected", "failed" -> {
                            logger.warn(sessionId, COMPONENT, "outbox_processing_rejected", JSONObject()
                                .put("batch_id", batchId).put("processing_status", processingStatus))
                            items.remove(item)
                            writeOutbox(config, items)
                        }
                        else -> Unit // Still queued; keep immutable payload for the next run.
                    }
                } else {
                    val response = sendSourceSync(config, body, item.optString("server_day"), sessionId)
                    item.put("batch_id", response.optJSONObject("data")!!.optLong("batch_id"))
                    writeOutbox(config, items)
                }
            } catch (error: TerminalSourceSyncRejection) {
                if (batchId > 0L && error.httpCode == 404) {
                    // Expired/unknown receipt: resend the SAME frozen logical key.
                    item.put("batch_id", 0L)
                } else {
                    items.remove(item)
                    logger.warn(sessionId, COMPONENT, "discarded_terminal_pending_batch", error = error)
                }
                writeOutbox(config, items)
            }
        }
    }

    private fun readOutbox(config: HealthSyncConfig): MutableList<JSONObject> = ActivityBatchEnvelope.queue(
        credentials.readPendingActivityBatch(), config.apiBase, config.sourceId,
    )

    private fun writeOutbox(config: HealthSyncConfig, items: List<JSONObject>) {
        check(sessionGeneration == NativeAuthSession.sessionGeneration()) { "Native session changed" }
        NativeAuthSession.inSession(sessionGeneration) {
            if (items.isEmpty()) credentials.clearPendingActivityBatch()
            else credentials.writePendingActivityBatch(ActivityBatchEnvelope.storedQueue(
                config.apiBase, config.sourceId, items,
            ))
        }
    }

    private fun sendSourceSync(config: HealthSyncConfig, body: JSONObject, serverDay: String, sessionId: String): JSONObject {
        attachPayloadSecurityIfNeeded(config, body, serverDay, sessionId)
        val response = postJsonForResponse(
            "${config.apiBase}/api/v1/me/sources/sync", config.token, body, sessionId, "source_sync",
        )
        if (!response.optBoolean("success") || response.optJSONObject("data")?.optLong("batch_id", 0L)?.let { it > 0L } != true) {
            throw java.io.IOException("Missing source-sync acknowledgement")
        }
        // A 202 is not a processed receipt; the encrypted FIFO owns this batch
        // until sync-status confirms backend import or definitive rejection.
        return response
    }

    /** Reusing a settled source hash saves HTTP and prevents request churn. */
    fun alreadyAcknowledgedDayClose(config: HealthSyncConfig, body: JSONObject): Boolean {
        val stored = credentials.readAcknowledgedDayClose() ?: return false
        val ack = runCatching { JSONObject(stored) }.getOrNull() ?: return false
        return ack.optString("api_base") == config.apiBase
            && ack.optLong("source_id") == config.sourceId
            && ack.optString("activity_date") == body.optString("activity_date")
            && ack.optString("source_hash").isNotBlank()
            && ack.optString("source_hash") == body.optString("source_hash")
    }

    private fun rememberDayCloseAcknowledgement(config: HealthSyncConfig, body: JSONObject) {
        credentials.writeAcknowledgedDayClose(JSONObject()
            .put("api_base", config.apiBase)
            .put("source_id", config.sourceId)
            .put("activity_date", body.optString("activity_date"))
            .put("source_hash", body.optString("source_hash"))
            .toString())
    }

    fun postDayClose(config: HealthSyncConfig, body: JSONObject, sessionId: String): JSONObject {
        val items = readOutbox(config)
        val key = body.optString("activity_date") + ":" + body.optString("source_hash")
        var item = items.firstOrNull { it.optString("endpoint") == "day_close"
            && it.optString("logical_key") == key }
        if (item == null) {
            item = JSONObject().put("endpoint", "day_close").put("logical_key", key)
                .put("body", JSONObject(body.toString()))
            items.add(item)
            writeOutbox(config, items)
        }
        try {
            val response = sendDayClose(config, item.getJSONObject("body"), sessionId)
            val status = response.optJSONObject("data")?.optString("status", "") ?: ""
            if (status == "settlement_open") rememberDayCloseAcknowledgement(config, item.getJSONObject("body"))
            else logger.warn(sessionId, COMPONENT, "day_close_outbox_not_accepted", JSONObject().put("status", status))
            items.remove(item)
            writeOutbox(config, items)
            return response
        } catch (error: TerminalSourceSyncRejection) {
            items.remove(item)
            writeOutbox(config, items)
            logger.warn(sessionId, COMPONENT, "discarded_terminal_pending_day_close", error = error)
            throw error
        }
    }

    private fun sendDayClose(config: HealthSyncConfig, body: JSONObject, sessionId: String): JSONObject =
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
                if (operation in setOf("source_sync", "sync_status", "day_close")
                    && code in setOf(400, 403, 404, 409, 413, 422)) {
                    throw TerminalSourceSyncRejection(code, operation)
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
        private const val MAX_OUTBOX_REPLAY_PER_RUN = 6
    }

    private class TerminalSourceSyncRejection(val httpCode: Int, operation: String) :
        IllegalStateException("$operation definitively rejected with HTTP $httpCode")
}
