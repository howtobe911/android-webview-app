package com.second.risedie.challengeapp.sync

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class HealthReconnectContractTest {
    @Test fun offlineRecoveryPersistsWithoutCancellingInflightWork() {
        val worker = source("sync/HealthSyncWorker.kt")
        assertTrue(worker.contains("NetworkType.CONNECTED"))
        assertTrue(worker.contains("enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP"))
        assertTrue(worker.contains("HealthSyncConfigStore(appContext).load() == null"))
    }

    @Test fun connectivityReturnStartsForegroundRecovery() {
        val activity = source("ui/ChallengeWebViewActivity.kt")
        val monitor = source("sync/HealthNetworkRecoveryMonitor.kt")
        assertTrue(activity.contains("networkRecoveryMonitor.start()"))
        assertTrue(activity.contains("override fun onDestroy() {\n        networkRecoveryMonitor.stop()"))
        assertTrue(activity.contains("requestForegroundSync(\"network_restored\")"))
        assertTrue(monitor.contains("registerDefaultNetworkCallback"))
        assertTrue(monitor.contains("NET_CAPABILITY_VALIDATED"))
        assertTrue(monitor.contains("onDisconnected()"))
    }

    @Test fun bootAndConfigurationQueueRecovery() {
        val manifest = File("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifest.contains("HealthBootReceiver"))
        assertTrue(source("sync/HealthBootReceiver.kt").contains("HealthSyncWorker.enqueueImmediate(context)"))
        assertTrue(source("bridge/ChallengeAppBridge.kt").contains("HealthSyncWorker.enqueueImmediate(context)"))
    }

    @Test fun historicalDetailPollPrecedesCurrentDayRead() {
        val coordinator = source("sync/HealthSyncCoordinator.kt")
        assertTrue(coordinator.indexOf("postPendingDetailRequests(config, syncWindow, sessionId)") <
            coordinator.indexOf("buildFreshServerWindowSyncPayload"))
        assertFalse(coordinator.contains("filter { it.activity_date == syncWindow.serverDay }"))
    }

    private fun source(relative: String): String = File(
        "app/src/main/java/com/second/risedie/challengeapp/" + relative
    ).readText()
}
