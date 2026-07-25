package com.second.risedie.challengeapp.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.second.risedie.challengeapp.sync.HealthSyncLogger
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

data class ServerSyncWindow(
    val serverDay: String,
    val serverTimezone: String,
    val windowFromUtc: Instant,
    val windowToUtc: Instant,
    val serverDayEndsAtUtc: Instant,
)

class HealthConnectRepository(
    private val context: Context,
    private val syncLogger: HealthSyncLogger? = null,
) {
    private val appContext = context.applicationContext

    val dataPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    val permissions: Set<String>
        get() = dataPermissions

    val backgroundReadPermission: String
        get() = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun isBackgroundReadAvailable(): Boolean {
        val client = clientOrNull() ?: return false
        return try {
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (_: Throwable) {
            false
        }
    }

    fun sdkStatus(): Int = try {
        HealthConnectClient.getSdkStatus(appContext, HEALTH_CONNECT_PACKAGE_NAME)
    } catch (_: Exception) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    fun clientOrNull(): HealthConnectClient? {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return null
        return try {
            HealthConnectClient.getOrCreate(appContext, HEALTH_CONNECT_PACKAGE_NAME)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun grantedPermissions(): Set<String> = withContext(Dispatchers.IO) {
        clientOrNull()?.permissionController?.getGrantedPermissions() ?: emptySet()
    }

    suspend fun hasPermissions(): Boolean = grantedPermissions().containsAll(dataPermissions)

    /**
     * Compatibility entry point. Steps and running distance are always read and sent together.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun buildFreshCurrentDaySyncPayload(
        serverWindow: ServerSyncWindow,
        includeRunDistance: Boolean = true,
        attempts: Int = 4,
        delayMillis: Long = 450L,
        traceSessionId: String? = null,
    ): JSONObject = buildFreshServerWindowSyncPayload(
        serverWindow = serverWindow,
        includeRunDistance = includeRunDistance,
        attempts = attempts,
        delayMillis = delayMillis,
        traceSessionId = traceSessionId,
    )

    /**
     * Compatibility entry point. includeRunDistance is intentionally ignored because the
     * server-window snapshot is atomic: steps and running distance always travel together.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun buildFreshServerWindowSyncPayload(
        serverWindow: ServerSyncWindow,
        includeRunDistance: Boolean = true,
        attempts: Int = 4,
        delayMillis: Long = 450L,
        traceSessionId: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val client = clientOrNull()
            ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) {
            return@withContext emptyPayload("Разрешения Health Connect не выданы.")
        }

        var bestStepsSnapshot: SyncSnapshot? = null
        var bestRunSnapshot: SyncSnapshot? = null
        var previousCompleteSnapshot: SyncSnapshot? = null
        var stableCompleteReads = 0
        val allWarnings = linkedSetOf<String>()
        val attemptsTotal = attempts.coerceAtLeast(1)

        for (index in 0 until attemptsTotal) {
            val attemptStarted = System.nanoTime()
            traceSessionId?.let { sessionId ->
                syncLogger?.info(sessionId, TRACE_COMPONENT, "read_attempt_started", JSONObject()
                    .put("attempt", index + 1)
                    .put("attempts_total", attemptsTotal))
            }
            val candidate = readSyncSnapshot(client, serverWindow)
            allWarnings.addAll(candidate.diagnostics.warnings)
            traceSessionId?.let { sessionId ->
                syncLogger?.info(sessionId, TRACE_COMPONENT, "read_attempt_completed", JSONObject()
                    .put("attempt", index + 1)
                    .put("steps", candidate.steps)
                    .put("run_meters", candidate.runMeters)
                    .put("steps_aggregate_seen", candidate.diagnostics.stepsAggregateSeen)
                    .put("steps_records_seen", candidate.diagnostics.stepRecordsSeen)
                    .put("steps_read_success", candidate.diagnostics.stepsReadSuccess)
                    .put("run_read_success", candidate.diagnostics.runReadSuccess)
                    .put("warnings_count", candidate.diagnostics.warnings.size)
                    .put("effective_window_to_utc", candidate.effectiveWindowToUtc.toString())
                    .put("duration_ms", (System.nanoTime() - attemptStarted) / 1_000_000))
            }

            if (
                candidate.diagnostics.stepsReadSuccess && (
                bestStepsSnapshot == null ||
                candidate.steps > bestStepsSnapshot!!.steps ||
                (
                    candidate.steps == bestStepsSnapshot!!.steps &&
                        candidate.generatedAt.isAfter(bestStepsSnapshot!!.generatedAt)
                    )
                )
            ) {
                bestStepsSnapshot = candidate
            }

            if (
                candidate.diagnostics.runReadSuccess && (
                bestRunSnapshot == null ||
                candidate.runMeters > bestRunSnapshot!!.runMeters ||
                (
                    candidate.runMeters == bestRunSnapshot!!.runMeters &&
                        candidate.generatedAt.isAfter(bestRunSnapshot!!.generatedAt)
                    )
                )
            ) {
                bestRunSnapshot = candidate
            }

            if (candidate.diagnostics.readComplete) {
                val previous = previousCompleteSnapshot
                stableCompleteReads = if (
                    previous != null &&
                    previous.steps == candidate.steps &&
                    previous.runMeters == candidate.runMeters
                ) stableCompleteReads + 1 else 1
                previousCompleteSnapshot = candidate

                if (stableCompleteReads >= STABLE_COMPLETE_READS_REQUIRED) {
                    traceSessionId?.let { sessionId ->
                        syncLogger?.info(sessionId, TRACE_COMPONENT, "read_attempts_stopped_stable", JSONObject()
                            .put("attempts_used", index + 1)
                            .put("attempts_total", attemptsTotal)
                            .put("stable_reads", stableCompleteReads)
                            .put("steps", candidate.steps)
                            .put("run_meters", candidate.runMeters))
                    }
                    break
                }
            } else {
                stableCompleteReads = 0
                previousCompleteSnapshot = null
            }

            if (index < attemptsTotal - 1) {
                delay(delayMillis.coerceAtLeast(0L))
            }
        }

        val stepsSnapshot = bestStepsSnapshot
            ?: return@withContext emptyPayload(
                "Health Connect не вернул достоверные данные шагов."
            ).apply {
                if (allWarnings.isNotEmpty()) put("warnings", allWarnings.toList().toJsonArray())
            }
        val runSnapshot = bestRunSnapshot
            ?: return@withContext emptyPayload(
                "Health Connect не вернул достоверные данные беговой дистанции."
            ).apply {
                if (allWarnings.isNotEmpty()) put("warnings", allWarnings.toList().toJsonArray())
            }
        val generatedAt = maxInstant(stepsSnapshot.generatedAt, runSnapshot.generatedAt)
        traceSessionId?.let { sessionId ->
            syncLogger?.info(sessionId, TRACE_COMPONENT, "best_snapshot_selected", JSONObject()
                .put("steps", stepsSnapshot.steps)
                .put("run_meters", runSnapshot.runMeters)
                .put("steps_generated_at", stepsSnapshot.generatedAt.toString())
                .put("run_generated_at", runSnapshot.generatedAt.toString())
                .put("warnings_count", allWarnings.size))
            logStepsFreshnessDiagnostics(client, serverWindow, sessionId)
        }

        val combined = SyncSnapshot(
            steps = stepsSnapshot.steps,
            runMeters = runSnapshot.runMeters,
            window = serverWindow,
            effectiveWindowToUtc = maxInstant(
                stepsSnapshot.effectiveWindowToUtc,
                runSnapshot.effectiveWindowToUtc,
            ),
            generatedAt = generatedAt,
            diagnostics = SyncDiagnostics(
                stepsAggregateSeen = stepsSnapshot.diagnostics.stepsAggregateSeen,
                stepRecordsSeen = stepsSnapshot.diagnostics.stepRecordsSeen,
                stepsReadSuccess = true,
                runReadSuccess = true,
                warnings = allWarnings.toList(),
            ),
        )

        buildSyncPayloadJson(
            snapshot = combined,
            closedDayBatch = null,
        )
    }

    /**
     * Public signature is preserved for existing callers. includeRunDistance remains only for
     * source and binary compatibility; both metrics are always included.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun buildSyncPayload(
        serverWindow: ServerSyncWindow,
        includeClosedDayWindows: Boolean = true,
        includeRunDistance: Boolean = true,
        traceSessionId: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        traceSessionId?.let { sessionId ->
            syncLogger?.info(
                sessionId,
                TRACE_COMPONENT,
                "server_window_read_started",
                JSONObject()
                    .put("server_day", serverWindow.serverDay)
                    .put("window_from_utc", serverWindow.windowFromUtc.toString())
                    .put("window_to_utc", serverWindow.windowToUtc.toString()),
            )
        }
        val client = clientOrNull()
            ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) {
            return@withContext emptyPayload("Разрешения Health Connect не выданы.")
        }

        val snapshot = readSyncSnapshot(client, serverWindow)
        if (!snapshot.diagnostics.readComplete) {
            return@withContext incompleteSnapshotPayload(snapshot)
        }

        val closedDayBatch = if (includeClosedDayWindows) {
            val day = runCatching { LocalDate.parse(serverWindow.serverDay) }.getOrNull()
            day?.let { buildActivityWindowsBatch(client, it.minusDays(1), snapshot.generatedAt) }
        } else {
            null
        }

        buildSyncPayloadJson(snapshot, closedDayBatch)
    }

    suspend fun buildDetailWindowsPayload(
        requests: JSONArray,
        traceSessionId: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        traceSessionId?.let { syncLogger?.info(it, TRACE_COMPONENT, "detail_read_started", JSONObject().put("requests_count", requests.length())) }
        val client = clientOrNull()
            ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) {
            return@withContext emptyPayload("Разрешения Health Connect не выданы.")
        }

        val generatedAt = Instant.now()
        val records = JSONArray()

        for (requestIndex in 0 until requests.length()) {
            val request = requests.optJSONObject(requestIndex) ?: continue
            val activityType = request.optString("activity_type", "walk")
            val metric = request.optString("metric", "steps")
            val from = parseInstantOrNull(request.optString("requested_window_from")) ?: continue
            val to = parseInstantOrNull(request.optString("requested_window_to")) ?: continue
            if (!to.isAfter(from)) continue

            val bucketMinutes = max(1L, request.optLong("preferred_bucket_minutes", 1L))
            val buckets = detailBucketValues(client, metric, from, to, bucketMinutes)

            for (bucket in buckets) {
                if (bucket.value <= 0.0) continue
                val normalizedValue = normalizeTwoDecimals(bucket.value)
                records.put(
                    JSONObject()
                        .put("activity_type", activityType)
                        .put("metric_type", metric)
                        .put("value", normalizedValue)
                        .put("recorded_from", bucket.from.toString())
                        .put("recorded_to", bucket.to.toString())
                        .put("bucket_minutes", bucketMinutes)
                        .put("detail_request_id", request.optLong("id", 0L))
                        .put(
                            "source_hash",
                            sha256(
                                listOf(
                                    "health-connect-detail",
                                    request.optLong("id", 0L).toString(),
                                    bucket.from.toString(),
                                    bucket.to.toString(),
                                    formatMeters(normalizedValue),
                                ).joinToString("|")
                            )
                        )
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
            .also { traceSessionId?.let { sessionId -> syncLogger?.info(sessionId, TRACE_COMPONENT, "detail_read_completed", JSONObject().put("records_count", records.length())) } }
    }

    /**
     * Public signature is preserved. Running distance is always included.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun buildDayCloseSnapshotPayload(
        activityDate: LocalDate,
        includeRunDistance: Boolean = true,
        traceSessionId: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        traceSessionId?.let { syncLogger?.info(it, TRACE_COMPONENT, "day_close_read_started", JSONObject().put("activity_date", activityDate.toString())) }
        val client = clientOrNull()
            ?: return@withContext emptyPayload("Health Connect недоступен.")
        if (!hasPermissions()) {
            return@withContext emptyPayload("Разрешения Health Connect не выданы.")
        }

        val generatedAt = Instant.now()
        val windowFromUtc = Instant.parse("${activityDate}T00:00:00Z")
        val windowToUtc = Instant.parse("${activityDate.plusDays(1)}T00:00:00Z").minusSeconds(1)

        if (!windowToUtc.isBefore(generatedAt)) {
            return@withContext emptyPayload("UTC-день ещё не закрыт.")
        }

        val warnings = mutableListOf<String>()
        val stepsRead = readStepsTotal(client, windowFromUtc, windowToUtc, warnings)
        val runningData = readRunningActivityData(client, windowFromUtc, windowToUtc, warnings)

        if (!stepsRead.success || !runningData.readSuccess) {
            return@withContext emptyPayload(
                "Не удалось получить полный снимок Health Connect за закрытый UTC-день."
            ).apply {
                if (warnings.isNotEmpty()) put("warnings", warnings.toJsonArray())
            }
        }

        val walkSteps = stepsRead.total
        val normalizedRunMeters = normalizeTwoDecimals(runningData.totalMeters)
        val runSeconds = runningData.totalDurationSeconds
        val sourceHash = sha256(
            listOf(
                "day-close",
                activityDate.toString(),
                windowFromUtc.toString(),
                windowToUtc.toString(),
                walkSteps.toString(),
                formatMeters(normalizedRunMeters),
                runSeconds.toString(),
            ).joinToString("|")
        )

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
                if (warnings.isNotEmpty()) put("warnings", warnings.toJsonArray())
            }
            .also { traceSessionId?.let { sessionId -> syncLogger?.info(sessionId, TRACE_COMPONENT, "day_close_read_completed", JSONObject()
                .put("activity_date", activityDate.toString())
                .put("walk_steps", walkSteps)
                .put("run_meters", normalizedRunMeters)
                .put("warnings_count", warnings.size)) } }
    }

    private data class SyncSnapshot(
        val steps: Long,
        val runMeters: Double,
        val window: ServerSyncWindow,
        val effectiveWindowToUtc: Instant,
        val generatedAt: Instant,
        val diagnostics: SyncDiagnostics,
    )

    private data class SyncDiagnostics(
        val stepsAggregateSeen: Boolean,
        val stepRecordsSeen: Int,
        val stepsReadSuccess: Boolean,
        val runReadSuccess: Boolean,
        val warnings: List<String>,
    ) {
        val readComplete: Boolean
            get() = stepsReadSuccess && runReadSuccess
    }

    private data class StepsReadResult(
        val total: Long,
        val aggregateSeen: Boolean,
        val recordsSeen: Int,
        val success: Boolean,
    )

    private data class TimeInterval(
        val from: Instant,
        val to: Instant,
    )

    private data class RunningDistanceSegment(
        val from: Instant,
        val to: Instant,
        val meters: Double,
    )

    private data class RunningActivityData(
        val originalSessions: List<ExerciseSessionRecord>,
        val coverageIntervals: List<TimeInterval>,
        val distanceSegments: List<RunningDistanceSegment>,
        val sessionsReadSuccess: Boolean,
        val distanceReadSuccess: Boolean,
    ) {
        val readSuccess: Boolean
            get() = sessionsReadSuccess && distanceReadSuccess

        val totalMeters: Double
            get() = distanceSegments.sumOf { it.meters }

        // Coverage is used only for aggregate duration/deduplication. Original sessions remain intact.
        val totalDurationSeconds: Long
            get() = coverageIntervals.sumOf { Duration.between(it.from, it.to).seconds }
    }

    private data class DetailBucket(
        val from: Instant,
        val to: Instant,
        var value: Double = 0.0,
    )

    private suspend fun readSyncSnapshot(
        client: HealthConnectClient,
        serverWindow: ServerSyncWindow,
    ): SyncSnapshot {
        val generatedAt = Instant.now()
        val effectiveTo = minInstant(serverWindow.windowToUtc, generatedAt)
        val warnings = mutableListOf<String>()

        if (!effectiveTo.isAfter(serverWindow.windowFromUtc)) {
            return SyncSnapshot(
                steps = 0L,
                runMeters = 0.0,
                window = serverWindow,
                effectiveWindowToUtc = effectiveTo,
                generatedAt = generatedAt,
                diagnostics = SyncDiagnostics(
                    stepsAggregateSeen = false,
                    stepRecordsSeen = 0,
                    stepsReadSuccess = false,
                    runReadSuccess = false,
                    warnings = listOf("window: Некорректное или пустое временное окно."),
                ),
            )
        }

        val stepsRead = readStepsTotal(
            client = client,
            from = serverWindow.windowFromUtc,
            to = effectiveTo,
            warnings = warnings,
        )

        val runningData = readRunningActivityData(
            client = client,
            from = serverWindow.windowFromUtc,
            to = effectiveTo,
            warnings = warnings,
        )

        return SyncSnapshot(
            steps = stepsRead.total,
            runMeters = normalizeTwoDecimals(runningData.totalMeters),
            window = serverWindow,
            effectiveWindowToUtc = effectiveTo,
            generatedAt = generatedAt,
            diagnostics = SyncDiagnostics(
                stepsAggregateSeen = stepsRead.aggregateSeen,
                stepRecordsSeen = stepsRead.recordsSeen,
                stepsReadSuccess = stepsRead.success,
                runReadSuccess = runningData.readSuccess,
                warnings = warnings,
            ),
        )
    }

    private fun incompleteSnapshotPayload(snapshot: SyncSnapshot): JSONObject =
        emptyPayload("Не удалось получить полный атомарный снимок Health Connect.").apply {
            put("server_timezone", snapshot.window.serverTimezone)
            put("window_from_utc", snapshot.window.windowFromUtc.toString())
            put("window_to_utc", snapshot.effectiveWindowToUtc.toString())
            put("server_day_ends_at_utc", snapshot.window.serverDayEndsAtUtc.toString())
            put("device_time", snapshot.generatedAt.toString())
            if (snapshot.diagnostics.warnings.isNotEmpty()) {
                put("warnings", snapshot.diagnostics.warnings.toJsonArray())
            }
        }

    private fun buildSyncPayloadJson(
        snapshot: SyncSnapshot,
        closedDayBatch: JSONObject?,
    ): JSONObject {
        val window = snapshot.window
        val now = snapshot.generatedAt
        val windowFromUtc = window.windowFromUtc
        val windowToUtc = snapshot.effectiveWindowToUtc
        val normalizedDistance = normalizeTwoDecimals(snapshot.runMeters)

        val aggregateRecords = JSONArray()
            .put(
                JSONObject()
                    .put("activity_type", "walk")
                    .put("metric_type", "steps")
                    .put("value", snapshot.steps)
                    .put("health_connect_records_seen", snapshot.diagnostics.stepRecordsSeen)
                    .put("health_connect_aggregate_seen", snapshot.diagnostics.stepsAggregateSeen)
                    .put("recorded_from", windowFromUtc.toString())
                    .put("recorded_to", windowToUtc.toString())
                    .put("window_from_utc", windowFromUtc.toString())
                    .put("window_to_utc", windowToUtc.toString())
                    .put("server_timezone", window.serverTimezone)
                    .put("client_generated_at", now.toString())
                    .put("device_time", now.toString())
            )
            .put(
                JSONObject()
                    .put("activity_type", "run")
                    .put("metric_type", "meters")
                    .put("value", normalizedDistance)
                    .put("recorded_from", windowFromUtc.toString())
                    .put("recorded_to", windowToUtc.toString())
                    .put("window_from_utc", windowFromUtc.toString())
                    .put("window_to_utc", windowToUtc.toString())
                    .put("server_timezone", window.serverTimezone)
                    .put("client_generated_at", now.toString())
                    .put("device_time", now.toString())
            )

        val aggregateSourceHash = sha256(
            listOf(
                "health-connect-aggregate",
                window.serverDay,
                window.serverTimezone,
                windowFromUtc.toString(),
                windowToUtc.toString(),
                snapshot.steps.toString(),
                formatMeters(normalizedDistance),
            ).joinToString("|")
        )

        val aggregateBatch = JSONObject()
            .put("kind", "health_connect_aggregate")
            .put(
                "external_batch_id",
                uniqueBatchId("android-aggregate-serverday-${window.serverDay}", now),
            )
            .put("source_hash", aggregateSourceHash)
            .put("generated_at", now.toString())
            .put("device_time", now.toString())
            .put("server_timezone", window.serverTimezone)
            .put("window_from_utc", windowFromUtc.toString())
            .put("window_to_utc", windowToUtc.toString())
            .put("server_day_ends_at_utc", window.serverDayEndsAtUtc.toString())
            .put("records", aggregateRecords)

        val batches = JSONArray()
        closedDayBatch?.let { batches.put(it) }
        batches.put(aggregateBatch)

        return JSONObject()
            .put("batches", batches)
            .put("generated_at", now.toString())
            .put("server_timezone", window.serverTimezone)
            .put("window_from_utc", windowFromUtc.toString())
            .put("window_to_utc", windowToUtc.toString())
            .put("server_day_ends_at_utc", window.serverDayEndsAtUtc.toString())
            .put("device_time", now.toString())
            .put("preferred_source", "health_connect")
            .put("provider", providerPayload())
            .apply {
                if (snapshot.diagnostics.warnings.isNotEmpty()) {
                    put("warnings", snapshot.diagnostics.warnings.toJsonArray())
                }
            }
    }

    private suspend fun logStepsFreshnessDiagnostics(
        client: HealthConnectClient,
        serverWindow: ServerSyncWindow,
        sessionId: String,
    ) {
        val started = System.nanoTime()
        runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        serverWindow.windowFromUtc,
                        minInstant(serverWindow.windowToUtc, Instant.now()),
                    ),
                )
            ).records
        }.onSuccess { records ->
            val origins = records.map { it.metadata.dataOrigin.packageName }.filter { it.isNotBlank() }.distinct().sorted()
            val latestRecordAt = records.maxOfOrNull { it.endTime }
            val totalRaw = records.sumOf { it.count }
            syncLogger?.info(sessionId, TRACE_COMPONENT, "steps_freshness_diagnostics", JSONObject()
                .put("records_count", records.size)
                .put("records_raw_sum", totalRaw)
                .put("latest_record_at", latestRecordAt?.toString() ?: JSONObject.NULL)
                .put("record_age_seconds", latestRecordAt?.let { max(0L, Duration.between(it, Instant.now()).seconds) } ?: JSONObject.NULL)
                .put("origins", JSONArray(origins))
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000))
        }.onFailure { error ->
            if (error is CancellationException) throw error
            syncLogger?.warn(sessionId, TRACE_COMPONENT, "steps_freshness_diagnostics_failed",
                JSONObject().put("duration_ms", (System.nanoTime() - started) / 1_000_000), error)
        }
    }

    private suspend fun readStepsTotal(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
        warnings: MutableList<String>,
    ): StepsReadResult {
        val aggregateTotal = try {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            )[StepsRecord.COUNT_TOTAL]
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            warnings.add("steps_aggregate: ${error.message ?: error.javaClass.simpleName}")
            null
        }

        if (aggregateTotal != null) {
            return StepsReadResult(
                total = max(0L, aggregateTotal),
                aggregateSeen = true,
                recordsSeen = 0,
                success = true,
            )
        }

        return try {
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            ).records

            var total = 0.0
            for (record in records) {
                val overlapStart = maxInstant(from, record.startTime)
                val overlapEnd = minInstant(to, record.endTime)
                if (!overlapEnd.isAfter(overlapStart)) continue

                val recordMillis = max(1L, Duration.between(record.startTime, record.endTime).toMillis())
                val overlapMillis = max(0L, Duration.between(overlapStart, overlapEnd).toMillis())
                total += record.count.toDouble() * overlapMillis.toDouble() / recordMillis.toDouble()
            }

            StepsReadResult(
                total = max(0L, total.roundToLong()),
                aggregateSeen = false,
                recordsSeen = records.size,
                success = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            warnings.add("steps_records: ${error.message ?: error.javaClass.simpleName}")
            StepsReadResult(
                total = 0L,
                aggregateSeen = false,
                recordsSeen = 0,
                success = false,
            )
        }
    }

    private suspend fun readRunningActivityData(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
        warnings: MutableList<String>,
    ): RunningActivityData {
        if (!to.isAfter(from)) {
            return RunningActivityData(
                originalSessions = emptyList(),
                coverageIntervals = emptyList(),
                distanceSegments = emptyList(),
                sessionsReadSuccess = false,
                distanceReadSuccess = false,
            )
        }

        val originalSessions = try {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            ).records.filter { session ->
                session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING &&
                    minInstant(to, session.endTime).isAfter(maxInstant(from, session.startTime))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            warnings.add("running_sessions: ${error.message ?: error.javaClass.simpleName}")
            return RunningActivityData(
                originalSessions = emptyList(),
                coverageIntervals = emptyList(),
                distanceSegments = emptyList(),
                sessionsReadSuccess = false,
                distanceReadSuccess = false,
            )
        }

        // Real sessions are preserved unchanged. Coverage is a derived technical view used only
        // for filtering, deduplication and aggregate duration.
        val coverageIntervals = mergeIntervals(
            originalSessions.mapNotNull { session ->
                val clippedFrom = maxInstant(from, session.startTime)
                val clippedTo = minInstant(to, session.endTime)
                if (clippedTo.isAfter(clippedFrom)) TimeInterval(clippedFrom, clippedTo) else null
            }
        )
        if (coverageIntervals.isEmpty()) {
            return RunningActivityData(
                originalSessions = originalSessions,
                coverageIntervals = emptyList(),
                distanceSegments = emptyList(),
                sessionsReadSuccess = true,
                distanceReadSuccess = true,
            )
        }

        val records = try {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = DistanceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            ).records
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            warnings.add("distance_records: ${error.message ?: error.javaClass.simpleName}")
            return RunningActivityData(
                originalSessions = originalSessions,
                coverageIntervals = coverageIntervals,
                distanceSegments = emptyList(),
                sessionsReadSuccess = true,
                distanceReadSuccess = false,
            )
        }

        val uniqueRecords = LinkedHashMap<String, DistanceRecord>()
        for (record in records) {
            if (record.distance.inMeters <= 0.0 || !record.endTime.isAfter(record.startTime)) continue
            if (coverageIntervals.none { intervalsOverlap(it.from, it.to, record.startTime, record.endTime) }) continue

            val origin = record.metadata.dataOrigin.packageName
            val recordKey = listOf(
                origin,
                record.startTime.toString(),
                record.endTime.toString(),
                formatMeters(record.distance.inMeters, 3),
            ).joinToString("|")
            uniqueRecords.putIfAbsent(recordKey, record)
        }

        val selectedSegments = mutableListOf<RunningDistanceSegment>()

        for (coverage in coverageIntervals) {
            val segmentsByOrigin = linkedMapOf<String, MutableList<RunningDistanceSegment>>()

            for (record in uniqueRecords.values) {
                val overlapFrom = maxInstant(coverage.from, record.startTime)
                val overlapTo = minInstant(coverage.to, record.endTime)
                if (!overlapTo.isAfter(overlapFrom)) continue

                val totalMillis = max(1L, Duration.between(record.startTime, record.endTime).toMillis())
                val overlapMillis = max(0L, Duration.between(overlapFrom, overlapTo).toMillis())
                val allocatedMeters =
                    record.distance.inMeters * overlapMillis.toDouble() / totalMillis.toDouble()
                if (allocatedMeters <= 0.0) continue

                val origin = record.metadata.dataOrigin.packageName
                segmentsByOrigin.getOrPut(origin) { mutableListOf() }
                    .add(
                        RunningDistanceSegment(
                            from = overlapFrom,
                            to = overlapTo,
                            meters = allocatedMeters,
                        )
                    )
            }

            val selectedOrigin = segmentsByOrigin.maxByOrNull { (_, segments) ->
                segments.sumOf { it.meters }
            }?.key

            if (selectedOrigin != null) {
                selectedSegments.addAll(segmentsByOrigin.getValue(selectedOrigin))
            }
        }

        return RunningActivityData(
            originalSessions = originalSessions,
            coverageIntervals = coverageIntervals,
            distanceSegments = selectedSegments,
            sessionsReadSuccess = true,
            distanceReadSuccess = true,
        )
    }

    private fun mergeIntervals(intervals: List<TimeInterval>): List<TimeInterval> {
        if (intervals.isEmpty()) return emptyList()

        val sorted = intervals
            .filter { it.to.isAfter(it.from) }
            .sortedBy { it.from }
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf<TimeInterval>()
        var currentFrom = sorted.first().from
        var currentTo = sorted.first().to

        for (interval in sorted.drop(1)) {
            if (!interval.from.isAfter(currentTo)) {
                if (interval.to.isAfter(currentTo)) currentTo = interval.to
            } else {
                merged.add(TimeInterval(currentFrom, currentTo))
                currentFrom = interval.from
                currentTo = interval.to
            }
        }

        merged.add(TimeInterval(currentFrom, currentTo))
        return merged
    }

    private suspend fun buildActivityWindowsBatch(
        client: HealthConnectClient,
        sourceDay: LocalDate,
        generatedAt: Instant,
    ): JSONObject? {
        val startOfDay = Instant.parse("${sourceDay}T00:00:00Z")
        val endOfDay = Instant.parse("${sourceDay.plusDays(1)}T00:00:00Z")
        if (!endOfDay.isBefore(generatedAt)) return null

        val warnings = mutableListOf<String>()
        var stepRecordsReadSuccess = true
        val stepRecords = try {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay),
                )
            ).records
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            warnings.add("activity_windows_steps: ${error.message ?: error.javaClass.simpleName}")
            stepRecordsReadSuccess = false
            emptyList()
        }

        val runningData = readRunningActivityData(
            client = client,
            from = startOfDay,
            to = endOfDay,
            warnings = warnings,
        )

        if (!stepRecordsReadSuccess || !runningData.readSuccess) return null

        val buckets = buildBuckets(startOfDay, endOfDay, 15L)
        val windows = JSONArray()

        for (bucket in buckets) {
            val steps = stepsForBucketByBestOrigin(stepRecords, bucket).roundToLong()
            if (steps > 0L) {
                windows.put(
                    JSONObject()
                        .put("activity_type", "walk")
                        .put("metric_type", "steps_window")
                        .put("value", steps)
                        .put("recorded_from", bucket.from.toString())
                        .put("recorded_to", bucket.to.toString())
                        .put(
                            "source_hash",
                            sha256(
                                listOf(
                                    "health-connect-steps-window",
                                    sourceDay.toString(),
                                    bucket.from.toString(),
                                    bucket.to.toString(),
                                    steps.toString(),
                                ).joinToString("|")
                            )
                        )
                )
            }

            val runDistanceMeters = runningData.distanceSegments.sumOf { segment ->
                proportionalValueInWindow(
                    sourceFrom = segment.from,
                    sourceTo = segment.to,
                    sourceValue = segment.meters,
                    targetFrom = bucket.from,
                    targetTo = bucket.to,
                )
            }

            if (runDistanceMeters > 0.0) {
                val normalized = normalizeTwoDecimals(runDistanceMeters)
                windows.put(
                    JSONObject()
                        .put("activity_type", "run")
                        .put("metric_type", "run_distance_window")
                        .put("value", normalized)
                        .put("recorded_from", bucket.from.toString())
                        .put("recorded_to", bucket.to.toString())
                        .put(
                            "source_hash",
                            sha256(
                                listOf(
                                    "health-connect-run-distance-window",
                                    sourceDay.toString(),
                                    bucket.from.toString(),
                                    bucket.to.toString(),
                                    formatMeters(normalized),
                                ).joinToString("|")
                            )
                        )
                )
            }

            val runDurationSeconds = runningData.coverageIntervals.sumOf { interval ->
                overlapSeconds(interval.from, interval.to, bucket.from, bucket.to)
            }

            if (runDurationSeconds > 0L) {
                windows.put(
                    JSONObject()
                        .put("activity_type", "run")
                        .put("metric_type", "run_duration_window")
                        .put("value", runDurationSeconds)
                        .put("recorded_from", bucket.from.toString())
                        .put("recorded_to", bucket.to.toString())
                        .put(
                            "source_hash",
                            sha256(
                                listOf(
                                    "health-connect-run-duration-window",
                                    sourceDay.toString(),
                                    bucket.from.toString(),
                                    bucket.to.toString(),
                                    runDurationSeconds.toString(),
                                ).joinToString("|")
                            )
                        )
                )
            }
        }

        if (windows.length() == 0) return null

        return JSONObject()
            .put("kind", "activity_windows")
            .put("external_batch_id", "health-connect-activity-windows-${sourceDay}")
            .put("generated_at", generatedAt.toString())
            .put("device_time", generatedAt.toString())
            .put("window_size_minutes", 15)
            .put("records", windows)
            .apply {
                if (warnings.isNotEmpty()) put("warnings", warnings.toJsonArray())
            }
    }

    private suspend fun detailBucketValues(
        client: HealthConnectClient,
        metric: String,
        from: Instant,
        to: Instant,
        bucketMinutes: Long,
    ): List<DetailBucket> {
        val buckets = buildBuckets(from, to, bucketMinutes)
        val warnings = mutableListOf<String>()

        when (metric) {
            "steps", "walk_steps" -> {
                val records = try {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = StepsRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(from, to),
                        )
                    ).records
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }

                for (bucket in buckets) {
                    bucket.value = stepsForBucketByBestOrigin(records, bucket)
                }
            }

            "meters", "run_distance", "run_meters" -> {
                val runningData = readRunningActivityData(client, from, to, warnings)
                for (segment in runningData.distanceSegments) {
                    distributeValue(segment.from, segment.to, segment.meters, buckets)
                }
            }

            "run_duration", "run_workout_seconds", "workout" -> {
                val runningData = readRunningActivityData(client, from, to, warnings)
                for (interval in runningData.coverageIntervals) {
                    distributeValue(
                        start = interval.from,
                        end = interval.to,
                        value = Duration.between(interval.from, interval.to).seconds.toDouble(),
                        buckets = buckets,
                    )
                }
            }
        }

        return buckets
    }

    private fun buildBuckets(
        from: Instant,
        to: Instant,
        bucketMinutes: Long,
    ): MutableList<DetailBucket> {
        val buckets = mutableListOf<DetailBucket>()
        var cursor = from
        val safeBucketMinutes = max(1L, bucketMinutes)

        while (cursor.isBefore(to)) {
            val proposedEnd = cursor.plus(Duration.ofMinutes(safeBucketMinutes))
            val bucketEnd = minInstant(proposedEnd, to)
            buckets.add(DetailBucket(cursor, bucketEnd))
            cursor = bucketEnd
        }

        return buckets
    }

    private fun stepsForBucketByBestOrigin(
        records: List<StepsRecord>,
        bucket: DetailBucket,
    ): Double {
        val totalsByOrigin = linkedMapOf<String, Double>()
        val seenRecords = mutableSetOf<String>()

        for (record in records) {
            val overlapStart = maxInstant(bucket.from, record.startTime)
            val overlapEnd = minInstant(bucket.to, record.endTime)
            if (!overlapEnd.isAfter(overlapStart)) continue

            val origin = record.metadata.dataOrigin.packageName
            val recordKey = listOf(
                origin,
                record.startTime.toString(),
                record.endTime.toString(),
                record.count.toString(),
            ).joinToString("|")
            if (!seenRecords.add(recordKey)) continue

            val allocated = proportionalValueInWindow(
                sourceFrom = record.startTime,
                sourceTo = record.endTime,
                sourceValue = record.count.toDouble(),
                targetFrom = bucket.from,
                targetTo = bucket.to,
            )
            totalsByOrigin[origin] = (totalsByOrigin[origin] ?: 0.0) + allocated
        }

        return totalsByOrigin.values.maxOrNull() ?: 0.0
    }

    private fun distributeValue(
        start: Instant,
        end: Instant,
        value: Double,
        buckets: List<DetailBucket>,
    ) {
        if (value <= 0.0 || !end.isAfter(start)) return

        for (bucket in buckets) {
            bucket.value += proportionalValueInWindow(
                sourceFrom = start,
                sourceTo = end,
                sourceValue = value,
                targetFrom = bucket.from,
                targetTo = bucket.to,
            )
        }
    }

    private fun proportionalValueInWindow(
        sourceFrom: Instant,
        sourceTo: Instant,
        sourceValue: Double,
        targetFrom: Instant,
        targetTo: Instant,
    ): Double {
        if (sourceValue <= 0.0 || !sourceTo.isAfter(sourceFrom)) return 0.0

        val overlapFrom = maxInstant(sourceFrom, targetFrom)
        val overlapTo = minInstant(sourceTo, targetTo)
        if (!overlapTo.isAfter(overlapFrom)) return 0.0

        val totalMillis = max(1L, Duration.between(sourceFrom, sourceTo).toMillis())
        val overlapMillis = max(0L, Duration.between(overlapFrom, overlapTo).toMillis())
        return sourceValue * overlapMillis.toDouble() / totalMillis.toDouble()
    }

    private fun overlapSeconds(
        firstFrom: Instant,
        firstTo: Instant,
        secondFrom: Instant,
        secondTo: Instant,
    ): Long {
        val overlapFrom = maxInstant(firstFrom, secondFrom)
        val overlapTo = minInstant(firstTo, secondTo)
        return if (overlapTo.isAfter(overlapFrom)) {
            max(0L, Duration.between(overlapFrom, overlapTo).seconds)
        } else {
            0L
        }
    }

    private fun intervalsOverlap(
        firstFrom: Instant,
        firstTo: Instant,
        secondFrom: Instant,
        secondTo: Instant,
    ): Boolean = minInstant(firstTo, secondTo).isAfter(maxInstant(firstFrom, secondFrom))

    private fun minInstant(a: Instant, b: Instant): Instant = if (a.isBefore(b)) a else b

    private fun maxInstant(a: Instant, b: Instant): Instant = if (a.isAfter(b)) a else b

    private fun parseInstantOrNull(value: String): Instant? =
        value.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun normalizeTwoDecimals(value: Double): Double =
        String.format(Locale.US, "%.2f", max(0.0, value)).toDouble()

    private fun formatMeters(value: Double, decimals: Int = 2): String =
        String.format(Locale.US, "%.${decimals}f", max(0.0, value))

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun List<String>.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach(array::put) }

    private fun emptyPayload(message: String): JSONObject = JSONObject()
        .put("batches", JSONArray())
        .put("generated_at", Instant.now().toString())
        .put("preferred_source", "health_connect")
        .put("provider", providerPayload())
        .put("message", message)

    private fun providerPayload(): JSONObject = JSONObject()
        .put("type", "health_connect")
        .put("name", "Health Connect")
        .put("priority", 80)
        .put("confidence_score", 88)

    private fun uniqueBatchId(prefix: String, now: Instant): String =
        "$prefix-${now.toEpochMilli()}-${UUID.randomUUID()}"

    companion object {
        private const val STABLE_COMPLETE_READS_REQUIRED = 2
        private const val TRACE_COMPONENT = "health_connect_repository"
        const val HEALTH_CONNECT_PACKAGE_NAME = "com.google.android.apps.healthdata"
    }
}
