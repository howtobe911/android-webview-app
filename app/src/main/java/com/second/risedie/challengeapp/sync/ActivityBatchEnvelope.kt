package com.second.risedie.challengeapp.sync

import org.json.JSONObject

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
}
