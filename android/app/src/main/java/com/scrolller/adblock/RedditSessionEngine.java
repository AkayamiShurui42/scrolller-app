package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RedditSessionEngine {
    public interface Callback {
        void onResult(ApiResult result);
    }

    public interface ReadyListener {
        void onReady(String url);
    }

    public static final class ApiResult {
        public final boolean ok;
        public final int status;
        public final String body;
        public final String error;
        private final JSONObject parsedObject;

        ApiResult(boolean ok, int status, String body, String error) {
            this(ok, status, body, error, null);
        }

        ApiResult(
                boolean ok,
                int status,
                String body,
                String error,
                JSONObject parsedObject) {
            this.ok = ok;
            this.status = status;
            this.body = body == null ? "" : body;
            this.error = error == null ? "" : error;
            this.parsedObject = parsedObject;
        }

        public JSONObject jsonObject() {
            if (parsedObject != null) return parsedObject;
            try { return new JSONObject(body); } catch (Exception ignored) { return null; }
        }
    }

    private static final ExecutorService RESPONSE_PARSER =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "reddit-response-parser");
                t.setDaemon(true);
                return t;
            });

    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Callback> callbacks = new ConcurrentHashMap<>();
    private final ReadyListener readyListener;
    private boolean ready;

    public RedditSessionEngine(WebView webView, ReadyListener readyListener) {
        this.webView = webView;
        this.readyListener = readyListener;
        webView.addJavascriptInterface(new Bridge(), "NativeRedditBridge");
    }

    public void markReady(String url) {
        ready = true;
        if (readyListener != null) readyListener.onReady(url);
    }

    public boolean isReady() {
        return ready;
    }

    public void get(String path, Callback callback) {
        request(path, "GET", "", callback);
    }

    public void postForm(String path, String body, Callback callback) {
        request(path, "POST", body, callback);
    }

    public void cancelPendingRequests() {
        callbacks.clear();
    }

    public void request(String path, String method, String body, Callback callback) {
        if (!ready) {
            handler.postDelayed(() -> request(path, method, body, callback), 150);
            return;
        }

        final String token = UUID.randomUUID().toString();
        callbacks.put(token, callback);
        handler.postDelayed(() -> {
            Callback timedOut = callbacks.remove(token);
            if (timedOut != null) timedOut.onResult(new ApiResult(false, 0, "", "Request timed out"));
        }, 12000);

        String pathJs = JSONObject.quote(path);
        String methodJs = JSONObject.quote(method);
        String bodyJs = JSONObject.quote(body == null ? "" : body);
        String tokenJs = JSONObject.quote(token);
        String js = "(async()=>{try{" +
                "const m=" + methodJs + ";" +
                "const opts={method:m,credentials:'include',headers:{'Accept':'application/json'}};" +
                "if(m==='POST'){opts.headers['Content-Type']='application/x-www-form-urlencoded; charset=UTF-8';opts.body=" + bodyJs + ";}" +
                "const r=await fetch(" + pathJs + ",opts);" +
                "const t=await r.text();" +
                "NativeRedditBridge.deliver(" + tokenJs + ",JSON.stringify({ok:r.ok,status:r.status,body:t,error:''}));" +
                "}catch(e){NativeRedditBridge.deliver(" + tokenJs + ",JSON.stringify({ok:false,status:0,body:'',error:String(e)}));}})();";
        webView.evaluateJavascript(js, null);
    }

    public static String decodeEvaluateResult(String value) {
        if (value == null || "null".equals(value)) return "";
        try {
            JSONArray wrapper = new JSONArray("[" + value + "]");
            return wrapper.optString(0, "");
        } catch (Exception ignored) {
            return value;
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void deliver(String token, String payload) {
            RESPONSE_PARSER.execute(() -> {
                ApiResult parsed;
                try {
                    JSONObject result = new JSONObject(payload);
                    String responseBody = result.optString("body", "");
                    JSONObject parsedBody = null;
                    if (!responseBody.isEmpty()) {
                        try { parsedBody = new JSONObject(responseBody); }
                        catch (Exception ignored) {}
                    }
                    parsed = new ApiResult(
                            result.optBoolean("ok", false),
                            result.optInt("status", 0),
                            responseBody,
                            result.optString("error", ""),
                            parsedBody);
                } catch (Exception e) {
                    parsed = new ApiResult(false, 0, "", e.getMessage());
                }

                ApiResult finalParsed = parsed;
                handler.post(() -> {
                    Callback callback = callbacks.remove(token);
                    if (callback != null) callback.onResult(finalParsed);
                });
            });
        }
    }
}
