package com.second.risedie.challengeapp.bridge

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.ext.SdkExtensions
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
import com.second.risedie.challengeapp.security.TrustedWebOrigin
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
    private val onPrerequisitesJavascript: (String) -> Unit,
    private val onBackgroundReadinessJavascript: (String) -> Unit,
    private val onLaunchNotificationPermission: () -> Unit,
) {
    private val context: Context = activity.applicationContext
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionFlowInProgress = AtomicBoolean(false)
    private val notificationPermissionInProgress = AtomicBoolean(false)
    private val permissionStateChecked = AtomicBoolean(false)
    private val stepSourceEvidenceRefreshInProgress = AtomicBoolean(false)
    private val stepSourceEvidenceChecked = AtomicBoolean(false)
    private val stepDataObserved = AtomicBoolean(false)
    private val distanceDataObserved = AtomicBoolean(false)
    private val exerciseDataObserved = AtomicBoolean(false)
    private val detectedStepWriterPackage = AtomicReference<String?>(null)
    private val detectedDistanceWriterPackage = AtomicReference<String?>(null)
    private val detectedExerciseWriterPackage = AtomicReference<String?>(null)
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

    private fun sdkExtension34(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        runCatching { SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) }.getOrDefault(0)
    } else {
        0
    }

    private fun onDeviceStepsSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && sdkExtension34() >= 20

    private fun notificationPermissionRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun notificationPermissionGranted(): Boolean =
        !notificationPermissionRequired() ||
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val healthClient: HealthConnectClient?
        get() = healthRepository.clientOrNull()

    @JavascriptInterface
    fun getHealthSyncLog(): String = JSONObject()
        .put("content", healthSyncLogger.tail(400))
        .toString()

    @JavascriptInterface
    fun clearHealthSyncLog(): String {
        val cleared = healthSyncLogger.clear()
        return JSONObject()
            .put("cleared", cleared)
            .put("content", if (cleared) healthSyncLogger.tail(400) else "Не удалось очистить журнал.")
            .toString()
    }

    @JavascriptInterface
    fun shareHealthSyncLog(): String {
        val shared = healthSyncLogger.share(activity)
        return JSONObject()
            .put("shared", shared)
            .put("message", if (shared) "Открыто системное меню отправки." else "Журнал ещё не создан.")
            .toString()
    }

    @JavascriptInterface
    fun getBridgeInfo(): String {
        val status = sdkStatus()
        return JSONObject()
            .put("bridge", "ChallengeAppBridge")
            .put("platform", "android")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("sdk_extension_34", sdkExtension34())
            .put("on_device_steps_supported", onDeviceStepsSupported())
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
    fun getActivityPrerequisites(): String = activityPrerequisitesPayload().toString()

    @JavascriptInterface
    fun getBackgroundActivityReadiness(): String = backgroundActivityReadinessPayload().toString()

    @JavascriptInterface
    fun openBackgroundActivitySettings(): String {
        val packageUri = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            "battery" to Intent("android.settings.APP_BATTERY_SETTINGS").setData(packageUri),
            "application_details" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri),
        )

        for ((destination, intent) in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) == null) continue
                activity.startActivity(intent)
                return JSONObject().put("opened", true).put("destination", destination).toString()
            } catch (_: ActivityNotFoundException) {
            } catch (_: Throwable) {
            }
        }

        return JSONObject()
            .put("opened", false)
            .put("message", "Не удалось открыть системные настройки приложения.")
            .toString()
    }

    @JavascriptInterface
    fun getBatteryOptimizationState(): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val provider = activityProviderPackages().firstOrNull { isPackageInstalled(it.first) }
        fun row(packageName: String, label: String): JSONObject {
            val ignoringOptimizations: Boolean? = pm?.isIgnoringBatteryOptimizations(packageName)
            return JSONObject()
                .put("package", packageName)
                .put("label", label)
                .put("installed", isPackageInstalled(packageName))
                .put("optimization_state_known", ignoringOptimizations != null)
                .put("ignoring_battery_optimizations", ignoringOptimizations ?: JSONObject.NULL)
                .put("unrestricted", ignoringOptimizations ?: JSONObject.NULL) // compatibility alias; not an Android UI-mode claim
        }
        return JSONObject().put("platform", "android")
            .put("app", row(context.packageName, "GraFit"))
            .put("health_connect", row(HealthConnectRepository.HEALTH_CONNECT_PACKAGE_NAME, "Health Connect"))
            .put("provider", if (provider != null) row(provider.first, provider.second) else JSONObject.NULL)
            .toString()
    }

    @JavascriptInterface
    fun openBatterySettingsFor(target: String?): String {
        val provider = activityProviderPackages().firstOrNull { isPackageInstalled(it.first) }
        val packageName = when (target?.trim()) {
            "app" -> context.packageName
            "health_connect" -> HealthConnectRepository.HEALTH_CONNECT_PACKAGE_NAME
            "provider" -> provider?.first
            else -> null
        } ?: return JSONObject().put("opened", false).put("message", "Приложение не найдено.").toString()
        val uri = Uri.parse("package:$packageName")
        val candidates = listOf(Intent("android.settings.APP_BATTERY_SETTINGS").setData(uri), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(uri))
        for (intent in candidates) try { if (intent.resolveActivity(context.packageManager) != null) { activity.startActivity(intent); return JSONObject().put("opened", true).put("package", packageName).toString() } } catch (_: Throwable) {}
        return JSONObject().put("opened", false).put("message", "Не удалось открыть настройки батареи.").toString()
    }

    @JavascriptInterface
    fun performActivityPrerequisiteAction(actionId: String?): String {
        val id = actionId?.trim().orEmpty()
        val packageName = when (id) {
            "health_connect" -> HealthConnectRepository.HEALTH_CONNECT_PACKAGE_NAME
            "google_fit" -> "com.google.android.apps.fitness"
            "samsung_health" -> "com.sec.android.app.shealth"
            "huawei_health" -> "com.huawei.health"
            "honor_health" -> "com.hihonor.health"
            "mi_fitness" -> "com.xiaomi.wearable"
            "zepp_life" -> "com.xiaomi.hm.health"
            "zepp" -> "com.huami.watch.hmwatchmanager"
            else -> return JSONObject().put("opened", false).put("message", "Неизвестное действие readiness gate.").toString()
        }

        val sdkStatus = sdkStatus()
        if (id == "health_connect" && sdkStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            return JSONObject().put("opened", false).put("message", "Health Connect недоступен на этом устройстве.").toString()
        }

        val candidates = mutableListOf<Intent>()
        val healthConnectUpdateRequired = id == "health_connect" &&
            sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

        if (healthConnectUpdateRequired || !isPackageInstalled(packageName)) {
            candidates += Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).setPackage("com.android.vending")
            candidates += Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)?.let { candidates += it }
            candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
        }

        for (intent in candidates) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return JSONObject().put("opened", true).put("action_id", id).toString()
            } catch (_: ActivityNotFoundException) {
            } catch (_: Throwable) {
            }
        }
        return JSONObject().put("opened", false).put("action_id", id).put("message", "Не удалось открыть безопасное действие.").toString()
    }

    @JavascriptInterface
    fun configureNativeHealthSync(token: String?, apiBase: String?, sourceId: String?): String {
        healthSyncLogger.info("configuration", "bridge", "configure_requested")
        val normalizedToken = token?.trim().orEmpty()
        val normalizedApiBase = TrustedWebOrigin.canonicalOrigin(apiBase)
        val normalizedSourceId = sourceId?.trim()?.toLongOrNull() ?: 0L

        if (normalizedToken.isBlank() || normalizedApiBase == null || normalizedSourceId <= 0L) {
            return JSONObject()
                .put("configured", false)
                .put("message", "Недостаточно данных для фоновой Health Connect синхронизации.")
                .toString()
        }

        foregroundSyncEngine.configure(normalizedToken, normalizedApiBase, normalizedSourceId)
        HealthSyncWorker.enqueuePeriodic(context)
        foregroundSyncEngine.startForegroundLoop()

        runCatching { PushTokenRegistrar.configure(context, normalizedToken, normalizedApiBase) }
            .onFailure { healthSyncLogger.warn("configuration", "bridge", "push_configuration_failed_non_blocking", error = it) }
        // Permission dialogs are owned by the first-launch/recovery coordinator. Configuration must be side-effect free.
        emitDebugEvent("foreground_sync:configured", mapOf("apiBase" to normalizedApiBase, "sourceId" to normalizedSourceId))

        return JSONObject()
            .put("configured", true)
            .put("source_id", normalizedSourceId)
            .put("server_timezone", "UTC")
            .put("foreground_loop_min_seconds", 90)
            .put("foreground_loop_max_seconds", 180)
            .put("immediate_sync_queued", false)
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

        // Compatibility wrapper for explicit user actions: request only the next missing layer.
        // It intentionally does not chain multiple system dialogs; the coordinator owns sequencing.
        if (!isActivityRecognitionGranted()) return requestPhysicalActivityPermission()
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
                        backgroundSupported = healthRepository.isBackgroundReadAvailable(),
                        backgroundGranted = grantedPermissions.contains(healthRepository.backgroundReadPermission),
                    )
                    cachedPermissionPayload = grantedPayload
                    onNotifyJavascript(grantedPayload)
                    refreshActivitySourceEvidenceAndNotify(force = true)
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

            if (completedStage == PermissionRequestStage.DATA && dataGranted) {
                healthClient?.let { refreshActivitySourceEvidenceAndNotify(force = true) }
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

    @JavascriptInterface
    fun requestNotificationPermission(): String {
        val required = notificationPermissionRequired()
        val granted = notificationPermissionGranted()
        if (!required || granted) {
            return JSONObject(cachedPermissionPayload)
                .put("notification_required", required)
                .put("notification_granted", true)
                .put("notification_pending", false)
                .put("message", if (required) "Разрешение на уведомления уже выдано." else "Разрешение на уведомления не требуется.")
                .toString()
        }
        if (!notificationPermissionInProgress.compareAndSet(false, true)) {
            return JSONObject(cachedPermissionPayload)
                .put("notification_required", true)
                .put("notification_granted", false)
                .put("notification_pending", true)
                .put("message", "Окно разрешения на уведомления уже открыто.")
                .toString()
        }
        healthSyncLogger.info("permissions", "bridge", "notification_permission_requested")
        onLaunchNotificationPermission()
        return JSONObject(cachedPermissionPayload)
            .put("notification_required", true)
            .put("notification_granted", false)
            .put("notification_pending", true)
            .put("message", "Запрашиваем разрешение на уведомления.")
            .toString()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        notificationPermissionInProgress.set(false)
        healthSyncLogger.info("permissions", "bridge", "notification_permission_result", JSONObject().put("granted", granted))
        emitDebugEvent("permissions:notification_result", mapOf("granted" to granted))
        refreshPermissionState(notifyJavascript = true, enqueueNativeSync = false)
    }

    fun onActivityRecognitionPermissionResult(granted: Boolean) {
        healthSyncLogger.info("permissions", "bridge", "activity_recognition_result", JSONObject().put("granted", granted))
        if (granted) liveStepTracker.start()
        val payload = permissionPayload(
            available = sdkStatus() == HealthConnectClient.SDK_AVAILABLE,
            granted = healthConnectGrantedFromCache(),
            pending = false,
            message = if (granted) {
                "Системное разрешение на физическую активность получено."
            } else {
                "Системное разрешение на физическую активность не выдано."
            },
        )
        cachedPermissionPayload = payload
        onNotifyJavascript(payload)
        emitDebugEvent("permissions:activity_recognition_result", mapOf("granted" to granted))
    }

    fun onHostResumed() {
        if (isActivityRecognitionGranted()) liveStepTracker.start()
        foregroundSyncEngine.onAppBackground()
        emitDebugEvent("host:resumed", mapOf("activityRecognitionGranted" to isActivityRecognitionGranted(), "sdkStatus" to sdkStatus()))
        refreshPermissionState(notifyJavascript = true, enqueueNativeSync = true, forceSourceEvidence = true)
        try { onPrerequisitesJavascript(activityPrerequisitesPayload().toString()) } catch (_: Throwable) {}
        try { onBackgroundReadinessJavascript(backgroundActivityReadinessPayload().toString()) } catch (_: Throwable) {}
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

    private fun refreshPermissionState(notifyJavascript: Boolean, enqueueNativeSync: Boolean, forceSourceEvidence: Boolean = false) {
        bridgeScope.launch {
            val payload = try {
                val status = sdkStatus()
                if (status != HealthConnectClient.SDK_AVAILABLE) {
                    unavailablePayload(status)
                } else {
                    val client = healthClient
                    if (client == null) {
                        permissionPayload(false, false, false, "Health Connect не инициализировался.")
                    } else {
                        val grantedPermissions = safeGrantedPermissions(client)
                        val dataGranted = grantedPermissions.containsAll(healthRepository.dataPermissions)
                        if (grantedPermissions.contains(healthRepository.stepsReadPermission)) {
                            refreshActivitySourceEvidence(force = forceSourceEvidence)
                        } else {
                            stepSourceEvidenceChecked.set(false)
                            stepDataObserved.set(false)
                            distanceDataObserved.set(false)
                            exerciseDataObserved.set(false)
                            detectedStepWriterPackage.set(null)
                            detectedDistanceWriterPackage.set(null)
                            detectedExerciseWriterPackage.set(null)
                        }
                        permissionPayload(
                            available = true,
                            granted = dataGranted,
                            pending = permissionFlowInProgress.get(),
                            message = when {
                                !isActivityRecognitionGranted() -> "Системное разрешение на физическую активность ещё не выдано."
                                dataGranted -> "Разрешения Health Connect получены."
                                else -> "Разрешения Health Connect на шаги, дистанцию и тренировки пока не выданы."
                            },
                            backgroundSupported = healthRepository.isBackgroundReadAvailable(),
                            backgroundGranted = grantedPermissions.contains(healthRepository.backgroundReadPermission),
                        )
                    }
                }
            } catch (error: Throwable) {
                permissionPayload(false, false, false, error.message ?: "Не удалось проверить состояние Health Connect.")
            }
            permissionStateChecked.set(true)
            val checkedPayload = runCatching { JSONObject(payload).put("state_checked", true).toString() }.getOrDefault(payload)
            cachedPermissionPayload = checkedPayload
            val permissionState = runCatching { JSONObject(checkedPayload) }.getOrNull()
            if (enqueueNativeSync) {
                val physicalGranted = permissionState?.optBoolean("physical_activity_granted", false) == true
                val healthGranted = permissionState?.optBoolean("health_connect_granted", false) == true
                val coreReady = activityPrerequisitesPayload(triggerEvidenceRefresh = false).optBoolean("ready", false) &&
                    physicalGranted && healthGranted
                if (coreReady) foregroundSyncEngine.onAppForeground("app_resume") else foregroundSyncEngine.onAppBackground()
            }
            if (notifyJavascript) onNotifyJavascript(checkedPayload)
            if (notifyJavascript) {
                runCatching { onPrerequisitesJavascript(activityPrerequisitesPayload(triggerEvidenceRefresh = false).toString()) }
            }
        }
    }

    private suspend fun refreshActivitySourceEvidence(force: Boolean = false) {
        if (!force && stepSourceEvidenceChecked.get()) return
        if (!stepSourceEvidenceRefreshInProgress.compareAndSet(false, true)) return
        try {
            val stepOrigins = if (onDeviceStepsSupported()) emptySet() else healthRepository.observedStepDataOrigins()
            val distanceOrigins = healthRepository.observedDistanceDataOrigins()
            val exerciseOrigins = healthRepository.observedExerciseDataOrigins()

            stepSourceEvidenceChecked.set(true)
            stepDataObserved.set(stepOrigins.isNotEmpty())
            distanceDataObserved.set(distanceOrigins.isNotEmpty())
            exerciseDataObserved.set(exerciseOrigins.isNotEmpty())
            detectedStepWriterPackage.set(stepOrigins.firstOrNull())
            detectedDistanceWriterPackage.set(distanceOrigins.firstOrNull())
            detectedExerciseWriterPackage.set(exerciseOrigins.firstOrNull())
            emitDebugEvent(
                "activity_setup:source_evidence",
                mapOf(
                    "on_device_steps_supported" to onDeviceStepsSupported(),
                    "steps_observed" to stepOrigins.isNotEmpty(),
                    "distance_observed" to distanceOrigins.isNotEmpty(),
                    "exercise_observed" to exerciseOrigins.isNotEmpty(),
                    "known_step_provider" to stepOrigins.firstNotNullOfOrNull { origin ->
                        activityProviderPackages().firstOrNull { it.first == origin }?.second
                    },
                ),
            )
        } finally {
            stepSourceEvidenceRefreshInProgress.set(false)
        }
    }

    private suspend fun refreshActivitySourceEvidenceAndNotify(force: Boolean = false) {
        refreshActivitySourceEvidence(force = force)
        runCatching { onPrerequisitesJavascript(activityPrerequisitesPayload(triggerEvidenceRefresh = false).toString()) }
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
        "com.google.android.apps.fitness" to "Google Fit",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.huawei.health" to "Huawei Health",
        "com.hihonor.health" to "Honor Health",
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.xiaomi.hm.health" to "Zepp Life",
        "com.huami.watch.hmwatchmanager" to "Zepp",
    )


    private fun activityProviderPackages(): List<Pair<String, String>> = knownHealthAppPackages()
        .filterNot { it.first == HealthConnectRepository.HEALTH_CONNECT_PACKAGE_NAME }

    private fun backgroundActivityReadinessPayload(): JSONObject {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager == null) {
            return JSONObject()
                .put("platform", "android")
                .put("supported", false)
                .put("restricted", false)
                .put("status", "unknown")
                .put("action_available", true)
        }
        val restricted = activityManager.isBackgroundRestricted
        return JSONObject()
            .put("platform", "android")
            .put("supported", true)
            .put("restricted", restricted)
            .put("status", if (restricted) "restricted" else "ok")
            .put("action_available", true)
    }

    private fun providerActionId(packageName: String): String? = when (packageName) {
        "com.google.android.apps.fitness" -> "google_fit"
        "com.sec.android.app.shealth" -> "samsung_health"
        "com.huawei.health" -> "huawei_health"
        "com.hihonor.health" -> "honor_health"
        "com.xiaomi.wearable" -> "mi_fitness"
        "com.xiaomi.hm.health" -> "zepp_life"
        "com.huami.watch.hmwatchmanager" -> "zepp"
        else -> null
    }

    private fun activityPrerequisitesPayload(triggerEvidenceRefresh: Boolean = true): JSONObject {
        val status = sdkStatus()
        val hcAvailable = status == HealthConnectClient.SDK_AVAILABLE
        val hcUpdateRequired = status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        val onDeviceSteps = onDeviceStepsSupported()
        val installedProviders = JSONArray()
        val providerCatalog = JSONArray()
        var firstInstalledProvider: Pair<String, String>? = null
        for ((packageName, label) in activityProviderPackages()) {
            val installed = isPackageInstalled(packageName)
            val actionId = providerActionId(packageName)
            providerCatalog.put(
                JSONObject()
                    .put("package", packageName)
                    .put("label", label)
                    .put("installed", installed)
                    .put("action_id", actionId ?: JSONObject.NULL)
            )
            if (installed) {
                if (firstInstalledProvider == null) firstInstalledProvider = packageName to label
                installedProviders.put(
                    JSONObject()
                        .put("package", packageName)
                        .put("label", label)
                        .put("action_id", actionId ?: JSONObject.NULL)
                )
            }
        }

        val externalRequired = !onDeviceSteps
        val observedWriter = stepDataObserved.get()
        val knownProviderPrepared = installedProviders.length() > 0
        val stepSourceReady = hcAvailable && (onDeviceSteps || knownProviderPrepared || observedWriter)
        val detectedPackage = detectedStepWriterPackage.get()
        val detectedKnownProvider = detectedPackage?.let { pkg ->
            activityProviderPackages().firstOrNull { it.first == pkg }
        }
        val stepMode = when {
            onDeviceSteps -> "health_connect_on_device"
            observedWriter || knownProviderPrepared -> "external_health_connect_writer"
            else -> "unknown"
        }
        val distanceObserved = distanceDataObserved.get()
        val exerciseObserved = exerciseDataObserved.get()
        val distanceSourceReady = hcAvailable && (distanceObserved || knownProviderPrepared)
        val exerciseSourceReady = hcAvailable && (exerciseObserved || knownProviderPrepared)

        if (triggerEvidenceRefresh && hcAvailable && externalRequired && !knownProviderPrepared && !stepSourceEvidenceChecked.get() && healthConnectGrantedFromCache()) {
            healthClient?.let {
                bridgeScope.launch {
                    refreshActivitySourceEvidenceAndNotify()
                }
            }
        }

        val missing = JSONArray()
        if (!hcAvailable) missing.put("health_connect")
        if (hcAvailable && !stepSourceReady) missing.put("step_source")

        return JSONObject()
            .put("platform", "android")
            .put("ready", hcAvailable && stepSourceReady)
            .put("blocking", !(hcAvailable && stepSourceReady))
            .put("health_connect", JSONObject()
                .put("status", when { hcAvailable -> "available"; hcUpdateRequired -> "install_or_update_required"; else -> "unsupported" })
                .put("available", hcAvailable)
                .put("action_id", if (hcUpdateRequired) "health_connect" else JSONObject.NULL))
            .put("capabilities", JSONObject()
                .put("sdk_int", Build.VERSION.SDK_INT)
                .put("sdk_extension_34", sdkExtension34())
                .put("on_device_steps_supported", onDeviceSteps)
                .put("background_read_supported", healthRepository.isBackgroundReadAvailable()))
            .put("step_source", JSONObject()
                .put("ready", stepSourceReady)
                .put("external_required", externalRequired)
                .put("mode", stepMode)
                .put("evidence_checked", stepSourceEvidenceChecked.get() || onDeviceSteps || knownProviderPrepared)
                .put("data_observed", observedWriter)
                .put("detected_provider", when {
                    detectedKnownProvider != null -> JSONObject()
                        .put("label", detectedKnownProvider.second)
                        .put("known", true)
                    observedWriter -> JSONObject().put("label", "Источник Health Connect").put("known", false)
                    firstInstalledProvider != null -> JSONObject()
                        .put("label", firstInstalledProvider!!.second)
                        .put("known", true)
                    else -> JSONObject.NULL
                }))
            .put("distance_source", JSONObject()
                .put("ready", distanceSourceReady)
                .put("data_capability", hcAvailable)
                .put("data_observed", distanceObserved)
                .put("external_provider_available", knownProviderPrepared)
                .put("detected_provider", detectedDistanceWriterPackage.get() ?: JSONObject.NULL)
                .put("blocking_for_step_onboarding", false))
            .put("exercise_source", JSONObject()
                .put("ready", exerciseSourceReady)
                .put("data_capability", hcAvailable)
                .put("data_observed", exerciseObserved)
                .put("external_provider_available", knownProviderPrepared)
                .put("detected_provider", detectedExerciseWriterPackage.get() ?: JSONObject.NULL)
                .put("blocking_for_step_onboarding", false))
            .put("permissions", JSONObject()
                .put("activity_recognition_required", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                .put("activity_recognition_granted", isActivityRecognitionGranted())
                .put("notification_required", notificationPermissionRequired())
                .put("notification_granted", notificationPermissionGranted())
                .put("background_read_supported", healthRepository.isBackgroundReadAvailable()))
            // Compatibility fields remain diagnostic only; providers.ready now means step capability, not allowlist membership.
            .put("providers", JSONObject()
                .put("ready", stepSourceReady)
                .put("installed", installedProviders)
                .put("catalog", providerCatalog))
            .put("missing", missing)
    }

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
            .put("state_checked", permissionStateChecked.get())
            .put("granted", granted)
            .put("pending", pending)
            .put("message", message)
            .put("physical_activity_granted", isActivityRecognitionGranted())
            .put("health_connect_granted", granted)
            .put("health_connect_available", sdkStatus() == HealthConnectClient.SDK_AVAILABLE)
            .put("background_read_supported", backgroundSupported)
            .put("background_read_granted", backgroundGranted)
            .put("notification_required", notificationPermissionRequired())
            .put("notification_granted", notificationPermissionGranted())
            .put("notification_pending", notificationPermissionInProgress.get())
            .put("setup_permissions_ready",
                isActivityRecognitionGranted() &&
                    granted &&
                    (!backgroundSupported || backgroundGranted) &&
                    notificationPermissionGranted())
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
