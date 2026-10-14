package com.second.risedie.challengeapp.health

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class LiveActivityOverlayStoreInstrumentedTest {
    @Test
    fun overlayStateSurvivesRealDataStoreRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = LiveActivityOverlayStore(context)
        val state = LiveActivityOverlayState(
            activityDate = "2026-10-13",
            serverVerifiedSteps = 12_345,
            serverVerifiedRunMeters = 4_321,
            serverVerifiedRunSeconds = 1_800,
            localBaseSteps = 12_345,
            sensorBaseValue = 50_000f,
            sensorLastValue = 50_123f,
            realtimeDeltaSteps = 123,
            displaySteps = 12_468,
            awaitingFreshBaseline = false,
            lastHealthConnectReadAt = Instant.parse("2026-10-13T12:00:00Z"),
            lastResetReason = "instrumentation_round_trip",
            lastRawCounterResetAt = Instant.parse("2026-10-13T11:00:00Z"),
            updatedAt = Instant.parse("2026-10-13T12:00:01Z"),
        )

        store.write(state)
        val restored = store.read()

        assertEquals(state.activityDate, restored.activityDate)
        assertEquals(state.serverVerifiedSteps, restored.serverVerifiedSteps)
        assertEquals(state.serverVerifiedRunMeters, restored.serverVerifiedRunMeters)
        assertEquals(state.serverVerifiedRunSeconds, restored.serverVerifiedRunSeconds)
        assertEquals(state.localBaseSteps, restored.localBaseSteps)
        assertEquals(state.sensorBaseValue, restored.sensorBaseValue)
        assertEquals(state.sensorLastValue, restored.sensorLastValue)
        assertEquals(state.realtimeDeltaSteps, restored.realtimeDeltaSteps)
        assertEquals(state.displaySteps, restored.displaySteps)
        assertFalse(restored.awaitingFreshBaseline)
        assertEquals(state.lastHealthConnectReadAt, restored.lastHealthConnectReadAt)
        assertEquals(state.lastResetReason, restored.lastResetReason)
        assertEquals(state.lastRawCounterResetAt, restored.lastRawCounterResetAt)
        assertEquals(state.updatedAt, restored.updatedAt)
    }
}
