package com.scrolller.adblock;

import android.app.Activity;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * Opens Scrolller's real website inside the existing WebView for authentication,
 * then returns to the bundled app while preserving the WebView cookie/session jar.
 */
public final class AuthBridge {
    private static final String APP_URL = "file:///android_asset/www/index.html";
    private static final String WEBSITE_URL = "https://scrolller.com/";

    private final Activity activity;
    private final WebView webView;
    private volatile boolean loginMode = false;

    public AuthBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void openLogin() {
        loginMode = true;
        activity.runOnUiThread(() -> webView.loadUrl(WEBSITE_URL));
    }

    @JavascriptInterface
    public void returnToApp() {
        loginMode = false;
        activity.runOnUiThread(() -> webView.loadUrl(APP_URL));
    }

    public boolean isLoginMode() {
        return loginMode;
    }

    public void onPageFinished(String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            loginMode = false;
            return;
        }

        if (loginMode && host != null && (host.equals("scrolller.com") || host.endsWith(".scrolller.com"))) {
            injectReturnButton();
        }
    }

    private void injectReturnButton() {
        String js = "(function(){" +
                "if(document.getElementById('scrolller-pro-return'))return;" +
                "function add(){" +
                " if(document.getElementById('scrolller-pro-return'))return;" +
                " var b=document.createElement('button');" +
                " b.id='scrolller-pro-return';" +
                " b.textContent='Return to Scrolller Pro';" +
                " b.style.cssText='position:fixed;right:12px;top:12px;z-index:2147483647;padding:11px 15px;border:0;border-radius:12px;background:#111;color:#fff;font:600 13px sans-serif;box-shadow:0 4px 18px rgba(0,0,0,.45)';" +
                " b.onclick=function(){NativeAuth.returnToApp();};" +
                " (document.body||document.documentElement).appendChild(b);" +
                "}" +
                "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',add);else add();" +
                "})();";
        webView.evaluateJavascript(js, null);
    }
}
