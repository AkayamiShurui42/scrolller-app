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

        if (loginMode && isScrolllerWebsite(uri)) {
            installLoginGuard();
        }
    }

    private boolean isScrolllerWebsite(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        return host.equalsIgnoreCase("scrolller.com") || host.toLowerCase().endsWith(".scrolller.com");
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

    /**
     * Scrolller sometimes shows its own ad-block warning in an embedded WebView
     * even though this app is not blocking its website resources. React can
     * recreate that modal after page load, so a one-time hide is insufficient.
     *
     * This guard runs only while the authentication website is open. It does
     * not intercept requests, remove ads, disable scripts, or change the normal
     * direct client. It watches the login DOM, closes only ad-block-warning UI,
     * restores scrolling, and returns to the app once Scrolller writes a login
     * token to its own localStorage.
     */
    private void installLoginGuard() {
        String js = "(function(){try{" +
                "if(window.__scrolllerLoginGuard)return;window.__scrolllerLoginGuard=true;" +
                "var returning=false;" +
                "function txt(n){return String((n&&n.innerText)||'').toLowerCase().replace(/\\s+/g,' ').trim();}" +
                "function adText(t){return t.indexOf('adblock')>=0||t.indexOf('ad blocker')>=0||t.indexOf('ad-block')>=0||t.indexOf('disable your ad')>=0||t.indexOf('disable adblock')>=0||t.indexOf('whitelist')>=0;}" +
                "function hasLogin(n){try{return !!n.querySelector('input[type=password],input[name=password],input[autocomplete=current-password]');}catch(e){return false;}}" +
                "function unlock(){try{document.documentElement.style.setProperty('overflow','auto','important');document.body.style.setProperty('overflow','auto','important');document.documentElement.style.removeProperty('pointer-events');document.body.style.removeProperty('pointer-events');}catch(e){}}" +
                "function hideNode(n){if(!n||n===document.body||n===document.documentElement||hasLogin(n))return false;try{" +
                "var buttons=[].slice.call(n.querySelectorAll('button,[role=button],[aria-label],[title]'));" +
                "for(var i=0;i<buttons.length;i++){var b=buttons[i],label=(txt(b)+' '+String(b.getAttribute('aria-label')||'')+' '+String(b.getAttribute('title')||'')).toLowerCase();if(label==='x'||label.indexOf('close')>=0||label.indexOf('dismiss')>=0||label.indexOf('×')>=0||label.indexOf('✕')>=0){try{b.click();}catch(e){}break;}}" +
                "n.style.setProperty('display','none','important');n.style.setProperty('visibility','hidden','important');n.style.setProperty('pointer-events','none','important');n.setAttribute('aria-hidden','true');" +
                "var p=n.parentElement,depth=0;while(p&&p!==document.body&&depth++<4){var r=p.getBoundingClientRect(),s=getComputedStyle(p),c=String(p.className||'').toLowerCase();var cover=(s.position==='fixed'||s.position==='absolute')&&r.width>=innerWidth*0.9&&r.height>=innerHeight*0.9;var modalClass=c.indexOf('overlay')>=0||c.indexOf('backdrop')>=0||c.indexOf('modal')>=0||c.indexOf('popup')>=0;if(cover&&modalClass&&!hasLogin(p)){p.style.setProperty('display','none','important');p.style.setProperty('pointer-events','none','important');}p=p.parentElement;}" +
                "return true;}catch(e){return false;}}" +
                "function clearAdWarning(){try{" +
                "var selector='[role=dialog],[aria-modal=true],[class*=popup],[class*=modal],[class*=overlay],[class*=backdrop],section,aside,div';" +
                "var nodes=[].slice.call(document.querySelectorAll(selector));var hits=[];" +
                "for(var i=0;i<nodes.length;i++){var n=nodes[i],t=txt(n);if(!t||!adText(t)||hasLogin(n))continue;var r=n.getBoundingClientRect();if(r.width<20||r.height<20)continue;hits.push(n);}" +
                "hits.sort(function(a,b){var ta=txt(a),tb=txt(b);if(ta.length!==tb.length)return ta.length-tb.length;var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return ra.width*ra.height-rb.width*rb.height;});" +
                "if(hits.length)hideNode(hits[0]);unlock();" +
                "}catch(e){unlock();}}" +
                "function signedIn(){try{var raw=localStorage.getItem('SCROLLLER_LOGIN_DEV')||'';if(!raw)return false;try{return !!JSON.parse(raw).token;}catch(e){return raw.indexOf('\\\"token\\\"')>=0;}}catch(e){return false;}}" +
                "function tick(){clearAdWarning();if(!returning&&signedIn()){returning=true;setTimeout(function(){try{if(window.NativeAuth&&NativeAuth.returnToApp)NativeAuth.returnToApp();}catch(e){}},180);}}" +
                "var root=document.documentElement||document.body;if(root){new MutationObserver(function(){setTimeout(tick,0);}).observe(root,{childList:true,subtree:true,attributes:true,attributeFilter:['class','style','aria-hidden','aria-modal']});}" +
                "setInterval(tick,300);tick();setTimeout(tick,80);setTimeout(tick,600);setTimeout(tick,1500);" +
                "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }
}
