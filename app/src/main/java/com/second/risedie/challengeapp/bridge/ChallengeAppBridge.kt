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
import kotlinx.coroutines.runBlocking
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

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )


    private fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE_NAME)

    private val healthClient: HealthConnectClient?
        get() = if (sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context, HEALTH_CONNECT_PACKAGE_NAME)
        } else {
            null
        }

    @JavascriptInterface
    fun getBridgeInfo(): String {
        val sdkStatus = sdkStatus()
        return JSONObject()
            .put("bridge", "ChallengeAppBridge")
            .put("platform", "android")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("health_connect_package", HEALTH_CONNECT_PACKAGE_NAME)
            .put("sdk_status", sdkStatus)
            .put("available", sdkStatus == HealthConnectClient.SDK_AVAILABLE)
            .put("permissions", JSONArray(permissions.toList()))
            .toString()
    }

    @JavascriptInterface
    fun requestActivityPermissions(): String {
        val sdkStatus = sdkStatus()
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return unavailablePayload(sdkStatus)
        }

        val client = healthClient ?: return unavailablePayload(sdkStatus)
        val grantedPermissions = safeGrantedPermissions(client)

        if (grantedPermissions.containsAll(permissions)) {
            return permissionPayload(
                available = true,
                granted = true,
                pending = false,
                message = "Разрешения Health Connect уже выданы.",
            )
        }

        if (!permissionFlowInProgress.compareAndSet(false, true)) {
            return permissionPayload(
                available = true,
                granted = false,
                pending = true,
                message = "Окно разрешений уже открыто. Подтверди доступ и вернись в приложение.",
            )
        }

        bridgeScope.launch {
            try {
                val intent = PermissionController.createRequestPermissionResultContract()
                    .createIntent(activity, permissions)
                onLaunchPermissions(intent)
            } catch (error: Throwable) {
                permissionFlowInProgress.set(false)
                onNotifyJavascript(
                    permissionPayload(
                        available = true,
                        granted = false,
                        pending = false,
                        message = error.message ?: "Не удалось открыть окно разрешений Health Connect.",
                    )
                )
            }
        }

        return permissionPayload(
            available = true,
            granted = false,
            pending = true,
            message = "Открыто окно Health Connect. Разреши доступ к шагам и дистанции.",
        )
    }

    fun onPermissionsFlowFinished() {
        permissionFlowInProgress.set(false)
        notifyPermissionState()
    }

    fun onHostResumed() {
        notifyPermissionState()
    }

    fun dispose() {
        bridgeScope.cancel()
    }

    @JavascriptInterface
    fun getActivitySyncPayload(): String {
        val sdkStatus = sdkStatus()
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("message", unavailableMessage(sdkStatus))
                .toString()
        }

        val client = healthClient ?: return JSONObject()
            .put("batches", JSONArray())
            .put("message", "Health Connect не инициализировался.")
            .toString()

        val grantedPermissions = safeGrantedPermissions(client)
        if (!grantedPermissions.containsAll(permissions)) {
            return JSONObject()
                .put("batches", JSONArray())
                .put("message", "Разрешения на шаги и дистанцию ещё не выданы.")
                .toString()
        }

        return try {
            runBlocking {
                withTimeout(10_000) {
                    readMutex.withLock {
                        withContext(Dispatchers.IO) {
                            buildActivityPayload(client)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            JSONObject()
                .put("batches", JSONArray())
                .put("message", error.message ?: "Не удалось прочитать данные из Health Connect.")
                .toString()
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

    private fun notifyPermissionState() {
        bridgeScope.launch {
            val client = healthClient
            val granted = client != null && safeGrantedPermissions(client).containsAll(permissions)
            onNotifyJavascript(
                permissionPayload(
                    available = client != null,
                    granted = granted,
                    pending = false,
                    message = if (granted) {
                        "Разрешения получены. Можно синхронизировать шаги и дистанцию."
                    } else {
                        "Разрешения не выданы полностью."
                    },
                )
            )
        }
    }

    private fun safeGrantedPermissions(client: HealthConnectClient): Set<String> {
        return try {
            runBlocking {
                withTimeout(5_000) {
                    permissionsMutex.withLock {
                        withContext(Dispatchers.IO) {
                            client.permissionController.getGrantedPermissions()
                        }
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
