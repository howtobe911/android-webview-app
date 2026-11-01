package com.second.risedie.challengeapp.sync

import org.json.JSONObject
import org.json.JSONArray

/** One key belongs to one frozen logical send, independent of nonce/HTTP retry. */
object ActivityBatchEnvelope {
    fun prepare(body: JSONObject): JSONObject {
        if (body.optString("idempotency_key").isBlank()) {
            body.put("idempotency_key", ActivityBatchKey.resolve(null, body.optString("external_batch_id")))
        }
        return body
    }

    fun stored(apiBase: String, sourceId: Long, body: JSONObject): String = JSONObject()
        .put("api_base", apiBase).put("source_id", sourceId).put("body", prepare(JSONObject(body.toString()))).toString()

    fun pending(raw: String?, apiBase: String, sourceId: Long): JSONObject? {
        val envelope = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (envelope.optString("api_base") != apiBase || envelope.optLong("source_id") != sourceId) return null
        return envelope.optJSONObject("body")
    }

    /** Backward-compatible encrypted FIFO of independent, immutable logical sends. */
    fun queue(raw: String?, apiBase: String, sourceId: Long): MutableList<JSONObject> {
        val envelope = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return mutableListOf()
        if (envelope.optString("api_base") != apiBase || envelope.optLong("source_id") != sourceId) {
            return mutableListOf()
        }
        val items = envelope.optJSONArray("items")
        if (items != null) {
            return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .filter { it.optJSONObject("body") != null }.toMutableList()
        }
        val legacyBody = envelope.optJSONObject("body") ?: return mutableListOf()
        return mutableListOf(JSONObject().put("body", prepare(legacyBody))
            .put("server_day", legacyBody.optString("server_day"))
            .put("batch_id", 0L))
    }

    fun storedQueue(apiBase: String, sourceId: Long, items: List<JSONObject>): String = JSONObject()
        .put("api_base", apiBase)
        .put("source_id", sourceId)
        .put("items", JSONArray().also { array -> items.forEach { item -> array.put(item) } })
        .toString()
}
