package com.second.risedie.challengeapp.sync

import android.app.ActivityManager
import android.content.Context
import android.os.RemoteException
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
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
import java.io.IOException
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
        val scheduleKind = inputData.getString(INPUT_SCHEDULE_KIND).orEmpty()

        logger.info(sessionId, COMPONENT, "background_worker_started", JSONObject()
            .put("run_attempt_count", runAttemptCount)
            .put("tags", tags.joinToString(","))
            .put("app_foreground", appForeground)
            .put("activity_foreground", appForeground)
            .put("process_importance", processImportance)
            .put("execution_context", if (appForeground) "foreground_process" else "no_foreground_activity")
            .put("schedule_kind", scheduleKind.ifBlank { "periodic_or_immediate" })
            .put("started_at", Instant.now().toString()))

        return try {
            val syncResult = coordinator.sync("work_manager", sessionId)
            val resultType = syncResult.optString("type", "unknown")
            val workResult = when (resultType) {
                "success", "no_data", "no_permissions", "not_configured", "unavailable" -> "success"
                "transient_failure" -> "retry"
                "failed" -> "failure"
                else -> "failure"
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

            when (workResult) {
                "success" -> {
                    if (scheduleKind == SCHEDULE_KIND_NEXT) {
                        enqueueNext(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
                    } else {
                        enqueueNext(applicationContext, ExistingWorkPolicy.KEEP)
                    }
                    logger.info(sessionId, COMPONENT, "background_worker_completed", fields)
                    Result.success()
                }
                "retry" -> {
                    logger.warn(sessionId, COMPONENT, "background_worker_retry", fields.put("run_attempt_count", runAttemptCount))
                    Result.retry()
                }
                else -> {
                    logger.warn(sessionId, COMPONENT, "background_worker_permanent_failure", fields
                        .put("run_attempt_count", runAttemptCount))
                    Result.failure()
                }
            }
        } catch (error: CancellationException) {
            logger.warn(sessionId, COMPONENT, "background_worker_cancelled", JSONObject()
                .put("run_attempt_count", runAttemptCount)
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000), error)
            throw error
        } catch (error: Throwable) {
            val retryable = isTransientFailure(error)
            logger.error(sessionId, COMPONENT, if (retryable) "background_worker_transient_failure" else "background_worker_failed", error, JSONObject()
                .put("run_attempt_count", runAttemptCount)
                .put("retryable", retryable)
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000))
            if (retryable) Result.retry() else Result.failure()
        }
    }

    private fun isTransientFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is RemoteException || current is IOException) {
                return true
            }
            current = current.cause
        }
        return false
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
        private const val NEXT_WORK = "grafit_health_connect_next_sync"
        private const val NEXT_DELAY_MINUTES = 15L
        private const val INPUT_SCHEDULE_KIND = "schedule_kind"
        private const val SCHEDULE_KIND_NEXT = "next"

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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
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
            enqueueNext(appContext, ExistingWorkPolicy.KEEP)
        }

        private fun enqueueNext(context: Context, policy: ExistingWorkPolicy) {
            val appContext = context.applicationContext
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setInputData(Data.Builder().putString(INPUT_SCHEDULE_KIND, SCHEDULE_KIND_NEXT).build())
                .setInitialDelay(NEXT_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(appContext)
                .enqueueUniqueWork(NEXT_WORK, policy, request)
            HealthSyncLogger(appContext).info("scheduler", COMPONENT, "next_work_enqueued", JSONObject()
                .put("unique_name", NEXT_WORK)
                .put("delay_minutes", NEXT_DELAY_MINUTES)
                .put("policy", policy.name))
        }
    }
}
