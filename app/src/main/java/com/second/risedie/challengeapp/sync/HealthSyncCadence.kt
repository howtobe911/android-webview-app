package com.second.risedie.challengeapp.sync

import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest

/** Shared, session-scoped read cadence and aggregate heartbeat for every sync entry point. */
internal class HealthSyncCadence(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        HealthSyncConfigStore.PREFS, Context.MODE_PRIVATE,
    )

    fun readIsRecent(sourceId: Long, serverDay: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        matches("last_read", sourceId, serverDay) && elapsedUnder("last_read", READ_INTERVAL_MS, nowMs)

    fun noteRead(sourceId: Long, serverDay: String, aggregateRecords: JSONArray?, nowMs: Long = System.currentTimeMillis()) {
        val signature = aggregateRecords?.let { fingerprint(it) }
        prefs.edit().putLong("last_read_source", sourceId).putString("last_read_day", serverDay)
            .putLong("last_read_ms", nowMs).putString("last_read_fingerprint", signature).apply()
    }

    fun aggregateIsRecent(
        sourceId: Long,
        serverDay: String,
        records: JSONArray,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = matches("last_batch", sourceId, serverDay) &&
        prefs.getString("last_batch_fingerprint", null) == fingerprint(records) &&
        prefs.getString("last_batch_status", null) in setOf("processing", "processed", "detail_pending") &&
        elapsedUnder("last_batch", HEARTBEAT_INTERVAL_MS, nowMs)

    fun aggregateWasProcessed(sourceId: Long, serverDay: String, records: JSONArray): Boolean =
        matches("last_batch", sourceId, serverDay) &&
            prefs.getString("last_batch_fingerprint", null) == fingerprint(records) &&
            prefs.getString("last_batch_status", null) == "processed"

    fun aggregateStatus(sourceId: Long, serverDay: String, records: JSONArray): String =
        if (matches("last_batch", sourceId, serverDay) &&
            prefs.getString("last_batch_fingerprint", null) == fingerprint(records)) {
            prefs.getString("last_batch_status", null) ?: "not_posted"
        } else "not_posted"

    fun recentProcessedRead(sourceId: Long, serverDay: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        readIsRecent(sourceId, serverDay, nowMs) && matches("last_batch", sourceId, serverDay) &&
            prefs.getString("last_read_fingerprint", null) != null &&
            prefs.getString("last_read_fingerprint", null) == prefs.getString("last_batch_fingerprint", null) &&
            prefs.getString("last_batch_status", null) == "processed"

    fun dayCloseIsRecent(sourceId: Long, activityDate: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        matches("day_close_read", sourceId, activityDate) &&
            elapsedUnder("day_close_read", HEARTBEAT_INTERVAL_MS, nowMs)

    fun noteDayCloseRead(sourceId: Long, activityDate: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("day_close_read_source", sourceId)
            .putString("day_close_read_day", activityDate)
            .putLong("day_close_read_ms", nowMs).apply()
    }

    fun noteReceipt(sourceId: Long, serverDay: String, records: JSONArray, status: String, batchId: Long) {
        if (status !in setOf("processing", "processed", "detail_pending")) return
        val signature = fingerprint(records) ?: return
        prefs.edit().putLong("last_batch_source", sourceId).putString("last_batch_day", serverDay)
            .putString("last_batch_fingerprint", signature).putString("last_batch_status", status)
            .putLong("last_batch_id", batchId).putLong("last_batch_ms", System.currentTimeMillis()).apply()
    }

    /** Update a persisted 202 receipt after outbox replay, without treating 202 as processing success. */
    fun updateReceiptStatus(sourceId: Long, serverDay: String, records: JSONArray, status: String) {
        if (status !in setOf("processed", "detail_pending", "failed", "rejected")) return
        if (!matches("last_batch", sourceId, serverDay) ||
            prefs.getString("last_batch_fingerprint", null) != fingerprint(records)) return
        prefs.edit().putString("last_batch_status", status).apply()
    }

    private fun matches(prefix: String, sourceId: Long, day: String): Boolean =
        prefs.getLong("${prefix}_source", -1L) == sourceId &&
            prefs.getString("${prefix}_day", null) == day

    private fun elapsedUnder(prefix: String, limitMs: Long, nowMs: Long): Boolean {
        val previous = prefs.getLong("${prefix}_ms", 0L)
        val elapsed = nowMs - previous
        return previous > 0L && elapsed in 0 until limitMs
    }

    private fun fingerprint(records: JSONArray): String? {
        val values = ArrayList<String>(records.length())
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val kind = record.optString("metric_type")
            if (kind !in setOf("steps", "meters", "run_duration") || !record.has("value")) continue
            // Ignore recorded_to/generated_at: each HC snapshot has different timestamps.
            values.add("${record.optString("activity_type")}:$kind:${record.opt("value")}")
        }
        if (values.isEmpty()) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(values.sorted().joinToString("|").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val READ_INTERVAL_MS = 2 * 60_000L
        const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L
    }
}
