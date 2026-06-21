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

        val stepsRead = readStepsTotal(client, windowFromUtc, windowToUtc, warnings)
        val stepsTotal = stepsRead.total

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
            buildActivityWindowsBatch(client, day.minusDays(1), now)?.let { batches.put(it) }
        }
        // Server-window aggregate is always requested by the backend-issued UTC window.
        // Steps and running distance are sent in one authoritative batch so activity_current_state
        // receives one combined snapshot instead of two competing partial updates. Finally, a sane idea.
        val hasAuthoritativeSteps = stepsTotal > 0L || stepsRead.recordsSeen > 0 || stepsRead.aggregateSeen
        val hasAuthoritativeRunDistance = distanceMeters > 0.0
        val aggregateRecords = JSONArray()
        if (hasAuthoritativeSteps) {
            aggregateRecords.put(
                JSONObject()
                    .put("activity_type", "walk")
                    .put("metric_type", "steps")
                    .put("value", stepsTotal)
                    .put("health_connect_records_seen", stepsRead.recordsSeen)
                    .put("health_connect_aggregate_seen", stepsRead.aggregateSeen)
                    .put("recorded_from", windowFromUtc.toString())
                    .put("recorded_to", windowToUtc.toString())
                    .put("window_from_utc", windowFromUtc.toString())
                    .put("window_to_utc", windowToUtc.toString())
                    .put("server_timezone", effectiveWindow.serverTimezone)
                    .put("client_generated_at", now.toString())
                    .put("device_time", now.toString())
            )
        } else {
            warnings.put("steps_empty_window_zero_suppressed")
        }

        if (hasAuthoritativeRunDistance) {
            val normalizedDistance = String.format(Locale.US, "%.2f", distanceMeters).toDouble()
            aggregateRecords.put(
                JSONObject()
                    .put("activity_type", "run")
                    .put("metric_type", "meters")
                    .put("value", normalizedDistance)
                    .put("recorded_from", windowFromUtc.toString())
                    .put("recorded_to", windowToUtc.toString())
                    .put("window_from_utc", windowFromUtc.toString())
                    .put("window_to_utc", windowToUtc.toString())
                    .put("server_timezone", effectiveWindow.serverTimezone)
                    .put("client_generated_at", now.toString())
                    .put("device_time", now.toString())
            )
        }

        if (aggregateRecords.length() > 0) {
            batches.put(
                JSONObject()
                    .put("kind", "health_connect_aggregate")
                    .put("external_batch_id", uniqueBatchId("android-aggregate-serverday-${effectiveWindow.serverDay}", now))
                    .put("generated_at", now.toString())
                    .put("device_time", now.toString())
                    .put("server_timezone", effectiveWindow.serverTimezone)
                    .put("window_from_utc", windowFromUtc.toString())
                    .put("window_to_utc", windowToUtc.toString())
                    .put("server_day_ends_at_utc", effectiveWindow.serverDayEndsAtUtc.toString())
                    .put("records", aggregateRecords)
            )
        }

        JSONObject()
            .put("batches", batches)
            .put("generated_at", now.toString())
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





    suspend fun buildDetailWindowsPayload(requests: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) return@withContext emptyPayload("Разрешения Health Connect не выданы.")

        val generatedAt = Instant.now()
        val records = JSONArray()
        for (requestIndex in 0 until requests.length()) {
            val request = requests.optJSONObject(requestIndex) ?: continue
            val activityType = request.optString("activity_type", "walk")
            val metric = request.optString("metric", "steps")
            val from = runCatching { Instant.parse(request.optString("requested_window_from")) }.getOrNull() ?: continue
            val to = runCatching { Instant.parse(request.optString("requested_window_to")) }.getOrNull() ?: continue
            if (!to.isAfter(from)) continue

            val bucketMinutes = max(1L, request.optLong("preferred_bucket_minutes", 1L))
            val buckets = detailBucketValues(client, metric, from, to, bucketMinutes)
            for (bucket in buckets) {
                if (bucket.value <= 0.0) continue
                records.put(
                    JSONObject()
                        .put("activity_type", activityType)
                        .put("metric_type", metric)
                        .put("value", String.format(Locale.US, "%.2f", bucket.value).toDouble())
                        .put("recorded_from", bucket.from.toString())
                        .put("recorded_to", bucket.to.toString())
                        .put("bucket_minutes", bucketMinutes)
                        .put("detail_request_id", request.optLong("id", 0L))
                        .put("source_hash", "health-connect-detail-${request.optLong("id", 0L)}-${bucket.from.epochSecond}-${bucket.to.epochSecond}-${String.format(Locale.US, "%.2f", bucket.value)}")
                )
            }
        }

        JSONObject()
            .put("kind", "activity_detail_windows")
            .put("external_batch_id", uniqueBatchId("android-detail-windows", generatedAt))
            .put("generated_at", generatedAt.toString())
            .put("device_time", generatedAt.toString())
            .put("server_timezone", "UTC")
            .put("window_size_minutes", 1)
            .put("records", records)
    }

    private data class DetailBucket(val from: Instant, val to: Instant, var value: Double = 0.0)

    private suspend fun detailBucketValues(
        client: HealthConnectClient,
        metric: String,
        from: Instant,
        to: Instant,
        bucketMinutes: Long,
    ): List<DetailBucket> {
        val buckets = mutableListOf<DetailBucket>()
        var cursor = from
        while (cursor.isBefore(to)) {
            val bucketEnd = cursor.plus(Duration.ofMinutes(bucketMinutes)).let { if (it.isAfter(to)) to else it }
            buckets.add(DetailBucket(cursor, bucketEnd))
            cursor = bucketEnd
        }

        when (metric) {
            "steps", "walk_steps" -> fillStepBuckets(client, from, to, buckets)
            "meters", "run_distance", "run_meters" -> fillDistanceBuckets(client, from, to, buckets)
            "run_duration", "run_workout_seconds", "workout" -> fillWorkoutDurationBuckets(client, from, to, buckets)
        }

        return buckets
    }

    private suspend fun fillStepBuckets(client: HealthConnectClient, from: Instant, to: Instant, buckets: List<DetailBucket>) {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
            )
        ).records

        for (record in records) {
            distributeValue(record.startTime, record.endTime, record.count.toDouble(), buckets)
        }
    }

    private suspend fun fillDistanceBuckets(client: HealthConnectClient, from: Instant, to: Instant, buckets: List<DetailBucket>) {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = DistanceRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
            )
        ).records

        for (record in records) {
            distributeValue(record.startTime, record.endTime, record.distance.inMeters, buckets)
        }
    }

    private suspend fun fillWorkoutDurationBuckets(client: HealthConnectClient, from: Instant, to: Instant, buckets: List<DetailBucket>) {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
            )
        ).records

        for (record in records) {
            distributeValue(record.startTime, record.endTime, Duration.between(record.startTime, record.endTime).seconds.toDouble(), buckets)
        }
    }

    private fun distributeValue(start: Instant, end: Instant, value: Double, buckets: List<DetailBucket>) {
        if (value <= 0.0 || !end.isAfter(start)) return
        val totalSeconds = max(1L, Duration.between(start, end).seconds).toDouble()
        for (bucket in buckets) {
            val overlapStart = if (start.isAfter(bucket.from)) start else bucket.from
            val overlapEnd = if (end.isBefore(bucket.to)) end else bucket.to
            if (!overlapEnd.isAfter(overlapStart)) continue
            val overlapSeconds = Duration.between(overlapStart, overlapEnd).seconds.toDouble()
            bucket.value += value * (overlapSeconds / totalSeconds)
        }
    }

    suspend fun buildDayCloseSnapshotPayload(
        activityDate: LocalDate,
        includeRunDistance: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) return@withContext emptyPayload("Разрешения Health Connect не выданы.")

        val generatedAt = Instant.now()
        val windowFromUtc = Instant.parse("${activityDate}T00:00:00Z")
        val windowToUtc = Instant.parse("${activityDate.plusDays(1)}T00:00:00Z").minusSeconds(1)
        if (!windowToUtc.isBefore(generatedAt)) {
            return@withContext emptyPayload("UTC-день ещё не закрыт.")
        }

        val warnings = JSONArray()
        val stepsRead = readStepsTotal(client, windowFromUtc, windowToUtc, warnings)
        val walkSteps = stepsRead.total
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

    private data class StepsReadResult(
        val total: Long,
        val aggregateSeen: Boolean,
        val recordsSeen: Int,
    )

    private suspend fun readStepsTotal(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
        warnings: JSONArray,
    ): StepsReadResult {
        val aggregateTotal = try {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            )[StepsRecord.COUNT_TOTAL]
        } catch (error: Throwable) {
            warnings.put("steps_aggregate: ${error.message ?: error.javaClass.simpleName}")
            null
        }

        if ((aggregateTotal ?: 0L) > 0L) {
            return StepsReadResult(aggregateTotal ?: 0L, aggregateSeen = true, recordsSeen = 0)
        }

        return try {
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            ).records
            val total = records.sumOf { record ->
                val overlapStart = maxInstant(from, record.startTime)
                val overlapEnd = minInstant(to, record.endTime)
                if (!overlapEnd.isAfter(overlapStart)) 0L else record.count
            }
            StepsReadResult(total, aggregateSeen = aggregateTotal != null, recordsSeen = records.size)
        } catch (error: Throwable) {
            warnings.put("steps_records: ${error.message ?: error.javaClass.simpleName}")
            StepsReadResult(0L, aggregateSeen = aggregateTotal != null, recordsSeen = 0)
        }
    }

    private suspend fun buildActivityWindowsBatch(
        client: HealthConnectClient,
        sourceDay: LocalDate,
        generatedAt: Instant,
    ): JSONObject? {
        val startOfDay = Instant.parse("${sourceDay}T00:00:00Z")
        val endOfDay = Instant.parse("${sourceDay.plusDays(1)}T00:00:00Z")
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
