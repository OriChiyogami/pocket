-keep class io.pocket.host.runner.PocketBridge { *; }
-keepclassmembers class io.pocket.host.runner.PocketBridge {
    @android.webkit.JavascriptInterface <methods>;
}
