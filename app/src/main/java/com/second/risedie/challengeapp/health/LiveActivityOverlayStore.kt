package com.second.risedie.challengeapp.health

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.Instant

private val Context.liveActivityOverlayDataStore by preferencesDataStore(name = "grafit_live_activity_overlay")

class LiveActivityOverlayStore(private val context: Context) {
    suspend fun read(): LiveActivityOverlayState {
        val data = context.liveActivityOverlayDataStore.data.first()
        return LiveActivityOverlayState(
            activityDate = data[ACTIVITY_DATE] ?: "",
            serverVerifiedSteps = data[SERVER_VERIFIED_STEPS] ?: 0L,
            serverVerifiedRunMeters = data[SERVER_VERIFIED_RUN_METERS] ?: 0L,
            serverVerifiedRunSeconds = data[SERVER_VERIFIED_RUN_SECONDS] ?: 0L,
            localBaseSteps = data[LOCAL_BASE_STEPS] ?: 0L,
            sensorBaseValue = data[SENSOR_BASE_VALUE] ?: 0f,
            sensorLastValue = data[SENSOR_LAST_VALUE] ?: 0f,
            realtimeDeltaSteps = data[REALTIME_DELTA_STEPS] ?: 0L,
            displaySteps = data[DISPLAY_STEPS] ?: 0L,
            awaitingFreshBaseline = data[AWAITING_FRESH_BASELINE] ?: false,
            lastHealthConnectReadAt = data[LAST_HEALTH_CONNECT_READ_AT]?.let { Instant.parse(it) },
            updatedAt = data[UPDATED_AT]?.let { Instant.parse(it) } ?: Instant.now(),
        )
    }

    suspend fun write(state: LiveActivityOverlayState) {
        context.liveActivityOverlayDataStore.edit { data ->
            data[ACTIVITY_DATE] = state.activityDate
            data[SERVER_VERIFIED_STEPS] = state.serverVerifiedSteps
            data[SERVER_VERIFIED_RUN_METERS] = state.serverVerifiedRunMeters
            data[SERVER_VERIFIED_RUN_SECONDS] = state.serverVerifiedRunSeconds
            data[LOCAL_BASE_STEPS] = state.localBaseSteps
            data[SENSOR_BASE_VALUE] = state.sensorBaseValue
            data[SENSOR_LAST_VALUE] = state.sensorLastValue
            data[REALTIME_DELTA_STEPS] = state.realtimeDeltaSteps
            data[DISPLAY_STEPS] = state.displaySteps
            data[AWAITING_FRESH_BASELINE] = state.awaitingFreshBaseline
            state.lastHealthConnectReadAt?.let { data[LAST_HEALTH_CONNECT_READ_AT] = it.toString() }
            data[UPDATED_AT] = state.updatedAt.toString()
        }
    }

    companion object {
        private val ACTIVITY_DATE = stringPreferencesKey("activity_date")
        private val SERVER_VERIFIED_STEPS = longPreferencesKey("server_verified_steps")
        private val SERVER_VERIFIED_RUN_METERS = longPreferencesKey("server_verified_run_meters")
        private val SERVER_VERIFIED_RUN_SECONDS = longPreferencesKey("server_verified_run_seconds")
        private val LOCAL_BASE_STEPS = longPreferencesKey("local_base_steps")
        private val SENSOR_BASE_VALUE = floatPreferencesKey("sensor_base_value")
        private val SENSOR_LAST_VALUE = floatPreferencesKey("sensor_last_value")
        private val REALTIME_DELTA_STEPS = longPreferencesKey("realtime_delta_steps")
        private val DISPLAY_STEPS = longPreferencesKey("display_steps")
        private val AWAITING_FRESH_BASELINE = booleanPreferencesKey("awaiting_fresh_baseline")
        private val LAST_HEALTH_CONNECT_READ_AT = stringPreferencesKey("last_health_connect_read_at")
        private val UPDATED_AT = stringPreferencesKey("updated_at")
    }
}
