package com.second.risedie.challengeapp.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.second.risedie.challengeapp.health.HealthConnectRepository
import com.second.risedie.challengeapp.health.LiveStepTracker
import com.second.risedie.challengeapp.health.ServerSyncWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return@withContext Result.success()
            val apiBase = prefs.getString(KEY_API_BASE, null)?.trimEnd('/')?.takeIf { it.startsWith("https://") } ?: return@withContext Result.success()
            val sourceId = prefs.getLong(KEY_SOURCE_ID, 0L).takeIf { it > 0L } ?: return@withContext Result.success()

            val repository = HealthConnectRepository(applicationContext)
            val liveStepTracker = LiveStepTracker(applicationContext)
            if (!repository.hasPermissions()) return@withContext Result.success()

            val syncWindow = fetchSyncWindow(apiBase, token)
            require(syncWindow.serverTimezone == "UTC") { "Backend sync-window must use UTC" }
            postPreviousDayCloseSnapshotIfNeeded(apiBase, token, sourceId, repository, syncWindow)

            val payload = repository.buildFreshServerWindowSyncPayload(
                serverWindow = syncWindow,
                includeRunDistance = true,
                attempts = 8,
                delayMillis = 750L,
            )
            keepHealthConnectAuthoritative(payload, syncWindow, liveStepTracker)
            val batches = payload.optJSONArray("batches") ?: return@withContext Result.success()
            if (batches.length() == 0) return@withContext Result.success()

            for (index in 0 until batches.length()) {
                val batch = batches.optJSONObject(index) ?: continue
                val kind = batch.optString("kind")
                if (kind in WINDOW_KINDS && kind != "activity_detail_windows") continue
                val body = JSONObject()
                    .put("source_id", sourceId)
                    .put("kind", kind)
                    .put("external_batch_id", batch.optString("external_batch_id"))
                    .put("generated_at", batch.optString("generated_at", payload.optString("generated_at")))
                    .put("device_time", batch.optString("device_time", payload.optString("device_time")))
                    .put("server_day", syncWindow.serverDay)
                    .put("activity_date", syncWindow.serverDay)
                    .put("timezone", "UTC")
                    .put("server_timezone", "UTC")
                    .put("window_from_utc", syncWindow.windowFromUtc.toString())
                    .put("window_to_utc", syncWindow.windowToUtc.toString())
                    .put("server_day_ends_at_utc", syncWindow.serverDayEndsAtUtc.toString())
                    .put("records", batch.optJSONArray("records"))
                    .put("is_live_ui_only", false)
                    .put("source_of_truth", batch.optString("source_of_truth", "health_connect"))
                    .put("health_connect_read_at", payload.optString("generated_at"))
                    .put("client_observed_at", payload.optString("device_time", payload.optString("generated_at")))
                    .apply {
                        if (batch.has("window_size_minutes")) {
                            put("window_size_minutes", batch.optInt("window_size_minutes", 15))
                        }
                    }
                attachPayloadSecurityIfNeeded(apiBase, token, body)
                postJson("$apiBase/api/v1/me/sources/sync", token, body)
            }

            postPendingDetailRequests(apiBase, token, sourceId, repository, syncWindow)

            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
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
            postJson("$apiBase/api/v1/me/activity/health-connect/day-close-sync", token, body)
        }
    }

    private fun keepHealthConnectAuthoritative(payload: JSONObject, syncWindow: ServerSyncWindow, liveStepTracker: LiveStepTracker) {
        // Health Connect remains the only authoritative server sync value.
        // Native counter is used only by the live/pending layer and is never merged into this payload.
        payload.put("preferred_source", "health_connect")
    }



    private suspend fun postPendingDetailRequests(
        apiBase: String,
        token: String,
        sourceId: Long,
        repository: HealthConnectRepository,
        syncWindow: ServerSyncWindow,
    ) {
        val requests = fetchDetailRequests(apiBase, token, syncWindow.serverDay)
        if (requests.length() == 0) return
        val payload = repository.buildDetailWindowsPayload(requests)
        val records = payload.optJSONArray("records") ?: JSONArray()
        if (records.length() == 0) return

        val body = JSONObject()
            .put("source_id", sourceId)
            .put("kind", "activity_detail_windows")
            .put("external_batch_id", payload.optString("external_batch_id"))
            .put("generated_at", payload.optString("generated_at"))
            .put("device_time", payload.optString("device_time", payload.optString("generated_at")))
            .put("server_day", syncWindow.serverDay)
            .put("activity_date", syncWindow.serverDay)
            .put("timezone", "UTC")
            .put("server_timezone", "UTC")
            .put("window_from_utc", syncWindow.windowFromUtc.toString())
            .put("window_to_utc", syncWindow.windowToUtc.toString())
            .put("records", records)
            .put("is_live_ui_only", false)
            .put("source_of_truth", "health_connect")
        attachPayloadSecurityIfNeeded(apiBase, token, body)
        postJsonForResponse("$apiBase/api/v1/me/sources/sync", token, body)
    }

    private fun fetchDetailRequests(apiBase: String, token: String, serverDay: String): JSONArray {
        val response = getJsonForResponse("$apiBase/api/v1/me/activity/detail-requests?activity_date=$serverDay", token)
        return response.optJSONObject("data")?.optJSONArray("requests") ?: JSONArray()
    }

    private fun fetchSyncWindow(apiBase: String, token: String): ServerSyncWindow {
        val response = getJsonForResponse("$apiBase/api/v1/me/activity/sync-window", token)
        val data = response.optJSONObject("data") ?: response
        val serverDay = data.optString("server_day")
        val windowFrom = data.optString("window_from_utc")
        val windowTo = data.optString("window_to_utc")
        val endsAt = data.optString("server_day_ends_at_utc")
        require(serverDay.isNotBlank() && windowFrom.isNotBlank() && windowTo.isNotBlank() && endsAt.isNotBlank()) {
            "Incomplete server sync window"
        }
        return ServerSyncWindow(
            serverDay = serverDay,
            serverTimezone = data.optString("server_timezone", "UTC"),
            windowFromUtc = Instant.parse(windowFrom),
            windowToUtc = Instant.parse(windowTo),
            serverDayEndsAtUtc = Instant.parse(endsAt),
        )
    }

    private fun getJsonForResponse(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("Health sync window failed with HTTP $code")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }


    private fun attachPayloadSecurityIfNeeded(apiBase: String, token: String, body: JSONObject) {
        val kind = body.optString("kind")
        if (kind !in FINAL_WINDOW_KINDS) return

        val activityDate = fetchSyncWindow(apiBase, token).serverDay
        val nonceResponse = postJsonForResponse(
            "$apiBase/api/v1/me/activity/payload-nonce",
            token,
            JSONObject().put("activity_date", activityDate),
        )
        val data = nonceResponse.optJSONObject("data") ?: return
        val nonce = data.optString("nonce")
        val signingKey = data.optString("payload_signing_key")
        if (nonce.isBlank() || signingKey.isBlank()) return

        body.put("nonce", nonce)
        body.put("payload_signature", hmacSha256Hex(canonicalActivityPayload(body, data), signingKey))
    }

    private fun canonicalActivityPayload(body: JSONObject, nonceData: JSONObject): String {
        val canonical = JSONObject()
            .put("user_id", nonceData.optLong("user_id", 0L))
            .put("source_id", body.optLong("source_id", 0L))
            .put("kind", if (body.has("kind")) body.optString("kind") else JSONObject.NULL)
            .put("activity_date", if (body.has("server_day")) body.optString("server_day") else if (body.has("activity_date")) body.optString("activity_date") else JSONObject.NULL)
            .put("timezone", if (body.has("timezone")) body.optString("timezone") else JSONObject.NULL)
            .put("generated_at", if (body.has("generated_at")) body.optString("generated_at") else JSONObject.NULL)
            .put("nonce", if (body.has("nonce")) body.optString("nonce") else JSONObject.NULL)
            .put("app_version", if (body.has("app_version")) body.optString("app_version") else JSONObject.NULL)
            .put("records", body.optJSONArray("records") ?: JSONArray())
        return canonical.toString()
    }

    private fun postJsonForResponse(url: String, token: String, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("Health sync helper failed with HTTP $code")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun hmacSha256Hex(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun postJson(url: String, token: String, body: JSONObject) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Health sync failed with HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PREFS = "grafit_native_health_sync"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_SOURCE_ID = "source_id"
        private val WINDOW_KINDS = setOf("walk_steps_windows", "activity_windows", "activity_detail_windows")
        private val FINAL_WINDOW_KINDS = setOf("activity_detail_windows")
        private const val PERIODIC_WORK = "grafit_health_connect_periodic_sync"
        private const val IMMEDIATE_WORK = "grafit_health_connect_immediate_sync"

        fun configure(context: Context, token: String, apiBase: String, sourceId: Long) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_API_BASE, apiBase.trimEnd('/'))
                .putLong(KEY_SOURCE_ID, sourceId)
                .apply()
        }

        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(Random.nextLong(0, 15), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
