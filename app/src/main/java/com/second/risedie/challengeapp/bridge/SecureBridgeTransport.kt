package com.second.risedie.challengeapp.bridge

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.second.risedie.challengeapp.BuildConfig
import com.second.risedie.challengeapp.security.TrustedWebOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only native-verified main-frame messages can reach the privileged service. */
class SecureBridgeTransport(private val view: WebView, private val bridge: NativeBridgeEndpoint) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authMutex = Mutex()
    @Volatile private var document: String? = null
    @Volatile private var sessionEpoch = 0L
    private val inFlight = mutableSetOf<String>()
    private var installed = false
    private val bootstrap = view.context.assets.open("secure-native-bridge.js").bufferedReader().use { it.readText() }

    fun install(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        val origins = TrustedWebOrigin.allowedHosts(BuildConfig.APP_ALLOWED_HOSTS_JSON).map { "https://$it" }.toSet()
        WebViewCompat.addWebMessageListener(view, "GrafitNative", origins) { _, message, sourceOrigin, isMainFrame, reply ->
            val origin = TrustedWebOrigin.canonicalOrigin(sourceOrigin.toString())
            if (!isMainFrame || origin == null || origin != TrustedWebOrigin.canonicalOrigin(view.url)) return@addWebMessageListener
            val raw = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
            if (raw.length > 65536) return@addWebMessageListener
            val request = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
            val token = document ?: return@addWebMessageListener
            if (request.optString("document") != token) return@addWebMessageListener
            val id = request.optString("id")
            val method = request.optString("method")
            if (!id.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) return@addWebMessageListener
            if (method == "clearAuthSession") {
                sessionEpoch++
                inFlight.clear()
                bridge.endWebSession()
            }
            val epoch = sessionEpoch
            val requestKey = "$token:$epoch:$id"
            if (inFlight.size >= 32 || !inFlight.add(requestKey)) return@addWebMessageListener
            val args = request.optJSONArray("args") ?: JSONArray()
            scope.launch {
                try {
                    check(document == token && epoch == sessionEpoch) { "DOCUMENT_CHANGED" }
                    val result = if (method in setOf("authenticate", "refreshAccessToken", "clearAuthSession")) {
                        authMutex.withLock {
                            check(document == token && epoch == sessionEpoch) { "SESSION_CHANGED" }
                            withContext(Dispatchers.IO) {
                                check(document == token && epoch == sessionEpoch) { "SESSION_CHANGED" }
                                invoke(method, args)
                            }
                        }
                    } else {
                        // Preserve the old bridge's off-main execution contract: file reads,
                        // DataStore snapshots and configuration must not block WebView rendering.
                        withContext(Dispatchers.IO) {
                            check(document == token && epoch == sessionEpoch) { "SESSION_CHANGED" }
                            check(method == "getBridgeInfo" || bridge.hasNativeSession()) { "AUTH_REQUIRED" }
                            invoke(method, args)
                        }
                    }
                    if (document == token && epoch == sessionEpoch && origin == TrustedWebOrigin.canonicalOrigin(view.url)) {
                        reply.postMessage(JSONObject().put("id", id).put("ok", true).put("result", result).toString())
                    }
                } catch (error: CancellationException) { throw error
                } catch (_: Exception) {
                    if (document == token && epoch == sessionEpoch) reply.postMessage(JSONObject().put("id", id).put("ok", false).put("error", "NATIVE_REQUEST_FAILED").toString())
                } finally { inFlight.remove(requestKey) }
            }
        }
        installed = true
        return true
    }

    fun invalidateDocument() { document = null; inFlight.clear() }

    fun attachDocument(url: String?) {
        if (!installed || url == null || url != view.url || !TrustedWebOrigin.isTrustedUrl(url)) return
        // onPageFinished may fire more than once for a document: keep outstanding requests alive.
        if (document != null) return
        val token = UUID.randomUUID().toString()
        document = token
        view.evaluateJavascript(bootstrap.replace("__DOCUMENT_JSON__", JSONObject.quote(token)), null)
    }

    fun dispose() {
        invalidateDocument()
        scope.cancel()
        if (installed) WebViewCompat.removeWebMessageListener(view, "GrafitNative")
    }

    private fun invoke(method: String, args: JSONArray): String = bridge.invoke(method, args)
}
