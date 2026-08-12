package com.scrolller.adblock;

import android.app.Activity;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * Opens Scrolller's real website unchanged for authentication, then returns to
 * the bundled client using Android Back while preserving the WebView cookie jar.
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
        if ("file".equalsIgnoreCase(uri.getScheme())) loginMode = false;
        // Deliberately do not inject JavaScript, CSS, buttons, blockers, or overlays
        // into Scrolller's website. Authentication is allowed to run untouched.
    }
}
