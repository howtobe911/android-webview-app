package com.second.risedie.challengeapp.health

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.second.risedie.challengeapp.sync.HealthSyncApiClient
import com.second.risedie.challengeapp.sync.HealthSyncLogger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthSyncCanonicalInstrumentedTest {
    @Test
    fun productionAndroidCanonicalBodyAndHmacMatchBackendContractFixture() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = HealthSyncApiClient(context, HealthSyncLogger(context))
        val records = JSONArray().put(
            JSONObject()
                .put("activity_type", "walk")
                .put("metric_type", "steps")
                .put("value", 1200)
                .put("recorded_from", "2026-10-13T00:00:00Z")
                .put("recorded_to", "2026-10-13T12:00:00Z"),
        )
        val body = JSONObject()
            .put("source_id", 11)
            .put("kind", "health_connect_aggregate")
            .put("server_day", "2026-10-13")
            .put("timezone", "UTC")
            .put("generated_at", "2026-10-13T12:00:00Z")
            .put("nonce", "nonce-fixture")
            .put("app_version", "1.2.test")
            .put("records", records)
        val nonceData = JSONObject().put("user_id", 7)

        val canonicalMethod = HealthSyncApiClient::class.java.getDeclaredMethod(
            "canonicalActivityPayload",
            JSONObject::class.java,
            JSONObject::class.java,
        ).apply { isAccessible = true }
        val hmacMethod = HealthSyncApiClient::class.java.getDeclaredMethod(
            "hmacSha256Hex",
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        val canonical = canonicalMethod.invoke(client, body, nonceData) as String
        val expectedCanonical = """{"user_id":7,"source_id":11,"kind":"health_connect_aggregate","activity_date":"2026-10-13","timezone":"UTC","generated_at":"2026-10-13T12:00:00Z","nonce":"nonce-fixture","app_version":"1.2.test","records":[{"activity_type":"walk","metric_type":"steps","value":1200,"recorded_from":"2026-10-13T00:00:00Z","recorded_to":"2026-10-13T12:00:00Z"}]}"""
        assertEquals(expectedCanonical, canonical)

        val signature = hmacMethod.invoke(client, canonical, "fixture-signing-key") as String
        assertEquals("6ee5c6b71fbe0bcf98db8e5d429838571b6d1c4030c004263cf928595b90068b", signature)
    }
}
