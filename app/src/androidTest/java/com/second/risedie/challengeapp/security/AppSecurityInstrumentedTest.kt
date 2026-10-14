package com.second.risedie.challengeapp.security

import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.second.risedie.challengeapp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSecurityInstrumentedTest {
    @Test
    fun releaseOriginPolicyIsEnforcedOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("com.second.risedie.challengeapp", context.packageName)
        assertTrue(TrustedWebOrigin.isTrustedUrl(BuildConfig.APP_WEB_URL))
        assertTrue(TrustedWebOrigin.isTrustedUrl("https://second.risedie.ru/web"))
        assertFalse(TrustedWebOrigin.isTrustedUrl("http://second.risedie.ru/web"))
        assertFalse(TrustedWebOrigin.isTrustedUrl("https://second.risedie.ru@evil.example/web"))
        assertFalse(TrustedWebOrigin.isTrustedUrl("https://second.risedie.ru.evil.example/web"))
        assertFalse(TrustedWebOrigin.isTrustedUrl("https://second.risedie.ru:8443/web"))
    }

    @Test
    fun cleartextTrafficIsBlockedByInstalledApplicationPolicy() {
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
    }
}
