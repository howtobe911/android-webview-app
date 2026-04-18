package com.second.risedie.challengeapp.bridge

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ChallengeAppBridge(
    private val activity: ComponentActivity,
    private val onLaunchPermissions: (Intent) -> Unit,
    private val onNotifyJavascript: (String) -> Unit,
) {
    private val context: Context = activity.applicationContext

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    private val healthClient: HealthConnectClient? by lazy {
        when (HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE_NAME)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(context, HEALTH_CONNECT_PACKAGE_NAME)
            else -> null
        }
    }

    @JavascriptInterface
    fun getBridgeInfo(): String {
        val sdkStatus = HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE_NAME)
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
        val sdkStatus = HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE_NAME)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return unavailablePayload(sdkStatus)
        }

        val client = healthClient ?: return unavailablePayload(sdkStatus)
        val grantedPermissions = safeGrantedPermissions(client)

        if (grantedPermissions.containsAll(permissions)) {
            return JSONObject()
                .put("available", true)
                .put("granted", true)
                .put("pending", false)
                .put("message", "Разрешения уже выданы.")
                .toString()
        }

        val intent = PermissionController.createRequestPermissionResultContract()
            .createIntent(activity, permissions)

        onLaunchPermissions(intent)

        return JSONObject()
            .put("available", true)
            .put("granted", false)
            .put("pending", true)
            .put("message", "Открыто окно Health Connect. Разреши доступ к шагам и дистанции.")
            .toString()
    }

    fun onPermissionsFlowFinished() {
        val client = healthClient
        val granted = client != null && safeGrantedPermissions(client).containsAll(permissions)

        val payload = JSONObject()
            .put("available", client != null)
            .put("granted", granted)
            .put("pending", false)
            .put("message", if (granted) "Разрешения получены." else "Разрешения не выданы полностью.")
            .toString()

        onNotifyJavascript(payload)
    }

    @JavascriptInterface
    fun getActivitySyncPayload(): String {
        val sdkStatus = HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE_NAME)
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
            val zoneId = ZoneId.systemDefault()
            val now = Instant.now()
            val startOfDay = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()

            val stepsTotal = runBlocking {
                val response = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                    )
                )
                response[StepsRecord.COUNT_TOTAL] ?: 0L
            }

            val distanceMeters = runBlocking {
                val response = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                    )
                )
                response[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
            }

            val batches = JSONArray()
            if (stepsTotal > 0L) {
                batches.put(
                    JSONObject()
                        .put("kind", "walk_steps")
                        .put("external_batch_id", "android-steps-${startOfDay.epochSecond}")
                        .put("records", JSONArray().put(
                            JSONObject()
                                .put("activity_type", "walk")
                                .put("metric_type", "steps")
                                .put("value", stepsTotal)
                                .put("recorded_from", startOfDay.toString())
                                .put("recorded_to", now.toString())
                                .put("source_hash", "android-steps-${startOfDay.epochSecond}-$stepsTotal")
                        ))
                )
            }
            if (distanceMeters > 0.0) {
                val normalizedDistance = String.format(Locale.US, "%.2f", distanceMeters).toDouble()
                batches.put(
                    JSONObject()
                        .put("kind", "run_distance")
                        .put("external_batch_id", "android-distance-${startOfDay.epochSecond}")
                        .put("records", JSONArray().put(
                            JSONObject()
                                .put("activity_type", "run")
                                .put("metric_type", "meters")
                                .put("value", normalizedDistance)
                                .put("recorded_from", startOfDay.toString())
                                .put("recorded_to", now.toString())
                                .put("source_hash", "android-distance-${startOfDay.epochSecond}-$normalizedDistance")
                        ))
                )
            }

            JSONObject()
                .put("batches", batches)
                .put("generated_at", now.toString())
                .toString()
        } catch (error: Throwable) {
            JSONObject()
                .put("batches", JSONArray())
                .put("message", error.message ?: "Не удалось прочитать данные из Health Connect.")
                .toString()
        }
    }

    @JavascriptInterface
    fun openHealthConnectSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$HEALTH_CONNECT_PACKAGE_NAME"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            JSONObject().put("opened", true).toString()
        } catch (_: Throwable) {
            JSONObject().put("opened", false).toString()
        }
    }

    private fun safeGrantedPermissions(client: HealthConnectClient): Set<String> {
        return try {
            runBlocking { client.permissionController.getGrantedPermissions() }
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun unavailablePayload(sdkStatus: Int): String {
        return JSONObject()
            .put("available", false)
            .put("granted", false)
            .put("pending", false)
            .put("message", unavailableMessage(sdkStatus))
            .toString()
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
