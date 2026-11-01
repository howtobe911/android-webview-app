package com.second.risedie.challengeapp.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HealthSyncSourceContractTest {
    @Test
    fun nativeStepCounterIsUiOnlyAndNeverBackendTruth() {
        val tracker = source("app/src/main/java/com/second/risedie/challengeapp/health/LiveStepTracker.kt")
        assertTrue(tracker.contains("TYPE_STEP_COUNTER is local UI overlay only"))
        assertTrue(tracker.contains("return safeHealthSteps"))
        assertTrue(tracker.contains("source_of_truth\", \"native_live_ui"))
        assertFalse(tracker.contains("source_of_truth\", \"activity_current_state\").put(\"raw_counter\""))
    }

    @Test
    fun serverTruthIncreaseHardResetsOverlayBaseline() {
        val tracker = source("app/src/main/java/com/second/risedie/challengeapp/health/LiveStepTracker.kt")
        assertTrue(tracker.contains("safeSteps > previousTruth"))
        assertTrue(tracker.contains("hard_reset_truth_increased"))
        assertTrue(tracker.contains("realtimeDeltaSteps = 0L"))
        assertTrue(tracker.contains("displaySteps = serverSteps"))
    }

    @Test
    fun lowerOrOlderServerTruthCannotReduceExistingAnchor() {
        val tracker = source("app/src/main/java/com/second/risedie/challengeapp/health/LiveStepTracker.kt")
        assertTrue(tracker.contains("preserve_older_or_lower_truth"))
        assertFalse(tracker.substringAfter("preserve_older_or_lower_truth").substringBefore("cachedState = next").contains("serverVerifiedSteps = safeSteps"))
    }

    @Test
    fun serverDayChangeHardResetsOverlay() {
        val tracker = source("app/src/main/java/com/second/risedie/challengeapp/health/LiveStepTracker.kt")
        assertTrue(tracker.contains("previousDay != serverDay"))
        assertTrue(tracker.contains("hard_reset_new_server_day"))
    }

    @Test
    fun rawCounterResetCannotCreateNegativeDelta() {
        val tracker = source("app/src/main/java/com/second/risedie/challengeapp/health/LiveStepTracker.kt")
        assertTrue(tracker.contains("raw < dayState.sensorBaseValue"))
        assertTrue(tracker.contains("lastResetReason = \"raw_counter_reset\""))
        assertTrue(tracker.contains("max(0f, it - base).toLong()"))
    }

    @Test
    fun localMidnightNeverDefinesTruthDay() {
        val tracker = source("app/src/main/java/com/second/risedie/challengeapp/health/LiveStepTracker.kt")
        assertTrue(tracker.contains("No local/UTC midnight reset here"))
        assertFalse(tracker.contains("LocalDate.now("))
    }

    @Test
    fun repositoryReadsOnlyHealthConnectRecordTypesForConfirmedData() {
        val repo = source("app/src/main/java/com/second/risedie/challengeapp/health/HealthConnectRepository.kt")
        assertTrue(repo.contains("StepsRecord::class"))
        assertTrue(repo.contains("DistanceRecord::class"))
        assertTrue(repo.contains("ExerciseSessionRecord::class"))
        assertFalse(repo.contains("Sensor.TYPE_STEP_COUNTER"))
    }

    @Test
    fun aggregateAndDayClosePayloadsDeclareHealthConnectTruth() {
        val repo = source("app/src/main/java/com/second/risedie/challengeapp/health/HealthConnectRepository.kt")
        assertTrue(repo.contains(".put(\"kind\", \"health_connect_aggregate\")"))
        assertTrue(repo.contains(".put(\"preferred_source\", \"health_connect\")"))
        assertTrue(repo.contains(".put(\"source_of_truth\", \"health_connect_day_close_snapshot\")"))
    }

    @Test
    fun metricReadFailuresCannotZeroOrSuppressSuccessfulOtherDiscipline() {
        val repo = source("app/src/main/java/com/second/risedie/challengeapp/health/HealthConnectRepository.kt")
        assertTrue(repo.contains("if (snapshot.diagnostics.stepsReadSuccess) aggregateRecords.put("))
        assertTrue(repo.contains("if (snapshot.diagnostics.runReadSuccess) aggregateRecords.put("))
        assertTrue(repo.contains("if (stepsRead.success) put(\"walk_steps\", walkSteps)"))
        assertTrue(repo.contains("if (runningData.readSuccess) {"))
        assertFalse(repo.contains("bestRunSnapshot\n            ?: return@withContext emptyPayload"))
        assertTrue(repo.contains("token = response.pageToken?.takeIf { it.isNotBlank() }"))
    }

    @Test
    fun detailWindowsStayExplicitForensicBatch() {
        val repo = source("app/src/main/java/com/second/risedie/challengeapp/health/HealthConnectRepository.kt")
        assertTrue(repo.contains(".put(\"kind\", \"activity_detail_windows\")"))
        assertTrue(repo.contains("run_distance_window"))
        assertTrue(repo.contains("run_duration_window"))
    }

    @Test
    fun diagnosticsCarryHealthConnectEvidence() {
        val repo = source("app/src/main/java/com/second/risedie/challengeapp/health/HealthConnectRepository.kt")
        assertTrue(repo.contains("health_connect_records_seen"))
        assertTrue(repo.contains("health_connect_aggregate_seen"))
        assertTrue(repo.contains("server_day_ends_at_utc"))
    }

    @Test
    fun currentDayIsDurablyPostedBeforeHistoricalProviderReads() {
        val coordinator = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt")
        val liveUpload = coordinator.indexOf("apiClient.postSourceSync(config, body, syncWindow.serverDay, sessionId)")
        val history = coordinator.indexOf("syncPreviousDayWithoutBlockingCurrent(config, syncWindow, sessionId)")
        val details = coordinator.indexOf("val detailAttempt = runCatching")
        assertTrue(liveUpload > 0 && history > liveUpload && details > history)
        assertTrue(coordinator.contains("skipped_after_grace_cutoff"))
        assertTrue(coordinator.contains("deferred_provider_quota_cooldown"))
    }

    @Test
    fun unchangedHistoricalHashIsRememberedEncryptedAfterServerReceipt() {
        val api = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncApiClient.kt")
        val credentials = source("app/src/main/java/com/second/risedie/challengeapp/security/SecureCredentialStore.kt")
        assertTrue(api.contains("fun alreadyAcknowledgedDayClose("))
        assertTrue(api.contains("if (status == \"settlement_open\") rememberDayCloseAcknowledgement("))
        assertTrue(credentials.contains("fun writeAcknowledgedDayClose("))
        assertTrue(credentials.contains("fun readAcknowledgedDayClose()"))
        assertTrue(credentials.contains(".remove(KEY_DAY_CLOSE_ACK).remove(KEY_DAY_CLOSE_ACK_IV)"))
    }

    private fun source(relative: String): String = sequenceOf(
        File(relative), File(relative.removePrefix("app/")),
    ).firstOrNull { it.isFile }?.readText() ?: error("Source not found: $relative")
}
