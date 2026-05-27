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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.max

data class ServerSyncWindow(
    val serverDay: String,
    val serverTimezone: String,
    val windowFromUtc: Instant,
    val windowToUtc: Instant,
    val serverDayEndsAtUtc: Instant,
)

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

    /**
     * Health Connect providers may publish their newest aggregate a moment after the
     * WebView resumes. Google Fit appears fresh because it opens its own provider UI
     * and then writes before the user returns. For our app we actively re-read the
     * same current-day aggregate several times and use the highest monotonic value,
     * so the first foreground sync is not stuck on the previous cached total.
     */
    suspend fun buildFreshCurrentDaySyncPayload(
        serverWindow: ServerSyncWindow,
        includeRunDistance: Boolean = true,
        attempts: Int = 4,
        delayMillis: Long = 450L,
    ): JSONObject = buildFreshServerWindowSyncPayload(
        serverWindow = serverWindow,
        includeRunDistance = includeRunDistance,
        attempts = attempts,
        delayMillis = delayMillis,
    )

    suspend fun buildFreshServerWindowSyncPayload(
        serverWindow: ServerSyncWindow,
        includeRunDistance: Boolean = true,
        attempts: Int = 4,
        delayMillis: Long = 450L,
    ): JSONObject = withContext(Dispatchers.IO) {
        var bestPayload: JSONObject? = null
        var bestSteps = -1L
        var bestGeneratedAt = Instant.EPOCH

        repeat(attempts.coerceAtLeast(1)) { index ->
            val candidate = buildSyncPayload(
                serverWindow = serverWindow,
                includeClosedDayWindows = false,
                includeRunDistance = includeRunDistance,
            )
            val candidateSteps = currentDayStepsFromPayload(candidate)
            val generatedAt = candidate.optString("generated_at").takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.now()

            if (candidateSteps > bestSteps || (candidateSteps == bestSteps && generatedAt.isAfter(bestGeneratedAt))) {
                bestPayload = candidate
                bestSteps = candidateSteps
                bestGeneratedAt = generatedAt
            }

            if (index < attempts - 1) {
                delay(delayMillis)
            }
        }

        bestPayload ?: emptyPayload("Health Connect не вернул payload.")
    }

    private fun currentDayStepsFromPayload(payload: JSONObject): Long {
        val batches = payload.optJSONArray("batches") ?: return 0L
        var maxSteps = 0L
        for (batchIndex in 0 until batches.length()) {
            val batch = batches.optJSONObject(batchIndex) ?: continue
            if (batch.optString("kind") != "walk_steps") continue
            val records = batch.optJSONArray("records") ?: continue
            for (recordIndex in 0 until records.length()) {
                val record = records.optJSONObject(recordIndex) ?: continue
                if (record.optString("metric_type") == "steps") {
                    maxSteps = max(maxSteps, record.optLong("value", 0L))
                }
            }
        }
        return maxSteps
    }

    suspend fun buildSyncPayload(
        serverWindow: ServerSyncWindow,
        includeClosedDayWindows: Boolean = true,
        includeRunDistance: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) return@withContext emptyPayload("Разрешения Health Connect не выданы.")

        val now = Instant.now()
        val effectiveWindow = serverWindow
        val day = LocalDate.parse(effectiveWindow.serverDay)
        val windowFromUtc = effectiveWindow.windowFromUtc
        val windowToUtc = if (effectiveWindow.windowToUtc.isAfter(now)) now else effectiveWindow.windowToUtc
        val warnings = JSONArray()

        val stepsTotal = readStepsTotal(client, windowFromUtc, windowToUtc, warnings)

        val distanceMeters = if (includeRunDistance) {
            try {
                calculateRunningDistanceMeters(client, windowFromUtc, windowToUtc)
            } catch (error: Throwable) {
                warnings.put("distance: ${error.message ?: error.javaClass.simpleName}")
                0.0
            }
        } else {
            0.0
        }

        val batches = JSONArray()


        if (includeClosedDayWindows) {
            buildActivityWindowsBatch(client, day.minusDays(1), ZoneId.of(effectiveWindow.serverTimezone), now)?.let { batches.put(it) }
        }
        // Server-window aggregate: even 0 is a valid confirmed result for the current UTC day.
        // Do not skip the batch when Health Connect returns zero; backend must receive
        // and persist the authoritative activity_current_state for this server window.
        batches.put(
            JSONObject()
                    .put("kind", "walk_steps")
                    .put("external_batch_id", uniqueBatchId("android-steps-serverday-${effectiveWindow.serverDay}", now))
                    .put("generated_at", now.toString())
                    .put("device_time", now.toString())
                    .put("source_day", effectiveWindow.serverDay)
                    .put("timezone", "UTC")
                    .put("server_timezone", effectiveWindow.serverTimezone)
                    .put("window_from_utc", windowFromUtc.toString())
                    .put("window_to_utc", windowToUtc.toString())
                    .put("server_day_ends_at_utc", effectiveWindow.serverDayEndsAtUtc.toString())
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject()
                                .put("activity_type", "walk")
                                .put("metric_type", "steps")
                                .put("value", stepsTotal)
                                .put("recorded_from", windowFromUtc.toString())
                                .put("recorded_to", windowToUtc.toString())
                                .put("window_from_utc", windowFromUtc.toString())
                                .put("window_to_utc", windowToUtc.toString())
                                .put("server_timezone", effectiveWindow.serverTimezone)
                                .put("source_day_role", "metadata_only")
                                .put("client_generated_at", now.toString())
                                .put("device_time", now.toString())
                                .put("source_day", effectiveWindow.serverDay)
                                .put("client_timezone", "UTC")
                        )
                    )
        )

        if (distanceMeters > 0.0) {
            val normalizedDistance = String.format(Locale.US, "%.2f", distanceMeters).toDouble()
            batches.put(
                JSONObject()
                    .put("kind", "run_distance")
                    .put("external_batch_id", uniqueBatchId("android-distance-serverday-${effectiveWindow.serverDay}", now))
                    .put("generated_at", now.toString())
                    .put("device_time", now.toString())
                    .put("source_day", effectiveWindow.serverDay)
                    .put("timezone", "UTC")
                    .put("server_timezone", effectiveWindow.serverTimezone)
                    .put("window_from_utc", windowFromUtc.toString())
                    .put("window_to_utc", windowToUtc.toString())
                    .put("server_day_ends_at_utc", effectiveWindow.serverDayEndsAtUtc.toString())
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject()
                                .put("activity_type", "run")
                                .put("metric_type", "meters")
                                .put("value", normalizedDistance)
                                .put("recorded_from", windowFromUtc.toString())
                                .put("recorded_to", windowToUtc.toString())
                                .put("window_from_utc", windowFromUtc.toString())
                                .put("window_to_utc", windowToUtc.toString())
                                .put("server_timezone", effectiveWindow.serverTimezone)
                                .put("source_day_role", "metadata_only")
                                .put("client_generated_at", now.toString())
                                .put("device_time", now.toString())
                                .put("source_day", effectiveWindow.serverDay)
                                .put("client_timezone", "UTC")
                        )
                    )
            )
        }

        JSONObject()
            .put("batches", batches)
            .put("generated_at", now.toString())
            .put("source_day", effectiveWindow.serverDay)
            .put("timezone", "UTC")
            .put("server_timezone", effectiveWindow.serverTimezone)
            .put("window_from_utc", windowFromUtc.toString())
            .put("window_to_utc", windowToUtc.toString())
            .put("server_day_ends_at_utc", effectiveWindow.serverDayEndsAtUtc.toString())
            .put("device_time", now.toString())
            .put("preferred_source", "health_connect")
            .put("provider", providerPayload())
            .apply {
                if (warnings.length() > 0) put("warnings", warnings)
            }
    }



    suspend fun buildDayCloseSnapshotPayload(
        activityDate: LocalDate,
        includeRunDistance: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) return@withContext emptyPayload("Разрешения Health Connect не выданы.")

        val generatedAt = Instant.now()
        val windowFromUtc = activityDate.atStartOfDay(ZoneId.of("UTC")).toInstant()
        val windowToUtc = activityDate.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().minusSeconds(1)
        if (!windowToUtc.isBefore(generatedAt)) {
            return@withContext emptyPayload("UTC-день ещё не закрыт.")
        }

        val warnings = JSONArray()
        val walkSteps = readStepsTotal(client, windowFromUtc, windowToUtc, warnings)
        val runMeters = if (includeRunDistance) {
            try {
                calculateRunningDistanceMeters(client, windowFromUtc, windowToUtc)
            } catch (error: Throwable) {
                warnings.put("distance: ${error.message ?: error.javaClass.simpleName}")
                0.0
            }
        } else {
            0.0
        }
        val runSeconds = try {
            calculateRunningDurationSeconds(client, windowFromUtc, windowToUtc)
        } catch (error: Throwable) {
            warnings.put("duration: ${error.message ?: error.javaClass.simpleName}")
            0L
        }
        val normalizedRunMeters = String.format(Locale.US, "%.2f", runMeters).toDouble()
        val sourceHash = listOf(
            "day-close", activityDate.toString(), windowFromUtc.toString(), windowToUtc.toString(), walkSteps.toString(), normalizedRunMeters.toLong().toString(), runSeconds.toString()
        ).joinToString("|").hashCode().toString()

        JSONObject()
            .put("activity_date", activityDate.toString())
            .put("window_from_utc", windowFromUtc.toString())
            .put("window_to_utc", windowToUtc.toString())
            .put("walk_steps", walkSteps)
            .put("run_meters", normalizedRunMeters.toLong())
            .put("run_seconds", runSeconds)
            .put("source_hash", sourceHash)
            .put("external_batch_id", uniqueBatchId("android-day-close-${activityDate}", generatedAt))
            .put("generated_at", generatedAt.toString())
            .put("preferred_source", "health_connect")
            .put("source_of_truth", "health_connect_day_close_snapshot")
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

    private suspend fun readStepsTotal(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
        warnings: JSONArray,
    ): Long {
        val aggregateTotal = try {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            )[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (error: Throwable) {
            warnings.put("steps_aggregate: ${error.message ?: error.javaClass.simpleName}")
            0L
        }

        if (aggregateTotal > 0L) return aggregateTotal

        return try {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            ).records.sumOf { record ->
                val overlapStart = maxInstant(from, record.startTime)
                val overlapEnd = minInstant(to, record.endTime)
                if (!overlapEnd.isAfter(overlapStart)) 0L else record.count
            }
        } catch (error: Throwable) {
            warnings.put("steps_records: ${error.message ?: error.javaClass.simpleName}")
            0L
        }
    }

    private suspend fun buildActivityWindowsBatch(
        client: HealthConnectClient,
        sourceDay: LocalDate,
        zoneId: ZoneId,
        generatedAt: Instant,
    ): JSONObject? {
        val startOfDay = sourceDay.atStartOfDay(zoneId).toInstant()
        val endOfDay = sourceDay.plusDays(1).atStartOfDay(zoneId).toInstant()
        if (!endOfDay.isBefore(generatedAt)) return null

        val windows = JSONArray()
        var windowStart = startOfDay
        val runningSessions = try {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay),
                )
            ).records.filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING }
        } catch (_: Throwable) {
            emptyList()
        }

        while (windowStart.isBefore(endOfDay)) {
            val windowEnd = windowStart.plus(Duration.ofMinutes(15)).let { if (it.isAfter(endOfDay)) endOfDay else it }
            val steps = try {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd),
                    )
                )[StepsRecord.COUNT_TOTAL] ?: 0L
            } catch (_: Throwable) {
                0L
            }

            if (steps > 0L) {
                windows.put(
                    JSONObject()
                        .put("activity_type", "walk")
                        .put("metric_type", "steps_window")
                        .put("value", steps)
                        .put("recorded_from", windowStart.toString())
                        .put("recorded_to", windowEnd.toString())
                        .put("source_day", sourceDay.toString())
                        .put("source_hash", "health-connect-steps-window-${sourceDay}-${windowStart.epochSecond}-$steps")
                )
            }

            var runDistanceMeters = 0.0
            var runDurationSeconds = 0L
            for (session in runningSessions) {
                val from = maxInstant(windowStart, session.startTime)
                val to = minInstant(windowEnd, session.endTime)
                if (!to.isAfter(from)) continue
                runDurationSeconds += Duration.between(from, to).seconds
                runDistanceMeters += try {
                    client.aggregate(
                        AggregateRequest(
                            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(from, to),
                        )
                    )[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
                } catch (_: Throwable) {
                    0.0
                }
            }

            if (runDistanceMeters > 0.0) {
                val normalized = String.format(Locale.US, "%.2f", runDistanceMeters).toDouble()
                windows.put(
                    JSONObject()
                        .put("activity_type", "run")
                        .put("metric_type", "run_distance_window")
                        .put("value", normalized)
                        .put("recorded_from", windowStart.toString())
                        .put("recorded_to", windowEnd.toString())
                        .put("source_day", sourceDay.toString())
                        .put("source_hash", "health-connect-run-distance-window-${sourceDay}-${windowStart.epochSecond}-$normalized")
                )
            }

            if (runDurationSeconds > 0L) {
                windows.put(
                    JSONObject()
                        .put("activity_type", "run")
                        .put("metric_type", "run_duration_window")
                        .put("value", runDurationSeconds)
                        .put("recorded_from", windowStart.toString())
                        .put("recorded_to", windowEnd.toString())
                        .put("source_day", sourceDay.toString())
                        .put("source_hash", "health-connect-run-duration-window-${sourceDay}-${windowStart.epochSecond}-$runDurationSeconds")
                )
            }

            windowStart = windowEnd
        }

        if (windows.length() == 0) return null
        return JSONObject()
            .put("kind", "activity_windows")
            .put("external_batch_id", "health-connect-activity-windows-${sourceDay}")
            .put("generated_at", generatedAt.toString())
            .put("device_time", generatedAt.toString())
            .put("source_day", sourceDay.toString())
            .put("timezone", zoneId.id)
            .put("window_size_minutes", 15)
            .put("records", windows)
    }

    private fun minInstant(a: Instant, b: Instant): Instant = if (a.isBefore(b)) a else b

    private fun maxInstant(a: Instant, b: Instant): Instant = if (a.isAfter(b)) a else b

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

    private fun uniqueBatchId(prefix: String, now: Instant): String = "$prefix-${now.toEpochMilli()}-${UUID.randomUUID()}"

    companion object {
        const val HEALTH_CONNECT_PACKAGE_NAME = "com.google.android.apps.healthdata"
    }

    private suspend fun calculateRunningDurationSeconds(
        client: HealthConnectClient,
        fromUtc: Instant,
        toUtc: Instant,
    ): Long {
        if (!toUtc.isAfter(fromUtc)) return 0L

        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(fromUtc, toUtc),
            )
        ).records
            .asSequence()
            .filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING }
            .mapNotNull { session ->
                val clippedFrom = maxInstant(fromUtc, session.startTime)
                val clippedTo = minInstant(toUtc, session.endTime)
                if (clippedTo.isAfter(clippedFrom)) Pair(clippedFrom, clippedTo) else null
            }
            .sortedBy { it.first }
            .toList()

        if (sessions.isEmpty()) return 0L

        var totalSeconds = 0L
        var mergedStart = sessions.first().first
        var mergedEnd = sessions.first().second

        for ((start, end) in sessions.drop(1)) {
            if (!start.isAfter(mergedEnd)) {
                if (end.isAfter(mergedEnd)) mergedEnd = end
            } else {
                totalSeconds += Duration.between(mergedStart, mergedEnd).seconds
                mergedStart = start
                mergedEnd = end
            }
        }

        totalSeconds += Duration.between(mergedStart, mergedEnd).seconds
        return max(0L, totalSeconds)
    }

}
