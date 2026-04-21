package com.second.risedie.challengeapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.second.risedie.challengeapp.BuildConfig
import com.second.risedie.challengeapp.R
import com.second.risedie.challengeapp.bridge.ChallengeAppBridge
import org.json.JSONArray
import org.json.JSONObject

class ChallengeWebViewActivity : ComponentActivity() {

    companion object {
        private const val LOG_TAG = "GrafitActivitySync"
    }

    private lateinit var webView: WebView
    private lateinit var bridge: ChallengeAppBridge
    private val allowedHosts: Set<String> by lazy { parseAllowedHosts(BuildConfig.APP_ALLOWED_HOSTS_JSON) }

    private val healthPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            bridge.onPermissionsFlowFinished()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_challenge_webview)

        webView = findViewById(R.id.challengeWebView)

        bridge = ChallengeAppBridge(
            activity = this,
            onLaunchPermissions = { intent -> healthPermissionLauncher.launch(intent) },
            isActivityRecognitionGranted = { isActivityRecognitionGranted() },
            onNotifyJavascript = { eventJson -> dispatchJavascriptEvent(eventJson) },
            onDebugJavascript = { eventJson -> dispatchJavascriptDebugEvent(eventJson) },
        )

        configureWebView(webView)
        webView.addJavascriptInterface(bridge, "ChallengeAppBridge")

        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        webView.clearSslPreferences()

        if (savedInstanceState == null) {
            val launchUrl = Uri.parse(BuildConfig.APP_WEB_URL)
                .buildUpon()
                .appendQueryParameter("app_version", BuildConfig.VERSION_NAME)
                .appendQueryParameter("app_build", BuildConfig.VERSION_CODE.toString())
                .appendQueryParameter("nocache", System.currentTimeMillis().toString())
                .build()
                .toString()
            Log.d(LOG_TAG, "webview:loadUrl url=$launchUrl")
            webView.loadUrl(launchUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bridge.onHostResumed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(target: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, true)

        with(target.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false
            displayZoomControls = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = userAgentString + " ChallengeAppWebView/3.0"
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        target.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(LOG_TAG, "webconsole:${it.messageLevel()} ${it.message()} @${it.sourceId()}:${it.lineNumber()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        target.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                return !isAllowedInternalUrl(url).also { allowed ->
                    if (!allowed) openExternal(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(LOG_TAG, "webview:onPageFinished url=$url")
                notifyBridgeReady()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    dispatchJavascriptEvent(
                        JSONObject()
                            .put("available", true)
                            .put("granted", false)
                            .put("pending", false)
                            .put("message", error?.description?.toString() ?: "Не удалось загрузить приложение.")
                            .toString()
                    )
                }
            }
        }
    }

    private fun dispatchJavascriptEvent(eventJson: String) {
        Log.d(LOG_TAG, "dispatchJavascriptEvent payload=$eventJson")
        val quoted = JSONObject.quote(eventJson)
        val script = """
            (function() {
                var payload = JSON.parse($quoted);
                window.dispatchEvent(new CustomEvent('challengeapp:permissions-changed', { detail: payload }));
            })();
        """.trimIndent()

        webView.post {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
        }
    }




    private fun dispatchJavascriptDebugEvent(eventJson: String) {
        Log.d(LOG_TAG, "dispatchJavascriptDebugEvent payload=$eventJson")
        val quoted = JSONObject.quote(eventJson)
        val script = """
            (function() {
                var payload = JSON.parse($quoted);
                window.dispatchEvent(new CustomEvent('challengeapp:debug-log', { detail: payload }));
            })();
        """.trimIndent()

        webView.post {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun notifyBridgeReady() {
        val script = "window.dispatchEvent(new CustomEvent('grafit-native-bridge-ready'));true;"
        webView.post {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun isAllowedInternalUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host in allowedHosts
    }

    private fun isActivityRecognitionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openExternal(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun parseAllowedHosts(rawJson: String): Set<String> {
        val array = JSONArray(rawJson)
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            result += array.getString(index).lowercase()
        }
        return result
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("ChallengeAppBridge")
        webView.stopLoading()
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        bridge.dispose()
        webView.destroy()
        super.onDestroy()
    }
}
