package com.second.risedie.challengeapp.sync

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import com.second.risedie.challengeapp.health.HealthConnectRepository
import com.second.risedie.challengeapp.health.ServerSyncWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class HealthSyncCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val configStore = HealthSyncConfigStore(appContext)
    private val logger = HealthSyncLogger(appContext)
    private val repository = HealthConnectRepository(appContext, logger)
    private val apiClient = HealthSyncApiClient(appContext, logger)

    fun configure(token: String, apiBase: String, sourceId: Long) {
        configStore.save(token, apiBase, sourceId)
        logger.info("configuration", COMPONENT, "configured", JSONObject()
            .put("api_host", runCatching { java.net.URL(apiBase).host }.getOrDefault("invalid"))
            .put("source_id", sourceId))
    }

    suspend fun sync(reason: String, sessionId: String): JSONObject = processSyncMutex.withLock {
        withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        apiClient.resetSourceSyncHttpCode()
        logger.info(sessionId, COMPONENT, "sync_started", JSONObject().put("reason", reason))

        val config = configStore.load()
            ?: return@withContext failure("not_configured", reason, sessionId, "Native sync is not configured")
        logger.info(sessionId, COMPONENT, "configuration_loaded", JSONObject()
            .put("api_host", runCatching { java.net.URL(config.apiBase).host }.getOrDefault("invalid"))
            .put("source_id", config.sourceId))

        val sdkStatus = repository.sdkStatus()
        logger.info(sessionId, "health_reader", "sdk_status", JSONObject().put("status", sdkStatus))
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return@withContext failure("unavailable", reason, sessionId, "Health Connect is unavailable")
        }

        val permissionsGranted = repository.hasPermissions()
        logger.info(sessionId, "health_reader", "permissions_state", JSONObject().put("data_permissions_granted", permissionsGranted))
        if (!permissionsGranted) {
            val granted = runCatching { repository.grantedPermissions() }.getOrDefault(emptySet())
            // Missing RUN permissions must not strand a WALK investigation.
            // Do not publish a partial daily aggregate as complete run data.
            val stepPermission = granted.contains(repository.stepsReadPermission)
            if (stepPermission) {
                val forensic = runCatching {
                    val window = apiClient.fetchSyncWindow(config, sessionId)
                    postPendingDetailRequests(config, window, sessionId)
                }
                forensic.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                forensic.onFailure { logger.warn(sessionId, "detail_requests", "partial_permission_recovery_failed", error = it) }
                return@withContext failure("no_permissions", reason, sessionId,
                    "Full Health Connect permissions are required for daily totals; available steps detail was checked")
                    .put("detail_verification_requests", forensic.getOrNull()?.optInt("pending_requests", 0) ?: 0)
                    .put("detail_verification_pending_retry", forensic.isFailure)
                    .put("detail_verification_error", forensic.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
            }
            return@withContext failure("no_permissions", reason, sessionId, "Health Connect step permission is missing")
        }

        try {
            val syncWindow = apiClient.fetchSyncWindow(config, sessionId)
            require(syncWindow.serverTimezone == "UTC") { "Backend sync-window must use UTC" }

            apiClient.retryPendingSourceSync(config, sessionId)
            // Old verification windows can expire sooner than the current-day snapshot.
            // Give outstanding detail requests first priority on every reconnection.
            val detailAttempt = runCatching {
                postPendingDetailRequests(config, syncWindow, sessionId)
            }
            var detailError = detailAttempt.exceptionOrNull()
            detailError?.let { if (it is CancellationException) throw it }
            detailError?.let { error ->
                // Do not silently classify a failed evidence read/upload as success.
                // The request remains requested server-side and will be retried on
                // the next sync; ordinary fresh-day telemetry may still proceed.
                logger.warn(sessionId, "detail_requests", "verification_pending_retry", JSONObject()
                    .put("error_class", error.javaClass.simpleName)
                    .put("retryable", isTransientFailure(error)), error)
            }
            var detailResult = detailAttempt.getOrNull()
            postPreviousDayCloseSnapshotIfNeeded(config, syncWindow, sessionId)

            logger.info(sessionId, "health_reader", "read_started", JSONObject()
                .put("attempts", READ_ATTEMPTS)
                .put("delay_ms", READ_DELAY_MS)
                .put("window_from_utc", syncWindow.windowFromUtc.toString())
                .put("window_to_utc", syncWindow.windowToUtc.toString()))

            val readStarted = System.nanoTime()
            val payload = repository.buildFreshServerWindowSyncPayload(
                serverWindow = syncWindow,
                includeRunDistance = true,
                attempts = READ_ATTEMPTS,
                delayMillis = READ_DELAY_MS,
                traceSessionId = sessionId,
            )
            payload.put("preferred_source", "health_connect")
            val payloadSummary = logPayloadSummary(sessionId, payload, readStarted)
            logger.info(sessionId, "health_reader", "health_connect_snapshot", JSONObject(payloadSummary.toString())
                .put("window_from_utc", syncWindow.windowFromUtc.toString())
                .put("window_to_utc", syncWindow.windowToUtc.toString()))

            val batches = payload.optJSONArray("batches") ?: JSONArray()
            if (batches.length() == 0) {
                logger.info(sessionId, "health_reader", "no_current_batches_detail_requests_still_checked")
            }

            var accepted = detailResult?.optInt("accepted", 0) ?: 0
            var duplicate = 0
            var rejected = detailResult?.optInt("rejected", 0) ?: 0
            var posted = detailResult?.optInt("posted", 0) ?: 0
            var currentState: JSONObject? = detailResult?.optJSONObject("current_state")?.takeIf { it.length() > 0 }
            var totals: JSONObject? = detailResult?.optJSONObject("authoritative_totals")?.takeIf { it.length() > 0 }

            var lastAggregateBatchId = 0L
            for (index in 0 until batches.length()) {
                val batch = batches.optJSONObject(index) ?: continue
                val kind = batch.optString("kind")
                if (kind in WINDOW_KINDS && kind != "activity_detail_windows") {
                    logger.info(sessionId, "payload_builder", "batch_skipped", JSONObject().put("kind", kind).put("reason", "window_kind"))
                    continue
                }
                val records = batch.optJSONArray("records") ?: JSONArray()
                if (records.length() == 0) continue

                val body = buildSourceSyncBody(config.sourceId, batch, payload, syncWindow)
                logger.info(sessionId, "payload_builder", "batch_built", JSONObject()
                    .put("kind", kind)
                    .put("records_count", records.length()))

                val response = apiClient.postSourceSync(config, body, syncWindow.serverDay, sessionId)
                val data = response.optJSONObject("data") ?: JSONObject()
                accepted += data.optInt("accepted_records", 0)
                duplicate += data.optInt("duplicate_records", 0)
                rejected += data.optInt("rejected_records", 0)
                currentState = data.optJSONObject("current_state") ?: currentState
                totals = data.optJSONObject("authoritative_totals") ?: totals
                posted += 1
                if (kind == "health_connect_aggregate") lastAggregateBatchId = data.optLong("batch_id", 0L)
            }

            // HTTP 202 only means queued. The investigation is created by the
            // importer AFTER that ACK, so a poll before uploading cannot see it.
            if (lastAggregateBatchId > 0L && (detailResult?.optInt("pending_requests", 0) ?: 0) == 0) {
                val followUp = runCatching {
                    awaitAggregateProcessing(config, lastAggregateBatchId, sessionId)
                    postPendingDetailRequests(config, syncWindow, sessionId)
                }
                val followUpError = followUp.exceptionOrNull()
                if (followUpError is CancellationException) throw followUpError
                detailError = followUpError
                followUp.getOrNull()?.let { recovered ->
                    detailResult = recovered
                    accepted += recovered.optInt("accepted", 0)
                    rejected += recovered.optInt("rejected", 0)
                    posted += recovered.optInt("posted", 0)
                    currentState = recovered.optJSONObject("current_state")?.takeIf { it.length() > 0 } ?: currentState
                    totals = recovered.optJSONObject("authoritative_totals")?.takeIf { it.length() > 0 } ?: totals
                }
            }

            // Preserve the original no-data contract if neither the current-day
            // read nor the historical detail recovery had anything to upload.
            if (batches.length() == 0 && posted == 0) {
                return@withContext noData(reason, sessionId, syncWindow, payload.optString("message", "Health Connect returned no records"))
                    .put("detail_verification_pending_retry", detailError != null || (detailResult?.optInt("failed_requests", 0) ?: 0) > 0)
                    .put("detail_verification_retryable", (detailError?.let { isTransientFailure(it) } ?: false) || (detailResult?.optBoolean("retryable", false) ?: false))
                    .put("detail_verification_requests", detailResult?.optInt("pending_requests", 0) ?: 0)
                    .put("detail_verification_error", detailError?.javaClass?.simpleName ?: detailResult?.optString("last_error")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            }

            val type = if (accepted > 0 || duplicate > 0 || currentState != null || posted > 0) "success" else "no_data"
            val fresh = type == "success"
            val sourceSyncHttpStatus = apiClient.sourceSyncHttpCode()
            val requestDelivered = sourceSyncHttpStatus != null && sourceSyncHttpStatus in 200..299
            val serverProcessingQueued = sourceSyncHttpStatus == 202
            val serverAccepted = accepted > 0 || serverProcessingQueued
            val serverStateChanged = accepted > 0
            val result = JSONObject()
                .put("type", type)
                .put("success", fresh)
                .put("fresh", fresh)
                .put("request_id", sessionId)
                .put("reason", reason)
                .put("server_day", syncWindow.serverDay)
                .put("server_timezone", "UTC")
                .put("window_from_utc", syncWindow.windowFromUtc.toString())
                .put("window_to_utc", syncWindow.windowToUtc.toString())
                .put("accepted_records", accepted)
                .put("duplicate_records", duplicate)
                .put("rejected_records", rejected)
                .put("detail_verification_pending_retry", detailError != null || (detailResult?.optInt("pending_requests", 0) ?: 0) > 0)
                .put("detail_verification_error", detailError?.javaClass?.simpleName ?: detailResult?.optString("last_error")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("detail_verification_retryable", (detailError?.let { isTransientFailure(it) } ?: false) || (detailResult?.optBoolean("retryable", false) ?: false))
                .put("detail_verification_requests", detailResult?.optInt("pending_requests", 0) ?: 0)
                .put("steps", payloadSummary.optLong("steps", 0L))
                .put("run_meters", payloadSummary.optDouble("run_meters", 0.0))
                .put("records_count", payloadSummary.optInt("records_count", 0))
                .put("http_status", sourceSyncHttpStatus ?: JSONObject.NULL)
                .put("last_http_status", apiClient.lastHttpCode() ?: JSONObject.NULL)
                .put("request_delivered", requestDelivered)
                .put("server_accepted", serverAccepted)
                .put("server_processing_queued", serverProcessingQueued)
                .put("server_state_changed", serverStateChanged)
                .put("server_updated", serverStateChanged)
                .put("authoritative_totals", totals ?: JSONObject())
                .put("current_state", currentState ?: JSONObject())
                .put("should_reload_snapshot", fresh)
                .put("synced_at", Instant.now().toString())

            logger.info(sessionId, COMPONENT, "sync_completed", JSONObject()
                .put("reason", reason)
                .put("result_type", type)
                .put("posted_batches", posted)
                .put("accepted_records", accepted)
                .put("duplicate_records", duplicate)
                .put("rejected_records", rejected)
                .put("steps", payloadSummary.optLong("steps", 0L))
                .put("run_meters", payloadSummary.optDouble("run_meters", 0.0))
                .put("records_count", payloadSummary.optInt("records_count", 0))
                .put("http_status", sourceSyncHttpStatus ?: JSONObject.NULL)
                .put("last_http_status", apiClient.lastHttpCode() ?: JSONObject.NULL)
                .put("request_delivered", requestDelivered)
                .put("server_accepted", serverAccepted)
                .put("server_processing_queued", serverProcessingQueued)
                .put("server_state_changed", serverStateChanged)
                .put("server_updated", serverStateChanged)
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000))
            result
        } catch (error: CancellationException) {
            logger.warn(sessionId, COMPONENT, "sync_cancelled", JSONObject().put("reason", reason), error)
            throw error
        } catch (error: Throwable) {
            val transient = isTransientFailure(error)
            logger.error(sessionId, COMPONENT, if (transient) "sync_transient_failure" else "sync_failed", error, JSONObject()
                .put("reason", reason)
                .put("retryable", transient)
                .put("duration_ms", (System.nanoTime() - started) / 1_000_000))
            failure(
                if (transient) "transient_failure" else "failed",
                reason,
                sessionId,
                error.message ?: error.javaClass.simpleName,
            ).put("retryable", transient)
        }
        }
    }

    private fun isTransientFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is RemoteException || current is IOException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun postPreviousDayCloseSnapshotIfNeeded(
        config: HealthSyncConfig,
        syncWindow: ServerSyncWindow,
        sessionId: String,
    ) {
        val previousDay = LocalDate.parse(syncWindow.serverDay).minusDays(1)
        logger.info(sessionId, "day_close", "read_started", JSONObject().put("activity_date", previousDay.toString()))
        val payload = repository.buildDayCloseSnapshotPayload(previousDay, includeRunDistance = true, traceSessionId = sessionId)
        if (payload.optString("activity_date").isBlank()) {
            logger.info(sessionId, "day_close", "skipped_no_data")
            return
        }
        val body = JSONObject(payload.toString()).put("source_id", config.sourceId)
        runCatching { apiClient.postDayClose(config, body, sessionId) }
            .onSuccess { logger.info(sessionId, "day_close", "posted") }
            .onFailure { logger.warn(sessionId, "day_close", "post_failed", error = it) }
    }

    private suspend fun postPendingDetailRequests(
        config: HealthSyncConfig,
        syncWindow: ServerSyncWindow,
        sessionId: String,
    ): JSONObject {
        val result = JSONObject().put("accepted", 0).put("rejected", 0).put("posted", 0)
        val requests = apiClient.fetchDetailRequests(config, sessionId)
        logger.info(sessionId, "detail_requests", "fetched", JSONObject().put("requests_count", requests.length()))
        if (requests.length() == 0) return result
        result.put("pending_requests", requests.length())

        // Read and upload one request at a time. One provider/read/permission
        // failure must not discard already-read evidence for every other request.
        var accepted = 0
        var rejected = 0
        var posted = 0
        var failed = 0
        var retryableFailure = false
        var lastError: String? = null
        var lastData = JSONObject()
        for (requestIndex in 0 until requests.length()) {
            val request = requests.optJSONObject(requestIndex) ?: continue
            val requestId = request.optLong("id", 0L)
            var phase = "reading"
            try {
                apiClient.reportDetailProgress(config, requestId, phase, null, sessionId)
                val payload = repository.buildDetailWindowsPayload(JSONArray().put(request), traceSessionId = sessionId)
                val records = payload.optJSONArray("records") ?: JSONArray()
                if (records.length() == 0) {
                    failed++
                    lastError = "no_native_evidence"
                    apiClient.reportDetailProgress(config, requestId, "no_native_evidence", lastError, sessionId)
                    continue
                }
                phase = "uploading"
                // UTC request timestamps stay historical; nonce day stays current.
                for (offset in 0 until records.length() step 1000) {
                    val chunk = JSONArray()
                    for (i in offset until minOf(offset + 1000, records.length())) chunk.put(records.getJSONObject(i))
                    val body = JSONObject()
                        .put("source_id", config.sourceId)
                        .put("kind", "activity_detail_windows")
                        .put("external_batch_id", payload.optString("external_batch_id") + "-" + (offset / 1000))
                        .put("generated_at", payload.optString("generated_at"))
                        .put("device_time", payload.optString("device_time", payload.optString("generated_at")))
                        .put("server_day", syncWindow.serverDay)
                        .put("activity_date", syncWindow.serverDay)
                        .put("timezone", "UTC")
                        .put("server_timezone", "UTC")
                        .put("window_from_utc", syncWindow.windowFromUtc.toString())
                        .put("window_to_utc", syncWindow.windowToUtc.toString())
                        .put("records", chunk)
                        .put("is_live_ui_only", false)
                        .put("source_of_truth", "health_connect")
                    logger.info(sessionId, "detail_requests", "chunk_upload_started", JSONObject()
                        .put("detail_request_id", requestId).put("chunk_index", offset / 1000).put("chunk_records", chunk.length()))
                    val response = apiClient.postSourceSync(config, body, syncWindow.serverDay, sessionId)
                    lastData = response.optJSONObject("data") ?: JSONObject()
                    logger.info(sessionId, "detail_requests", "chunk_upload_acknowledged", JSONObject()
                        .put("detail_request_id", requestId).put("batch_id", lastData.optLong("batch_id", 0L)))
                    accepted += lastData.optInt("accepted_records", 0)
                    rejected += lastData.optInt("rejected_records", 0)
                    posted++
                }
                apiClient.reportDetailProgress(config, requestId, "upload_queued", null, sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed++
                retryableFailure = retryableFailure || isTransientFailure(error)
                lastError = error.javaClass.simpleName.replace(Regex("[^a-zA-Z0-9_.:-]"), "_").take(120)
                apiClient.reportDetailProgress(config, requestId,
                    if (phase == "reading") "read_failed" else "upload_failed", lastError, sessionId)
                logger.warn(sessionId, "detail_requests", "request_failed_will_retry", JSONObject()
                    .put("detail_request_id", requestId).put("phase", phase), error)
            }
        }
        return result.put("accepted", accepted).put("rejected", rejected).put("posted", posted)
            .put("failed_requests", failed).put("retryable", retryableFailure).put("last_error", lastError ?: JSONObject.NULL)
            .put("verification_state", if (failed > 0) "retry_pending" else "upload_queued")
            .put("authoritative_totals", lastData.optJSONObject("authoritative_totals") ?: JSONObject())
            .put("current_state", lastData.optJSONObject("current_state") ?: JSONObject())
    }

    private suspend fun awaitAggregateProcessing(config: HealthSyncConfig, batchId: Long, sessionId: String) {
        for (delayMs in listOf(0L, 300L, 600L, 1200L, 2000L)) {
            if (delayMs > 0) delay(delayMs)
            if (apiClient.fetchSyncStatus(config, batchId, sessionId) != "processing") return
        }
        // Longer queue delays are recovered by the foreground/background loop.
        logger.info(sessionId, "detail_requests", "aggregate_still_processing", JSONObject().put("batch_id", batchId))
    }

    private fun buildSourceSyncBody(
        sourceId: Long,
        batch: JSONObject,
        payload: JSONObject,
        syncWindow: ServerSyncWindow,
    ): JSONObject = JSONObject()
        .put("source_id", sourceId)
        .put("kind", batch.optString("kind"))
        .put("external_batch_id", batch.optString("external_batch_id"))
        .put("generated_at", batch.optString("generated_at", payload.optString("generated_at")))
        .put("device_time", batch.optString("device_time", payload.optString("device_time")))
        .put("server_day", syncWindow.serverDay)
        .put("activity_date", syncWindow.serverDay)
        .put("timezone", "UTC")
        .put("server_timezone", "UTC")
        .put("window_from_utc", syncWindow.windowFromUtc.toString())
        .put("window_to_utc", syncWindow.windowToUtc.toString())
        .put("server_day_ends_at_utc", syncWindow.serverDayEndsAtUtc.toString())
        .put("records", batch.optJSONArray("records") ?: JSONArray())
        .put("is_live_ui_only", false)
        .put("source_of_truth", batch.optString("source_of_truth", "health_connect"))
        .put("health_connect_read_at", payload.optString("generated_at"))
        .put("client_observed_at", payload.optString("device_time", payload.optString("generated_at")))
        .apply {
            if (batch.has("window_size_minutes")) put("window_size_minutes", batch.optInt("window_size_minutes", 15))
        }

    private fun logPayloadSummary(sessionId: String, payload: JSONObject, readStarted: Long): JSONObject {
        val batches = payload.optJSONArray("batches") ?: JSONArray()
        var recordsCount = 0
        var steps: Long? = null
        var runMeters: Double? = null
        var latestRecordedTo: String? = null
        var healthConnectRecordsSeen: Int? = null
        var healthConnectAggregateSeen: Boolean? = null
        val kinds = JSONArray()
        for (index in 0 until batches.length()) {
            val batch = batches.optJSONObject(index) ?: continue
            kinds.put(batch.optString("kind"))
            val records = batch.optJSONArray("records") ?: JSONArray()
            recordsCount += records.length()
            for (recordIndex in 0 until records.length()) {
                val record = records.optJSONObject(recordIndex) ?: continue
                when (record.optString("metric_type")) {
                    "steps" -> {
                        steps = record.optLong("value", 0L)
                        healthConnectRecordsSeen = record.optInt("health_connect_records_seen", 0)
                        healthConnectAggregateSeen = record.optBoolean("health_connect_aggregate_seen", false)
                    }
                    "meters" -> runMeters = record.optDouble("value", 0.0)
                }
                val recordedTo = record.optString("recorded_to")
                if (recordedTo.isNotBlank() && (latestRecordedTo == null || recordedTo > latestRecordedTo!!)) {
                    latestRecordedTo = recordedTo
                }
            }
        }
        val fields = JSONObject()
            .put("generated_at", payload.optString("generated_at"))
            .put("device_time", payload.optString("device_time"))
            .put("batches_count", batches.length())
            .put("records_count", recordsCount)
            .put("batch_kinds", kinds)
            .put("duration_ms", (System.nanoTime() - readStarted) / 1_000_000)
        steps?.let { fields.put("steps", it) }
        runMeters?.let { fields.put("run_meters", it) }
        latestRecordedTo?.let { fields.put("latest_recorded_to", it) }
        healthConnectRecordsSeen?.let { fields.put("health_connect_records_seen", it) }
        healthConnectAggregateSeen?.let { fields.put("health_connect_aggregate_seen", it) }
        logger.info(sessionId, "health_reader", "read_completed", fields)
        return fields
    }

    private fun noData(reason: String, sessionId: String, window: ServerSyncWindow, message: String): JSONObject {
        logger.warn(sessionId, COMPONENT, "sync_no_data", JSONObject().put("reason", reason).put("message", message))
        return JSONObject()
            .put("type", "no_data")
            .put("success", false)
            .put("fresh", false)
            .put("request_id", sessionId)
            .put("reason", reason)
            .put("server_day", window.serverDay)
            .put("server_timezone", "UTC")
            .put("window_from_utc", window.windowFromUtc.toString())
            .put("window_to_utc", window.windowToUtc.toString())
            .put("message", message)
            .put("synced_at", Instant.now().toString())
    }

    private fun failure(type: String, reason: String, sessionId: String, message: String): JSONObject {
        logger.warn(sessionId, COMPONENT, "sync_rejected", JSONObject().put("reason", reason).put("type", type).put("message", message))
        return JSONObject()
            .put("type", type)
            .put("success", false)
            .put("fresh", false)
            .put("request_id", sessionId)
            .put("reason", reason)
            .put("message", message)
            .put("synced_at", Instant.now().toString())
    }

    companion object {
        private val processSyncMutex = Mutex()
        private const val COMPONENT = "coordinator"
        private const val READ_ATTEMPTS = 8
        private const val READ_DELAY_MS = 750L
        private val WINDOW_KINDS = setOf("walk_steps_windows", "activity_windows", "activity_detail_windows")
    }
}
