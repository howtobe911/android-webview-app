# WebView bridge methods must stay accessible from JavaScript.
-keepclassmembers class com.second.risedie.challengeapp.bridge.ChallengeAppBridge {
    @android.webkit.JavascriptInterface <methods>;
}
