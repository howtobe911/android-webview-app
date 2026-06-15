package com.second.risedie.challengeapp.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class LiveStepTracker(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val counterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val overlayStore = LiveActivityOverlayStore(appContext)
    private var persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile private var rawCounter: Float? = null
    @Volatile private var updatedAt: Instant? = null
    @Volatile private var cachedState: LiveActivityOverlayState? = null
    @Volatile private var lastPersistAtMillis: Long = 0L
    @Volatile private var lastSensorFlushAtMillis: Long = 0L

    fun start() {
        if (sensorManager == null || counterSensor == null) return
        if (!started.compareAndSet(false, true)) return

        val registered = sensorManager.registerListener(this, counterSensor, SensorManager.SENSOR_DELAY_FASTEST, 0)
        if (!registered) started.set(false)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runBlocking { persistCurrent(force = true) }
        sensorManager?.unregisterListener(this)
    }

    fun dispose() {
        stop()
        persistScope.cancel()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = Instant.now()
        updatedAt = now
        when (event.sensor?.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val value = event.values.firstOrNull() ?: return
                rawCounter = value
                updateState(now = now, raw = value)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun flushCounterSensorIfNeeded() {
        val manager = sensorManager ?: return
        if (!started.get()) return
        val nowMillis = System.currentTimeMillis()
        if (nowMillis - lastSensorFlushAtMillis < 1_000L) return
        lastSensorFlushAtMillis = nowMillis
        runCatching { manager.flush(this) }
    }

    fun reconcileAnchorFromServer(activityDate: String, serverSteps: Long, serverRecordedAt: String?) {
        applyServerTruth(activityDate, serverSteps, serverRecordedAt)
    }

    fun resetAnchorFromServer(activityDate: String, serverSteps: Long, serverRecordedAt: String?): String {
        return applyServerTruth(activityDate, serverSteps, serverRecordedAt)
    }

    private fun applyServerTruth(activityDate: String, serverSteps: Long, serverRecordedAt: String?): String {
        val serverDay = activityDate.trim()
        if (serverDay.isBlank()) return "ignored_missing_server_day"

        val now = Instant.now()
        val safeSteps = max(0L, serverSteps)
        var action = "preserve_equal_truth"

        synchronized(stateLock) {
            val previous = cachedState ?: runBlocking { overlayStore.read() }
            val previousDay = previous.activityDate.trim()
            val previousTruth = max(0L, previous.serverVerifiedSteps)
            val parsedRecordedAt = serverRecordedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now

            val rawBeforeServerTruth = rawCounter
            val next = when {
                previousDay != serverDay -> {
                    action = "hard_reset_new_server_day"
                    hardResetState(serverDay, safeSteps, parsedRecordedAt, now, rawBeforeServerTruth)
                }

                safeSteps > previousTruth -> {
                    action = "hard_reset_truth_increased"
                    hardResetState(serverDay, safeSteps, parsedRecordedAt, now, rawBeforeServerTruth)
                }

                safeSteps == previousTruth -> {
                    action = "preserve_equal_truth"
                    previous.copy(
                        activityDate = serverDay,
                        serverVerifiedSteps = safeSteps,
                        lastHealthConnectReadAt = parsedRecordedAt,
                        updatedAt = now,
                    )
                }

                else -> {
                    action = "preserve_older_or_lower_truth"
                    previous.copy(
                        activityDate = serverDay,
                        lastHealthConnectReadAt = parsedRecordedAt,
                        updatedAt = now,
                    )
                }
            }

            cachedState = next
        }
        persistCurrentThrottled(force = true)
        return action
    }

    private fun hardResetState(serverDay: String, serverSteps: Long, recordedAt: Instant, now: Instant, rawBaseline: Float? = rawCounter): LiveActivityOverlayState {
        val safeRawBaseline = rawBaseline?.takeIf { it >= 0f }
        return LiveActivityOverlayState(
            activityDate = serverDay,
            serverVerifiedSteps = serverSteps,
            sensorBaseValue = safeRawBaseline ?: 0f,
            sensorLastValue = safeRawBaseline ?: 0f,
            realtimeDeltaSteps = 0L,
            displaySteps = serverSteps,
            awaitingFreshBaseline = safeRawBaseline == null,
            lastHealthConnectReadAt = recordedAt,
            lastResetReason = "server_truth_anchor",
            lastRawCounterResetAt = now,
            updatedAt = now,
        )
    }

    fun snapshot(activityRecognitionGranted: Boolean): JSONObject {
        val now = Instant.now()
        ensureState(now)

        if (counterSensor == null) return unavailable("На устройстве нет аппаратного счётчика шагов.")
        if (!activityRecognitionGranted) return unavailable("Для live-шагов нужно разрешение ACTIVITY_RECOGNITION.")

        start()
        flushCounterSensorIfNeeded()
        val state = ensureState(now)
        val raw = rawCounter
        if (raw == null && state.realtimeDeltaSteps <= 0L) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("generated_at", now.toString())
                .put("preferred_source", "live_device_step_counter")
                .put("is_live_ui_only", true)
                .put("source_of_truth", "native_live_ui")
                .put("available", true)
                .put("warming_up", true)
                .put("steps_today", state.displaySteps)
                .put("message", "Live-счётчик запускается.")
        }

        raw?.let { updateState(now = now, raw = it) }
        val current = ensureState(now)
        persistCurrentThrottled()

        return JSONObject()
            .put("batches", JSONArray())
            .put("generated_at", now.toString())
            .put("preferred_source", "live_device_step_counter")
            .put("provider", JSONObject().put("type", "live_device_step_counter").put("name", "Android Live Step Counter").put("priority", 0).put("confidence_score", 0))
            .put("is_live_ui_only", true)
            .put("source_of_truth", "native_live_ui")
            .put("available", true)
            .put("source_day", current.activityDate)
            .put("activity_date", current.activityDate)
            .put("steps_today", current.displaySteps)
            .put("server_verified_steps", current.serverVerifiedSteps)
            .put("confirmed_steps", current.serverVerifiedSteps)
            .put("raw_counter_since_boot", raw?.toDouble() ?: JSONObject.NULL)
            .put("raw_counter_at_anchor", current.sensorBaseValue.toDouble())
            .put("last_seen_raw_counter", current.sensorLastValue.toDouble())
            .put("sensor_base_value", current.sensorBaseValue.toDouble())
            .put("last_display_steps", current.displaySteps)
            .put("realtime_delta_steps", current.realtimeDeltaSteps)
            .put("last_health_connect_sync_at", current.lastHealthConnectReadAt?.toString() ?: JSONObject.NULL)
            .put("last_reset_reason", current.lastResetReason ?: if (current.awaitingFreshBaseline) "awaiting_fresh_baseline" else JSONObject.NULL)
            .put("last_raw_counter_reset_at", current.lastRawCounterResetAt?.toString() ?: JSONObject.NULL)
            .put("awaiting_fresh_baseline", current.awaitingFreshBaseline)
            .put("updated_at", current.updatedAt.toString())
    }


    /**
     * Returns only confirmed Health Connect steps for the current server UTC day.
     * TYPE_STEP_COUNTER is local UI overlay only and must never be posted as backend truth.
     */
    fun serverWindowStepsSnapshot(serverWindow: ServerSyncWindow, healthConnectSteps: Long): Long {
        // Backend sync must receive only confirmed Health Connect truth.
        // TYPE_STEP_COUNTER overlay is local UI only and is exposed through snapshot().
        val safeHealthSteps = max(0L, healthConnectSteps)
        val serverDay = serverWindow.serverDay.trim()
        if (serverDay.isNotBlank()) {
            applyServerTruth(serverDay, safeHealthSteps, null)
        }
        return safeHealthSteps
    }

    /**
     * No local/UTC midnight reset here. The active overlay day is reconciled only from
     * the server snapshot/sync window. When server_day changes, the old optimistic
     * overlay is simply ignored because the new server_day has its own namespace.
     */
    private fun ensureState(now: Instant): LiveActivityOverlayState {
        cachedState?.let { return it }
        val loaded = runBlocking { overlayStore.read() }
        cachedState = ensureInitializedState(loaded, now)
        return cachedState!!
    }

    private fun ensureInitializedState(state: LiveActivityOverlayState, now: Instant): LiveActivityOverlayState {
        if (state.activityDate.isNotBlank()) return state
        val initialized = state.copy(updatedAt = now)
        cachedState = initialized
        persistCurrentThrottled(force = true)
        return initialized
    }

    private fun updateState(now: Instant, raw: Float?) {
        synchronized(stateLock) {
            val previous = ensureState(now)
            val dayKey = previous.activityDate
            val dayState = previous

            if (dayState.awaitingFreshBaseline) {
                if (raw == null) {
                    cachedState = dayState.copy(
                        activityDate = dayKey,
                        realtimeDeltaSteps = 0L,
                        displaySteps = dayState.serverVerifiedSteps,
                        updatedAt = now,
                    )
                    return
                }

                cachedState = dayState.copy(
                    activityDate = dayKey,
                    sensorBaseValue = raw,
                    sensorLastValue = raw,
                    realtimeDeltaSteps = 0L,
                    displaySteps = dayState.serverVerifiedSteps,
                    awaitingFreshBaseline = false,
                    lastResetReason = "sensor_baseline_initialized",
                    lastRawCounterResetAt = now,
                    updatedAt = now,
                )
                return
            }

            if (raw != null && dayState.sensorBaseValue > 0f && raw < dayState.sensorBaseValue) {
                cachedState = dayState.copy(
                    activityDate = dayKey,
                    sensorBaseValue = raw,
                    sensorLastValue = raw,
                    realtimeDeltaSteps = 0L,
                    displaySteps = dayState.serverVerifiedSteps,
                    lastResetReason = "raw_counter_reset",
                    lastRawCounterResetAt = now,
                    updatedAt = now,
                )
                return
            }

            val base = when {
                raw == null -> dayState.sensorBaseValue
                dayState.sensorBaseValue <= 0f -> raw
                else -> dayState.sensorBaseValue
            }
            val realtimeDelta = raw?.let { max(0f, it - base).toLong() } ?: dayState.realtimeDeltaSteps
            val display = dayState.serverVerifiedSteps + realtimeDelta

            cachedState = dayState.copy(
                activityDate = dayKey,
                sensorBaseValue = base,
                sensorLastValue = raw ?: dayState.sensorLastValue,
                realtimeDeltaSteps = realtimeDelta,
                displaySteps = display,
                updatedAt = now,
            )
        }
        persistCurrentThrottled()
    }

    private fun persistCurrentThrottled(force: Boolean = false) {
        val nowMillis = System.currentTimeMillis()
        if (!force && nowMillis - lastPersistAtMillis < 1_000L) return
        lastPersistAtMillis = nowMillis
        val snapshot = cachedState ?: return
        persistScope.launch { overlayStore.write(snapshot) }
    }

    private suspend fun persistCurrent(force: Boolean = false) {
        if (force) cachedState?.let { overlayStore.write(it) }
    }

    private fun unavailable(message: String): JSONObject = JSONObject()
        .put("batches", JSONArray())
        .put("generated_at", Instant.now().toString())
        .put("preferred_source", "live_device_step_counter")
        .put("is_live_ui_only", true)
        .put("source_of_truth", "native_live_ui")
        .put("available", false)
        .put("message", message)
}
