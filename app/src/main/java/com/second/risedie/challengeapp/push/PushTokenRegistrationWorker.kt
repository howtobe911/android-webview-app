package com.second.risedie.challengeapp.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PushTokenRegistrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (runAttemptCount >= MAX_ATTEMPTS) return@withContext Result.failure()

        val fcmToken = inputData.getString(KEY_FCM_TOKEN).orEmpty()
        val (accessToken, apiBase) = PushTokenRegistrar.credentials(applicationContext)
            ?: return@withContext Result.success()
        if (fcmToken.isBlank()) return@withContext Result.success()

        val connection = runCatching {
            (URL("$apiBase/api/v1/me/device/push-token").openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                outputStream.use {
                    it.write(JSONObject().put("token", fcmToken).toString().toByteArray(Charsets.UTF_8))
                }
            }
        }.getOrElse { return@withContext Result.retry() }

        try {
            when (connection.responseCode) {
                in 200..299 -> Result.success()
                401, 403 -> {
                    PushTokenRegistrar.clear(applicationContext)
                    Result.success()
                }
                408, 425, 429, in 500..599 -> Result.retry()
                else -> Result.failure()
            }
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        const val KEY_FCM_TOKEN = "fcm_token"
    }
}
