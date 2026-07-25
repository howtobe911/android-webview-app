package com.second.risedie.challengeapp.sync

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Single structured JSONL journal for the complete native Health Connect sync chain.
 * Never logs auth tokens, signing keys, record bodies or other sensitive payload content.
 */
class HealthSyncLogger(context: Context) {
    private val logDir = File(context.applicationContext.filesDir, "logs")
    private val logFile = File(logDir, FILE_NAME)

    fun info(sessionId: String, component: String, event: String, fields: JSONObject = JSONObject()) =
        write("INFO", sessionId, component, event, fields, null)

    fun warn(sessionId: String, component: String, event: String, fields: JSONObject = JSONObject(), error: Throwable? = null) =
        write("WARN", sessionId, component, event, fields, error)

    fun error(sessionId: String, component: String, event: String, error: Throwable, fields: JSONObject = JSONObject()) =
        write("ERROR", sessionId, component, event, fields, error)

    fun path(): String = logFile.absolutePath

    private fun write(
        level: String,
        sessionId: String,
        component: String,
        event: String,
        fields: JSONObject,
        error: Throwable?,
    ) {
        runCatching {
            synchronized(LOCK) {
                if (!logDir.exists()) logDir.mkdirs()
                rotateIfNeeded()

                val line = JSONObject()
                    .put("ts", Instant.now().toString())
                    .put("level", level)
                    .put("session_id", sessionId)
                    .put("component", component)
                    .put("event", event)

                fields.keys().forEach { key ->
                    if (key !in FORBIDDEN_KEYS) line.put(key, fields.opt(key))
                }

                if (error != null) {
                    line.put("error_type", error.javaClass.simpleName)
                    line.put("error_message", sanitize(error.message ?: error.javaClass.simpleName))
                }

                logFile.appendText(line.toString() + "\n", Charsets.UTF_8)
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_FILE_BYTES) return
        for (index in MAX_BACKUPS downTo 1) {
            val source = if (index == 1) logFile else File(logDir, "$FILE_NAME.${index - 1}")
            val target = File(logDir, "$FILE_NAME.$index")
            if (target.exists()) target.delete()
            if (source.exists()) source.renameTo(target)
        }
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("Bearer\\s+[A-Za-z0-9._~+\\-/]+=*", RegexOption.IGNORE_CASE), "Bearer [redacted]")
        .take(MAX_MESSAGE_LENGTH)

    companion object {
        private val LOCK = Any()
        private const val FILE_NAME = "health-sync.log"
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_BACKUPS = 3
        private const val MAX_MESSAGE_LENGTH = 1000
        private val FORBIDDEN_KEYS = setOf(
            "token", "auth_token", "authorization", "payload_signing_key", "payload_signature", "nonce", "records", "body",
        )
    }
}
