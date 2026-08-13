package com.scrolller.adblock;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String APP_URL = "file:///android_asset/www/index.html";

    private FrameLayout root;
    private WebView webView;
    private AuthBridge authBridge;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        root.addView(webView, webParams);
        setContentView(root);

        // Android 15/16 enforce edge-to-edge for modern targets. Keep the window
        // edge-to-edge, then explicitly reserve only the real system-bar insets
        // around the WebView. This preserves full left/right media width while
        // guaranteeing the app header and website-login controls never sit
        // underneath the status bar or gesture/navigation area.
        installSystemBarInsets();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        authBridge = new AuthBridge(this, webView);
        webView.addJavascriptInterface(authBridge, "NativeAuth");
        webView.addJavascriptInterface(new MediaBridge(this, webView), "NativeMedia");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                android.util.Log.d("SCROLLLER_UI", consoleMessage.message());
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                authBridge.onPageFinished(url);
            }
        });

        enterAppMode();

        if (savedInstanceState == null) {
            webView.loadUrl(APP_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void installSystemBarInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = windowInsets.getSystemWindowInsetTop();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) webView.getLayoutParams();
            if (lp.topMargin != top || lp.bottomMargin != bottom || lp.leftMargin != 0 || lp.rightMargin != 0) {
                lp.leftMargin = 0;
                lp.rightMargin = 0;
                lp.topMargin = top;
                lp.bottomMargin = bottom;
                webView.setLayoutParams(lp);
            }
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    /**
     * The bundled client uses the entire safe WebView rectangle. The media is
     * still edge-to-edge left/right, while Android owns the reserved top/bottom
     * system-bar strips.
     */
    public void enterAppMode() {
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
            }
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
            webView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            root.requestApplyInsets();
        });
    }

    /**
     * Scrolller login uses the same safe rectangle, so the website's own close,
     * sign-in and popup controls cannot be covered by Android's status bar.
     */
    public void enterWebsiteMode() {
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
            }
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
            webView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            root.requestApplyInsets();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (authBridge != null && authBridge.isLoginMode()) {
            authBridge.returnToApp();
            return;
        }

        webView.evaluateJavascript(
                "(function(){try{return !!(window.ScrolllerNativeBack&&window.ScrolllerNativeBack());}catch(e){return false;}})();",
                result -> {
                    if ("true".equals(result)) return;
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        MainActivity.super.onBackPressed();
                    }
                }
        );
    }
}
