package com.second.risedie.challengeapp.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** JVM source contracts run offline; instrumented tests validate real Keystore behavior. */
class AuthV3NativePayloadContractTest {
    @Test fun nativeLoginForwardsMfaCodeButDoesNotPersistItInKeystore() {
        val source = File("src/main/java/com/second/risedie/challengeapp/security/NativeAuthSession.kt").readText()
        val store = File("src/main/java/com/second/risedie/challengeapp/security/SecureCredentialStore.kt").readText()
        assertTrue(source.contains("\"mfa_code\""))
        assertTrue(source.contains("fields.forEach"))
        assertFalse(store.contains("mfa_code"))
    }

    @Test fun nativeCredentialStoreSharesLockAcrossIndependentInstances() {
        val source = File("src/main/java/com/second/risedie/challengeapp/security/SecureCredentialStore.kt").readText()
        assertTrue(source.contains("private val CREDENTIAL_LOCK = Any()"))
        assertTrue(source.contains("synchronized(CREDENTIAL_LOCK)"))
    }
}
