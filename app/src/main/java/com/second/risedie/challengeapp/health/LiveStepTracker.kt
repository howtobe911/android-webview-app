package com.second.risedie.challengeapp.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val overlayStore = LiveActivityOverlayStore(appContext)
    private val started = AtomicBoolean(false)

    @Volatile private var rawCounter: Float? = null
    @Volatile private var updatedAt: Instant? = null

    fun start() {
        if (sensorManager == null || sensor == null) return
        if (!started.compareAndSet(false, true)) return
        val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!registered) started.set(false)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runBlocking { persistCurrent() }
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull() ?: return
        rawCounter = value
        updatedAt = Instant.now()
        runBlocking { normalizeBaseline(value, updatedAt!!) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun snapshot(activityRecognitionGranted: Boolean): JSONObject {
        val now = Instant.now()
        val dayKey = LocalDate.now().toString()

        if (sensor == null) return unavailable("На устройстве нет аппаратного Sensor.TYPE_STEP_COUNTER.")
        if (!activityRecognitionGranted) return unavailable("Для live-шагов нужно разрешение ACTIVITY_RECOGNITION.")

        start()
        val saved = runBlocking { overlayStore.read() }
        val raw = rawCounter
        if (raw == null) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("generated_at", now.toString())
                .put("source_day", dayKey)
                .put("preferred_source", "live_device_step_counter")
                .put("is_live_ui_only", true)
                .put("source_of_truth", "native_live_ui")
                .put("available", true)
                .put("warming_up", true)
                .put("steps_today", saved.displaySteps)
                .put("message", "Live-счётчик запускается.")
        }

        val state = runBlocking { normalizeBaseline(raw, now) }
        return JSONObject()
            .put("batches", JSONArray())
            .put("generated_at", now.toString())
            .put("source_day", dayKey)
            .put("preferred_source", "live_device_step_counter")
            .put("provider", JSONObject().put("type", "live_device_step_counter").put("name", "Android Live Step Counter").put("priority", 0).put("confidence_score", 0))
            .put("is_live_ui_only", true)
            .put("source_of_truth", "native_live_ui")
            .put("available", true)
            .put("steps_today", state.displaySteps)
            .put("raw_counter_since_boot", raw.toDouble())
            .put("sensor_base_value", state.sensorBaseValue.toDouble())
            .put("realtime_delta_steps", state.realtimeDeltaSteps)
            .put("updated_at", state.updatedAt.toString())
    }

    private suspend fun normalizeBaseline(raw: Float, now: Instant): LiveActivityOverlayState {
        val dayKey = LocalDate.now().toString()
        val previous = overlayStore.read()
        val base = if (previous.activityDate != dayKey || raw < previous.sensorLastValue) raw else previous.sensorBaseValue
        val delta = max(0f, raw - base).toLong()
        val display = previous.serverVerifiedSteps + delta
        val next = previous.copy(
            activityDate = dayKey,
            sensorBaseValue = base,
            sensorLastValue = raw,
            realtimeDeltaSteps = delta,
            displaySteps = display,
            updatedAt = now,
        )
        overlayStore.write(next)
        return next
    }

    private suspend fun persistCurrent() {
        val raw = rawCounter ?: return
        normalizeBaseline(raw, Instant.now())
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
