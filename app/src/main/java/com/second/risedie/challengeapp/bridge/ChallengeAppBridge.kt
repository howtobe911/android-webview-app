package com.second.risedie.challengeapp.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import com.second.risedie.challengeapp.BuildConfig
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max


class ChallengeAppBridge(
    private val activity: ComponentActivity,
    private val onLaunchPermissions: (Intent) -> Unit,
    private val onLaunchActivityRecognitionPermission: () -> Unit,
    private val isActivityRecognitionGranted: () -> Boolean,
    private val onNotifyJavascript: (String) -> Unit,
    private val onDebugJavascript: (String) -> Unit,
) {
    private val context: Context = activity.applicationContext
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val readMutex = Mutex()
    private val permissionsMutex = Mutex()
    private val permissionFlowInProgress = AtomicBoolean(false)
    private val activityRefreshInProgress = AtomicBoolean(false)

    @Volatile
    private var cachedPermissionPayload: String = permissionPayload(
        available = false,
        granted = false,
        pending = false,
        message = "Проверяем доступность Health Connect.",
    )

    @Volatile
    private var cachedActivityPayload: String = JSONObject()
        .put("batches", JSONArray())
        .put("message", "Синхронизация активности ещё не выполнялась.")
        .toString()

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    init {
        logDebug("bridge:init")
        emitDebugEvent("bridge:init", mapOf("sdkStatus" to sdkStatus(), "activityRecognitionGranted" to isActivityRecognitionGranted()))
        refreshPermissionState(notifyJavascript = false, refreshActivity = false)
    }

    private fun sdkStatus(): Int {
        return try {
            HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE_NAME)
        } catch (_: Throwable) {
            HealthConnectClient.SDK_UNAVAILABLE
        }
    }

    private val healthClient: HealthConnectClient?
        get() = if (sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            try {
                HealthConnectClient.getOrCreate(context, HEALTH_CONNECT_PACKAGE_NAME)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

    @JavascriptInterface
    fun getBridgeInfo(): String {
        val status = sdkStatus()
        logDebug("bridge:getBridgeInfo", mapOf("sdkStatus" to status, "activityRecognitionGranted" to isActivityRecognitionGranted()))
        return JSONObject()
            .put("bridge", "ChallengeAppBridge")
            .put("platform", "android")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("health_connect_package", HEALTH_CONNECT_PACKAGE_NAME)
            .put("sdk_status", status)
            .put("available", status == HealthConnectClient.SDK_AVAILABLE)
            .put("permissions", JSONArray(permissions.toList()))
            .put("activity_recognition_granted", isActivityRecognitionGranted())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("app_version_code", BuildConfig.VERSION_CODE)
            .put("preferred_source", if (status == HealthConnectClient.SDK_AVAILABLE) "health_connect" else "device_step_counter")
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("known_health_apps", knownHealthApps())
            .toString()
    }

    @JavascriptInterface
    fun requestActivityPermissions(): String {
        logDebug("permissions:request:start", mapOf("activityRecognitionGranted" to isActivityRecognitionGranted(), "sdkStatus" to sdkStatus()))
        emitDebugEvent("permissions:request:start", mapOf("activityRecognitionGranted" to isActivityRecognitionGranted(), "sdkStatus" to sdkStatus()))

        if (!isActivityRecognitionGranted()) {
            val payload = permissionPayload(
                available = true,
                granted = false,
                pending = true,
                message = "Запрашиваем системное разрешение на физическую активность.",
            )
            cachedPermissionPayload = payload
            logDebug("permissions:request:launch_activity_recognition", mapOf("payload" to payload))
            emitDebugEvent("permissions:request:launch_activity_recognition", mapOf("payload" to payload))
            onLaunchActivityRecognitionPermission()
            return payload
        }

        val status = sdkStatus()
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            val payload = permissionPayload(
                available = hasStepCounterSensor(),
                granted = hasStepCounterSensor(),
                pending = false,
                message = if (hasStepCounterSensor()) "Health Connect недоступен. Используем аппаратный счётчик шагов телефона как резервный источник." else unavailableMessage(status),
            )
            cachedPermissionPayload = payload
            logDebug("permissions:request:sdk_unavailable", mapOf("payload" to payload))
            emitDebugEvent("permissions:request:sdk_unavailable", mapOf("payload" to payload))
            return payload
        }

        if (!permissionFlowInProgress.compareAndSet(false, true)) {
            val payload = permissionPayload(
                available = true,
                granted = false,
                pending = true,
                message = "Окно разрешений уже открыто. Подтверди доступ и вернись в приложение.",
            )
            cachedPermissionPayload = payload
            logDebug("permissions:request:flow_already_in_progress", mapOf("payload" to payload))
            emitDebugEvent("permissions:request:flow_already_in_progress", mapOf("payload" to payload))
            return payload
        }

        val pendingPayload = permissionPayload(
            available = true,
            granted = false,
            pending = true,
            message = "Проверяем разрешения и открываем окно Health Connect при необходимости.",
        )
        cachedPermissionPayload = pendingPayload
        logDebug("permissions:request:pending", mapOf("payload" to pendingPayload))
        emitDebugEvent("permissions:request:pending", mapOf("payload" to pendingPayload))

        bridgeScope.launch {
            try {
                val client = healthClient
                if (client == null) {
                    permissionFlowInProgress.set(false)
                    val payload = unavailablePayload(sdkStatus())
                    cachedPermissionPayload = payload
                    onNotifyJavascript(payload)
                    return@launch
                }

                val grantedPermissions = safeGrantedPermissions(client)
                logDebug("permissions:request:granted_permissions", mapOf("grantedPermissions" to grantedPermissions.joinToString(",")))
                emitDebugEvent("permissions:request:granted_permissions", mapOf("grantedPermissions" to grantedPermissions.joinToString(",")))
                if (grantedPermissions.containsAll(permissions)) {
                    permissionFlowInProgress.set(false)
                    val grantedPayload = permissionPayload(
                        available = true,
                        granted = true,
                        pending = false,
                        message = "Разрешения Health Connect уже выданы.",
                    )
                    cachedPermissionPayload = grantedPayload
                    onNotifyJavascript(grantedPayload)
                    emitDebugEvent("permissions:request:already_granted", mapOf("payload" to grantedPayload))
                    refreshActivityPayload()
                    return@launch
                }

                val intent = PermissionController.createRequestPermissionResultContract()
                    .createIntent(activity, permissions)
                logDebug("permissions:request:launch_health_connect")
                emitDebugEvent("permissions:request:launch_health_connect", mapOf("permissions" to permissions.joinToString(",")))
                onLaunchPermissions(intent)
            } catch (error: Throwable) {
                logError("permissions:request:error", error)
                permissionFlowInProgress.set(false)
                val payload = permissionPayload(
                    available = true,
                    granted = false,
                    pending = false,
                    message = error.message ?: "Не удалось открыть окно разрешений Health Connect.",
                )
                cachedPermissionPayload = payload
                onNotifyJavascript(payload)
                emitDebugEvent("permissions:request:error", mapOf("message" to (error.message ?: "unknown"), "payload" to payload))
            }
        }

        return pendingPayload
    }

    fun onPermissionsFlowFinished() {
        permissionFlowInProgress.set(false)
        logDebug("permissions:flow:finished")
        refreshPermissionState(notifyJavascript = true, refreshActivity = true)
    }

    fun onActivityRecognitionPermissionResult(granted: Boolean) {
        logDebug("permissions:activity_recognition_result", mapOf("granted" to granted))
        emitDebugEvent("permissions:activity_recognition_result", mapOf("granted" to granted))
        if (granted) {
            requestActivityPermissions()
        } else {
            val payload = permissionPayload(
                available = true,
                granted = false,
                pending = false,
                message = "Системное разрешение на физическую активность не выдано.",
            )
            cachedPermissionPayload = payload
            onNotifyJavascript(payload)
        }
    }

    fun onHostResumed() {
        logDebug("bridge:host_resumed")
        refreshPermissionState(notifyJavascript = true, refreshActivity = false)
    }

    fun dispose() {
        logDebug("bridge:dispose")
        bridgeScope.cancel()
    }

    @JavascriptInterface
    fun getActivitySyncPayload(): String {
        logDebug("sync:getActivitySyncPayload:start")
        return runBlocking {
            val payload = readActivityPayloadOnce()
            cachedActivityPayload = payload
            logDebug("sync:getActivitySyncPayload:done", mapOf("payload" to payload))
            payload
        }
    }

    @JavascriptInterface
    fun openHealthConnectSettings(): String {
        val candidates = listOf(
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$HEALTH_CONNECT_PACKAGE_NAME")),
        )

        for (intent in candidates) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return JSONObject().put("opened", true).toString()
            } catch (_: ActivityNotFoundException) {
            } catch (_: Throwable) {
            }
        }

        return JSONObject().put("opened", false).toString()
    }

    private fun refreshPermissionState(
        notifyJavascript: Boolean,
        refreshActivity: Boolean,
    ) {
        bridgeScope.launch {
            val payload = try {
                logDebug("permissions:refresh:start", mapOf("notifyJavascript" to notifyJavascript, "refreshActivity" to refreshActivity))
                val status = sdkStatus()
                if (!isActivityRecognitionGranted()) {
                    permissionPayload(
                        available = true,
                        granted = false,
                        pending = false,
                        message = "Системное разрешение на физическую активность ещё не выдано.",
                    )
                } else if (status != HealthConnectClient.SDK_AVAILABLE) {
                    unavailablePayload(status)
                } else {
                    val client = healthClient
                    if (client == null) {
                        permissionPayload(
                            available = false,
                            granted = false,
                            pending = false,
                            message = "Health Connect не инициализировался.",
                        )
                    } else {
                        val granted = safeGrantedPermissions(client).containsAll(permissions)
                        permissionPayload(
                            available = true,
                            granted = granted,
                            pending = permissionFlowInProgress.get(),
                            message = if (granted) {
                                "Разрешения получены. Можно синхронизировать шаги и дистанцию."
                            } else {
                                "Разрешения на шаги и дистанцию пока не выданы."
                            },
                        )
                    }
                }
            } catch (error: Throwable) {
                permissionPayload(
                    available = false,
                    granted = false,
                    pending = false,
                    message = error.message ?: "Не удалось проверить состояние Health Connect.",
                )
            }

            cachedPermissionPayload = payload

            if (notifyJavascript) {
                onNotifyJavascript(payload)
            }

            val payloadJson = try {
                JSONObject(payload)
            } catch (_: Throwable) {
                null
            }

            if (refreshActivity && payloadJson?.optBoolean("granted") == true) {
                refreshActivityPayload()
            }
        }
    }

    private fun refreshActivityPayload() {
        if (!activityRefreshInProgress.compareAndSet(false, true)) {
            logDebug("sync:refresh:skipped_in_progress")
            return
        }

        bridgeScope.launch {
            try {
                logDebug("sync:refresh:start")
                cachedActivityPayload = readActivityPayloadOnce()
                logDebug("sync:refresh:done", mapOf("payload" to cachedActivityPayload))
            } finally {
                activityRefreshInProgress.set(false)
            }
        }
    }

    private suspend fun readActivityPayloadOnce(): String {
        return try {
            logDebug("sync:read:start", mapOf("sdkStatus" to sdkStatus(), "activityRecognitionGranted" to isActivityRecognitionGranted()))
            emitDebugEvent("sync:read:start", mapOf("sdkStatus" to sdkStatus(), "activityRecognitionGranted" to isActivityRecognitionGranted()))
            val status = sdkStatus()
            when {
                status != HealthConnectClient.SDK_AVAILABLE -> buildDeviceStepCounterPayload(status)

                healthClient == null -> JSONObject()
                    .put("batches", JSONArray())
                    .put("message", "Health Connect не инициализировался.")
                    .toString()

                else -> {
                    val client = healthClient!!
                    val granted = safeGrantedPermissions(client)
                    if (!granted.containsAll(permissions)) {
                        JSONObject()
                            .put("batches", JSONArray())
                            .put("message", "Разрешения на шаги и дистанцию ещё не выданы.")
                            .toString()
                    } else {
                        withTimeout(10_000) {
                            readMutex.withLock {
                                withContext(Dispatchers.IO) {
                                    buildActivityPayload(client)
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            logError("sync:read:error", error)
            emitDebugEvent("sync:read:error", mapOf("message" to (error.message ?: "unknown")))
            JSONObject()
                .put("batches", JSONArray())
                .put("message", error.message ?: "Не удалось прочитать данные из Health Connect.")
                .toString()
        }
    }



    private fun knownHealthApps(): JSONArray {
        val packages = listOf(
            "com.google.android.apps.healthdata" to "Health Connect",
            "com.sec.android.app.shealth" to "Samsung Health",
            "com.huawei.health" to "Huawei Health",
            "com.hihonor.health" to "Honor Health",
            "com.xiaomi.wearable" to "Mi Fitness",
            "com.xiaomi.hm.health" to "Zepp Life",
            "com.huami.watch.hmwatchmanager" to "Zepp"
        )
        val array = JSONArray()
        for ((packageName, label) in packages) {
            array.put(JSONObject().put("package", packageName).put("label", label).put("installed", isPackageInstalled(packageName)))
        }
        return array
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasStepCounterSensor(): Boolean {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    private suspend fun buildDeviceStepCounterPayload(status: Int): String {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val startOfDay = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()

        if (sensor == null) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("preferred_source", "device_step_counter")
                .put("message", unavailableMessage(status))
                .toString()
        }

        if (!isActivityRecognitionGranted()) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("generated_at", now.toString())
                .put("preferred_source", "device_step_counter")
                .put("provider", providerPayload("device_step_counter", "Android Device Step Counter", 40, 55))
                .put("message", "Для чтения аппаратного счётчика шагов нужно разрешение ACTIVITY_RECOGNITION.")
                .toString()
        }

        val rawCounter = readStepCounterSnapshot(sensorManager, sensor)
        if (rawCounter == null) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("generated_at", now.toString())
                .put("preferred_source", "device_step_counter")
                .put("provider", providerPayload("device_step_counter", "Android Device Step Counter", 40, 55))
                .put("message", "Не удалось получить live-снимок аппаратного счётчика шагов.")
                .toString()
        }

        val dayKey = now.atZone(zoneId).toLocalDate().toString()
        val prefs = context.getSharedPreferences(STEP_COUNTER_PREFS, Context.MODE_PRIVATE)
        val baselineKey = "baseline_$dayKey"
        val lastRawKey = "last_raw"
        val lastDayKey = "last_day"

        val previousRaw = prefs.getFloat(lastRawKey, rawCounter)
        val storedDay = prefs.getString(lastDayKey, null)
        val baseline = if (storedDay != dayKey || !prefs.contains(baselineKey) || rawCounter < previousRaw) {
            rawCounter
        } else {
            prefs.getFloat(baselineKey, rawCounter)
        }

        prefs.edit()
            .putString(lastDayKey, dayKey)
            .putFloat(baselineKey, baseline)
            .putFloat(lastRawKey, rawCounter)
            .apply()

        val stepsToday = max(0f, rawCounter - baseline).toLong()
        val batches = JSONArray()
        if (stepsToday > 0L) {
            batches.put(
                JSONObject()
                    .put("kind", "walk_steps")
                    .put("external_batch_id", "device-step-counter-${startOfDay.epochSecond}")
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject()
                                .put("activity_type", "walk")
                                .put("metric_type", "steps")
                                .put("value", stepsToday)
                                .put("recorded_from", startOfDay.toString())
                                .put("recorded_to", now.toString())
                                .put("source_hash", "device-step-counter-${startOfDay.epochSecond}-$stepsToday")
                        )
                    )
            )
        }

        return JSONObject()
            .put("batches", batches)
            .put("generated_at", now.toString())
            .put("preferred_source", "device_step_counter")
            .put("provider", providerPayload("device_step_counter", "Android Device Step Counter", 40, 55))
            .put("raw_counter_since_boot", rawCounter.toDouble())
            .put("baseline_counter", baseline.toDouble())
            .put("message", if (stepsToday > 0L) "Шаги получены из аппаратного счётчика телефона." else "Счётчик доступен, дневной baseline создан. Следующие шаги начнут учитываться после движения.")
            .toString()
    }

    private suspend fun readStepCounterSnapshot(sensorManager: SensorManager, sensor: Sensor): Float? {
        return withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(3_000) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            val value = event.values.firstOrNull()
                            if (value != null && continuation.isActive) {
                                sensorManager.unregisterListener(this)
                                continuation.resume(value)
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }

                    val registered = sensorManager.registerListener(
                        listener,
                        sensor,
                        SensorManager.SENSOR_DELAY_NORMAL,
                    )

                    if (!registered && continuation.isActive) {
                        continuation.resume(null)
                    }

                    continuation.invokeOnCancellation {
                        sensorManager.unregisterListener(listener)
                    }
                }
            }
        }
    }

    private fun providerPayload(type: String, name: String, priority: Int, confidenceScore: Int): JSONObject {
        return JSONObject()
            .put("type", type)
            .put("name", name)
            .put("priority", priority)
            .put("confidence_score", confidenceScore)
    }

    private suspend fun safeGrantedPermissions(client: HealthConnectClient): Set<String> {
        return try {
            withTimeout(5_000) {
                permissionsMutex.withLock {
                    withContext(Dispatchers.IO) {
                        client.permissionController.getGrantedPermissions()
                    }
                }
            }
        } catch (error: Throwable) {
            logError("permissions:getGranted:error", error)
            emptySet()
        }
    }

    private suspend fun buildActivityPayload(client: HealthConnectClient): String {
        logDebug("sync:build_payload:start")
        emitDebugEvent("sync:build_payload:start")
        val zoneId = ZoneId.systemDefault()
        val now = Instant.now()
        val startOfDay = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()

        val stepsTotal = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            )
        )[StepsRecord.COUNT_TOTAL] ?: 0L

        val distanceMeters = calculateRunningDistanceMeters(client, startOfDay, now)

        val batches = JSONArray()
        if (stepsTotal > 0L) {
            batches.put(
                JSONObject()
                    .put("kind", "walk_steps")
                    .put("external_batch_id", "android-steps-${startOfDay.epochSecond}")
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject()
                                .put("activity_type", "walk")
                                .put("metric_type", "steps")
                                .put("value", stepsTotal)
                                .put("recorded_from", startOfDay.toString())
                                .put("recorded_to", now.toString())
                                .put("source_hash", "android-steps-${startOfDay.epochSecond}-$stepsTotal")
                        )
                    )
            )
        }
        if (distanceMeters > 0.0) {
            val normalizedDistance = String.format(Locale.US, "%.2f", distanceMeters).toDouble()
            batches.put(
                JSONObject()
                    .put("kind", "run_distance")
                    .put("external_batch_id", "android-distance-${startOfDay.epochSecond}")
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject()
                                .put("activity_type", "run")
                                .put("metric_type", "meters")
                                .put("value", normalizedDistance)
                                .put("recorded_from", startOfDay.toString())
                                .put("recorded_to", now.toString())
                                .put("source_hash", "android-distance-${startOfDay.epochSecond}-$normalizedDistance")
                        )
                    )
            )
        }

        val payload = JSONObject()
            .put("batches", batches)
            .put("generated_at", now.toString())
            .put("preferred_source", "health_connect")
            .put("provider", JSONObject().put("type", "health_connect").put("name", "Health Connect").put("priority", 80).put("confidence_score", 88))
            .toString()

        logDebug("sync:build_payload:result", mapOf("stepsTotal" to stepsTotal, "distanceMeters" to distanceMeters, "batchesCount" to batches.length(), "payload" to payload))
        emitDebugEvent("sync:build_payload:result", mapOf("stepsTotal" to stepsTotal, "distanceMeters" to distanceMeters, "batchesCount" to batches.length(), "payload" to payload))

        return payload
    }



    private suspend fun calculateRunningDistanceMeters(
        client: HealthConnectClient,
        startOfDay: Instant,
        now: Instant,
    ): Double {
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            )
        ).records

        var total = 0.0
        for (session in sessions) {
            if (session.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_RUNNING) {
                continue
            }

            val sessionDistance = client.aggregate(
                AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                )
            )[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0

            total += sessionDistance
        }

        return total
    }

    private fun emitDebugEvent(stage: String, payload: Map<String, Any?> = emptyMap()) {
        val event = JSONObject()
            .put("stage", stage)
            .put("payload", JSONObject.wrap(payload) ?: JSONObject())
            .put("at", Instant.now().toString())
            .toString()

        try {
            onDebugJavascript(event)
        } catch (_: Throwable) {
        }
    }

    private fun permissionPayload(
        available: Boolean,
        granted: Boolean,
        pending: Boolean,
        message: String,
    ): String {
        return JSONObject()
            .put("available", available)
            .put("granted", granted)
            .put("pending", pending)
            .put("message", message)
            .toString()
    }

    private fun unavailablePayload(sdkStatus: Int): String {
        return permissionPayload(
            available = false,
            granted = false,
            pending = false,
            message = unavailableMessage(sdkStatus),
        )
    }

    private fun unavailableMessage(sdkStatus: Int): String {
        return when (sdkStatus) {
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect нужно обновить или установить из Google Play."
            HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect недоступен на этом устройстве."
            else -> "Health Connect сейчас недоступен."
        }
    }


    private fun logDebug(message: String, extras: Map<String, Any?> = emptyMap()) {
        if (extras.isEmpty()) {
            Log.d(LOG_TAG, message)
            return
        }

        Log.d(LOG_TAG, "$message | ${JSONObject.wrap(extras)}")
    }

    private fun logError(message: String, error: Throwable) {
        Log.e(LOG_TAG, "$message | ${error.message}", error)
    }

    companion object {
        private const val HEALTH_CONNECT_PACKAGE_NAME = "com.google.android.apps.healthdata"
        private const val STEP_COUNTER_PREFS = "grafit_device_step_counter"
        private const val LOG_TAG = "GrafitActivitySync"
    }
}
