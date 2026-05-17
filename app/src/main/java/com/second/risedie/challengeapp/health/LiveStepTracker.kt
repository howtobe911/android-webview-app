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
import java.time.LocalDate
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

    fun snapshot(activityRecognitionGranted: Boolean): JSONObject {
        val now = Instant.now()
        val dayKey = LocalDate.now().toString()

        if (counterSensor == null && detectorSensor == null) return unavailable("На устройстве нет аппаратного счётчика шагов.")
        if (!activityRecognitionGranted) return unavailable("Для live-шагов нужно разрешение ACTIVITY_RECOGNITION.")

        start()
        val state = ensureState(now)
        val raw = rawCounter
        if (raw == null && state.realtimeDeltaSteps <= 0L) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("generated_at", now.toString())
                .put("source_day", dayKey)
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
            .put("source_day", dayKey)
            .put("preferred_source", "live_device_step_counter")
            .put("provider", JSONObject().put("type", "live_device_step_counter").put("name", "Android Live Step Counter").put("priority", 0).put("confidence_score", 0))
            .put("is_live_ui_only", true)
            .put("source_of_truth", "native_live_ui")
            .put("available", true)
            .put("steps_today", current.displaySteps)
            .put("raw_counter_since_boot", raw?.toDouble() ?: JSONObject.NULL)
            .put("sensor_base_value", current.sensorBaseValue.toDouble())
            .put("realtime_delta_steps", current.realtimeDeltaSteps)
            .put("updated_at", current.updatedAt.toString())
    }

    private fun ensureState(now: Instant): LiveActivityOverlayState {
        cachedState?.let { cached ->
            return resetForNewDayIfNeeded(cached, now)
        }
        val loaded = runBlocking { overlayStore.read() }
        cachedState = resetForNewDayIfNeeded(loaded, now)
        return cachedState!!
    }

    private fun resetForNewDayIfNeeded(state: LiveActivityOverlayState, now: Instant): LiveActivityOverlayState {
        val dayKey = LocalDate.now().toString()
        if (state.activityDate == dayKey) return state
        val reset = LiveActivityOverlayState(
            activityDate = dayKey,
            sensorBaseValue = rawCounter ?: state.sensorLastValue,
            sensorLastValue = rawCounter ?: state.sensorLastValue,
            updatedAt = now,
        )
        cachedState = reset
        persistCurrentThrottled(force = true)
        return reset
    }

    private fun updateState(now: Instant, raw: Float?, detectorPulse: Long) {
        synchronized(stateLock) {
            val previous = ensureState(now)
            val dayKey = LocalDate.now().toString()
            val dayState = if (previous.activityDate == dayKey) previous else LiveActivityOverlayState(activityDate = dayKey, updatedAt = now)

            val base = when {
                raw == null -> dayState.sensorBaseValue
                dayState.sensorBaseValue <= 0f -> raw
                raw < dayState.sensorLastValue -> raw
                else -> dayState.sensorBaseValue
            }
            val counterDelta = raw?.let { max(0f, it - base).toLong() } ?: dayState.realtimeDeltaSteps
            val detectorDelta = dayState.realtimeDeltaSteps + detectorPulse
            val realtimeDelta = max(counterDelta, detectorDelta)
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
