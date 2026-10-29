# Methods called from reader.js through WebView.addJavascriptInterface are invisible to R8;
# without this they can be renamed or removed in release builds and the reader stops responding.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
