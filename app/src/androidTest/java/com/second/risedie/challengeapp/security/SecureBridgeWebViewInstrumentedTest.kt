package com.second.risedie.challengeapp.security

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.second.risedie.challengeapp.bridge.NativeBridgeEndpoint
import com.second.risedie.challengeapp.bridge.SecureBridgeTransport
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs the production transport inside a real installed System WebView, without network or health data. */
@RunWith(AndroidJUnit4::class)
class SecureBridgeWebViewInstrumentedTest {
    private class Endpoint : NativeBridgeEndpoint {
        @Volatile var authenticated = true
        val privilegedCalls = AtomicInteger()
        override fun hasNativeSession() = authenticated
        override fun endWebSession() { authenticated = false }
        override fun invoke(method: String, args: JSONArray): String {
            if (method == "getPermissionState") privilegedCalls.incrementAndGet()
            return "{\"ok\":true}"
        }
    }

    private fun runDocument(url: String, script: String, endpoint: Endpoint) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<String>()
        lateinit var view: WebView
        lateinit var transport: SecureBridgeTransport
        instrumentation.runOnMainSync {
            assertTrue("Update System WebView: WEB_MESSAGE_LISTENER is required",
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            transport = SecureBridgeTransport(view, endpoint)
            assertTrue(transport.install())
            // Test-only result channel; it never delegates to a privileged endpoint.
            WebViewCompat.addWebMessageListener(view, "TestProbe", setOf("*")) { _, message, _, main, _ ->
                if (main) { outcome.set(message.data); completed.countDown() }
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) {
                    transport.invalidateDocument()
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    transport.attachDocument(url)
                    view.evaluateJavascript(script, null)
                }
            }
            view.loadDataWithBaseURL(url, "<!doctype html><html><body></body></html>", "text/html", "UTF-8", null)
        }
        try {
            assertTrue("WebView scenario timed out", completed.await(12, TimeUnit.SECONDS))
            assertEquals("ok", outcome.get())
        } finally {
            instrumentation.runOnMainSync { transport.dispose(); view.destroy() }
        }
    }

    @Test fun iframeCannotInvokeEvenWithCopiedValidDocumentNonce() {
        val endpoint = Endpoint()
        runDocument("https://second.risedie.ru/web", """
            (async function () {
                try {
                    let captured;
                    const stringify = JSON.stringify;
                    JSON.stringify = function (value) {
                        if (value && value.method === 'getPermissionState') captured = value;
                        return stringify.apply(JSON, arguments);
                    };
                    await ChallengeAppBridge.getPermissionState();
                    JSON.stringify = stringify;
                    if (!captured) throw new Error('Missing main-frame invocation');
                    captured.id = 'forged:iframe';
                    const frame = document.createElement('iframe');
                    frame.srcdoc = '<script>GrafitNative.postMessage(' + stringify(stringify(captured)) + ');<\/script>';
                    frame.onload = () => setTimeout(() => TestProbe.postMessage('ok'), 200);
                    document.body.appendChild(frame);
                } catch (error) { TestProbe.postMessage(String(error)); }
            })();
        """.trimIndent(), endpoint)
        assertEquals("Only the main frame may reach the endpoint", 1, endpoint.privilegedCalls.get())
    }

    @Test fun logoutRejectsSubsequentPrivilegedCalls() {
        val endpoint = Endpoint()
        runDocument("https://second.risedie.ru/web", """
            (async function () {
                try {
                    await ChallengeAppBridge.getPermissionState();
                    await ChallengeAppBridge.clearAuthSession();
                    try { await ChallengeAppBridge.getPermissionState(); TestProbe.postMessage('privilege survived logout'); }
                    catch (_) { TestProbe.postMessage('ok'); }
                } catch (error) { TestProbe.postMessage(String(error)); }
            })();
        """.trimIndent(), endpoint)
        assertFalse(endpoint.authenticated)
        assertEquals(1, endpoint.privilegedCalls.get())
    }

    @Test fun externalOriginHasNoNativeTransport() {
        val endpoint = Endpoint()
        runDocument("https://external.invalid/", """
            TestProbe.postMessage(typeof GrafitNative === 'undefined' && typeof ChallengeAppBridge === 'undefined' ? 'ok' : 'bridge leaked');
        """.trimIndent(), endpoint)
        assertEquals(0, endpoint.privilegedCalls.get())
    }
}
