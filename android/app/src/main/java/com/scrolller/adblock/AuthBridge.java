package com.scrolller.adblock;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Uses Scrolller's own website for authentication only. The normal app remains
 * a direct API/media client. Login details are copied from Scrolller's own
 * localStorage after sign-in so direct API calls can use the same session.
 */
public final class AuthBridge {
    static final String PREFS_NAME = "scrolller_auth";
    static final String TOKEN_KEY = "token";

    private static final String APP_URL = "file:///android_asset/www/index.html";
    private static final String WEBSITE_URL = "https://scrolller.com/?show_login=1";

    private final Activity activity;
    private final WebView webView;
    private final SharedPreferences prefs;
    private volatile boolean loginMode = false;

    public AuthBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public void openLogin() {
        loginMode = true;
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).enterWebsiteMode();
            }
            webView.loadUrl(WEBSITE_URL);
        });
    }

    @JavascriptInterface
    public void returnToApp() {
        activity.runOnUiThread(this::captureLoginAndReturn);
    }

    @JavascriptInterface
    public String getToken() {
        return prefs.getString(TOKEN_KEY, "");
    }

    public boolean isLoginMode() {
        return loginMode;
    }

    public void onPageFinished(String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            loginMode = false;
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).enterAppMode();
            }
            return;
        }

        if (loginMode) {
            // Scrolller can falsely surface its own ad-block warning inside an
            // embedded browser. This only dismisses that warning UI; it does not
            // block ads, requests, scripts, or any website resources.
            dismissFalseAdblockWarning();
        }
    }

    private void captureLoginAndReturn() {
        if (!loginMode) {
            finishReturn();
            return;
        }
        String js = "(function(){try{return localStorage.getItem('SCROLLLER_LOGIN_DEV')||'';}catch(e){return '';}})();";
        webView.evaluateJavascript(js, value -> {
            saveWebsiteLogin(value);
            finishReturn();
        });
    }

    private void saveWebsiteLogin(String evaluatedValue) {
        try {
            Object decoded = new JSONTokener(evaluatedValue == null ? "\"\"" : evaluatedValue).nextValue();
            String raw = decoded instanceof String ? (String) decoded : "";
            if (raw.isEmpty()) return;
            JSONObject details = new JSONObject(raw);
            String token = details.optString("token", "");
            if (!token.isEmpty()) {
                prefs.edit().putString(TOKEN_KEY, token).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private void finishReturn() {
        loginMode = false;
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).enterAppMode();
        }
        webView.loadUrl(APP_URL);
    }

    private void dismissFalseAdblockWarning() {
        String js = "(function(){" +
                "function hide(){try{" +
                "var nodes=[].slice.call(document.querySelectorAll('[role=dialog],[aria-modal=true]'));" +
                "var hit=nodes.filter(function(n){var t=(n.innerText||'').toLowerCase();return t.indexOf('adblock')>=0||t.indexOf('ad block')>=0;})" +
                ".sort(function(a,b){return (a.innerText||'').length-(b.innerText||'').length;})[0];" +
                "if(hit){hit.style.setProperty('display','none','important');hit.setAttribute('aria-hidden','true');document.documentElement.style.overflow='auto';document.body.style.overflow='auto';}" +
                "}catch(e){}}" +
                "hide();setTimeout(hide,350);setTimeout(hide,1100);setTimeout(hide,2200);" +
                "})();";
        webView.evaluateJavascript(js, null);
    }
}
