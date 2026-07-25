package com.second.risedie.challengeapp.sync

import com.second.risedie.challengeapp.health.ServerSyncWindow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HealthSyncApiClient(
    private val logger: HealthSyncLogger,
) {
    @Volatile private var lastHttpCode: Int? = null
    @Volatile private var sourceSyncHttpCode: Int? = null

    fun lastHttpCode(): Int? = lastHttpCode
    fun sourceSyncHttpCode(): Int? = sourceSyncHttpCode
    fun resetSourceSyncHttpCode() { sourceSyncHttpCode = null }
    fun fetchSyncWindow(config: HealthSyncConfig, sessionId: String): ServerSyncWindow {
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
            windowFromUtc = Instant.parse(windowFrom),
            windowToUtc = Instant.parse(windowTo),
            serverDayEndsAtUtc = Instant.parse(endsAt),
        )
        logger.info(sessionId, COMPONENT, "sync_window_response", JSONObject()
            .put("server_day", window.serverDay)
            .put("server_timezone", window.serverTimezone)
            .put("window_from_utc", window.windowFromUtc.toString())
            .put("window_to_utc", window.windowToUtc.toString()))
        return window
    }

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
        attachPayloadSecurityIfNeeded(config, body, serverDay, sessionId)
        return postJsonForResponse(
            "${config.apiBase}/api/v1/me/sources/sync",
            config.token,
            body,
            sessionId,
            "source_sync",
        )
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
        if (body.optString("kind") !in FINAL_WINDOW_KINDS) return
        val nonceResponse = postJsonForResponse(
            "${config.apiBase}/api/v1/me/activity/payload-nonce",
            config.token,
            JSONObject().put("activity_date", serverDay),
            sessionId,
            "payload_nonce",
        )
        val data = nonceResponse.optJSONObject("data") ?: return
        val nonce = data.optString("nonce")
        val signingKey = data.optString("payload_signing_key")
        if (nonce.isBlank() || signingKey.isBlank()) return
        body.put("nonce", nonce)
        body.put("payload_signature", hmacSha256Hex(canonicalActivityPayload(body, data), signingKey))
        logger.info(sessionId, COMPONENT, "payload_security_attached", JSONObject().put("kind", body.optString("kind")))
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

    private fun openConnection(url: String, token: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            if (method == "POST") setRequestProperty("Content-Type", "application/json")
        }

    private fun execute(
        connection: HttpURLConnection,
        body: JSONObject?,
        sessionId: String,
        operation: String,
    ): JSONObject {
        val started = System.nanoTime()
        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = connection.responseCode
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
            if (code !in 200..299) throw IllegalStateException("$operation failed with HTTP $code")
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
        private val FINAL_WINDOW_KINDS = setOf("activity_detail_windows")
    }
}
