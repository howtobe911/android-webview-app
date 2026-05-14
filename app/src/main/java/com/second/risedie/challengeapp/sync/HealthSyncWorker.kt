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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

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
            if (!repository.hasPermissions()) return@withContext Result.success()

            val payload = repository.buildFreshCurrentDaySyncPayload(includeRunDistance = true)
            val batches = payload.optJSONArray("batches") ?: return@withContext Result.success()
            if (batches.length() == 0) return@withContext Result.success()

            for (index in 0 until batches.length()) {
                val batch = batches.optJSONObject(index) ?: continue
                val body = JSONObject()
                    .put("source_id", sourceId)
                    .put("kind", batch.optString("kind"))
                    .put("external_batch_id", batch.optString("external_batch_id"))
                    .put("generated_at", batch.optString("generated_at", payload.optString("generated_at")))
                    .put("device_time", batch.optString("device_time", payload.optString("device_time")))
                    .put("source_day", batch.optString("source_day", payload.optString("source_day")))
                    .put("timezone", batch.optString("timezone", payload.optString("timezone")))
                    .put("records", batch.optJSONArray("records"))
                    .apply {
                        if (batch.has("window_size_minutes")) {
                            put("window_size_minutes", batch.optInt("window_size_minutes", 15))
                        }
                    }
                attachPayloadSecurityIfNeeded(apiBase, token, body)
                postJson("$apiBase/api/v1/me/sources/sync", token, body)
            }

            enqueueFastLoop(applicationContext)
            Result.success()
        } catch (_: Throwable) {
            enqueueFastLoop(applicationContext)
            Result.retry()
        }
    }


    private fun attachPayloadSecurityIfNeeded(apiBase: String, token: String, body: JSONObject) {
        val kind = body.optString("kind")
        if (kind !in FINAL_WINDOW_KINDS) return

        val activityDate = body.optString("source_day").takeIf { it.length >= 10 }?.substring(0, 10) ?: return
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
        body.put("payload_signature", hmacSha256Hex(nonce, signingKey))
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
        private val FINAL_WINDOW_KINDS = setOf("walk_steps_windows", "activity_windows", "activity_detail_windows")
        private const val PERIODIC_WORK = "grafit_health_connect_periodic_sync"
        private const val IMMEDIATE_WORK = "grafit_health_connect_immediate_sync"
        private const val FAST_LOOP_WORK = "grafit_health_connect_fast_loop_sync"
        private const val FAST_LOOP_MINUTES = 5L

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
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            enqueueFastLoop(context)
        }

        fun enqueueFastLoop(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setInitialDelay(FAST_LOOP_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(FAST_LOOP_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
