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
                "    iframe, ins, [class*=\"Cam\"], [class*=\"cam\"], [class*=\"sponsored\"], [class*=\"sponsor\"], [class*=\"Sponsor\"], [class*=\"promoted\"], [class*=\"Promoted\"], [class*=\"promotion\"], [class*=\"Promotion\"], [class*=\"adContainer\"], [class*=\"exoclick\"], [class*=\"juicyads\"], a[href*=\"chaturbate\"], a[href*=\"stripchat\"], [class*=\"paywall\"], [class*=\"Paywall\"], [class*=\"Adblock\"], [class*=\"AdBlock\"], [class*=\"ad-block\"], [class*=\"Billing\"] { display: none !important; height: 0 !important; width: 0 !important; opacity: 0 !important; pointer-events: none !important; }" +
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
                "        });" +
                "        observer.observe(target, { childList: true, subtree: true });" +
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
                "      if (thisArg === XMLHttpRequest.prototype.open) return 'function open() { [native code] }';" +
                "      if (thisArg === window.WebSocket) return 'function WebSocket() { [native code] }';" +
                "      if (thisArg === window.fetch) return 'function fetch() { [native code] }';" +
                "      return target.apply(thisArg, args);" +
                "    }" +
                "  });" +
                "  " +
                "  var originalFetch = window.fetch;" +
                "  window.fetch = async function(input, init) {" +
                "    var urlStr = '';" +
                "    if (typeof input === 'string') {" +
                "      urlStr = input;" +
                "    } else if (input && typeof input === 'object') {" +
                "      urlStr = input.url || '';" +
                "    }" +
                "    var isScrolllerApi = urlStr && (urlStr.includes('/graphql') || urlStr.includes('/admin') || urlStr.includes('api.scrolller.com'));" +
                "    var isDiscoverQuery = false;" +
                "    if (isScrolllerApi && init && init.body && typeof init.body === 'string') {" +
                "      try {" +
                "        var bodyObj = JSON.parse(init.body);" +
                "        console.log('SCROLLLER_API_REQ: ' + init.body);" +
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
                "          try {" +
                "            var opName = bodyObj.operationName;" +
                "            if (!opName && bodyObj.query) {" +
                "              if (bodyObj.query.includes('SubredditQuery') || bodyObj.query.includes('getSubreddit')) opName = 'SubredditQuery';" +
                "              else if (bodyObj.query.includes('SubredditChildrenQuery') || bodyObj.query.includes('getSubredditChildren')) opName = 'SubredditChildrenQuery';" +
                "              else if (bodyObj.query.includes('FavoritesQuery') || bodyObj.query.includes('getFavorites')) opName = 'FavoritesQuery';" +
                "              else if (bodyObj.query.includes('PaidCollections') || bodyObj.query.includes('getMyPaidCollections')) opName = 'PaidCollections';" +
                "              else if (bodyObj.query.includes('DiscoverFilteredSubredditsQuery') || bodyObj.query.includes('discoverFilteredSubreddits')) opName = 'DiscoverFilteredSubredditsQuery';" +
                "            }" +
                "            if (opName) {" +
                "              var isFeedQuery = opName === 'SubredditQuery' || opName === 'SubredditChildrenQuery' || opName === 'FavoritesQuery' || opName === 'PaidCollections' || opName === 'DiscoverFilteredSubredditsQuery';" +
                "              if (isFeedQuery) {" +
                "                var curIt = bodyObj.variables.iterator;" +
                "                var curSort = bodyObj.variables.sortBy || 'HOT';" +
                "                var contextKey = opName + '_' + curSort + '_' + (bodyObj.variables.url || bodyObj.variables.subredditId || '');" +
                "                if (!window._pgState) {" +
                "                  window._pgState = {" +
                "                    activeContext: null," +
                "                    lastIterator: null," +
                "                    fallbackActive: false," +
                "                    seenPostIds: new Set()" +
                "                  };" +
                "                }" +
                "                if (window._pgState.activeContext !== contextKey) {" +
                "                  window._pgState.activeContext = contextKey;" +
                "                  window._pgState.lastIterator = null;" +
                "                  window._pgState.fallbackActive = false;" +
                "                  window._pgState.seenPostIds.clear();" +
                "                }" +
                "                if ((curIt === null || curIt === undefined) && window._pgState.lastIterator) {" +
                "                  bodyObj.variables.iterator = window._pgState.lastIterator;" +
                "                  modifiedReq = true;" +
                "                }" +
                "                if (curSort === 'TOP' && (window._pgState.lastIterator === '1' || window._pgState.fallbackActive)) {" +
                "                  window._pgState.fallbackActive = true;" +
                "                  bodyObj.variables.sortBy = 'NEW';" +
                "                  modifiedReq = true;" +
                "                }" +
                "              }" +
                "            }" +
                "          } catch (pe) { console.error('Pagination correction error:', pe); }" +
                "        }" +
                "        if (modifiedReq) {" +
                "          init.body = JSON.stringify(bodyObj);" +
                "        }" +
                "      } catch (e) { console.error('GraphQL variables auto-correction error:', e); }" +
                "    }" +
                "    var response = await originalFetch(input, init);" +
                "    if (isScrolllerApi) {" +
                "      try {" +
                "        var clone = response.clone();" +
                "        var json = await clone.json();" +
                "        if (json && json.data && window._pgState) {" +
                "          var d = json.data;" +
                "          var newIt = null;" +
                "          if (d.getSubreddit && d.getSubreddit.children) {" +
                "            newIt = d.getSubreddit.children.iterator;" +
                "          } else if (d.getSubredditChildren) {" +
                "            newIt = d.getSubredditChildren.iterator;" +
                "          } else if (d.getFavorites) {" +
                "            newIt = d.getFavorites.iterator;" +
                "          } else if (d.getMyPaidCollections) {" +
                "            newIt = d.getMyPaidCollections.iterator;" +
                "          } else if (d.discoverFilteredSubreddits) {" +
                "            newIt = d.discoverFilteredSubreddits.iterator;" +
                "          }" +
                "          if (newIt) {" +
                "            window._pgState.lastIterator = String(newIt);" +
                "          }" +
                "        }" +
                "        var modified = false;" +
                "        if (json && json.data) {" +
                "          if (json.data.getLoggedInUser) {" +
                "            if (json.data.getLoggedInUser.isPremium !== true) {" +
                "              json.data.getLoggedInUser.isPremium = true;" +
                "              modified = true;" +
                "            }" +
                "            if (json.data.getLoggedInUser.status !== 'ACTIVE') {" +
                "              json.data.getLoggedInUser.status = 'ACTIVE';" +
                "              modified = true;" +
                "            }" +
                "          }" +
                "          if (json.data.login) {" +
                "            if (json.data.login.isPremium !== true) {" +
                "              json.data.login.isPremium = true;" +
                "              modified = true;" +
                "            }" +
                "            if (json.data.login.status !== 'ACTIVE') {" +
                "              json.data.login.status = 'ACTIVE';" +
                "              modified = true;" +
                "            }" +
                "          }" +
                "          function unlockPremium(obj) {" +
                "            if (!obj || typeof obj !== 'object') return;" +
                "            if (Array.isArray(obj)) {" +
                "              for (var i = 0; i < obj.length; i++) {" +
                "                unlockPremium(obj[i]);" +
                "              }" +
                "            } else {" +
                "              var isPost = obj.__typename === 'SubredditPost' || obj.mediaSources || obj.blurredMediaSources;" +
                "              if (isPost) {" +
                "                if ('isPaid' in obj && obj.isPaid !== false) {" +
                "                  obj.isPaid = false;" +
                "                  modified = true;" +
                "                }" +
                "                if ('isPremium' in obj && obj.isPremium !== true) {" +
                "                  obj.isPremium = true;" +
                "                  modified = true;" +
                "                }" +
                "                if ('status' in obj && obj.status !== 'ACTIVE') {" +
                "                  obj.status = 'ACTIVE';" +
                "                  modified = true;" +
                "                }" +
                "                if ((!obj.mediaSources || obj.mediaSources.length === 0) && obj.blurredMediaSources && obj.blurredMediaSources.length > 0) {" +
                "                  obj.mediaSources = obj.blurredMediaSources;" +
                "                  modified = true;" +
                "                }" +
                "                if (obj.albumContent && typeof obj.albumContent === 'object') {" +
                "                  var album = obj.albumContent;" +
                "                  if ('isPaid' in album && album.isPaid !== false) {" +
                "                    album.isPaid = false;" +
                "                    modified = true;" +
                "                  }" +
                "                  if ('isPremium' in album && album.isPremium !== true) {" +
                "                    album.isPremium = true;" +
                "                    modified = true;" +
                "                  }" +
                "                  if ('status' in album && album.status !== 'ACTIVE') {" +
                "                    album.status = 'ACTIVE';" +
                "                    modified = true;" +
                "                  }" +
                "                  if ((!album.mediaSources || album.mediaSources.length === 0) && album.blurredMediaSources && album.blurredMediaSources.length > 0) {" +
                "                    album.mediaSources = album.blurredMediaSources;" +
                "                    modified = true;" +
                "                  }" +
                "                }" +
                "              }" +
                "              for (var k in obj) {" +
                "                if (obj.hasOwnProperty(k) && k !== 'mediaSources' && k !== 'blurredMediaSources' && k !== 'albumContent') {" +
                "                  unlockPremium(obj[k]);" +
                "                }" +
                "              }" +
                "            }" +
                "          }" +
                "          function filterAdPosts(obj) {" +
                "            if (!obj || typeof obj !== 'object') return;" +
                "            if (Array.isArray(obj)) {" +
                "              for (var i = 0; i < obj.length; i++) {" +
                "                filterAdPosts(obj[i]);" +
                "              }" +
                "            } else {" +
                "              if (obj.items && Array.isArray(obj.items)) {" +
                "                var lenBefore = obj.items.length;" +
                "                obj.items = obj.items.filter(item => {" +
                "                  if (item) {" +
                "                    if (item.username) {" +
                "                      var u = item.username.toLowerCase();" +
                "                      if (u.includes('scrolller')) {" +
                "                        return false;" +
                "                      }" +
                "                    }" +
                "                    if (item.isAd === true || item.is_ad === true || item.isSponsor === true || item.is_sponsor === true || item.sponsored === true || item.isPromoted === true || item.is_promoted === true || item.promoted === true || item.promotion === true) {" +
                "                      return false;" +
                "                    }" +
                "                  }" +
                "                  return true;" +
                "                });" +
                "                if (obj.items.length !== lenBefore) {" +
                "                  modified = true;" +
                "                }" +
                "              }" +
                "              for (var k in obj) {" +
                "                if (obj.hasOwnProperty(k) && k !== 'items') {" +
                "                  filterAdPosts(obj[k]);" +
                "                }" +
                "              }" +
                "            }" +
                "          }" +
                "          unlockPremium(json.data);" +
                "          filterAdPosts(json.data);" +
                "        }" +
                "        console.log('SCROLLLER_API_RES: ' + JSON.stringify(json));" +
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
