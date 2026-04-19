package com.second.risedie.challengeapp.bridge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

class ChallengeAppBridge(
    private val activity: ComponentActivity,
    private val onLaunchPermissions: (Intent) -> Unit,
    private val onNotifyJavascript: (String) -> Unit,
) {
    companion object {
        private const val TAG = "GrafitActivitySync"
    }

    private val context: Context = activity.applicationContext
    private val healthClient: HealthConnectClient? by lazy {
        val providerStatus = HealthConnectClient.getSdkStatus(context)
        if (providerStatus == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }

    @JavascriptInterface
    fun getBridgeInfo(): String {
        return JSONObject()
            .put("bridge", "ChallengeAppBridge")
            .put("platform", "android")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("available", healthClient != null)
            .toString()
    }

    @JavascriptInterface
    fun requestActivityPermissions(): String {
        logDebug("requestActivityPermissions(): called; sdkAvailable=${healthClient != null}")
        val client = healthClient ?: return JSONObject()
            .put("available", false)
            .put("granted", false)
            .put("pending", false)
            .put("message", "Health Connect недоступен на этом устройстве.")
            .toString()

        val granted = runBlocking {
            client.permissionController.getGrantedPermissions()
        }
        logDebug("requestActivityPermissions(): grantedPermissions=$granted")

        if (granted.containsAll(permissions)) {
            return JSONObject()
                .put("available", true)
                .put("granted", true)
                .put("pending", false)
                .put("message", "Разрешения уже выданы.")
                .toString()
        }

        val intent = PermissionController.createRequestPermissionResultContract()
            .createIntent(activity, permissions)

        logDebug("requestActivityPermissions(): launching permission intent for permissions=$permissions")
        onLaunchPermissions(intent)

        return JSONObject()
            .put("available", true)
            .put("granted", false)
            .put("pending", true)
            .put("message", "Открыто окно Health Connect. Разреши доступ к шагам и дистанции.")
            .toString()
    }

    fun onPermissionsFlowFinished() {
        logDebug("onPermissionsFlowFinished(): called")
        val client = healthClient
        val granted = if (client == null) false else runBlocking {
            client.permissionController.getGrantedPermissions().containsAll(permissions)
        }

        logDebug("onPermissionsFlowFinished(): available=${client != null}, granted=$granted")

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
        logDebug("getActivitySyncPayload(): called")

        return try {
            val client = healthClient ?: run {
                val payload = JSONObject()
                    .put("batches", JSONArray())
                    .put("message", "Health Connect недоступен.")
                    .toString()
                logDebug("getActivitySyncPayload(): health client unavailable; payload=$payload")
                return payload
            }

            val zoneId = ZoneId.systemDefault()
            val now = Instant.now()
            val startOfDay = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()

            val granted = runBlocking {
                client.permissionController.getGrantedPermissions()
            }
            logDebug("getActivitySyncPayload(): grantedPermissions=$granted")

            if (!granted.containsAll(permissions)) {
                val payload = JSONObject()
                    .put("batches", JSONArray())
                    .put("message", "Разрешения на шаги и дистанцию ещё не выданы.")
                    .toString()
                logDebug("getActivitySyncPayload(): permissions missing; payload=$payload")
                return payload
            }

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
                val distance = response[DistanceRecord.DISTANCE_TOTAL]
                distance?.inMeters ?: 0.0
            }

            logDebug(
                "getActivitySyncPayload(): window=${startOfDay}..${now}, stepsTotal=$stepsTotal, distanceMeters=$distanceMeters"
            )

            val batches = JSONArray()

            if (stepsTotal > 0L) {
                val records = JSONArray().put(
                    JSONObject()
                        .put("activity_type", "walk")
                        .put("metric_type", "steps")
                        .put("value", stepsTotal)
                        .put("recorded_from", startOfDay.toString())
                        .put("recorded_to", now.toString())
                        .put("source_hash", "android-steps-" + startOfDay.toString() + "-" + stepsTotal)
                )

                batches.put(
                    JSONObject()
                        .put("kind", "walk_steps")
                        .put("external_batch_id", "android-steps-" + now.toEpochMilli())
                        .put("records", records)
                )
            }

            if (distanceMeters > 0.0) {
                val records = JSONArray().put(
                    JSONObject()
                        .put("activity_type", "run")
                        .put("metric_type", "meters")
                        .put("value", distanceMeters)
                        .put("recorded_from", startOfDay.toString())
                        .put("recorded_to", now.toString())
                        .put("source_hash", "android-distance-" + startOfDay.toString() + "-" + distanceMeters)
                )

                batches.put(
                    JSONObject()
                        .put("kind", "run_distance")
                        .put("external_batch_id", "android-distance-" + now.toEpochMilli())
                        .put("records", records)
                )
            }

            val payload = JSONObject()
                .put("batches", batches)
                .put("generated_at", now.toString())
                .toString()

            logDebug("getActivitySyncPayload(): payload=$payload")
            payload
        } catch (throwable: Throwable) {
            logError("getActivitySyncPayload(): failed", throwable)
            JSONObject()
                .put("batches", JSONArray())
                .put("message", throwable.message ?: "Ошибка чтения активности с устройства.")
                .put("error", throwable.javaClass.simpleName)
                .toString()
        }
    }
}