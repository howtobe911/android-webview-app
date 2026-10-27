package com.second.risedie.challengeapp.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reschedule a constrained one-off recovery after the phone has booted and unlocked. */
class HealthBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Does not access Health Connect or the network on the broadcast thread.
        // WorkManager waits for connectivity and handles retries after the broadcast ends.
        HealthSyncWorker.enqueueImmediate(context)
    }
}
