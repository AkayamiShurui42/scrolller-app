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
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.util.Log;
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

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d("SCROLLLER_CONSOLE", consoleMessage.message());
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null) {
                    String host = android.net.Uri.parse(url).getHost();
                    if (host != null && !host.contains("scrolller.com")) {
                        Log.d("SCROLLLER_NAV_BLOCK", "Blocked external navigation to: " + url);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    String url = request.getUrl().toString();
                    String host = request.getUrl().getHost();
                    if (host != null && !host.contains("scrolller.com")) {
                        Log.d("SCROLLLER_NAV_BLOCK", "Blocked external navigation to: " + url);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    String url = request.getUrl().toString();
                    
                    // Bypass anti-adblock detection checks by returning successful mock empty scripts
                    if (url.contains("doubleclick.net") || 
                        url.contains("googlesyndication.com") || 
                        url.contains("google-analytics.com") || 
                        url.contains("googletagmanager.com")) {
                        return new WebResourceResponse("application/javascript", "UTF-8", 
                                new ByteArrayInputStream("console.log('Mocked Ad Network response for Adblock Detection bypass');".getBytes()));
                    }
                    
                    // Whitelist blocker: if not in whitelisted domains, block completely
                    if (!isAllowedDomain(url)) {
                        Log.d("SCROLLLER_AD_BLOCK", "Blocked non-whitelisted resource: " + url);
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes()));
                    }
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

    private boolean isAllowedDomain(String url) {
        if (url == null) return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return true; // Allow relative/local paths
            host = host.toLowerCase();
            return host.endsWith("scrolller.com") ||
                   host.endsWith("reddit.com") ||
                   host.endsWith("redd.it") ||
                   host.endsWith("redgifs.com") ||
                   host.endsWith("imgur.com") ||
                   host.endsWith("gfycat.com") ||
                   host.endsWith("googleapis.com") ||
                   host.endsWith("gstatic.com");
        } catch (Exception e) {
            return false;
        }
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
                "    function injectStyle() {" +
                "      var target = document.head || document.documentElement || document.body;" +
                "      if (style && target && !style.parentNode) {" +
                "        target.appendChild(style);" +
                "      }" +
                "    }" +
                "    " +
                "    function startObserver() {" +
                "      var target = document.documentElement || document.body;" +
                "      if (target) {" +
                "        injectStyle();" +
                "        var observer = new MutationObserver(function(mutations) {" +
                "          cleanUpBody();" +
                "          document.querySelectorAll('div, section, aside, article, dialog, a, span, p').forEach(function(el) {" +
                "            var className = el.className || \"\";" +
                "            if (typeof className === \"string\" && className) {" +
                "              var lowerClass = className.toLowerCase();" +
                "              if ((lowerClass.includes(\"premium\") || lowerClass.includes(\"upgrade\") || lowerClass.includes(\"paywall\") || lowerClass.includes(\"adblock\") || lowerClass.includes(\"billing\") || lowerClass.includes(\"sponsor\") || lowerClass.includes(\"promot\")) " +
                "                  && !lowerClass.includes(\"login\") && !lowerClass.includes(\"signin\") && !lowerClass.includes(\"auth\")) {" +
                "                el.remove();" +
                "                return;" +
                "              }" +
                "            }" +
                "            " +
                "            var label = (el.textContent || \"\").trim().toLowerCase();" +
                "            if (label === \"sponsored\" || label === \"promoted\" || label === \"advertisement\" || label === \"sponsored post\" || label === \"promoted post\") {" +
                "              var card = el.closest('[class*=\"card\"]') || el.closest('[class*=\"item\"]') || el.closest('[class*=\"container\"]') || el.closest('[class*=\"wrapper\"]') || el.closest('article');" +
                "              if (card && card.parentNode) {" +
                "                card.remove();" +
                "                return;" +
                "              }" +
                "            }" +
                "            " +
                "            var text = el.textContent || \"\";" +
                "            var lowerText = text.toLowerCase();" +
                "            if ((lowerText.includes(\"ad-free\") || lowerText.includes(\"ad free\") || lowerText.includes(\"remove ads\") || lowerText.includes(\"enjoying scrolller\") || lowerText.includes(\"get premium\") || lowerText.includes(\"adblock\") || lowerText.includes(\"ad block\") || lowerText.includes(\"adblocker\") || lowerText.includes(\"ad-blocker\") || lowerText.includes(\"disable ad\") || lowerText.includes(\"disable your ad\") || lowerText.includes(\"turn off ad\") || lowerText.includes(\"support us\") || lowerText.includes(\"ad blocker\")) " +
                "                && !lowerText.includes(\"login\") && !lowerText.includes(\"username\") && !lowerText.includes(\"password\") && !lowerText.includes(\"collection\") && !lowerText.includes(\"search\")) {" +
                "              var modal = el.closest('[class*=\"Dialog\"]') || el.closest('[class*=\"Modal\"]') || el.closest('[class*=\"popup\"]') || el;" +
                "              if (modal && modal.parentNode) {" +
                "                modal.remove();" +
                "              }" +
                "            }" +
                "          });" +
                "        });" +
                "        observer.observe(target, { childList: true, subtree: true, characterData: true });" +
                "      } else {" +
                "        setTimeout(startObserver, 50);" +
                "      }" +
                "    }" +
                "    " +
                "    startObserver();" +
                "    setInterval(function() {" +
                "      injectStyle();" +
                "      cleanUpBody();" +
                "    }, 200);" +
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
                "  /* --- XHR and WS Proxies --- */" +
                "  var blockedPatterns = ['exoclick', 'juicyads', 'cant3am', 'realsrv'];" +
                "  function shouldBlockUrl(url) {" +
                "    if (!url) return false;" +
                "    var lowerUrl = url.toString().toLowerCase();" +
                "    return blockedPatterns.some(pattern => lowerUrl.includes(pattern));" +
                "  }" +
                "  var originalXHROpen = XMLHttpRequest.prototype.open;" +
                "  XMLHttpRequest.prototype.open = new Proxy(originalXHROpen, {" +
                "    apply: function(target, thisArg, args) {" +
                "      if (shouldBlockUrl(args[1])) {" +
                "        console.log('Blocked XHR to: ' + args[1]);" +
                "        args[1] = 'about:blank';" +
                "      }" +
                "      return target.apply(thisArg, args);" +
                "    }" +
                "  });" +
                "  var OriginalWebSocket = window.WebSocket;" +
                "  window.WebSocket = new Proxy(OriginalWebSocket, {" +
                "    construct: function(target, args) {" +
                "      if (shouldBlockUrl(args[0])) {" +
                "        console.log('Blocked WS to: ' + args[0]);" +
                "        return { send: function(){}, close: function(){}, readyState: 0, addEventListener: function(){}, removeEventListener: function(){}, url: args[0] };" +
                "      }" +
                "      return new target(...args);" +
                "    }" +
                "  });" +
                "  var originalToString = Function.prototype.toString;" +
                "  Function.prototype.toString = new Proxy(originalToString, {" +
                "    apply: function(target, thisArg, args) {" +
                "      if (thisArg === XMLHttpRequest.prototype.open || thisArg === window.WebSocket) {" +
                "        return 'function () { [native code] }';" +
                "      }" +
                "      return target.apply(thisArg, args);" +
                "    }" +
                "  });" +
                "  " +
                "  var originalFetch = window.fetch;" +
                "  window.fetch = async function(...args) {" +
                "    var url = args[0];" +
                "    var options = args[1];" +
                "    var urlStr = '';" +
                "    if (url) {" +
                "      if (typeof url === 'string') {" +
                "        urlStr = url;" +
                "      } else if (typeof url === 'object') {" +
                "        urlStr = url.url || (typeof url.toString === 'function' ? url.toString() : '');" +
                "      }" +
                "    }" +
                "    var isScrolllerApi = urlStr && (urlStr.includes('/graphql') || urlStr.includes('/admin') || urlStr.includes('api.scrolller.com'));" +
                "    var isDiscoverQuery = false;" +
                "    if (isScrolllerApi && options && options.body) {" +
                "      try {" +
                "        var bodyObj = JSON.parse(options.body);" +
                "        if (bodyObj && bodyObj.query) {" +
                "          var q = bodyObj.query.toLowerCase();" +
                "          var isUserQuery = q.includes('favorite') || q.includes('collection') || q.includes('user') || q.includes('me') || q.includes('my');" +
                "          if (!isUserQuery && (q.includes('discover') || q.includes('feed') || q.includes('explore') || q.includes('home') || q.includes('subreddit'))) {" +
                "            isDiscoverQuery = true;" +
                "          }" +
                "        }" +
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
                "    if (isScrolllerApi) {" +
                "      try {" +
                "        var clone = response.clone();" +
                "        var json = await clone.json();" +
                "        var modified = true;" +
                "        function filterAds(obj) {" +
                "          if (!obj || typeof obj !== 'object') return obj;" +
                "          if ('isPremium' in obj) obj.isPremium = true;" +
                "          if ('status' in obj) obj.status = 'ACTIVE';" +
                "          if ('isPaid' in obj) obj.isPaid = false;" +
                "          if (Array.isArray(obj)) {" +
                "            var filtered = obj.filter(item => {" +
                "                if (!item || typeof item !== 'object') return true;" +
                "                if (item.isAd === true || item.is_ad === true || item.isSponsor === true || item.is_sponsor === true || item.sponsored === true || item.isPromoted === true || item.is_promoted === true || item.promoted === true || item.promotion === true || item.isPaid === true || item.is_paid === true) return false;" +
                "                if (isDiscoverQuery && (item.__typename === 'SubredditPost' || (item.mediaSources && Array.isArray(item.mediaSources)))) {" +
                "                  if (!item.redditPath || typeof item.redditPath !== 'string' || !item.redditPath.includes('/r/')) return false;" +
                "                }" +
                "                if (item.url && typeof item.url === 'string') {" +
                "                  var u = item.url.toLowerCase();" +
                "                  if (u.includes('cant3am.com') || u.includes('chaturbate') || u.includes('stripchat')) return false;" +
                "                }" +
                "                if (item.reddit_posted_by && typeof item.reddit_posted_by === 'string') {" +
                "                  var author = item.reddit_posted_by.toLowerCase();" +
                "                  if (author.includes('scroll') || author === 'admin' || author === 'official' || author === 'sponsor') return false;" +
                "                }" +
                "                if (item.username && typeof item.username === 'string') {" +
                "                  var user = item.username.toLowerCase();" +
                "                  if (user.includes('scroll') || user === 'admin' || user === 'official' || user === 'sponsor') return false;" +
                "                }" +
                "                if (item.displayName && typeof item.displayName === 'string') {" +
                "                  var dn = item.displayName.toLowerCase();" +
                "                  if (dn.includes('scroll') || dn === 'admin' || dn === 'official' || dn === 'sponsor') return false;" +
                "                }" +
                "                if (item.userType && typeof item.userType === 'string') {" +
                "                  var ut = item.userType.toLowerCase();" +
                "                  if (ut.includes('scroll') || ut === 'admin' || ut === 'official' || ut === 'sponsor') return false;" +
                "                }" +
                "                if (item.title && typeof item.title === 'string') {" +
                "                  var t = item.title.toLowerCase();" +
                "                  if (t.includes('cam') || t.includes('sponsor') || t.includes('promot') || t.includes('premium') || t.includes('unlock') || /\\bpro\\b/.test(t) || t.includes('wank') || t.includes('wish me luck') || t.includes('link in bio') || t.includes('onlyfans') || t.includes('snapchat') || t.includes('bio link')) return false;" +
                "                }" +
                "                if (item.description && typeof item.description === 'string') {" +
                "                  var d = item.description.toLowerCase();" +
                "                  if (d.includes('cam') || d.includes('sponsor') || d.includes('promot') || d.includes('premium') || d.includes('unlock') || /\\bpro\\b/.test(d) || d.includes('wank') || d.includes('wish me luck') || d.includes('link in bio') || d.includes('onlyfans') || d.includes('snapchat') || d.includes('bio link')) return false;" +
                "                }" +
                "                if ('isPaid' in item) item.isPaid = false;" +
                "                if ('isPremium' in item) item.isPremium = true;" +
                "                if ('status' in item) item.status = 'ACTIVE';" +
                "                /* Force HD media quality by truncating the array to only contain the highest resolution original source */" +
                "                if (item.mediaSources && Array.isArray(item.mediaSources) && item.mediaSources.length > 0) {" +
                "                  var sorted = [...item.mediaSources].sort((a, b) => {" +
                "                    if (b.width !== a.width) return b.width - a.width;" +
                "                    return (a.isOptimized ? 1 : 0) - (b.isOptimized ? 0 : 1);" +
                "                  });" +
                "                  var best = sorted[0];" +
                "                  if (best) {" +
                "                    item.mediaSources.length = 0;" +
                "                    item.mediaSources.push(best);" +
                "                  }" +
                "                }" +
                "                if (item.albumContent && Array.isArray(item.albumContent)) {" +
                "                  item.albumContent.forEach(slide => {" +
                "                    if ('isPaid' in slide) slide.isPaid = false;" +
                "                    if ('isPremium' in slide) slide.isPremium = true;" +
                "                    if ('status' in slide) slide.status = 'ACTIVE';" +
                "                    if (slide.mediaSources && Array.isArray(slide.mediaSources) && slide.mediaSources.length > 0) {" +
                "                      var sorted = [...slide.mediaSources].sort((a, b) => {" +
                "                        if (b.width !== a.width) return b.width - a.width;" +
                "                        return (a.isOptimized ? 1 : 0) - (b.isOptimized ? 0 : 1);" +
                "                      });" +
                "                      var best = sorted[0];" +
                "                      if (best) {" +
                "                        slide.mediaSources.length = 0;" +
                "                        slide.mediaSources.push(best);" +
                "                      }" +
                "                    }" +
                "                  });" +
                "                }" +
                "              return true;" +
                "            });" +
                "            obj.length = 0;" +
                "            obj.push(...filtered.map(filterAds));" +
                "          } else {" +
                "            for (var key in obj) {" +
                "              if (obj.hasOwnProperty(key)) obj[key] = filterAds(obj[key]);" +
                "            }" +
                "          }" +
                "          return obj;" +
                "        }" +
                "        console.log('SCROLLLER_API_RES: ' + JSON.stringify(json));" +
                "        filterAds(json);" +
                "        if (modified) {" +
                "          var newHeaders = new Headers(response.headers);" +
                "          newHeaders.delete('content-length');" +
                "          newHeaders.set('access-control-allow-origin', '*');" +
                "          return new Response(JSON.stringify(json), {" +
                "            status: response.status," +
                "            statusText: response.statusText," +
                "            headers: newHeaders" +
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
