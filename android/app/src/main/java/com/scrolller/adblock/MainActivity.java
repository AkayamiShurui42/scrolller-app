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
        
        // Force high-quality desktop video streams by setting desktop user agent
        String desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        settings.setUserAgentString(desktopUserAgent);

        // Scale pages properly like a desktop browser
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

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
                
                // Bypass anti-adblock detection checks by returning successful mock empty scripts
                if (url.contains("doubleclick.net") || 
                    url.contains("googlesyndication.com") || 
                    url.contains("google-analytics.com") || 
                    url.contains("googletagmanager.com")) {
                    return new WebResourceResponse("application/javascript", "UTF-8", 
                            new ByteArrayInputStream("console.log('Mocked Ad Network response for Adblock Detection bypass');".getBytes()));
                }
                
                // Block other ad networks, webcams, and trackers
                if (isAdOrCamsUrl(url)) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes()));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectCustomFilters(view);
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
               lowerUrl.contains("popads") || 
               lowerUrl.contains("trafficjunky") || 
               lowerUrl.contains("onclickads") || 
               lowerUrl.contains("adservice") || 
               lowerUrl.contains("adsystem") || 
               lowerUrl.contains("sponsored") || 
               lowerUrl.contains("sponsor") || 
               lowerUrl.contains("promotion") || 
               lowerUrl.contains("promoted") || 
               lowerUrl.contains("a.cant3am.com");
    }

    private void injectCustomFilters(WebView view) {
        // Inject JS style, MutationObserver and fetch hook to strip ads, live cams, adblock popups, and premium walls
        // Excluding 'login' or 'auth' modals to keep sign-in functionality fully operational
        String js = "(function() {" +
                "  if (window.adblockFiltersInjected) return;" +
                "  window.adblockFiltersInjected = true;" +
                "  " +
                "  var style = document.createElement('style');" +
                "  style.innerHTML = '" +
                "    iframe, ins, [class*=\"Cam\"], [class*=\"cam\"], [class*=\"sponsored\"], [class*=\"sponsor\"], [class*=\"Sponsor\"], [class*=\"promoted\"], [class*=\"Promoted\"], [class*=\"promotion\"], [class*=\"Promotion\"], [class*=\"adContainer\"], [class*=\"exoclick\"], [class*=\"juicyads\"], a[href*=\"chaturbate\"], a[href*=\"stripchat\"], [class*=\"Premium\"], [class*=\"Upgrade\"], [class*=\"paywall\"], [class*=\"Paywall\"], [class*=\"Adblock\"], [class*=\"AdBlock\"], [class*=\"ad-block\"], [class*=\"Billing\"] { display: none !important; height: 0 !important; width: 0 !important; opacity: 0 !important; pointer-events: none !important; }" +
                "    div[class*=\"slide\"]:has(iframe), div[class*=\"slide\"]:has(a[href*=\"chaturbate\"]), div[class*=\"slide\"]:has(a[href*=\"stripchat\"]), div[class*=\"slide\"]:has([class*=\"Cam\"]), div[class*=\"slide\"]:has([class*=\"cam\"]), div[class*=\"slide\"]:has([class*=\"sponsored\"]), div[class*=\"slide\"]:has([class*=\"sponsor\"]), div[class*=\"slide\"]:has([class*=\"Sponsor\"]), div[class*=\"slide\"]:has([class*=\"promoted\"]), div[class*=\"slide\"]:has([class*=\"Promoted\"]), div[class*=\"slide\"]:has([class*=\"promotion\"]), div[class*=\"slide\"]:has([class*=\"Promotion\"]), div[class*=\"card\"]:has(iframe), div[class*=\"card\"]:has([class*=\"cam\"]), div[class*=\"card\"]:has([class*=\"sponsored\"]) { display: none !important; height: 0 !important; width: 0 !important; visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; }" +
                "    html, body { overflow: auto !important; position: initial !important; pointer-events: auto !important; }" +
                "    /* Client-side Premium Collection Unlock Bypass */" +
                "    div[class*=\"fallbackContainer\"], div[class*=\"paidFallbackContainer\"], div[class*=\"exclusiveBadge\"] { display: none !important; height: 0 !important; width: 0 !important; opacity: 0 !important; pointer-events: none !important; }" +
                "    div[class*=\"hiddenContentContainer\"] { display: block !important; filter: none !important; opacity: 1 !important; }" +
                "    img, video, [class*=\"imageMedia\"], [class*=\"videoMedia\"], div[class*=\"mediaContainer\"] { filter: none !important; backdrop-filter: none !important; opacity: 1 !important; visibility: visible !important; }" +
                "  ';" +
                "  document.head.appendChild(style);" +
                "  " +
                "  function cleanUpBody() {" +
                "    if (document.body) {" +
                "      document.body.style.overflow = \"auto\";" +
                "      document.body.style.position = \"initial\";" +
                "    }" +
                "    if (document.documentElement) {" +
                "      document.documentElement.style.overflow = \"auto\";" +
                "    }" +
                "  }" +
                "  " +
                "  var observer = new MutationObserver(function(mutations) {" +
                "    cleanUpBody();" +
                "    document.querySelectorAll('div').forEach(function(el) {" +
                "      var className = el.className || \"\";" +
                "      if (typeof className === \"string\" && className) {" +
                "        var lowerClass = className.toLowerCase();" +
                "        if ((lowerClass.includes(\"premium\") || lowerClass.includes(\"upgrade\") || lowerClass.includes(\"paywall\") || lowerClass.includes(\"adblock\") || lowerClass.includes(\"billing\") || lowerClass.includes(\"sponsor\") || lowerClass.includes(\"promot\")) " +
                "            && !lowerClass.includes(\"login\") && !lowerClass.includes(\"signin\") && !lowerClass.includes(\"auth\")) {" +
                "          el.remove();" +
                "          return;" +
                "        }" +
                "      }" +
                "      var text = el.textContent || \"\";" +
                "      var lowerText = text.toLowerCase();" +
                "      if ((lowerText.includes(\"ad-free\") || lowerText.includes(\"ad free\") || lowerText.includes(\"remove ads\") || lowerText.includes(\"enjoying scrolller\") || lowerText.includes(\"get premium\")) " +
                "          && !lowerText.includes(\"login\") && !lowerText.includes(\"username\") && !lowerText.includes(\"password\") && !lowerText.includes(\"collection\") && !lowerText.includes(\"search\")) {" +
                "        var modal = el.closest('[class*=\"Dialog\"]') || el.closest('[class*=\"Modal\"]') || el.closest('[class*=\"popup\"]') || el;" +
                "        if (modal && modal.parentNode) {" +
                "          modal.remove();" +
                "        }" +
                "      }" +
                "    });" +
                "  });" +
                "  observer.observe(document.documentElement, { childList: true, subtree: true });" +
                "  setInterval(cleanUpBody, 200);" +
                "  " +
                "  /* Force active premium state inside localStorage for Zustand client store loading initialization */" +
                "  try {" +
                "    var localLogin = localStorage.getItem('scrolller-login-details');" +
                "    if (localLogin) {" +
                "      var loginObj = JSON.parse(localLogin);" +
                "      if (loginObj && !loginObj.isPremium) {" +
                "        loginObj.isPremium = true;" +
                "        loginObj.status = 'ACTIVE';" +
                "        localStorage.setItem('scrolller-login-details', JSON.stringify(loginObj));" +
                "      }" +
                "    }" +
                "  } catch (e) { console.error('Local login patch error:', e); }" +
                "  " +
                "  var originalFetch = window.fetch;" +
                "  window.fetch = async function(...args) {" +
                "    var url = args[0];" +
                "    var options = args[1];" +
                "    if (typeof url === 'string' && url.includes('/graphql') && options && options.body) {" +
                "      try {" +
                "        var bodyObj = JSON.parse(options.body);" +
                "        var modifiedReq = false;" +
                "        if (bodyObj && bodyObj.variables) {" +
                "          for (var key in bodyObj.variables) {" +
                "            if (bodyObj.variables.hasOwnProperty(key)) {" +
                "              var val = bodyObj.variables[key];" +
                "              if ((key.toLowerCase().includes('postid') || key === 'id') && typeof val === 'string' && /^\\d+$/.test(val)) {" +
                "                bodyObj.variables[key] = parseInt(val, 10);" +
                "                modifiedReq = true;" +
                "              }" +
                "            }" +
                "          }" +
                "        }" +
                "        if (modifiedReq) {" +
                "          options.body = JSON.stringify(bodyObj);" +
                "        }" +
                "      } catch (e) { console.error('GraphQL variables auto-correction error:', e); }" +
                "    }" +
                "    var response = await originalFetch.apply(this, args);" +
                "    if (typeof url === 'string' && url.includes('/graphql')) {" +
                "      try {" +
                "        var clone = response.clone();" +
                "        var json = await clone.json();" +
                "        var modified = false;" +
                "                "        function filterAds(obj) {" +
                "          if (!obj || typeof obj !== 'object') return obj;" +
                "          if (Array.isArray(obj)) {" +
                "            var originalLength = obj.length;" +
                "            var filtered = obj.filter(item => {" +
                "              if (item && typeof item === 'object') {" +
                "                if (item.isAd === true || item.is_ad === true || item.isSponsor === true || item.is_sponsor === true || item.sponsored === true || item.isPromoted === true || item.is_promoted === true || item.promoted === true || item.promotion === true || item.isPaid === true || item.is_paid === true) return false;" +
                "                if (item.url && typeof item.url === 'string') {" +
                "                  var u = item.url.toLowerCase();" +
                "                  if (u.includes('cant3am.com') || u.includes('chaturbate') || u.includes('stripchat')) return false;" +
                "                }" +
                "                if (item.title && typeof item.title === 'string') {" +
                "                  var t = item.title.toLowerCase();" +
                "                  if (t.includes('cam') || t.includes('sponsor') || t.includes('promot') || t.includes('premium') || t.includes('unlock') || /\\bpro\\b/.test(t) || t.includes('wank') || t.includes('wish me luck') || t.includes('link in bio') || t.includes('onlyfans') || t.includes('snapchat') || t.includes('bio link')) return false;" +
                "                }" +
                "                if (item.description && typeof item.description === 'string') {" +
                "                  var d = item.description.toLowerCase();" +
                "                  if (d.includes('cam') || d.includes('sponsor') || d.includes('promot') || d.includes('premium') || d.includes('unlock') || /\\bpro\\b/.test(d) || d.includes('wank') || d.includes('wish me luck') || d.includes('link in bio') || d.includes('onlyfans') || d.includes('snapchat') || d.includes('bio link')) return false;" +
                "                }" +
                "                /* Force HD media quality by replacing all sources with the highest resolution original */" +
                "                if (item.mediaSources && Array.isArray(item.mediaSources) && item.mediaSources.length > 0) {" +
                "                  var sorted = [...item.mediaSources].sort((a, b) => {" +
                "                    if (b.width !== a.width) return b.width - a.width;" +
                "                    return (a.isOptimized ? 1 : 0) - (b.isOptimized ? 0 : 1);" +
                "                  });" +
                "                  var best = sorted[0];" +
                "                  if (best) {" +
                "                    item.mediaSources.forEach(src => {" +
                "                      src.url = best.url;" +
                "                      src.width = best.width;" +
                "                      src.height = best.height;" +
                "                      src.isOptimized = true;" +
                "                    });" +
                "                  }" +
                "                }" +
                "                if (item.albumContent && Array.isArray(item.albumContent)) {" +
                "                  item.albumContent.forEach(slide => {" +
                "                    if (slide.mediaSources && Array.isArray(slide.mediaSources) && slide.mediaSources.length > 0) {" +
                "                      var sorted = [...slide.mediaSources].sort((a, b) => {" +
                "                        if (b.width !== a.width) return b.width - a.width;" +
                "                        return (a.isOptimized ? 1 : 0) - (b.isOptimized ? 0 : 1);" +
                "                      });" +
                "                      var best = sorted[0];" +
                "                      if (best) {" +
                "                        slide.mediaSources.forEach(src => {" +
                "                          src.url = best.url;" +
                "                          src.width = best.width;" +
                "                          src.height = best.height;" +
                "                          src.isOptimized = true;" +
                "                        });" +
                "                      }" +
                "                    }" +
                "                  });" +
                "                }" +
                "              }" +
                "              return true;" +
                "            });" +
                "            if (filtered.length !== originalLength) {" +
                "              modified = true;" +
                "              obj.length = 0;" +
                "              obj.push(...filtered.map(filterAds));" +
                "            } else {" +
                "              obj.forEach((val, idx) => { obj[idx] = filterAds(val); });" +
                "            }" +
                "          } else {" +
                "            for (var key in obj) {" +
                "              if (obj.hasOwnProperty(key)) obj[key] = filterAds(obj[key]);" +
                "            }" +
                "          }" +
                "          return obj;" +
                "        }" +
                "        filterAds(json);" +
                "        if (modified) {" +
                "          return new Response(JSON.stringify(json), {" +
                "            status: response.status," +
                "            statusText: response.statusText," +
                "            headers: response.headers" +
                "          });" +
                "        }" +
                "      } catch (err) { console.error(err); }" +
                "    }" +
                "    return response;" +
                "  };" +
                "})()";
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(js, null);
        } else {
            view.loadUrl("javascript:" + js);
        }
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
