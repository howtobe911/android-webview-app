package com.second.risedie.challengeapp.bridge

import org.json.JSONArray

interface NativeBridgeEndpoint {
    fun hasNativeSession(): Boolean
    fun endWebSession()
    fun invoke(method: String, args: JSONArray): String
}
