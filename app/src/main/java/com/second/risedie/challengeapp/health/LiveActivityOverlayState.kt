package com.second.risedie.challengeapp.health

import java.time.Instant

data class LiveActivityOverlayState(
    val activityDate: String,
    val serverVerifiedSteps: Long = 0,
    val serverVerifiedRunMeters: Long = 0,
    val serverVerifiedRunSeconds: Long = 0,
    val localBaseSteps: Long = 0,
    val sensorBaseValue: Float = 0f,
    val sensorLastValue: Float = 0f,
    val realtimeDeltaSteps: Long = 0,
    val displaySteps: Long = 0,
    val lastHealthConnectReadAt: Instant? = null,
    val updatedAt: Instant = Instant.now(),
)
