package com.second.risedie.challengeapp.security

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Classic Play Integrity request bound to the server-issued activity nonce. */
class PlayIntegrityTokenProvider(context: Context) {
    private val manager = IntegrityManagerFactory.create(context.applicationContext)

    fun tokenForNonce(nonce: String, timeoutSeconds: Long = 8): String? {
        if (nonce.isBlank()) return null

        val latch = CountDownLatch(1)
        var token: String? = null
        manager.requestIntegrityToken(
            IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build(),
        )
            .addOnSuccessListener { response ->
                token = response.token().takeIf { it.isNotBlank() }
                latch.countDown()
            }
            .addOnFailureListener { latch.countDown() }

        if (!latch.await(timeoutSeconds.coerceIn(1, 15), TimeUnit.SECONDS)) return null
        return token
    }
}
