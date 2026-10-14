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

    private fun source(relative: String): String = sequenceOf(
        File(relative), File(relative.removePrefix("app/")),
    ).firstOrNull { it.isFile }?.readText() ?: error("Source not found: $relative")
}
