package com.second.risedie.challengeapp.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class HealthConnectRepository(private val context: Context) {
    private val appContext = context.applicationContext

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    fun sdkStatus(): Int = try {
        HealthConnectClient.getSdkStatus(appContext, HEALTH_CONNECT_PACKAGE_NAME)
    } catch (_: Throwable) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    fun clientOrNull(): HealthConnectClient? {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return null
        return try {
            HealthConnectClient.getOrCreate(appContext, HEALTH_CONNECT_PACKAGE_NAME)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun grantedPermissions(): Set<String> = withContext(Dispatchers.IO) {
        clientOrNull()?.permissionController?.getGrantedPermissions() ?: emptySet()
    }

    suspend fun hasPermissions(): Boolean = grantedPermissions().containsAll(permissions)

    suspend fun buildSyncPayload(): JSONObject = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) return@withContext emptyPayload("Разрешения Health Connect не выданы.")

        val zoneId = ZoneId.systemDefault()
        val timezone = zoneId.id
        val now = Instant.now()
        val day = now.atZone(zoneId).toLocalDate()
        val startOfDay = day.atStartOfDay(zoneId).toInstant()
        val warnings = JSONArray()

        val stepsTotal = try {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                )
            )[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (error: Throwable) {
            warnings.put("steps: ${error.message ?: error.javaClass.simpleName}")
            0L
        }

        val distanceMeters = try {
            calculateRunningDistanceMeters(client, startOfDay, now)
        } catch (error: Throwable) {
            warnings.put("distance: ${error.message ?: error.javaClass.simpleName}")
            0.0
        }

        val batches = JSONArray()
        if (stepsTotal > 0L) {
            batches.put(
                JSONObject()
                    .put("kind", "walk_steps")
                    .put("external_batch_id", "health-connect-steps-${startOfDay.epochSecond}")
                    .put("generated_at", now.toString())
                    .put("device_time", now.toString())
                    .put("source_day", day.toString())
                    .put("timezone", timezone)
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject()
                                .put("activity_type", "walk")
                                .put("metric_type", "steps")
                                .put("value", stepsTotal)
                                .put("recorded_from", startOfDay.toString())
                                .put("recorded_to", now.toString())
                                .put("client_generated_at", now.toString())
                                .put("device_time", now.toString())
                                .put("source_day", day.toString())
                                .put("client_timezone", timezone)
                                .put("source_hash", "health-connect-steps-${startOfDay.epochSecond}-$stepsTotal")
                        )
                    )
            )
        }

        if (distanceMeters > 0.0) {
            val normalizedDistance = String.format(Locale.US, "%.2f", distanceMeters).toDouble()
            batches.put(
                JSONObject()
                    .put("kind", "run_distance")
                    .put("external_batch_id", "health-connect-distance-${startOfDay.epochSecond}")
                    .put("generated_at", now.toString())
                    .put("device_time", now.toString())
                    .put("source_day", day.toString())
                    .put("timezone", timezone)
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject()
                                .put("activity_type", "run")
                                .put("metric_type", "meters")
                                .put("value", normalizedDistance)
                                .put("recorded_from", startOfDay.toString())
                                .put("recorded_to", now.toString())
                                .put("client_generated_at", now.toString())
                                .put("device_time", now.toString())
                                .put("source_day", day.toString())
                                .put("client_timezone", timezone)
                                .put("source_hash", "health-connect-distance-${startOfDay.epochSecond}-$normalizedDistance")
                        )
                    )
            )
        }

        JSONObject()
            .put("batches", batches)
            .put("generated_at", now.toString())
            .put("source_day", day.toString())
            .put("timezone", timezone)
            .put("device_time", now.toString())
            .put("preferred_source", "health_connect")
            .put("provider", providerPayload())
            .apply {
                if (warnings.length() > 0) put("warnings", warnings)
            }
    }

    private fun emptyPayload(message: String): JSONObject = JSONObject()
        .put("batches", JSONArray())
        .put("generated_at", Instant.now().toString())
        .put("preferred_source", "health_connect")
        .put("provider", providerPayload())
        .put("message", message)

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
            if (session.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_RUNNING) continue
            total += client.aggregate(
                AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                )
            )[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
        }
        return total
    }

    private fun providerPayload(): JSONObject = JSONObject()
        .put("type", "health_connect")
        .put("name", "Health Connect")
        .put("priority", 80)
        .put("confidence_score", 88)

    companion object {
        const val HEALTH_CONNECT_PACKAGE_NAME = "com.google.android.apps.healthdata"
    }
}
