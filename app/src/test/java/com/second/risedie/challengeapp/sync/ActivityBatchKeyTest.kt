package com.second.risedie.challengeapp.sync

import org.junit.Assert.*
import org.junit.Test

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
}
