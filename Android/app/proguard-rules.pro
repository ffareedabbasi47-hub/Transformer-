# No reflection-based libraries are used; defaults are sufficient.

# OkHttp / Okio (WebSocket client) ship their own consumer rules; silence optional-platform warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 3D globe: methods called from JavaScript by name must survive shrinking
-keepclassmembers class com.stsmn.monitor.ui.GlobeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
