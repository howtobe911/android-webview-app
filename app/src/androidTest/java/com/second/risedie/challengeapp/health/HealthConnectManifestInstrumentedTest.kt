package com.second.risedie.challengeapp.health

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectManifestInstrumentedTest {
    @Test
    fun installedApkDeclaresRequiredHealthConnectAndActivityPermissions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions.orEmpty().toSet()

        val expected = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACTIVITY_RECOGNITION,
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_DISTANCE",
            "android.permission.health.READ_EXERCISE",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
        )

        expected.forEach { permission ->
            assertTrue("Missing manifest permission: $permission", requested.contains(permission))
        }
    }
}
