package com.second.risedie.challengeapp.sync

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
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

    fun tail(lines: Int = DEFAULT_TAIL_LINES): String = synchronized(LOCK) {
        val requestedLines = lines.coerceIn(1, MAX_TAIL_LINES)
        if (!logFile.exists()) return@synchronized LOG_NOT_CREATED
        if (logFile.length() == 0L) return@synchronized LOG_EMPTY

        runCatching {
            RandomAccessFile(logFile, "r").use { file ->
                var position = file.length()
                var newlineCount = 0
                val chunks = ArrayDeque<ByteArray>()

                while (position > 0 && newlineCount <= requestedLines) {
                    val chunkSize = minOf(TAIL_CHUNK_BYTES.toLong(), position).toInt()
                    position -= chunkSize
                    val chunk = ByteArray(chunkSize)
                    file.seek(position)
                    file.readFully(chunk)
                    newlineCount += chunk.count { it == '\n'.code.toByte() }
                    chunks.addFirst(chunk)
                }

                val bytes = ByteArray(chunks.sumOf { it.size })
                var offset = 0
                chunks.forEach { chunk ->
                    chunk.copyInto(bytes, offset)
                    offset += chunk.size
                }

                var start = 0
                var remainingBreaks = newlineCount - requestedLines
                while (remainingBreaks > 0 && start < bytes.size) {
                    if (bytes[start] == '\n'.code.toByte()) remainingBreaks--
                    start++
                }

                bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8).trimEnd('\n', '\r')
                    .ifBlank { LOG_EMPTY }
            }
        }.getOrElse { LOG_READ_ERROR }
    }

    fun clear(): Boolean = synchronized(LOCK) {
        runCatching {
            if (!logDir.exists() && !logDir.mkdirs()) return@runCatching false
            if (!logFile.exists() && !logFile.createNewFile()) return@runCatching false
            RandomAccessFile(logFile, "rw").use { it.setLength(0L) }
            true
        }.getOrDefault(false)
    }

    fun share(context: Context): Boolean = synchronized(LOCK) {
        if (!logFile.exists()) return@synchronized false
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile,
            )
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(intent, "Поделиться журналом синхронизации")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

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
        private const val DEFAULT_TAIL_LINES = 400
        private const val MAX_TAIL_LINES = 500
        private const val TAIL_CHUNK_BYTES = 16 * 1024
        private const val LOG_NOT_CREATED = "Журнал ещё не создан."
        private const val LOG_EMPTY = "Журнал пуст."
        private const val LOG_READ_ERROR = "Не удалось прочитать журнал."
        private val FORBIDDEN_KEYS = setOf(
            "token", "auth_token", "authorization", "payload_signing_key", "payload_signature", "nonce", "records", "body",
        )
    }
}
