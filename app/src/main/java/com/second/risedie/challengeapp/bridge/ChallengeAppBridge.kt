package com.second.risedie.challengeapp.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.second.risedie.challengeapp.BuildConfig
import com.second.risedie.challengeapp.health.HealthConnectRepository
import com.second.risedie.challengeapp.health.LiveStepTracker
import com.second.risedie.challengeapp.sync.ForegroundHealthSyncEngine
import com.second.risedie.challengeapp.sync.HealthSyncWorker
import com.second.risedie.challengeapp.sync.HealthSyncLogger
import com.second.risedie.challengeapp.push.PushTokenRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ChallengeAppBridge(
    private val activity: ComponentActivity,
    private val onLaunchPermissions: (Intent) -> Unit,
    private val onLaunchActivityRecognitionPermission: () -> Unit,
    private val isActivityRecognitionGranted: () -> Boolean,
    private val onNotifyJavascript: (String) -> Unit,
    private val onDebugJavascript: (String) -> Unit,
    private val onActivitySyncJavascript: (String) -> Unit,
    private val onLaunchNotificationPermission: () -> Unit,
) {
    private val context: Context = activity.applicationContext
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionFlowInProgress = AtomicBoolean(false)
    private val physicalPermissionContinuation = AtomicBoolean(false)
    private val permissionRequestStage = AtomicReference(PermissionRequestStage.NONE)
    private val permissionsMutex = Mutex()
    private val healthSyncLogger = HealthSyncLogger(context)
    private val healthRepository = HealthConnectRepository(context, healthSyncLogger)
    private val liveStepTracker = LiveStepTracker(context)
    private val foregroundSyncEngine = ForegroundHealthSyncEngine(context, liveStepTracker) { eventJson -> onActivitySyncJavascript(eventJson) }

    @Volatile
    private var cachedPermissionPayload: String = permissionPayload(
        available = false,
        granted = false,
        pending = false,
        message = "Проверяем доступность Health Connect.",
    )

    init {
        logDebug("bridge:init")
        emitDebugEvent("bridge:init", mapOf("sdkStatus" to sdkStatus(), "activityRecognitionGranted" to isActivityRecognitionGranted()))
        if (isActivityRecognitionGranted()) liveStepTracker.start()
        refreshPermissionState(notifyJavascript = false, enqueueNativeSync = false)
    }

    private fun sdkStatus(): Int = healthRepository.sdkStatus()

    private val healthClient: HealthConnectClient?
        get() = healthRepository.clientOrNull()

    @JavascriptInterface
    fun getBridgeInfo(): String {
        val status = sdkStatus()
        return JSONObject()
            .put("bridge", "ChallengeAppBridge")
            .put("platform", "android")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("health_connect_package", HealthConnectRepository.HEALTH_CONNECT_PACKAGE_NAME)
            .put("sdk_status", status)
            .put("available", status == HealthConnectClient.SDK_AVAILABLE)
            .put("permissions", JSONArray(healthRepository.dataPermissions.toList()))
            .put("background_read_supported", healthRepository.isBackgroundReadAvailable())
            .put("activity_recognition_granted", isActivityRecognitionGranted())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("app_version_code", BuildConfig.VERSION_CODE)
            .put("preferred_source", "health_connect")
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("known_health_apps", knownHealthApps())
            .toString()
    }

    @JavascriptInterface
    fun configureNativeHealthSync(token: String?, apiBase: String?, sourceId: String?): String {
        healthSyncLogger.info("configuration", "bridge", "configure_requested")
        val normalizedToken = token?.trim().orEmpty()
        val normalizedApiBase = apiBase?.trim().orEmpty()
        val normalizedSourceId = sourceId?.trim()?.toLongOrNull() ?: 0L

        if (normalizedToken.isBlank() || !normalizedApiBase.startsWith("https://") || normalizedSourceId <= 0L) {
            return JSONObject()
                .put("configured", false)
                .put("message", "Недостаточно данных для фоновой Health Connect синхронизации.")
                .toString()
        }

        foregroundSyncEngine.configure(normalizedToken, normalizedApiBase, normalizedSourceId)
        HealthSyncWorker.configure(context, normalizedToken, normalizedApiBase, normalizedSourceId)
        HealthSyncWorker.enqueuePeriodic(context)
        foregroundSyncEngine.startForegroundLoop()
        val immediate = foregroundSyncEngine.requestForegroundSync("configured")

        runCatching { PushTokenRegistrar.configure(context, normalizedToken, normalizedApiBase) }
            .onFailure { healthSyncLogger.warn("configuration", "bridge", "push_configuration_failed_non_blocking", error = it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { onLaunchNotificationPermission() }
                .onFailure { healthSyncLogger.warn("configuration", "bridge", "notification_permission_launch_failed_non_blocking", error = it) }
        }
        emitDebugEvent("foreground_sync:configured", mapOf("apiBase" to normalizedApiBase, "sourceId" to normalizedSourceId, "requestId" to immediate.optString("request_id")))

        return JSONObject()
            .put("configured", true)
            .put("source_id", normalizedSourceId)
            .put("server_timezone", "UTC")
            .put("foreground_loop_min_seconds", 90)
            .put("foreground_loop_max_seconds", 180)
            .put("immediate_sync_queued", true)
            .put("request_id", immediate.optString("request_id"))
            .toString()
    }



    @JavascriptInterface
    fun clearNativePushRegistration(): String {
        PushTokenRegistrar.clear(context)
        return JSONObject().put("cleared", true).toString()
    }

    @JavascriptInterface
    fun resetLiveAnchorFromServer(activityDate: String?, serverSteps: String?, recordedAt: String?): String {
        val day = activityDate?.trim().orEmpty()
        val steps = serverSteps?.trim()?.toLongOrNull() ?: 0L
        val action = liveStepTracker.resetAnchorFromServer(day, steps, recordedAt?.trim()?.takeIf { it.isNotBlank() })
        return JSONObject()
            .put("reset", action.startsWith("hard_reset"))
            .put("action", action)
            .put("activity_date", day)
            .put("server_steps", steps)
            .toString()
    }

    @JavascriptInterface
    fun triggerNativeHealthSync(): String {
        return requestForegroundSync("manual_refresh")
    }

    @JavascriptInterface
    fun requestForegroundSync(reason: String?): String {
        return foregroundSyncEngine.requestForegroundSync(reason?.trim().orEmpty()).toString()
    }

    @JavascriptInterface
    fun getPermissionState(): String {
        refreshPermissionState(notifyJavascript = false, enqueueNativeSync = false)
        return cachedPermissionPayload
    }

    @JavascriptInterface
    fun requestActivityPermissions(): String {
        logDebug("permissions:request:start", mapOf("activityRecognitionGranted" to isActivityRecognitionGranted(), "sdkStatus" to sdkStatus()))
        emitDebugEvent("permissions:request:start", mapOf("activityRecognitionGranted" to isActivityRecognitionGranted(), "sdkStatus" to sdkStatus()))

        if (!isActivityRecognitionGranted()) {
            physicalPermissionContinuation.set(true)
            return requestPhysicalActivityPermission()
        }

        liveStepTracker.start()
        return requestHealthSourcePermissions()
    }

    @JavascriptInterface
    fun requestPhysicalActivityPermission(): String {
        logDebug("permissions:physical:request", mapOf("activityRecognitionGranted" to isActivityRecognitionGranted()))
        emitDebugEvent("permissions:physical:request", mapOf("activityRecognitionGranted" to isActivityRecognitionGranted()))

        if (isActivityRecognitionGranted()) {
            liveStepTracker.start()
            val payload = permissionPayload(
                available = true,
                granted = healthConnectGrantedFromCache(),
                pending = false,
                message = "Разрешение на физическую активность уже выдано.",
            )
            cachedPermissionPayload = payload
            return payload
        }

        val payload = permissionPayload(
            available = true,
            granted = false,
            pending = true,
            message = "Запрашиваем системное разрешение на физическую активность.",
        )
        cachedPermissionPayload = payload
        onLaunchActivityRecognitionPermission()
        return payload
    }

    @JavascriptInterface
    fun requestHealthSourcePermissions(): String {
        healthSyncLogger.info("permissions", "bridge", "data_permission_requested")
        logDebug("permissions:source:request", mapOf("sdkStatus" to sdkStatus()))
        emitDebugEvent("permissions:source:request", mapOf("sdkStatus" to sdkStatus()))

        val status = sdkStatus()
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            val payload = unavailablePayload(status)
            cachedPermissionPayload = payload
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
            return payload
        }

        val pendingPayload = permissionPayload(
            available = true,
            granted = false,
            pending = true,
            message = "Проверяем разрешения и открываем окно Health Connect при необходимости.",
        )
        cachedPermissionPayload = pendingPayload

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
                if (grantedPermissions.containsAll(healthRepository.dataPermissions)) {
                    permissionFlowInProgress.set(false)
                    val grantedPayload = permissionPayload(
                        available = true,
                        granted = true,
                        pending = false,
                        message = "Разрешения Health Connect уже выданы.",
                    )
                    cachedPermissionPayload = grantedPayload
                    onNotifyJavascript(grantedPayload)
                    foregroundSyncEngine.requestForegroundSync("permission_granted")
                    requestBackgroundReadPermissionInternal()
                    return@launch
                }

                permissionRequestStage.set(PermissionRequestStage.DATA)
                val intent = PermissionController.createRequestPermissionResultContract()
                    .createIntent(activity, healthRepository.dataPermissions)
                onLaunchPermissions(intent)
            } catch (error: Throwable) {
                logError("permissions:source:request:error", error)
                permissionFlowInProgress.set(false)
                val payload = permissionPayload(
                    available = true,
                    granted = false,
                    pending = false,
                    message = error.message ?: "Не удалось открыть окно разрешений Health Connect.",
                )
                cachedPermissionPayload = payload
                onNotifyJavascript(payload)
            }
        }

        return pendingPayload
    }

    fun onPermissionsFlowFinished() {
        bridgeScope.launch {
            healthSyncLogger.info("permissions", "bridge", "permission_flow_finished_callback")
            val completedStage = permissionRequestStage.getAndSet(PermissionRequestStage.NONE)
            permissionFlowInProgress.set(false)
            val granted = healthRepository.grantedPermissions()
            val dataGranted = granted.containsAll(healthRepository.dataPermissions)
            healthSyncLogger.info("permissions", "bridge", "permission_result", JSONObject()
                .put("stage", completedStage.name)
                .put("data_granted", dataGranted)
                .put("background_granted", granted.contains(healthRepository.backgroundReadPermission)))

            if (dataGranted) {
                foregroundSyncEngine.requestForegroundSync("permission_result")
            }
            if (completedStage == PermissionRequestStage.DATA && dataGranted) {
                if (requestBackgroundReadPermissionInternal()) return@launch
            }

            refreshPermissionState(notifyJavascript = true, enqueueNativeSync = false)
        }
    }

    @JavascriptInterface
    fun requestBackgroundReadPermission(): String {
        healthSyncLogger.info("permissions", "bridge", "background_permission_requested")
        val supported = healthRepository.isBackgroundReadAvailable()
        if (!supported) {
            return backgroundPermissionPayload(false, false, false, "Фоновое чтение не поддерживается устройством.")
        }
        val payload = backgroundPermissionPayload(
            supported = true,
            granted = backgroundReadGrantedFromCache(),
            pending = true,
            message = "Открываем разрешение на фоновое чтение данных.",
        )
        cachedPermissionPayload = payload
        bridgeScope.launch {
            if (!requestBackgroundReadPermissionInternal()) {
                refreshPermissionState(notifyJavascript = true, enqueueNativeSync = false)
            }
        }
        return payload
    }

    private suspend fun requestBackgroundReadPermissionInternal(): Boolean {
        if (!healthRepository.isBackgroundReadAvailable()) return false
        val granted = healthRepository.grantedPermissions()
        if (!granted.containsAll(healthRepository.dataPermissions)) return false
        if (granted.contains(healthRepository.backgroundReadPermission)) {
            refreshPermissionState(notifyJavascript = true, enqueueNativeSync = false)
            return false
        }
        if (!permissionFlowInProgress.compareAndSet(false, true)) return true

        permissionRequestStage.set(PermissionRequestStage.BACKGROUND)
        val intent = PermissionController.createRequestPermissionResultContract()
            .createIntent(activity, setOf(healthRepository.backgroundReadPermission))
        onLaunchPermissions(intent)
        return true
    }

    fun onActivityRecognitionPermissionResult(granted: Boolean) {
        healthSyncLogger.info("permissions", "bridge", "activity_recognition_result", JSONObject().put("granted", granted))
        if (granted) {
            liveStepTracker.start()
            val continueToHealthConnect = physicalPermissionContinuation.getAndSet(false)
            if (continueToHealthConnect) {
                requestHealthSourcePermissions()
                return
            }
            val payload = permissionPayload(
                available = true,
                granted = healthConnectGrantedFromCache(),
                pending = false,
                message = "Системное разрешение на физическую активность получено.",
            )
            cachedPermissionPayload = payload
            onNotifyJavascript(payload)
        } else {
            physicalPermissionContinuation.set(false)
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
        if (isActivityRecognitionGranted()) liveStepTracker.start()
        foregroundSyncEngine.onAppForeground("app_resume")
        emitDebugEvent("host:resumed", mapOf("activityRecognitionGranted" to isActivityRecognitionGranted(), "sdkStatus" to sdkStatus(), "foregroundSync" to true))
        refreshPermissionState(notifyJavascript = true, enqueueNativeSync = false)
    }

    fun onHostStopped() {
        foregroundSyncEngine.onAppBackground()
    }

    fun dispose() {
        liveStepTracker.dispose()
        foregroundSyncEngine.onAppBackground()
        bridgeScope.cancel()
    }

    @JavascriptInterface
    fun getLiveActivitySnapshot(): String {
        return try {
            liveStepTracker.snapshot(isActivityRecognitionGranted()).toString()
        } catch (error: Throwable) {
            logError("live_ui:read:error", error)
            JSONObject()
                .put("is_live_ui_only", true)
                .put("available", false)
                .put("message", error.message ?: "Не удалось получить live-шаги.")
                .toString()
        }
    }

    @JavascriptInterface
    fun openHealthConnectSettings(): String {
        val candidates = listOf(
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${HealthConnectRepository.HEALTH_CONNECT_PACKAGE_NAME}")),
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

    @JavascriptInterface
    fun openKnownHealthApp(packageName: String): String {
        val allowedPackages = knownHealthAppPackages().map { it.first }.toSet()
        if (!allowedPackages.contains(packageName)) {
            return JSONObject().put("opened", false).put("message", "Неизвестное приложение здоровья.").toString()
        }
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                activity.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            JSONObject().put("opened", true).toString()
        } catch (_: Throwable) {
            JSONObject().put("opened", false).put("message", "Не удалось открыть приложение.").toString()
        }
    }

    private fun refreshPermissionState(notifyJavascript: Boolean, enqueueNativeSync: Boolean) {
        bridgeScope.launch {
            val payload = try {
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
                        permissionPayload(false, false, false, "Health Connect не инициализировался.")
                    } else {
                        val grantedPermissions = safeGrantedPermissions(client)
                        val granted = grantedPermissions.containsAll(healthRepository.dataPermissions)
                        if (granted && enqueueNativeSync) foregroundSyncEngine.requestForegroundSync("permission_granted")
                        permissionPayload(
                            available = true,
                            granted = granted,
                            pending = permissionFlowInProgress.get(),
                            message = if (granted) "Разрешения получены. Можно синхронизировать шаги и дистанцию." else "Разрешения на шаги и дистанцию пока не выданы.",
                            backgroundSupported = healthRepository.isBackgroundReadAvailable(),
                            backgroundGranted = grantedPermissions.contains(healthRepository.backgroundReadPermission),
                        )
                    }
                }
            } catch (error: Throwable) {
                permissionPayload(false, false, false, error.message ?: "Не удалось проверить состояние Health Connect.")
            }
            cachedPermissionPayload = payload
            if (notifyJavascript) onNotifyJavascript(payload)
        }
    }

    private suspend fun safeGrantedPermissions(client: HealthConnectClient): Set<String> {
        return try {
            withTimeout(5_000) {
                permissionsMutex.withLock {
                    withContext(Dispatchers.IO) { client.permissionController.getGrantedPermissions() }
                }
            }
        } catch (error: Throwable) {
            logError("permissions:getGranted:error", error)
            emptySet()
        }
    }

    private fun knownHealthAppPackages(): List<Pair<String, String>> = listOf(
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.huawei.health" to "Huawei Health",
        "com.hihonor.health" to "Honor Health",
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.xiaomi.hm.health" to "Zepp Life",
        "com.huami.watch.hmwatchmanager" to "Zepp",
    )

    private fun knownHealthApps(): JSONArray {
        val array = JSONArray()
        for ((packageName, label) in knownHealthAppPackages()) {
            array.put(JSONObject().put("package", packageName).put("label", label).put("installed", isPackageInstalled(packageName)))
        }
        return array
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun permissionPayload(
        available: Boolean,
        granted: Boolean,
        pending: Boolean,
        message: String,
        backgroundSupported: Boolean = healthRepository.isBackgroundReadAvailable(),
        backgroundGranted: Boolean = false,
    ): String {
        return JSONObject()
            .put("available", available)
            .put("granted", granted)
            .put("pending", pending)
            .put("message", message)
            .put("physical_activity_granted", isActivityRecognitionGranted())
            .put("health_connect_granted", granted)
            .put("health_connect_available", sdkStatus() == HealthConnectClient.SDK_AVAILABLE)
            .put("background_read_supported", backgroundSupported)
            .put("background_read_granted", backgroundGranted)
            .put("known_health_apps", knownHealthApps())
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .toString()
    }


    private fun backgroundPermissionPayload(supported: Boolean, granted: Boolean, pending: Boolean, message: String): String {
        return JSONObject(cachedPermissionPayload)
            .put("background_read_supported", supported)
            .put("background_read_granted", granted)
            .put("background_read_pending", pending)
            .put("message", message)
            .toString()
    }

    private fun backgroundReadGrantedFromCache(): Boolean {
        return try {
            JSONObject(cachedPermissionPayload).optBoolean("background_read_granted", false)
        } catch (_: Throwable) {
            false
        }
    }

    private enum class PermissionRequestStage { NONE, DATA, BACKGROUND }

    private fun healthConnectGrantedFromCache(): Boolean {
        return try {
            JSONObject(cachedPermissionPayload).optBoolean("health_connect_granted", false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun unavailablePayload(sdkStatus: Int): String = permissionPayload(false, false, false, unavailableMessage(sdkStatus))

    private fun unavailableMessage(sdkStatus: Int): String = when (sdkStatus) {
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect нужно обновить или установить из Google Play."
        HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect недоступен на этом устройстве."
        else -> "Health Connect сейчас недоступен."
    }

    private fun emitDebugEvent(stage: String, payload: Map<String, Any?> = emptyMap()) {
        val event = JSONObject()
            .put("stage", stage)
            .put("payload", JSONObject.wrap(payload) ?: JSONObject())
            .put("at", Instant.now().toString())
            .toString()
        try { onDebugJavascript(event) } catch (_: Throwable) {}
    }

    private fun logDebug(message: String, extras: Map<String, Any?> = emptyMap()) {
        if (extras.isEmpty()) Log.d(LOG_TAG, message) else Log.d(LOG_TAG, "$message | ${JSONObject.wrap(extras)}")
    }

    private fun logError(message: String, error: Throwable) {
        Log.e(LOG_TAG, "$message | ${error.message}", error)
    }

    companion object {
        private const val LOG_TAG = "GrafitActivitySync"
    }
}
