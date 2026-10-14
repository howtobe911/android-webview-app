package com.second.risedie.challengeapp.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.second.risedie.challengeapp.sync.ActivityBatchEnvelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthCredentialLifecycleInstrumentedTest {
    @Test fun credentialsAndPendingSendSurviveRecreationAndAreClearedOnLogout() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureCredentialStore(context)
        store.clearSessionTokens()
        try {
            store.writeSessionTokens("test-access", "test-refresh")
            val body = JSONObject().put("kind", "health_connect_aggregate").put("external_batch_id", "logical-17")
            store.writePendingActivityBatch(ActivityBatchEnvelope.stored("https://second.risedie.ru", 17, body))
            val reopened = SecureCredentialStore(context)
            assertEquals("test-access", reopened.readAccessToken())
            assertEquals("test-refresh", reopened.readRefreshToken())
            val pending = ActivityBatchEnvelope.pending(reopened.readPendingActivityBatch(), "https://second.risedie.ru", 17)
            assertEquals("logical-17", pending?.optString("idempotency_key"))
            pending?.put("nonce", "new-transport-nonce")
            assertEquals("logical-17", ActivityBatchEnvelope.prepare(requireNotNull(pending)).optString("idempotency_key"))
            assertNull(ActivityBatchEnvelope.pending(reopened.readPendingActivityBatch(), "https://second.risedie.ru", 18))
            reopened.clearSessionTokens()
            assertNull(store.readAccessToken())
            assertNull(store.readRefreshToken())
            assertNull(store.readPendingActivityBatch())
        } finally { store.clearSessionTokens() }
    }
}
