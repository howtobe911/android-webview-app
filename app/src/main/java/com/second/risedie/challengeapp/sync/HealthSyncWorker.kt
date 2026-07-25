package com.second.risedie.challengeapp.sync

import android.app.ActivityManager
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
import com.second.risedie.challengeapp.ui.ChallengeWebViewActivity
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant
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
        val started = System.nanoTime()
        val logger = HealthSyncLogger(applicationContext)
        val coordinator = HealthSyncCoordinator(applicationContext)
        val appForeground = ChallengeWebViewActivity.isInForeground
        val processImportance = currentProcessImportance()

        logger.info(sessionId, COMPONENT, "background_worker_started", JSONObject()
            .put("run_attempt_count", runAttemptCount)
            .put("tags", tags.joinToString(","))
            .put("app_foreground", appForeground)
            .put("activity_foreground", appForeground)
            .put("process_importance", processImportance)
            .put("execution_context", if (appForeground) "foreground_process" else "no_foreground_activity")
            .put("started_at", Instant.now().toString()))

        return try {
            val syncResult = coordinator.sync("work_manager", sessionId)
            val resultType = syncResult.optString("type", "unknown")
            val workResult = when (resultType) {
                "success", "no_data", "no_permissions", "not_configured", "unavailable" -> "success"
                else -> "retry"
            }
            val fields = JSONObject()
                .put("sync_result", resultType)
                .put("worker_result", workResult)
                .put("data_delivered", syncResult.optBoolean("request_delivered", false))
                .put("request_delivered", syncResult.optBoolean("request_delivered", false))
                .put("server_accepted", syncResult.optBoolean("server_accepted", false))
                .put("server_processing_queued", syncResult.optBoolean("server_processing_queued", false))
                .put("server_state_changed", syncResult.optBoolean("server_state_changed", false))
                .put("server_updated", syncResult.optBoolean("server_state_changed", false))
                .put("steps", syncResult.optLong("steps", 0L))
                .put("run_meters", syncResult.optDouble("run_meters", 0.0))
                .put("records_count", syncResult.optInt("records_count", 0))
                .put("http_status", syncResult.opt("http_status") ?: JSONObject.NULL)
                .put("accepted_records", syncResult.optInt("accepted_records", 0))
                .put("duplicate_records", syncResult.optInt("duplicate_records", 0))
                .put("rejected_records", syncResult.optInt("rejected_records", 0))
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000)

            if (workResult == "success") {
                logger.info(sessionId, COMPONENT, "background_worker_completed", fields)
                Result.success()
            } else {
                logger.warn(sessionId, COMPONENT, "background_worker_retry", fields.put("run_attempt_count", runAttemptCount))
                Result.retry()
            }
        } catch (error: CancellationException) {
            logger.warn(sessionId, COMPONENT, "background_worker_cancelled", JSONObject()
                .put("run_attempt_count", runAttemptCount)
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000), error)
            throw error
        } catch (error: Throwable) {
            logger.error(sessionId, COMPONENT, "background_worker_failed", error, JSONObject()
                .put("run_attempt_count", runAttemptCount)
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000))
            Result.retry()
        }
    }

    private fun currentProcessImportance(): Int {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance
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
            val appContext = context.applicationContext
            val logger = HealthSyncLogger(appContext)
            val initialDelayMinutes = Random.nextLong(0, 15)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            val workManager = WorkManager.getInstance(appContext)
            logger.info("scheduler", COMPONENT, "periodic_work_registration_started", JSONObject()
                .put("unique_name", PERIODIC_WORK)
                .put("policy", "KEEP")
                .put("interval_minutes", 15)
                .put("initial_delay_minutes", initialDelayMinutes))
            val operation = workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            operation.result.addListener({
                runCatching { operation.result.get() }
                    .onSuccess {
                        val infosFuture = workManager.getWorkInfosForUniqueWork(PERIODIC_WORK)
                        infosFuture.addListener({
                            runCatching { infosFuture.get() }
                                .onSuccess { infos ->
                                    val matchingRequest = infos.firstOrNull { it.id == request.id }
                                    val activeInfo = matchingRequest ?: infos.firstOrNull()
                                    val event = if (matchingRequest != null) {
                                        "periodic_work_registered"
                                    } else {
                                        "periodic_work_already_exists"
                                    }
                                    logger.info("scheduler", COMPONENT, event, JSONObject()
                                        .put("unique_name", PERIODIC_WORK)
                                        .put("policy", "KEEP")
                                        .put("work_id", activeInfo?.id?.toString() ?: JSONObject.NULL)
                                        .put("work_state", activeInfo?.state?.name ?: "UNKNOWN")
                                        .put("new_request_used", matchingRequest != null))
                                }
                                .onFailure { error ->
                                    logger.error("scheduler", COMPONENT, "periodic_work_state_query_failed", error, JSONObject()
                                        .put("unique_name", PERIODIC_WORK)
                                        .put("policy", "KEEP"))
                                }
                        }, { runnable -> runnable.run() })
                    }
                    .onFailure { error ->
                        logger.error("scheduler", COMPONENT, "periodic_work_registration_failed", error, JSONObject()
                            .put("unique_name", PERIODIC_WORK)
                            .put("policy", "KEEP"))
                    }
            }, { runnable -> runnable.run() })
        }
    }
}
