package com.second.risedie.challengeapp.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-lifetime connectivity observer. Durable background/offline recovery belongs to WorkManager. */
class HealthNetworkRecoveryMonitor(
    context: Context,
    private val onRestored: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val connected = AtomicBoolean(false)
    private var registered = false
    private var lastTriggerMs = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated) {
                if (!connected.getAndSet(true)) dispatchRecovery()
            } else if (!isValidatedNetwork()) {
                if (connected.getAndSet(false)) onDisconnected()
            }
        }

        override fun onLost(network: Network) {
            if (!isValidatedNetwork() && connected.getAndSet(false)) onDisconnected()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        // An existing connection is covered by the regular onResume sync.
        connected.set(isValidatedNetwork())
        registered = true
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            registered = false
            onDisconnected() // WorkManager is the fallback when callbacks are unavailable.
            return
        }
        if (!connected.get()) onDisconnected() // enqueue a persistent network-constrained recovery
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { manager.unregisterNetworkCallback(callback) }
        connected.set(false)
    }

    private fun isValidatedNetwork(): Boolean {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Synchronized
    private fun dispatchRecovery() {
        if (!registered) return
        val now = SystemClock.elapsedRealtime()
        if (lastTriggerMs != 0L && now - lastTriggerMs < MIN_RECONNECT_INTERVAL_MS) return
        lastTriggerMs = now
        onRestored()
    }

    companion object {
        private const val MIN_RECONNECT_INTERVAL_MS = 10_000L
    }
}
