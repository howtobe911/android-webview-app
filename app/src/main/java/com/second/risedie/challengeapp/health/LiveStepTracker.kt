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
    private val detectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val overlayStore = LiveActivityOverlayStore(appContext)
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile private var rawCounter: Float? = null
    @Volatile private var updatedAt: Instant? = null
    @Volatile private var cachedState: LiveActivityOverlayState? = null
    @Volatile private var lastPersistAtMillis: Long = 0L

    fun start() {
        if (sensorManager == null || (counterSensor == null && detectorSensor == null)) return
        if (!started.compareAndSet(false, true)) return

        var registered = false
        counterSensor?.let { sensor ->
            registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST) || registered
        }
        detectorSensor?.let { sensor ->
            registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST) || registered
        }
        if (!registered) started.set(false)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runBlocking { persistCurrent(force = true) }
        sensorManager?.unregisterListener(this)
        persistScope.cancel()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = Instant.now()
        updatedAt = now
        when (event.sensor?.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val value = event.values.firstOrNull() ?: return
                rawCounter = value
                updateState(now = now, raw = value, detectorPulse = 0L)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val pulses = event.values.firstOrNull()?.takeIf { it > 0f }?.toLong() ?: 1L
                updateState(now = now, raw = rawCounter, detectorPulse = max(1L, pulses))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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

            val next = when {
                previousDay != serverDay -> {
                    action = "hard_reset_new_server_day"
                    hardResetState(serverDay, safeSteps, parsedRecordedAt, now)
                }

                safeSteps > previousTruth -> {
                    action = "hard_reset_truth_increased"
                    hardResetState(serverDay, safeSteps, parsedRecordedAt, now)
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
                    action = "preserve_downward_truth_warning"
                    val preservedDisplay = max(previous.displaySteps, safeSteps + previous.realtimeDeltaSteps)
                    val preservedDelta = max(previous.realtimeDeltaSteps, preservedDisplay - safeSteps)
                    previous.copy(
                        activityDate = serverDay,
                        serverVerifiedSteps = safeSteps,
                        realtimeDeltaSteps = preservedDelta,
                        displaySteps = preservedDisplay,
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

    private fun hardResetState(serverDay: String, serverSteps: Long, recordedAt: Instant, now: Instant): LiveActivityOverlayState =
        LiveActivityOverlayState(
            activityDate = serverDay,
            serverVerifiedSteps = serverSteps,
            sensorBaseValue = 0f,
            sensorLastValue = 0f,
            realtimeDeltaSteps = 0L,
            displaySteps = serverSteps,
            awaitingFreshBaseline = true,
            lastHealthConnectReadAt = recordedAt,
            updatedAt = now,
        )

    fun snapshot(activityRecognitionGranted: Boolean): JSONObject {
        val now = Instant.now()
        val dayKey = ensureState(now).activityDate

        if (counterSensor == null && detectorSensor == null) return unavailable("На устройстве нет аппаратного счётчика шагов.")
        if (!activityRecognitionGranted) return unavailable("Для live-шагов нужно разрешение ACTIVITY_RECOGNITION.")

        start()
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

        raw?.let { updateState(now = now, raw = it, detectorPulse = 0L) }
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
            .put("steps_today", current.displaySteps)
            .put("raw_counter_since_boot", raw?.toDouble() ?: JSONObject.NULL)
            .put("sensor_base_value", current.sensorBaseValue.toDouble())
            .put("realtime_delta_steps", current.realtimeDeltaSteps)
            .put("awaiting_fresh_baseline", current.awaitingFreshBaseline)
            .put("updated_at", current.updatedAt.toString())
    }


    /**
     * Returns the best monotonic steps total for the current server UTC day.
     *
     * Important: TYPE_STEP_COUNTER is a since-boot counter, not a queryable historical
     * aggregate. Therefore this method can only add native deltas that were observed
     * after the app established a server-day anchor. Health Connect still covers
     * historical/watch data for the same UTC server window.
     */
    fun serverWindowStepsSnapshot(serverWindow: ServerSyncWindow, healthConnectSteps: Long): Long {
        val now = Instant.now()
        val serverDay = serverWindow.serverDay.trim()
        val safeHealthSteps = max(0L, healthConnectSteps)
        if (serverDay.isBlank()) return safeHealthSteps

        start()
        val freshRaw = rawCounter
        freshRaw?.let { updateState(now = now, raw = it, detectorPulse = 0L) }

        synchronized(stateLock) {
            val previous = cachedState ?: runBlocking { overlayStore.read() }
            val dayState = when {
                previous.activityDate != serverDay || safeHealthSteps > previous.serverVerifiedSteps -> {
                    hardResetState(serverDay, safeHealthSteps, now, now)
                }

                safeHealthSteps == previous.serverVerifiedSteps -> {
                    previous.copy(
                        activityDate = serverDay,
                        serverVerifiedSteps = safeHealthSteps,
                        lastHealthConnectReadAt = now,
                        updatedAt = now,
                    )
                }

                else -> {
                    val preservedDisplay = max(previous.displaySteps, safeHealthSteps + previous.realtimeDeltaSteps)
                    val preservedDelta = max(previous.realtimeDeltaSteps, preservedDisplay - safeHealthSteps)
                    previous.copy(
                        activityDate = serverDay,
                        serverVerifiedSteps = safeHealthSteps,
                        realtimeDeltaSteps = preservedDelta,
                        displaySteps = preservedDisplay,
                        lastHealthConnectReadAt = now,
                        updatedAt = now,
                    )
                }
            }

            cachedState = dayState
            persistCurrentThrottled(force = true)
            return max(safeHealthSteps, dayState.displaySteps)
        }
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

    private fun updateState(now: Instant, raw: Float?, detectorPulse: Long) {
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
                    updatedAt = now,
                )
                return
            }

            val base = when {
                raw == null -> dayState.sensorBaseValue
                dayState.sensorBaseValue <= 0f -> raw
                raw < dayState.sensorLastValue -> raw
                else -> dayState.sensorBaseValue
            }
            val counterDelta = raw?.let { max(0f, it - base).toLong() } ?: dayState.realtimeDeltaSteps
            val detectorDelta = dayState.realtimeDeltaSteps + detectorPulse
            val realtimeDelta = max(counterDelta, detectorDelta)
            val display = max(dayState.displaySteps, dayState.serverVerifiedSteps + realtimeDelta)

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
