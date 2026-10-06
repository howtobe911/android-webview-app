package com.second.risedie.challengeapp.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrustedWebOriginTest {
    private val hosts = "[\"second.risedie.ru\",\"www.second.risedie.ru\"]"

    @Test
    fun trustedOriginMatrix() {
        assertEquals("https://second.risedie.ru", TrustedWebOrigin.canonicalOrigin("https://SECOND.RISEDIE.RU/", hosts))
        assertEquals("https://www.second.risedie.ru", TrustedWebOrigin.canonicalOrigin("https://www.second.risedie.ru/path?q=1", hosts))
        assertNull(TrustedWebOrigin.canonicalOrigin("http://second.risedie.ru", hosts))
        assertNull(TrustedWebOrigin.canonicalOrigin("https://evil.example", hosts))
        assertNull(TrustedWebOrigin.canonicalOrigin("https://second.risedie.ru.evil.example", hosts))
        assertNull(TrustedWebOrigin.canonicalOrigin("https://second.risedie.ru@evil.example", hosts))
        assertNull(TrustedWebOrigin.canonicalOrigin("https://second.risedie.ru:8443", hosts))
        assertEquals("https://second.risedie.ru", TrustedWebOrigin.canonicalOrigin("https://second.risedie.ru:443/a", hosts))
    }

    @Test
    fun startupKeepsWebViewHttpCache() {
        val source = sequenceOf(
            File("app/src/main/java/com/second/risedie/challengeapp/ui/ChallengeWebViewActivity.kt"),
            File("src/main/java/com/second/risedie/challengeapp/ui/ChallengeWebViewActivity.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("ChallengeWebViewActivity.kt not found")

        assertTrue(source.contains("cacheMode = WebSettings.LOAD_DEFAULT"))
        assertTrue(source.contains("setAcceptThirdPartyCookies(target, false)"))
        assertFalse(source.contains("LOAD_NO_CACHE"))
        assertFalse(source.contains("clearCache(true)"))
        assertFalse(source.contains("appendQueryParameter(\"nocache\""))
    }
    @Test
    fun nativeBridgeAndClientsRequireTrustedOrigin() {
        val bridge = source("app/src/main/java/com/second/risedie/challengeapp/bridge/ChallengeAppBridge.kt")
        val api = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncApiClient.kt")
        val push = source("app/src/main/java/com/second/risedie/challengeapp/push/PushTokenRegistrar.kt")

        assertTrue(bridge.contains("TrustedWebOrigin.canonicalOrigin"))
        assertTrue(api.contains("TrustedWebOrigin.canonicalOrigin"))
        assertTrue(api.contains("instanceFollowRedirects = false"))
        assertTrue(push.contains("TrustedWebOrigin.canonicalOrigin"))
    }

    @Test
    fun releaseSigningMaterialComesFromExternalConfiguration() {
        val gradle = source("app/build.gradle.kts")
        assertTrue(gradle.contains("CM_KEYSTORE_PATH"))
        assertTrue(gradle.contains("GRAFIT_KEYSTORE_PATH"))
        assertFalse(gradle.contains("file(\"grafit.jks\")"))
        assertFalse(gradle.contains("storeFile = file(\"grafit.jks\")"))
    }

    @Test
    fun syncJournalDoesNotPersistRawSessionId() {
        val logger = source("app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncLogger.kt")
        assertFalse(logger.contains(".put(\"session_id\", sessionId)"))
        assertTrue(logger.contains(".put(\"trace_id\", traceId(sessionId))"))
    }

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File(relative.removePrefix("app/")),
    ).firstOrNull { it.isFile }?.readText() ?: error("Source not found: $relative")

}
