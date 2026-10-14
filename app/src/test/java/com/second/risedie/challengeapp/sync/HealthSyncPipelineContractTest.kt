package com.second.risedie.challengeapp.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HealthSyncPipelineContractTest {
    @Test
    fun coordinatorSerializesConcurrentSyncs() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt")
        assertTrue(s.contains("processSyncMutex.withLock"))
        assertTrue(s.contains("private val processSyncMutex = Mutex()"))
    }

    @Test
    fun coordinatorReadsServerWindowBeforeHealthConnect() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt")
        assertTrue(s.indexOf("fetchSyncWindow") < s.indexOf("buildFreshServerWindowSyncPayload"))
        assertTrue(s.contains("require(syncWindow.serverTimezone == \"UTC\")"))
    }

    @Test
    fun coordinatorMarks202AsQueuedRatherThanStateChanged() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt")
        assertTrue(s.contains("val serverProcessingQueued = sourceSyncHttpStatus == 202"))
        assertTrue(s.contains("val serverStateChanged = accepted > 0"))
        assertTrue(s.contains(".put(\"server_processing_queued\", serverProcessingQueued)"))
    }

    @Test
    fun coordinatorPostsPreviousDayCloseAndPendingDetails() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt")
        assertTrue(s.contains("postPreviousDayCloseSnapshotIfNeeded"))
        assertTrue(s.contains("postPendingDetailRequests"))
    }

    @Test
    fun coordinatorNeverUsesNativeStepTrackerAsUploadSource() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt")
        assertFalse(s.contains("LiveStepTracker"))
        assertTrue(s.contains("payload.put(\"preferred_source\", \"health_connect\")"))
    }

    @Test
    fun apiRejectsUntrustedOriginsAndRedirects() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncApiClient.kt")
        assertTrue(s.contains("TrustedWebOrigin.isTrustedUrl(url)"))
        assertTrue(s.contains("instanceFollowRedirects = false"))
        assertTrue(s.contains("Authorization\", \"Bearer \$accessToken\""))
    }

    @Test
    fun sourceSyncObtainsOneTimeNonceAndSignsCanonicalBody() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncApiClient.kt")
        assertTrue(s.contains("/api/v1/me/activity/payload-nonce"))
        assertTrue(s.contains("body.put(\"nonce\", nonce)"))
        assertTrue(s.contains("body.put(\"payload_signature\""))
        assertTrue(s.contains("HmacSHA256"))
    }

    @Test
    fun apiTreatsAll2xxIncluding202AsTransportSuccess() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncApiClient.kt")
        assertTrue(s.contains("code in 200..299"))
        assertTrue(s.contains("if (code !in 200..299)"))
        assertTrue(s.contains("sourceSyncHttpCode = code"))
    }

    @Test
    fun workerOwnsSchedulingButNotSyncMechanics() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncWorker.kt")
        assertTrue(s.contains("Sync mechanics live in HealthSyncCoordinator"))
        assertTrue(s.contains("HealthSyncCoordinator(applicationContext)"))
        assertFalse(s.contains("HealthConnectClient.getOrCreate"))
    }

    @Test
    fun workerRequiresNetworkAndUsesUniqueWork() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncWorker.kt")
        assertTrue(s.contains("NetworkType.CONNECTED"))
        assertTrue(s.contains("enqueueUniqueWork"))
        assertTrue(s.contains("enqueueUniquePeriodicWork"))
        assertTrue(s.contains("ExistingPeriodicWorkPolicy.KEEP"))
    }

    @Test
    fun workerRetriesOnlyTransientClassifications() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncWorker.kt")
        assertTrue(s.contains("isTransientFailure"))
        assertTrue(s.contains("Result.retry()"))
        assertTrue(s.contains("Result.failure()"))
        assertTrue(s.contains("\"transient_failure\" -> \"retry\""))
    }

    private fun source(relative: String): String = sequenceOf(
        File(relative), File(relative.removePrefix("app/")),
    ).firstOrNull { it.isFile }?.readText() ?: error("Source not found: $relative")
}
