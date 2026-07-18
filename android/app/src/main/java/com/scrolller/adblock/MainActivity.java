package com.scrolller.adblock;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayInputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Create full screen WebView
        webView = new WebView(this);
        setContentView(webView);

        // Hide navigation and status bars for true immersive full screen mode
        setImmersiveMode();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        
        // Autoplay support for videos
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Enable Cookies and localStorage sync for Scrolller account login
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isAdOrCamsUrl(url)) {
                    // Block ad, cams, trackers, and popup requests
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes()));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject custom adblock filters and bypass popup script
                injectCustomFilters(view);
            }
        });

        webView.loadUrl("https://scrolller.com");
    }

    private boolean isAdOrCamsUrl(String url) {
        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("exoclick") || 
               lowerUrl.contains("juicyads") || 
               lowerUrl.contains("realsrv") || 
               lowerUrl.contains("chaturbate") || 
               lowerUrl.contains("stripchat") || 
               lowerUrl.contains("cams") || 
               lowerUrl.contains("doubleclick") || 
               lowerUrl.contains("google-analytics") || 
               lowerUrl.contains("popads") || 
               lowerUrl.contains("trafficjunky") || 
               lowerUrl.contains("onclickads") || 
               lowerUrl.contains("adservice") || 
               lowerUrl.contains("adsystem") || 
               lowerUrl.contains("sponsored") || 
               lowerUrl.contains("a.cant3am.com");
    }

    private void injectCustomFilters(WebView view) {
        // Inject JS style and mutation observer to hide ad layout placeholders and override premium upgrade block dialogs
        String js = "javascript:(function() {" +
                "var style = document.createElement('style');" +
                "style.innerHTML = '" +
                "  iframe, [class*=\"Cam\"], [class*=\"cam\"], [class*=\"sponsored\"], [class*=\"adContainer\"], [class*=\"exoclick\"], [class*=\"juicyads\"], a[href*=\"chaturbate\"], a[href*=\"stripchat\"], div[class*=\"Premium\"], div[class*=\"premium\"], div[class*=\"Upgrade\"], div[class*=\"upgrade\"] { display: none !important; height: 0 !important; width: 0 !important; opacity: 0 !important; pointer-events: none !important; }" +
                "  html, body { overflow: auto !important; position: initial !important; }" +
                "';" +
                "document.head.appendChild(style);" +
                "" +
                "var observer = new MutationObserver(function(mutations) {" +
                "  document.querySelectorAll('div').forEach(function(div) {" +
                "    var className = div.className || \"\";" +
                "    if (typeof className === \"string\") {" +
                "      if (className.includes(\"Premium\") || className.includes(\"premium\") || className.includes(\"Upgrade\") || className.includes(\"upgrade\") || className.includes(\"Modal\") || className.includes(\"Popup\")) {" +
                "        div.style.display = \"none\";" +
                "      }" +
                "    }" +
                "  });" +
                "});" +
                "observer.observe(document.documentElement, { childList: true, subtree: true });" +
                "})()";
        view.loadUrl(js);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void setImmersiveMode() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setImmersiveMode();
        }
    }
}
