package com.second.risedie.challengeapp.sync

import android.content.Context

/** Persistent device-wide circuit breaker for Health Connect's request quota. */
internal class HealthConnectQuotaGuard(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remainingMillis(): Long = synchronized(lock) {
        (preferences.getLong(KEY_RETRY_AT, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun recordQuotaExceeded(): Long = synchronized(lock) {
        val failures = (preferences.getInt(KEY_FAILURES, 0) + 1).coerceAtMost(10)
        val minutes = (BASE_DELAY_MINUTES * (1L shl (failures - 1))).coerceAtMost(MAX_DELAY_MINUTES)
        val retryAt = System.currentTimeMillis() + minutes * 60_000L
        preferences.edit().putInt(KEY_FAILURES, failures).putLong(KEY_RETRY_AT, retryAt).apply()
        retryAt
    }

    fun recordSuccessfulRead() = synchronized(lock) {
        preferences.edit().remove(KEY_FAILURES).remove(KEY_RETRY_AT).apply()
    }

    companion object {
        private val lock = Any()
        private const val PREFS = "grafit_native_health_connect_quota"
        private const val KEY_FAILURES = "consecutive_quota_failures"
        private const val KEY_RETRY_AT = "next_allowed_read_epoch_ms"
        private const val BASE_DELAY_MINUTES = 15L
        private const val MAX_DELAY_MINUTES = 360L

        fun isQuotaExceeded(error: Throwable): Boolean {
            var cause: Throwable? = error
            while (cause != null) {
                val message = cause.message.orEmpty().lowercase()
                if ("rate limited" in message || "quota has been exceeded" in message ||
                    "rate limit" in message && "quota" in message) return true
                cause = cause.cause
            }
            return false
        }
    }
}
