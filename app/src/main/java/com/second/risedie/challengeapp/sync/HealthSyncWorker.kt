package com.second.risedie.challengeapp.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Owns only WorkManager scheduling and retry policy. Sync mechanics live in HealthSyncCoordinator. */
class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = UUID.randomUUID().toString()
        val logger = HealthSyncLogger(applicationContext)
        val coordinator = HealthSyncCoordinator(applicationContext)
        logger.info(sessionId, COMPONENT, "worker_started", JSONObject()
            .put("run_attempt_count", runAttemptCount)
            .put("tags", tags.joinToString(",")))

        return try {
            val result = coordinator.sync("work_manager", sessionId)
            when (result.optString("type")) {
                "success", "no_data", "no_permissions", "not_configured", "unavailable" -> {
                    logger.info(sessionId, COMPONENT, "worker_completed", JSONObject().put("result_type", result.optString("type")))
                    Result.success()
                }
                else -> {
                    logger.warn(sessionId, COMPONENT, "worker_retry", JSONObject()
                        .put("result_type", result.optString("type"))
                        .put("run_attempt_count", runAttemptCount))
                    Result.retry()
                }
            }
        } catch (error: Throwable) {
            logger.error(sessionId, COMPONENT, "worker_failed", error, JSONObject().put("run_attempt_count", runAttemptCount))
            Result.retry()
        }
    }

    companion object {
        private const val COMPONENT = "worker"
        private const val PERIODIC_WORK = "grafit_health_connect_periodic_sync"
        private const val IMMEDIATE_WORK = "grafit_health_connect_immediate_sync"

        fun configure(context: Context, token: String, apiBase: String, sourceId: Long) {
            HealthSyncCoordinator(context.applicationContext).configure(token, apiBase, sourceId)
        }

        fun enqueueImmediate(context: Context) {
            val logger = HealthSyncLogger(context.applicationContext)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
            logger.info("scheduler", COMPONENT, "immediate_work_enqueued")
        }

        fun enqueuePeriodic(context: Context) {
            val logger = HealthSyncLogger(context.applicationContext)
            val initialDelayMinutes = Random.nextLong(0, 15)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            logger.info("scheduler", COMPONENT, "periodic_work_enqueued", JSONObject()
                .put("interval_minutes", 15)
                .put("initial_delay_minutes", initialDelayMinutes))
        }
    }
}
