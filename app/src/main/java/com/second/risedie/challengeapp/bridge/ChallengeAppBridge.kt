package com.second.risedie.challengeapp.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ChallengeAppBridge(
    private val activity: ComponentActivity,
    private val onLaunchPermissions: (Intent) -> Unit,
    private val onNotifyJavascript: (String) -> Unit,
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
    )

    init {
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
        return JSONObject()
            .put("bridge", "ChallengeAppBridge")
            .put("platform", "android")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("health_connect_package", HEALTH_CONNECT_PACKAGE_NAME)
            .put("sdk_status", status)
            .put("available", status == HealthConnectClient.SDK_AVAILABLE)
            .put("permissions", JSONArray(permissions.toList()))
            .toString()
    }

    @JavascriptInterface
    fun requestActivityPermissions(): String {
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
                    refreshActivityPayload()
                    return@launch
                }

                val intent = PermissionController.createRequestPermissionResultContract()
                    .createIntent(activity, permissions)
                onLaunchPermissions(intent)
            } catch (error: Throwable) {
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
        permissionFlowInProgress.set(false)
        refreshPermissionState(notifyJavascript = true, refreshActivity = true)
    }

    fun onHostResumed() {
        refreshPermissionState(notifyJavascript = true, refreshActivity = false)
    }

    fun dispose() {
        bridgeScope.cancel()
    }

    @JavascriptInterface
    fun getActivitySyncPayload(): String {
        refreshActivityPayload()
        return cachedActivityPayload
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
                val status = sdkStatus()
                if (status != HealthConnectClient.SDK_AVAILABLE) {
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
            return
        }

        bridgeScope.launch {
            try {
                val status = sdkStatus()
                val payload = when {
                    status != HealthConnectClient.SDK_AVAILABLE -> JSONObject()
                        .put("batches", JSONArray())
                        .put("message", unavailableMessage(status))
                        .toString()

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

                cachedActivityPayload = payload
            } catch (error: Throwable) {
                cachedActivityPayload = JSONObject()
                    .put("batches", JSONArray())
                    .put("message", error.message ?: "Не удалось прочитать данные из Health Connect.")
                    .toString()
            } finally {
                activityRefreshInProgress.set(false)
            }
        }
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
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private suspend fun buildActivityPayload(client: HealthConnectClient): String {
        val zoneId = ZoneId.systemDefault()
        val now = Instant.now()
        val startOfDay = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()

        val stepsTotal = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            )
        )[StepsRecord.COUNT_TOTAL] ?: 0L

        val distanceMeters = client.aggregate(
            AggregateRequest(
                metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            )
        )[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0

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

        return JSONObject()
            .put("batches", batches)
            .put("generated_at", now.toString())
            .toString()
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

    companion object {
        private const val HEALTH_CONNECT_PACKAGE_NAME = "com.google.android.apps.healthdata"
    }
}
