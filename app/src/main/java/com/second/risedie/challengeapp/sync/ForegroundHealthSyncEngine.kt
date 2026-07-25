package com.second.risedie.challengeapp.sync

import android.content.Context
import com.second.risedie.challengeapp.health.LiveStepTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** Owns only foreground scheduling, concurrency and WebView event delivery. */
class ForegroundHealthSyncEngine(
    context: Context,
    @Suppress("UNUSED_PARAMETER") private val liveStepTracker: LiveStepTracker,
    private val emitSyncEvent: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val coordinator = HealthSyncCoordinator(appContext)
    private val logger = HealthSyncLogger(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var foreground = false
    @Volatile private var loopJob: Job? = null

    fun configure(token: String, apiBase: String, sourceId: Long) {
        coordinator.configure(token, apiBase, sourceId)
    }

    fun requestForegroundSync(reason: String): JSONObject {
        val normalizedReason = reason.ifBlank { "manual_refresh" }
        val requestId = UUID.randomUUID().toString()
        logger.info(requestId, COMPONENT, "sync_queued", JSONObject().put("reason", normalizedReason))
        scope.launch { syncNowForeground(normalizedReason, requestId) }
        return JSONObject()
            .put("queued", true)
            .put("request_id", requestId)
            .put("reason", normalizedReason)
    }

    fun onAppForeground(reason: String = "app_resume") {
        foreground = true
        logger.info("lifecycle", COMPONENT, "app_foreground", JSONObject().put("reason", reason))
        startForegroundLoop()
        requestForegroundSync(reason)
    }

    fun onAppBackground() {
        foreground = false
        loopJob?.cancel()
        loopJob = null
        logger.info("lifecycle", COMPONENT, "app_background")
    }

    fun startForegroundLoop() {
        if (loopJob?.isActive == true) return
        foreground = true
        logger.info("foreground_loop", COMPONENT, "loop_started")
        loopJob = scope.launch {
            while (isActive && foreground) {
                val delayMs = nextForegroundLoopDelayMs()
                logger.info("foreground_loop", COMPONENT, "loop_waiting", JSONObject().put("delay_ms", delayMs))
                delay(delayMs)
                if (!foreground) break
                if (mutex.isLocked) {
                    val payload = JSONObject()
                        .put("type", "skipped_already_running")
                        .put("reason", "foreground_loop")
                        .put("synced_at", Instant.now().toString())
                    logger.warn("foreground_loop", COMPONENT, "sync_skipped_already_running")
                    emit(payload)
                    continue
                }
                syncNowForeground("foreground_loop", UUID.randomUUID().toString())
            }
            logger.info("foreground_loop", COMPONENT, "loop_stopped")
        }
    }

    suspend fun syncNowForeground(
        reason: String,
        requestId: String = UUID.randomUUID().toString(),
    ): JSONObject = mutex.withLock {
        emit(JSONObject()
            .put("type", "started")
            .put("request_id", requestId)
            .put("reason", reason)
            .put("synced_at", Instant.now().toString()))

        val result = try {
            withTimeout(SYNC_TIMEOUT_MS) { coordinator.sync(reason, requestId) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error(requestId, COMPONENT, "sync_timeout", error, JSONObject().put("reason", reason))
            JSONObject()
                .put("type", "timeout")
                .put("success", false)
                .put("fresh", false)
                .put("request_id", requestId)
                .put("reason", reason)
                .put("message", "Foreground sync timeout")
                .put("synced_at", Instant.now().toString())
        } catch (error: Throwable) {
            logger.error(requestId, COMPONENT, "sync_failed", error, JSONObject().put("reason", reason))
            JSONObject()
                .put("type", "failed")
                .put("success", false)
                .put("fresh", false)
                .put("request_id", requestId)
                .put("reason", reason)
                .put("message", error.message ?: error.javaClass.simpleName)
                .put("synced_at", Instant.now().toString())
        }
        if (result.optBoolean("fresh", false)) {
            setLastSuccessfulSyncAt(Instant.now())
            logger.info(requestId, COMPONENT, "last_success_persisted")
        }
        emit(result)
        result
    }

    private fun setLastSuccessfulSyncAt(value: Instant) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SUCCESS, value.toString())
            .apply()
    }

    private fun nextForegroundLoopDelayMs(): Long = Random.nextLong(
        FOREGROUND_LOOP_INTERVAL_MIN_MS,
        FOREGROUND_LOOP_INTERVAL_MAX_MS + 1,
    )

    private fun emit(payload: JSONObject) = emitSyncEvent(payload.toString())

    companion object {
        private const val COMPONENT = "foreground_engine"
        private const val PREFS = "grafit_native_health_sync"
        private const val KEY_LAST_SUCCESS = "last_successful_foreground_sync_at"
        private const val SYNC_TIMEOUT_MS = 90_000L
        private const val FOREGROUND_LOOP_INTERVAL_MIN_MS = 90_000L
        private const val FOREGROUND_LOOP_INTERVAL_MAX_MS = 180_000L
    }
}
