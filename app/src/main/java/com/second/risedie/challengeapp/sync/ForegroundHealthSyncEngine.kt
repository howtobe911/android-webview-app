package com.second.risedie.challengeapp.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.second.risedie.challengeapp.health.HealthConnectRepository
import com.second.risedie.challengeapp.health.LiveStepTracker
import com.second.risedie.challengeapp.health.ServerSyncWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ForegroundHealthSyncEngine(
    context: Context,
    private val liveStepTracker: LiveStepTracker,
    private val emitSyncEvent: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val repository = HealthConnectRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var foreground = false
    @Volatile private var loopJob: Job? = null

    fun configure(token: String, apiBase: String, sourceId: Long) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_API_BASE, apiBase.trimEnd('/'))
            .putLong(KEY_SOURCE_ID, sourceId)
            .apply()
    }

    fun requestForegroundSync(reason: String): JSONObject {
        val requestId = UUID.randomUUID().toString()
        scope.launch { syncNowForeground(reason.ifBlank { "manual_refresh" }, requestId) }
        return JSONObject()
            .put("queued", true)
            .put("request_id", requestId)
            .put("reason", reason.ifBlank { "manual_refresh" })
    }

    fun onAppForeground(reason: String = "app_resume") {
        foreground = true
        startForegroundLoop()
        // App launch/resume is a hard sync trigger. Do not short-circuit with
        // skipped_recent: Health Connect providers may publish fresher aggregates
        // exactly when the WebView returns to foreground.
        requestForegroundSync(reason)
    }

    fun onAppBackground() {
        foreground = false
        loopJob?.cancel()
        loopJob = null
    }

    fun startForegroundLoop() {
        if (loopJob?.isActive == true) return
        foreground = true
        loopJob = scope.launch {
            while (isActive && foreground) {
                delay(nextForegroundLoopDelayMs())
                if (!foreground) break
                if (mutex.isLocked) {
                    emit(JSONObject().put("type", "skipped_already_running").put("reason", "foreground_loop").put("synced_at", Instant.now().toString()))
                    continue
                }
                syncNowForeground("foreground_loop", UUID.randomUUID().toString())
            }
        }
    }

    private fun nextForegroundLoopDelayMs(): Long = Random.nextLong(
        FOREGROUND_LOOP_INTERVAL_MIN_MS,
        FOREGROUND_LOOP_INTERVAL_MAX_MS + 1,
    )

    suspend fun syncNowForeground(reason: String, requestId: String = UUID.randomUUID().toString()): JSONObject {
        return mutex.withLock {
            emit(JSONObject().put("type", "started").put("request_id", requestId).put("reason", reason).put("synced_at", Instant.now().toString()))
            val result = try {
                withTimeout(SYNC_TIMEOUT_MS) { syncInternal(reason, requestId) }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                JSONObject().put("type", "timeout").put("success", false).put("fresh", false).put("request_id", requestId).put("reason", reason).put("message", "Foreground sync timeout").put("synced_at", Instant.now().toString())
            } catch (error: Throwable) {
                JSONObject().put("type", "failed").put("success", false).put("fresh", false).put("request_id", requestId).put("reason", reason).put("message", error.message ?: error.javaClass.simpleName).put("synced_at", Instant.now().toString())
            }
            emit(result)
            result
        }
    }

    private suspend fun syncInternal(reason: String, requestId: String): JSONObject = withContext(Dispatchers.IO) {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: return@withContext fail("not_configured", requestId, reason, "Auth token is not configured")
        val apiBase = prefs.getString(KEY_API_BASE, null)?.trimEnd('/')?.takeIf { it.startsWith("https://") }
            ?: return@withContext fail("not_configured", requestId, reason, "API base is not configured")
        val sourceId = prefs.getLong(KEY_SOURCE_ID, 0L).takeIf { it > 0L }
            ?: return@withContext fail("not_configured", requestId, reason, "Activity source id is not configured")

        if (repository.sdkStatus() != HealthConnectClient.SDK_AVAILABLE) {
            return@withContext fail("unavailable", requestId, reason, "Health Connect is unavailable")
        }
        if (!repository.hasPermissions()) {
            return@withContext JSONObject().put("type", "no_permissions").put("success", false).put("fresh", false).put("request_id", requestId).put("reason", reason).put("synced_at", Instant.now().toString())
        }

        val syncWindow = fetchSyncWindow(apiBase, token)
        require(syncWindow.serverTimezone == "UTC") { "Backend sync-window must use UTC" }

        postPreviousDayCloseSnapshotIfNeeded(apiBase, token, sourceId, repository, syncWindow)

        val payload = repository.buildFreshServerWindowSyncPayload(syncWindow, includeRunDistance = true, attempts = 8, delayMillis = 750L)
        keepHealthConnectAuthoritative(payload, syncWindow)
        val batches = payload.optJSONArray("batches") ?: JSONArray()
        if (batches.length() == 0) {
            return@withContext JSONObject()
                .put("type", "no_data")
                .put("success", false)
                .put("fresh", false)
                .put("request_id", requestId)
                .put("reason", reason)
                .put("server_day", syncWindow.serverDay)
                .put("server_timezone", "UTC")
                .put("window_from_utc", syncWindow.windowFromUtc.toString())
                .put("window_to_utc", syncWindow.windowToUtc.toString())
                .put("message", payload.optString("message", "Health Connect returned no records"))
                .put("synced_at", Instant.now().toString())
        }

        var accepted = 0
        var duplicate = 0
        var rejected = 0
        var posted = 0
        var currentState: JSONObject? = null
        var totals: JSONObject? = null

        for (index in 0 until batches.length()) {
            val batch = batches.optJSONObject(index) ?: continue
            val kind = batch.optString("kind")
            if (kind in WINDOW_KINDS && kind != "activity_detail_windows") continue
            val records = batch.optJSONArray("records") ?: JSONArray()
            if (records.length() == 0) continue

            val body = JSONObject()
                .put("source_id", sourceId)
                .put("kind", kind)
                .put("external_batch_id", batch.optString("external_batch_id"))
                .put("generated_at", batch.optString("generated_at", payload.optString("generated_at")))
                .put("device_time", batch.optString("device_time", payload.optString("device_time")))
                .put("source_day", batch.optString("source_day", payload.optString("source_day")))
                .put("timezone", batch.optString("timezone", payload.optString("timezone")))
                .put("server_day", syncWindow.serverDay)
                .put("server_timezone", "UTC")
                .put("window_from_utc", syncWindow.windowFromUtc.toString())
                .put("window_to_utc", syncWindow.windowToUtc.toString())
                .put("server_day_ends_at_utc", syncWindow.serverDayEndsAtUtc.toString())
                .put("source_day_role", "metadata_only")
                .put("records", records)
                .put("is_live_ui_only", false)
                .put("source_of_truth", "health_connect")
                .put("health_connect_read_at", payload.optString("generated_at"))
                .put("client_observed_at", payload.optString("device_time", payload.optString("generated_at")))
            if (batch.has("window_size_minutes")) body.put("window_size_minutes", batch.optInt("window_size_minutes", 15))
            attachPayloadSecurityIfNeeded(apiBase, token, body, syncWindow.serverDay)
            val response = postJsonForResponse("$apiBase/api/v1/me/sources/sync", token, body)
            val data = response.optJSONObject("data") ?: JSONObject()
            accepted += data.optInt("accepted_records", 0)
            duplicate += data.optInt("duplicate_records", 0)
            rejected += data.optInt("rejected_records", 0)
            currentState = data.optJSONObject("current_state") ?: currentState
            totals = data.optJSONObject("authoritative_totals") ?: totals
            posted += 1
        }

        val type = if (accepted > 0 || duplicate > 0 || currentState != null || posted > 0) "success" else "no_data"
        val fresh = type == "success"
        if (fresh) setLastSuccessfulSyncAt(Instant.now())
        JSONObject()
            .put("type", type)
            .put("success", fresh)
            .put("fresh", fresh)
            .put("request_id", requestId)
            .put("reason", reason)
            .put("server_day", syncWindow.serverDay)
            .put("server_timezone", "UTC")
            .put("window_from_utc", syncWindow.windowFromUtc.toString())
            .put("window_to_utc", syncWindow.windowToUtc.toString())
            .put("accepted_records", accepted)
            .put("duplicate_records", duplicate)
            .put("rejected_records", rejected)
            .put("authoritative_totals", totals ?: JSONObject())
            .put("current_state", currentState ?: JSONObject())
            .put("should_reload_snapshot", fresh)
            .put("synced_at", Instant.now().toString())
    }


    private suspend fun postPreviousDayCloseSnapshotIfNeeded(
        apiBase: String,
        token: String,
        sourceId: Long,
        repository: HealthConnectRepository,
        syncWindow: ServerSyncWindow,
    ) {
        val previousDay = LocalDate.parse(syncWindow.serverDay).minusDays(1)
        val payload = repository.buildDayCloseSnapshotPayload(previousDay, includeRunDistance = true)
        if (payload.optString("activity_date").isBlank()) return

        val body = JSONObject(payload.toString())
            .put("source_id", sourceId)
        runCatching {
            postJsonForResponse("$apiBase/api/v1/me/activity/health-connect/day-close-sync", token, body)
        }
    }

    private fun keepHealthConnectAuthoritative(payload: JSONObject, syncWindow: ServerSyncWindow) {
        // Health Connect remains the only authoritative server sync value.
        // Native counter is used only by the live/pending layer and is never merged into this payload.
        payload.put("preferred_source", "health_connect")
    }

    private fun fail(type: String, requestId: String, reason: String, message: String): JSONObject = JSONObject()
        .put("type", type)
        .put("success", false)
        .put("fresh", false)
        .put("request_id", requestId)
        .put("reason", reason)
        .put("message", message)
        .put("synced_at", Instant.now().toString())

    private fun fetchSyncWindow(apiBase: String, token: String): ServerSyncWindow {
        val response = getJsonForResponse("$apiBase/api/v1/me/activity/sync-window", token)
        val data = response.optJSONObject("data") ?: response
        val serverDay = data.optString("server_day")
        val windowFrom = data.optString("window_from_utc")
        val windowTo = data.optString("window_to_utc")
        val endsAt = data.optString("server_day_ends_at_utc")
        require(serverDay.isNotBlank() && windowFrom.isNotBlank() && windowTo.isNotBlank() && endsAt.isNotBlank()) { "Incomplete server sync window" }
        return ServerSyncWindow(serverDay, data.optString("server_timezone", "UTC"), Instant.parse(windowFrom), Instant.parse(windowTo), Instant.parse(endsAt))
    }

    private fun getJsonForResponse(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/json"); setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("GET failed with HTTP $code")
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun postJsonForResponse(url: String, token: String, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 20_000; doOutput = true
            setRequestProperty("Accept", "application/json"); setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("POST failed with HTTP $code: $text")
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun attachPayloadSecurityIfNeeded(apiBase: String, token: String, body: JSONObject, serverDay: String) {
        if (body.optString("kind") !in FINAL_WINDOW_KINDS) return
        val nonceResponse = postJsonForResponse("$apiBase/api/v1/me/activity/payload-nonce", token, JSONObject().put("activity_date", serverDay))
        val data = nonceResponse.optJSONObject("data") ?: return
        val nonce = data.optString("nonce")
        val signingKey = data.optString("payload_signing_key")
        if (nonce.isBlank() || signingKey.isBlank()) return
        body.put("nonce", nonce)
        body.put("payload_signature", hmacSha256Hex(nonce, signingKey))
    }

    private fun hmacSha256Hex(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun emit(payload: JSONObject) = emitSyncEvent(payload.toString())

    private fun lastSuccessfulSyncAt(): Instant? = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_SUCCESS, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun setLastSuccessfulSyncAt(value: Instant) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_SUCCESS, value.toString()).apply()
    }

    companion object {
        private const val PREFS = "grafit_native_health_sync"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_SOURCE_ID = "source_id"
        private const val KEY_LAST_SUCCESS = "last_successful_foreground_sync_at"
        private const val SYNC_TIMEOUT_MS = 25_000L
        private const val FOREGROUND_LOOP_INTERVAL_MIN_MS = 90_000L
        private const val FOREGROUND_LOOP_INTERVAL_MAX_MS = 180_000L
        private val WINDOW_KINDS = setOf("walk_steps_windows", "activity_windows", "activity_detail_windows")
        private val FINAL_WINDOW_KINDS = setOf("activity_detail_windows")
    }
}
