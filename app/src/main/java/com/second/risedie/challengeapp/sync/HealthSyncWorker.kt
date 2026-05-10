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
import java.io.OutputStreamWriter
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

            val payload = repository.buildSyncPayload()
            val batches = payload.optJSONArray("batches") ?: return@withContext Result.success()
            if (batches.length() == 0) return@withContext Result.success()

            for (index in 0 until batches.length()) {
                val batch = batches.optJSONObject(index) ?: continue
                val body = JSONObject()
                    .put("source_id", sourceId)
                    .put("kind", batch.optString("kind"))
                    .put("external_batch_id", batch.optString("external_batch_id"))
                    .put("records", batch.optJSONArray("records"))
                postJson("$apiBase/api/v1/me/sources/sync", token, body)
            }

            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
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
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
