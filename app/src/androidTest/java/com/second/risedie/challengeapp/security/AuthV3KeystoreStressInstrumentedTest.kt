package com.second.risedie.challengeapp.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Requires an actual Android emulator/device with AndroidKeyStore. No external network. */
@RunWith(AndroidJUnit4::class)
class AuthV3KeystoreStressInstrumentedTest {
    @Test fun fourIndependentStoreInstancesCannotMixAccessAndRefreshAcross200Rotations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val primary = SecureCredentialStore(context)
        primary.clearSessionTokens()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 4).map { worker ->
                pool.submit {
                    try {
                        val store = SecureCredentialStore(context)
                        start.await()
                        for (i in 0 until 50) {
                            val version = "$worker-$i"
                            store.writeSessionTokens("access-$version", "refresh-$version")
                            // Another process/component may rotate immediately after this write.
                            val latest = SecureCredentialStore(context)
                            val access = latest.readAccessToken()
                            val refresh = latest.readRefreshToken()
                            // Across separate getter calls another rotation can occur;
                            // each individual write must still be a valid encrypted pair.
                            assertNotNull(access)
                            assertNotNull(refresh)
                        }
                    } catch (e: Throwable) { failures.add(e) }
                }
            }
            start.countDown()
            jobs.forEach { it.get(90, TimeUnit.SECONDS) }
            assertTrue("Concurrent Keystore writes failed: $failures", failures.isEmpty())
            val stable = SecureCredentialStore(context)
            val access = requireNotNull(stable.readAccessToken())
            val refresh = requireNotNull(stable.readRefreshToken())
            assertEquals(access.removePrefix("access-"), refresh.removePrefix("refresh-"))
        } finally {
            pool.shutdownNow()
            primary.clearSessionTokens()
        }
    }

    @Test fun corruptedCiphertextFailsClosedAndClearingRemovesPendingPrivateState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SecureCredentialStore(context)
        store.clearSessionTokens()
        try {
            store.writeSessionTokens("private-access", "private-refresh")
            store.writePendingActivityBatch("private-activity")
            val prefs = context.getSharedPreferences("grafit_secure_credentials", Context.MODE_PRIVATE)
            assertFalse(prefs.all.values.any { it.toString().contains("private-refresh") })
            prefs.edit().putString("session_ciphertext_v2", "not-base64-ciphertext").commit()
            assertNull(SecureCredentialStore(context).readAccessToken())
            assertNull(SecureCredentialStore(context).readRefreshToken())
            store.clearSessionTokens()
            assertNull(SecureCredentialStore(context).readPendingActivityBatch())
        } finally { store.clearSessionTokens() }
    }

    @Test fun generationInvalidationForbidsReadingCredentialsAfterLogoutRace() {
        NativeAuthSession.invalidateSession()
        val staleGeneration = NativeAuthSession.sessionGeneration()
        NativeAuthSession.invalidateSession()
        try {
            NativeAuthSession.inSession(staleGeneration) { fail("Old generation entered privileged block") }
            fail("Expected stale-generation rejection")
        } catch (_: IllegalStateException) { /* expected */ }
    }
}
