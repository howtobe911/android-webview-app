package com.second.risedie.challengeapp.sync

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ActivityBatchKeyTest {
    @Test fun networkRetriesAndRefreshKeepTheLogicalKey() {
        val key = ActivityBatchKey.resolve(null, null)
        repeat(100) { assertEquals(key, ActivityBatchKey.resolve(key, "regenerated-transport-id-$it")) }
    }
    @Test fun newLogicalSendsNeverShareGeneratedKeys() {
        val keys = (0 until 1000).map { ActivityBatchKey.resolve(null, null) }
        assertEquals(1000, keys.toSet().size)
    }
    @Test fun anExistingBatchCanRecoverItsKeyAfterProcessRecreation() {
        assertEquals("logical-external-17", ActivityBatchKey.resolve(null, "logical-external-17"))
        assertEquals(ActivityBatchKey.resolve(null, "logical-external-17"), ActivityBatchKey.resolve(null, "logical-external-17"))
    }
    @Test fun explicitKeyIsOpaqueAndCaseSensitive() {
        assertEquals("Batch-A", ActivityBatchKey.resolve("Batch-A", "other"))
        assertNotEquals(ActivityBatchKey.resolve("Batch-A", null), ActivityBatchKey.resolve("batch-a", null))
    }
    @Test fun legacySinglePendingBatchMigratesToDurableOutbox() {
        val body = JSONObject().put("external_batch_id", "first").put("kind", "health_connect_aggregate")
        val legacy = ActivityBatchEnvelope.stored("https://example.test", 3L, body)
        val recovered = ActivityBatchEnvelope.queue(legacy, "https://example.test", 3L)
        assertEquals(1, recovered.size)
        assertEquals("first", recovered[0].getJSONObject("body").getString("idempotency_key"))
    }
    @Test fun outboxKeepsTwoIndependentMetricBatchesAcrossRestart() {
        val steps = JSONObject().put("body", JSONObject().put("kind", "health_connect_aggregate")
            .put("idempotency_key", "steps-only")).put("batch_id", 100L)
        val run = JSONObject().put("body", JSONObject().put("kind", "health_connect_aggregate")
            .put("idempotency_key", "run-only")).put("batch_id", 0L)
        val stored = ActivityBatchEnvelope.storedQueue("https://example.test", 3L, listOf(steps, run))
        val recovered = ActivityBatchEnvelope.queue(stored, "https://example.test", 3L)
        assertEquals(2, recovered.size)
        assertEquals(100L, recovered[0].getLong("batch_id"))
        assertEquals("run-only", recovered[1].getJSONObject("body").getString("idempotency_key"))
        assertTrue(ActivityBatchEnvelope.queue(stored, "https://elsewhere.test", 3L).isEmpty())
    }
}
