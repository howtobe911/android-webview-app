package com.second.risedie.challengeapp.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device/emulator-only tests: the Android Keystore cannot be emulated by plain JVM tests. */
@RunWith(AndroidJUnit4::class)
class AuthAccountSwitchInstrumentedTest {
    @Test fun accountSwitchReplacesBothCredentialsWithoutLeakingPreviousGeneration() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val credentials = SecureCredentialStore(context)
        credentials.clearSessionTokens()
        try {
            credentials.writeSessionTokens("alice-access", "alice-refresh")
            assertEquals("alice-access", SecureCredentialStore(context).readAccessToken())
            credentials.writeSessionTokens("bob-access", "bob-refresh")
            val reopened = SecureCredentialStore(context)
            assertEquals("bob-access", reopened.readAccessToken())
            assertEquals("bob-refresh", reopened.readRefreshToken())
            assertNotEquals("alice-access", reopened.readAccessToken())
            assertNotEquals("alice-refresh", reopened.readRefreshToken())
        } finally {
            credentials.clearSessionTokens()
        }
    }

    @Test fun logoutErasesPrivatePendingDataAlongsideAccountCredentials() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureCredentialStore(context)
        store.clearSessionTokens()
        try {
            store.writeSessionTokens("alice-access", "alice-refresh")
            store.writePendingActivityBatch("account-alice-private-batch")
            assertEquals("account-alice-private-batch", SecureCredentialStore(context).readPendingActivityBatch())
            store.clearSessionTokens()
            val reopened = SecureCredentialStore(context)
            assertNull(reopened.readAccessToken())
            assertNull(reopened.readRefreshToken())
            assertNull(reopened.readPendingActivityBatch())
        } finally { store.clearSessionTokens() }
    }

    @Test fun hundredCredentialRotationsKeepOnlyTheFinalAtomicPair() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureCredentialStore(context)
        store.clearSessionTokens()
        try {
            for (i in 0 until 100) {
                store.writeSessionTokens("access-$i", "refresh-$i")
                val reopened = SecureCredentialStore(context)
                assertEquals("access-$i", reopened.readAccessToken())
                assertEquals("refresh-$i", reopened.readRefreshToken())
            }
            assertEquals("access-99", store.readAccessToken())
            assertEquals("refresh-99", store.readRefreshToken())
        } finally { store.clearSessionTokens() }
    }
}
