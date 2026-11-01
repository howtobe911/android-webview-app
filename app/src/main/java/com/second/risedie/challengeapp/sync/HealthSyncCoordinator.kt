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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class HealthSyncCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val configStore = HealthSyncConfigStore(appContext)
    private val logger = HealthSyncLogger(appContext)
    private val repository = HealthConnectRepository(appContext, logger)
    private val apiClient = HealthSyncApiClient(appContext, logger)
    private val quotaGuard = HealthConnectQuotaGuard(appContext)

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

        val quotaDelay = quotaGuard.remainingMillis()
        if (quotaDelay > 0L) {
            // HC provider quota must not block already measured and durably
            // queued activity from reaching the backend over the network.
            val flush = runCatching { apiClient.retryPendingSourceSync(config, sessionId) }
            flush.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            flush.onFailure { logger.warn(sessionId, "api_client", "quota_outbox_delivery_pending", error = it) }
            logger.info(sessionId, "health_reader", "quota_cooldown_active", JSONObject()
                .put("retry_after_ms", quotaDelay))
            return@withContext JSONObject()
                .put("type", "quota_cooldown")
                .put("success", false)
                .put("fresh", false)
                .put("reason", reason)
                .put("request_id", sessionId)
                .put("retry_after_ms", quotaDelay)
                .put("outbox_retry_pending", flush.isFailure)
                .put("synced_at", Instant.now().toString())
        }

        val sdkStatus = repository.sdkStatus()
        logger.info(sessionId, "health_reader", "sdk_status", JSONObject().put("status", sdkStatus))
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return@withContext failure("unavailable", reason, sessionId, "Health Connect is unavailable")
        }

        val granted = repository.grantedPermissions()
        val canReadSteps = granted.contains(repository.stepsReadPermission)
        val canReadRun = granted.containsAll(setOf(repository.exerciseReadPermission, repository.distanceReadPermission))
        logger.info(sessionId, "health_reader", "permissions_state", JSONObject()
            .put("data_permissions_granted", granted.containsAll(repository.dataPermissions))
            .put("steps_permission", canReadSteps)
            .put("run_permission", canReadRun))
        if (!canReadSteps && !canReadRun) {
            return@withContext failure("no_permissions", reason, sessionId, "Health Connect reading permissions are missing")
        }

        try {
            val syncWindow = apiClient.fetchSyncWindow(config, sessionId)
            require(syncWindow.serverTimezone == "UTC") { "Backend sync-window must use UTC" }

            // A stale outbox HTTP timeout must not prevent today's independent
            // metric reads. Pending bytes remain in the encrypted queue and
            // will be retried on the next pass.
            val replay = runCatching { apiClient.retryPendingSourceSync(config, sessionId) }
            replay.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            replay.onFailure { logger.warn(sessionId, "api_client", "outbox_replay_deferred_current_read_continues", error = it) }

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
            val providerQuotaExceeded = payload.optBoolean("provider_quota_exceeded", false)
            val quotaRetryAfterMs = if (providerQuotaExceeded) {
                (quotaGuard.recordQuotaExceeded() - System.currentTimeMillis()).coerceAtLeast(0L)
            } else {
                quotaGuard.recordSuccessfulRead()
                0L
            }
            payload.put("preferred_source", "health_connect")
            val payloadSummary = logPayloadSummary(sessionId, payload, readStarted)
            logger.info(sessionId, "health_reader", "health_connect_snapshot", JSONObject(payloadSummary.toString())
                .put("window_from_utc", syncWindow.windowFromUtc.toString())
                .put("window_to_utc", syncWindow.windowToUtc.toString()))

            val batches = payload.optJSONArray("batches") ?: JSONArray()
            if (batches.length() == 0) {
                logger.info(sessionId, "health_reader", "no_current_batches_detail_requests_still_checked")
            }

            var accepted = 0
            var duplicate = 0
            var rejected = 0
            var posted = 0
            var currentState: JSONObject? = null
            var totals: JSONObject? = null

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

            // Preserve scarce provider quota for live activity first. Once it
            // has reached the durable HTTP outbox, read yesterday (if still
            // in grace) and then inspect the detail requests it may create.
            syncPreviousDayWithoutBlockingCurrent(config, syncWindow, sessionId)

            // Unverified historical details never block today's HTTP upload.
            // The 202 importer runs asynchronously: wait briefly before the
            // FIRST request fetch, rather than fetching every request twice.
            if (lastAggregateBatchId > 0L) {
                runCatching { awaitAggregateProcessing(config, lastAggregateBatchId, sessionId) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            val detailAttempt = runCatching {
                if (quotaGuard.remainingMillis() > 0L) {
                    logger.info(sessionId, "detail_requests", "deferred_provider_quota_cooldown")
                    JSONObject().put("deferred_quota", true)
                } else postPendingDetailRequests(config, syncWindow, sessionId)
            }
            val detailError = detailAttempt.exceptionOrNull()
            if (detailError is CancellationException) throw detailError
            detailError?.let { error ->
                if (HealthConnectQuotaGuard.isQuotaExceeded(error)) {
                    quotaGuard.recordQuotaExceeded()
                }
                logger.warn(sessionId, "detail_requests", "verification_pending_retry", JSONObject()
                    .put("error_class", error.javaClass.simpleName)
                    .put("retryable", isTransientFailure(error)), error)
            }
            val detailResult = detailAttempt.getOrNull()
            detailResult?.let { recovered ->
                accepted += recovered.optInt("accepted", 0)
                rejected += recovered.optInt("rejected", 0)
                posted += recovered.optInt("posted", 0)
                currentState = recovered.optJSONObject("current_state")?.takeIf { it.length() > 0 } ?: currentState
                totals = recovered.optJSONObject("authoritative_totals")?.takeIf { it.length() > 0 } ?: totals
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

            val sourceSyncHttpStatus = apiClient.sourceSyncHttpCode()
            val requestDelivered = sourceSyncHttpStatus != null && sourceSyncHttpStatus in 200..299
            val serverProcessingQueued = sourceSyncHttpStatus == 202
            // A 202 response is only an enqueue ACK: neither the user's totals
            // nor the successful-sync timestamp may advance before processing.
            val serverConfirmed = accepted > 0 || duplicate > 0 || currentState != null
            val type = when {
                serverConfirmed -> "success"
                posted > 0 || serverProcessingQueued -> "queued"
                else -> "no_data"
            }
            val fresh = serverConfirmed
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
                .put("steps", if (payloadSummary.has("steps")) payloadSummary.optLong("steps") else JSONObject.NULL)
                .put("run_meters", if (payloadSummary.has("run_meters")) payloadSummary.optDouble("run_meters") else JSONObject.NULL)
                .put("records_count", payloadSummary.optInt("records_count", 0))
                .put("provider_quota_exceeded", providerQuotaExceeded)
                .put("retry_after_ms", quotaRetryAfterMs)
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
                .put("steps", if (payloadSummary.has("steps")) payloadSummary.optLong("steps") else JSONObject.NULL)
                .put("run_meters", if (payloadSummary.has("run_meters")) payloadSummary.optDouble("run_meters") else JSONObject.NULL)
                .put("records_count", payloadSummary.optInt("records_count", 0))
                .put("provider_quota_exceeded", providerQuotaExceeded)
                .put("retry_after_ms", quotaRetryAfterMs)
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
            if (HealthConnectQuotaGuard.isQuotaExceeded(error)) {
                val retryAt = quotaGuard.recordQuotaExceeded()
                val remaining = (retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
                logger.warn(sessionId, "health_reader", "quota_cooldown_started", JSONObject()
                    .put("retry_after_ms", remaining)
                    .put("reason", reason), error)
                return@withContext JSONObject()
                    .put("type", "quota_cooldown")
                    .put("success", false)
                    .put("fresh", false)
                    .put("reason", reason)
                    .put("request_id", sessionId)
                    .put("retry_after_ms", remaining)
                    .put("synced_at", Instant.now().toString())
            }
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
        val graceDeadline = Instant.parse("${syncWindow.serverDay}T00:00:00Z")
            .plus(Duration.ofHours(GRACE_WINDOW_HOURS))
        if (!syncWindow.windowToUtc.isBefore(graceDeadline)) {
            logger.info(sessionId, "day_close", "skipped_after_grace_cutoff")
            return
        }
        val previousDay = LocalDate.parse(syncWindow.serverDay).minusDays(1)
        logger.info(sessionId, "day_close", "read_started", JSONObject().put("activity_date", previousDay.toString()))
        val payload = repository.buildDayCloseSnapshotPayload(previousDay, includeRunDistance = true, traceSessionId = sessionId)
        if (payload.optBoolean("provider_quota_exceeded", false)) {
            // A valid historical steps reading must still reach the server.
            // Only additional HC reads are deferred until quota replenishes.
            quotaGuard.recordQuotaExceeded()
        }
        if (payload.optString("activity_date").isBlank()) {
            logger.info(sessionId, "day_close", "skipped_no_data")
            return
        }
        val body = JSONObject(payload.toString()).put("source_id", config.sourceId)
        if (apiClient.alreadyAcknowledgedDayClose(config, body)) {
            logger.info(sessionId, "day_close", "unchanged_snapshot_already_acknowledged", JSONObject()
                .put("activity_date", previousDay.toString()))
            return
        }
        try {
            apiClient.postDayClose(config, body, sessionId)
            logger.info(sessionId, "day_close", "posted")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (HealthConnectQuotaGuard.isQuotaExceeded(error)) throw error
            logger.warn(sessionId, "day_close", "post_failed", error = error)
        }
    }

    private suspend fun syncPreviousDayWithoutBlockingCurrent(
        config: HealthSyncConfig,
        syncWindow: ServerSyncWindow,
        sessionId: String,
    ) {
        if (quotaGuard.remainingMillis() > 0L) {
            logger.info(sessionId, "day_close", "deferred_provider_quota_cooldown")
            return
        }
        try {
            postPreviousDayCloseSnapshotIfNeeded(config, syncWindow, sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (HealthConnectQuotaGuard.isQuotaExceeded(error)) {
                val retryAt = quotaGuard.recordQuotaExceeded()
                logger.warn(sessionId, "day_close", "quota_after_current_day_uploaded", JSONObject()
                    .put("retry_after_ms", (retryAt - System.currentTimeMillis()).coerceAtLeast(0L)), error)
            } else {
                logger.warn(sessionId, "day_close", "historical_read_retry_pending", error = error)
            }
        }
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
                if (HealthConnectQuotaGuard.isQuotaExceeded(error)) throw error
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
            if (batch.optString("kind") == "health_connect_aggregate") {
                put("health_connect_window_token", requireNotNull(syncWindow.healthConnectWindowToken) {
                    "Health Connect aggregate requires its signed server window"
                })
            }
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
        // One bounded provider read per scheduled run. Repeated full-day queries
        // every few seconds exhaust HC quotas and delay already valid metrics.
        private const val READ_ATTEMPTS = 1
        private const val READ_DELAY_MS = 750L
        private const val GRACE_WINDOW_HOURS = 10L
        private val WINDOW_KINDS = setOf("walk_steps_windows", "activity_windows", "activity_detail_windows")
    }
}
