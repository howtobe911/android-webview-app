package com.second.risedie.challengeapp.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class LiveStepTracker(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull() ?: return
        rawCounter = value
        updatedAt = Instant.now()
        normalizeBaseline(value, updatedAt!!)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun snapshot(activityRecognitionGranted: Boolean): JSONObject {
        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val dayKey = now.atZone(zoneId).toLocalDate().toString()

        if (sensor == null) {
            return unavailable("На устройстве нет аппаратного Sensor.TYPE_STEP_COUNTER.")
        }

        if (!activityRecognitionGranted) {
            return unavailable("Для live-шагов нужно разрешение ACTIVITY_RECOGNITION.")
        }

        start()
        val raw = rawCounter
        if (raw == null) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("generated_at", now.toString())
                .put("source_day", dayKey)
                .put("preferred_source", "live_device_step_counter")
                .put("is_live_ui_only", true)
                .put("available", true)
                .put("warming_up", true)
                .put("steps_today", 0)
                .put("message", "Live-счётчик запускается.")
        }

        val baseline = normalizeBaseline(raw, now)
        val stepsToday = max(0f, raw - baseline).toLong()

        return JSONObject()
            .put("batches", JSONArray())
            .put("generated_at", now.toString())
            .put("source_day", dayKey)
            .put("preferred_source", "live_device_step_counter")
            .put("provider", JSONObject().put("type", "live_device_step_counter").put("name", "Android Live Step Counter").put("priority", 0).put("confidence_score", 0))
            .put("is_live_ui_only", true)
            .put("available", true)
            .put("steps_today", stepsToday)
            .put("raw_counter_since_boot", raw.toDouble())
            .put("baseline_counter", baseline.toDouble())
            .put("updated_at", (updatedAt ?: now).toString())
    }

    private fun normalizeBaseline(raw: Float, now: Instant): Float {
        val dayKey = now.atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val baselineKey = "baseline_$dayKey"
        val storedDay = prefs.getString(KEY_LAST_DAY, null)
        val previousRaw = prefs.getFloat(KEY_LAST_RAW, raw)
        val baseline = if (storedDay != dayKey || !prefs.contains(baselineKey) || raw < previousRaw) {
            raw
        } else {
            prefs.getFloat(baselineKey, raw)
        }

        prefs.edit()
            .putString(KEY_LAST_DAY, dayKey)
            .putFloat(baselineKey, baseline)
            .putFloat(KEY_LAST_RAW, raw)
            .apply()

        return baseline
    }

    private fun unavailable(message: String): JSONObject = JSONObject()
        .put("batches", JSONArray())
        .put("generated_at", Instant.now().toString())
        .put("preferred_source", "live_device_step_counter")
        .put("is_live_ui_only", true)
        .put("available", false)
        .put("message", message)

    companion object {
        private const val PREFS = "grafit_live_step_counter"
        private const val KEY_LAST_DAY = "last_day"
        private const val KEY_LAST_RAW = "last_raw"
    }
}
