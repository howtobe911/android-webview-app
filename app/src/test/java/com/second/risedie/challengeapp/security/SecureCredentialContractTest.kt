package com.second.risedie.challengeapp.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecureCredentialContractTest {
    @Test
    fun accessAndRefreshTokensUseAesGcmAndroidKeystore() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/security/SecureCredentialStore.kt")
        assertTrue(s.contains("AndroidKeyStore"))
        assertTrue(s.contains("AES/GCM/NoPadding"))
        assertTrue(s.contains("setRandomizedEncryptionRequired(true)"))
        assertTrue(s.contains("KEY_REFRESH_CIPHERTEXT"))
    }

    @Test
    fun plaintextLegacyTokensAreRemovedOnlyAfterEncryptedReadback() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/security/SecureCredentialStore.kt")
        assertTrue(s.contains("Secure access-token readback failed"))
        assertTrue(s.contains("decryptStored(KEY_CIPHERTEXT, KEY_IV) != legacy"))
        assertTrue(s.contains("clearLegacyTokens()"))
    }

    @Test
    fun clearingSessionRemovesBothTokenKindsAndLegacyCopies() {
        val s = source("app/src/main/java/com/second/risedie/challengeapp/security/SecureCredentialStore.kt")
        val body = s.substringAfter("fun clearSessionTokens()").substringBefore("private fun persistEncrypted")
        assertTrue(body.contains("KEY_CIPHERTEXT"))
        assertTrue(body.contains("KEY_REFRESH_CIPHERTEXT"))
        assertTrue(body.contains("clearLegacyTokens()"))
    }

    @Test
    fun healthSyncLoggerNeverWritesRawBearerOrRefreshToken() {
        val logger = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncLogger.kt")
        assertFalse(logger.contains("access_token\""))
        assertFalse(logger.contains("refresh_token\""))
        assertFalse(logger.contains("Authorization\""))
        assertTrue(logger.contains("trace_id"))
    }

    @Test
    fun nativeAuthRefreshUsesTrustedOriginAndSecureStore() {
        val auth = source("app/src/main/java/com/second/risedie/challengeapp/security/NativeAuthSession.kt")
        assertTrue(auth.contains("SecureCredentialStore"))
        assertTrue(auth.contains("TrustedWebOrigin"))
        assertFalse(auth.contains("getSharedPreferences(\"refresh"))
    }

    @Test
    fun bridgeOnlyExposesAuthCapabilitiesWithoutRawCredentialSetters() {
        val bridge = source("app/src/main/java/com/second/risedie/challengeapp/bridge/ChallengeAppBridge.kt")
        assertFalse(bridge.contains("fun storeRefreshToken"))
        assertFalse(bridge.contains("fun configureNativeHealthSync(token"))
        assertTrue(bridge.contains("fun authenticate(operation: String?, payload: String?)"))
        assertTrue(bridge.contains("fun configureNativeHealthSync(sourceId: String?)"))
        assertTrue(bridge.contains("SecureCredentialStore(context).readAccessToken()"))
    }

    @Test
    fun nativeAuthRedactsCredentialsAndInstallsAnHttpOnlySessionCookie() {
        val auth = source("app/src/main/java/com/second/risedie/challengeapp/security/NativeAuthSession.kt")
        assertTrue(auth.contains("remove(\"refresh_token\")"))
        assertTrue(auth.contains("remove(\"token\")"))
        assertTrue(auth.contains("Secure; HttpOnly; SameSite=Strict"))
        assertTrue(auth.contains("credentials.writeSessionTokens(access, refresh)"))
        assertFalse(auth.contains(".put(\"token\", token)"))
        assertTrue(auth.contains("synchronized(sessionLock)"))
    }

    @Test
    fun unacknowledgedActivityIsEncryptedAndKeptUntilAnAcknowledgement() {
        val api = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncApiClient.kt")
        assertTrue(api.contains("writePendingActivityBatch(ActivityBatchEnvelope.stored"))
        assertTrue(api.contains("retryPendingSourceSync"))
        assertTrue(api.contains("Missing source-sync acknowledgement"))
        assertTrue(api.indexOf("Missing source-sync acknowledgement") < api.indexOf("credentials.clearPendingActivityBatch()"))
        val secure = source("app/src/main/java/com/second/risedie/challengeapp/security/SecureCredentialStore.kt")
        assertTrue(secure.contains("persistEncrypted(value, KEY_PENDING, KEY_PENDING_IV)"))
        val clear = secure.substringAfter("fun clearSessionTokens()").substringBefore("private fun persistEncrypted")
        assertTrue(clear.contains("KEY_PENDING"))
    }

    private fun source(relative: String): String = sequenceOf(
        File(relative), File(relative.removePrefix("app/")),
    ).firstOrNull { it.isFile }?.readText() ?: error("Source not found: $relative")
}
