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
import android.webkit.ValueCallback
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.second.risedie.challengeapp.BuildConfig
import com.second.risedie.challengeapp.R
import com.second.risedie.challengeapp.bridge.ChallengeAppBridge
import com.second.risedie.challengeapp.security.TrustedWebOrigin
import com.second.risedie.challengeapp.security.NativeAuthSession
import com.second.risedie.challengeapp.push.GraFitFirebaseMessagingService
import org.json.JSONObject

class ChallengeWebViewActivity : ComponentActivity() {

    companion object {
        private const val LOG_TAG = "GrafitActivitySync"
        const val EXTRA_PUSH_ROUTE = "grafit_push_route"
        const val EXTRA_INBOX_ITEM_ID = "grafit_inbox_item_id"
        @Volatile var isInForeground: Boolean = false
    }

    private lateinit var webView: WebView
    private lateinit var bridge: ChallengeAppBridge
    private var trustedDocumentReady = false
    private var webSessionRestored = false
    private var internalNavigationSequence = 0
    private var pendingInternalRoute: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val healthPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            bridge.onPermissionsFlowFinished()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            bridge.onNotificationPermissionResult(granted)
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            fileChooserCallback = null
            callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
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

        // The app is edge-to-edge (targetSdk 36), so the WebView itself must be
        // kept inside the real system-bar area. Otherwise fixed web controls are
        // rendered underneath Android's status/navigation bars on devices where
        // those bars are taller (notably 3-button navigation).
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = systemBars.top
                bottomMargin = systemBars.bottom
                leftMargin = systemBars.left
                rightMargin = systemBars.right
            }
            insets
        }
        ViewCompat.requestApplyInsets(webView)

        bridge = ChallengeAppBridge(
            activity = this,
            onLaunchPermissions = { intent -> healthPermissionLauncher.launch(intent) },
            onLaunchActivityRecognitionPermission = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    bridge.onActivityRecognitionPermissionResult(true)
                } else {
                    activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            },
            isActivityRecognitionGranted = { isActivityRecognitionGranted() },
            onNotifyJavascript = { eventJson -> dispatchJavascriptEvent(eventJson) },
            onDebugJavascript = { eventJson -> dispatchJavascriptDebugEvent(eventJson) },
            onActivitySyncJavascript = { eventJson -> dispatchActivitySyncEvent(eventJson) },
            onPrerequisitesJavascript = { eventJson -> dispatchPrerequisitesEvent(eventJson) },
            onBackgroundReadinessJavascript = { eventJson -> dispatchBackgroundReadinessEvent(eventJson) },
            onLaunchNotificationPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )

        configureWebView(webView)
        webView.addJavascriptInterface(bridge, "ChallengeAppBridge")

        Thread {
            runCatching { NativeAuthSession(applicationContext).restoreWebSession() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                webSessionRestored = true
                if (savedInstanceState == null) {
                    val launchUrl = Uri.parse(BuildConfig.APP_WEB_URL)
                        .buildUpon()
                        .appendQueryParameter("app_version", BuildConfig.VERSION_NAME)
                        .appendQueryParameter("app_build", BuildConfig.VERSION_CODE.toString())
                        .build()
                        .toString()
                    val resolvedLaunchUrl = resolvePushLaunchUrl(launchUrl, intent)
                    if (BuildConfig.DEBUG) Log.d(LOG_TAG, "webview:loadUrl url=$resolvedLaunchUrl")
                    webView.loadUrl(resolvedLaunchUrl)
                } else {
                    webView.restoreState(savedInstanceState)
                    pendingInternalRoute?.let { navigateInternalOrLoad(it) }
                }
                pendingInternalRoute = null
            }
        }.start()

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
        isInForeground = true
        createNotificationChannel()
        bridge.onHostResumed()
    }

    override fun onStop() {
        isInForeground = false
        bridge.onHostStopped()
        super.onStop()
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val route = sanitizePushRoute(intent.getStringExtra(EXTRA_PUSH_ROUTE) ?: intent.getStringExtra("route")) ?: return
        navigateInternalOrLoad(route)
    }

    private fun navigateInternalOrLoad(rawRoute: String) {
        val route = sanitizePushRoute(rawRoute) ?: return
        // A push arriving during cold auth restore is consumed by the initial load.
        if (!webSessionRestored) { pendingInternalRoute = route; return }
        val target = Uri.parse(route)
        val url = Uri.parse(BuildConfig.APP_WEB_URL).buildUpon()
            .encodedPath(target.encodedPath).encodedQuery(target.encodedQuery)
            .encodedFragment(target.encodedFragment).build().toString()
        if (!isAllowedInternalUrl(Uri.parse(url))) return
        val navigationId = ++internalNavigationSequence
        val fallback = {
            if (navigationId == internalNavigationSequence && !isFinishing && !isDestroyed) webView.loadUrl(url)
        }
        val current = webView.url
        if (!trustedDocumentReady || current == null || !isAllowedInternalUrl(Uri.parse(current))) {
            fallback(); return
        }
        val quotedRoute = JSONObject.quote(route)
        val quotedUrl = JSONObject.quote(url)
        val script = """
            (function() {
                try {
                    if (document.readyState === 'loading' || typeof window.GrafitNavigateInternal !== 'function') return false;
                    window.GrafitNavigateInternal($quotedRoute, {fallback:true}).catch(function() { window.location.assign($quotedUrl); });
                    return true;
                } catch (_) { return false; }
            })();
        """.trimIndent()
        try {
            webView.evaluateJavascript(script) { result -> if (result != "true") fallback() }
        } catch (_: Exception) { fallback() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel(GraFitFirebaseMessagingService.CHANNEL_ID, "GraFit", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun resolvePushLaunchUrl(defaultUrl: String, intent: Intent?): String {
        val route = sanitizePushRoute(intent?.getStringExtra(EXTRA_PUSH_ROUTE) ?: intent?.getStringExtra("route")) ?: return defaultUrl
        return Uri.parse(defaultUrl).buildUpon().encodedPath(route).build().toString()
    }

    private fun sanitizePushRoute(route: String?): String? {
        val value = route?.trim().orEmpty()
        return value.takeIf { it.startsWith("/web/") && !it.startsWith("//") && !it.contains("://") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(target: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, false)

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
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (error: ActivityNotFoundException) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    false
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    if (BuildConfig.DEBUG) Log.d(LOG_TAG, "webconsole:${it.messageLevel()} ${it.message()} @${it.sourceId()}:${it.lineNumber()}")
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

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                trustedDocumentReady = false
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                trustedDocumentReady = url != null && isAllowedInternalUrl(Uri.parse(url))
                if (BuildConfig.DEBUG) Log.d(LOG_TAG, "webview:onPageFinished url=$url")
                notifyBridgeReady()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    trustedDocumentReady = false
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
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, "dispatchJavascriptEvent payload=$eventJson")
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





    private fun dispatchActivitySyncEvent(eventJson: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, "dispatchActivitySyncEvent payload=$eventJson")
        val quoted = JSONObject.quote(eventJson)
        val script = """
            (function() {
                var payload = JSON.parse($quoted);
                window.dispatchEvent(new CustomEvent('grafit:activity-sync', { detail: payload }));
            })();
        """.trimIndent()

        webView.post {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun dispatchPrerequisitesEvent(eventJson: String) {
        runOnUiThread {
            val quoted = JSONObject.quote(eventJson)
            webView.evaluateJavascript(
                """
                (function() {
                    try {
                        var payload = JSON.parse($quoted);
                        window.dispatchEvent(new CustomEvent('challengeapp:prerequisites-changed', { detail: payload }));
                    } catch (_) {}
                })();
                """.trimIndent(),
                null,
            )
        }
    }

    private fun dispatchBackgroundReadinessEvent(eventJson: String) {
        runOnUiThread {
            val quoted = JSONObject.quote(eventJson)
            webView.evaluateJavascript(
                """
                (function() {
                    try {
                        var payload = JSON.parse($quoted);
                        window.dispatchEvent(new CustomEvent('challengeapp:background-readiness-changed', { detail: payload }));
                    } catch (_) {}
                })();
                """.trimIndent(),
                null,
            )
        }
    }

    private fun dispatchJavascriptDebugEvent(eventJson: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, "dispatchJavascriptDebugEvent payload=$eventJson")
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

    private fun isAllowedInternalUrl(uri: Uri): Boolean = TrustedWebOrigin.isTrustedUrl(uri.toString())

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

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        webView.removeJavascriptInterface("ChallengeAppBridge")
        webView.stopLoading()
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        bridge.dispose()
        webView.destroy()
        super.onDestroy()
    }
}
