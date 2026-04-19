package com.second.risedie.challengeapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    private lateinit var webView: WebView
    private lateinit var bridge: ChallengeAppBridge
    private val allowedHosts: Set<String> by lazy { parseAllowedHosts(BuildConfig.APP_ALLOWED_HOSTS_JSON) }

    private val healthPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            bridge.onPermissionsFlowFinished()
        }

    private val activityRecognitionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            bridge.onActivityRecognitionPermissionResult(granted)
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
            onLaunchActivityRecognitionPermission = { permission ->
                activityRecognitionPermissionLauncher.launch(permission)
            },
            isActivityRecognitionGranted = { isActivityRecognitionGranted() },
            onNotifyJavascript = { eventJson -> dispatchJavascriptEvent(eventJson) },
        )

        configureWebView(webView)
        webView.addJavascriptInterface(bridge, "ChallengeAppBridge")

        if (savedInstanceState == null) {
            webView.loadUrl(BuildConfig.APP_WEB_URL)
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
            cacheMode = WebSettings.LOAD_DEFAULT
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
