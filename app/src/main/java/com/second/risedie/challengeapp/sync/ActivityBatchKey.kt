package com.second.risedie.challengeapp.sync

import java.util.UUID

/** A retry keeps its opaque key; a new logical send receives a new one. */
object ActivityBatchKey {
    fun resolve(existingKey: String?, externalBatchId: String?): String =
        existingKey?.takeIf { it.isNotBlank() }
            ?: externalBatchId?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
}
