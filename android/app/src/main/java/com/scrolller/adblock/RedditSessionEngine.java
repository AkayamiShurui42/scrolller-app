package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
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

        ApiResult(boolean ok, int status, String body, String error, JSONObject parsedObject) {
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

    private static final long MIN_REQUEST_GAP_MS = 900L;
    private static final long REQUEST_TIMEOUT_MS = 20000L;
    private static final int MAX_429_RETRIES = 2;
    private static final ExecutorService RESPONSE_PARSER =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "reddit-response-parser");
                t.setDaemon(true);
                return t;
            });

    private static final class PendingRequest {
        final String path;
        final String method;
        final String body;
        final Callback callback;
        int rateLimitRetries;

        PendingRequest(String path, String method, String body, Callback callback) {
            this.path = path;
            this.method = method;
            this.body = body == null ? "" : body;
            this.callback = callback;
        }
    }

    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<PendingRequest> requestQueue = new ArrayDeque<>();
    private final Map<String, PendingRequest> inFlight = new ConcurrentHashMap<>();
    private final ReadyListener readyListener;
    private boolean ready;
    private boolean requestInFlight;
    private long nextRequestAtMs;
    private boolean drainScheduled;

    public RedditSessionEngine(WebView webView, ReadyListener readyListener) {
        this.webView = webView;
        this.readyListener = readyListener;
        webView.addJavascriptInterface(new Bridge(), "NativeRedditBridge");
    }

    public void markReady(String url) {
        ready = true;
        if (readyListener != null) readyListener.onReady(url);
        drainQueue();
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
        handler.post(() -> {
            requestQueue.clear();
            inFlight.clear();
            abortAllJavascriptRequests();

            // The WebView fetches themselves are aborted above, so releasing the
            // Java-side lane cannot accumulate orphan requests across tab/sort
            // changes.
            requestInFlight = false;
            nextRequestAtMs = 0L;
            drainScheduled = false;
        });
    }


    public void request(String path, String method, String body, Callback callback) {
        handler.post(() -> {
            requestQueue.addLast(new PendingRequest(path, method, body, callback));
            drainQueue();
        });
    }

    private void drainQueue() {
        if (!ready || requestInFlight || requestQueue.isEmpty()) return;
        long waitMs = nextRequestAtMs - SystemClock.elapsedRealtime();
        if (waitMs > 0L) {
            scheduleDrain(waitMs);
            return;
        }
        PendingRequest pending = requestQueue.removeFirst();
        startRequest(pending);
    }

    private void scheduleDrain(long delayMs) {
        if (drainScheduled) return;
        drainScheduled = true;
        handler.postDelayed(() -> {
            drainScheduled = false;
            drainQueue();
        }, Math.max(1L, delayMs));
    }

    private void startRequest(PendingRequest pending) {
        requestInFlight = true;
        nextRequestAtMs = SystemClock.elapsedRealtime() + MIN_REQUEST_GAP_MS;

        final String token = UUID.randomUUID().toString();
        inFlight.put(token, pending);
        handler.postDelayed(() -> {
            PendingRequest timedOut = inFlight.remove(token);
            if (timedOut == null) return;
            abortJavascriptRequest(token);
            finishRequest(timedOut, new ApiResult(false, 0, "", "Request timed out"));
        }, REQUEST_TIMEOUT_MS);

        String pathJs = JSONObject.quote(pending.path);
        String methodJs = JSONObject.quote(pending.method);
        String bodyJs = JSONObject.quote(pending.body);
        String tokenJs = JSONObject.quote(token);
        String js = "(async()=>{" +
                "window.__redditMediaControllers=window.__redditMediaControllers||{};" +
                "const token=" + tokenJs + ";" +
                "const controller=new AbortController();" +
                "window.__redditMediaControllers[token]=controller;" +
                "try{" +
                "const m=" + methodJs + ";" +
                "const opts={method:m,credentials:'include',signal:controller.signal,headers:{'Accept':'application/json'}};" +
                "if(m==='POST'){opts.headers['Content-Type']='application/x-www-form-urlencoded; charset=UTF-8';opts.body=" + bodyJs + ";}" +
                "const r=await fetch(" + pathJs + ",opts);" +
                "const t=await r.text();" +
                "const ra=r.headers.get('retry-after')||'';" +
                "const rr=r.headers.get('x-ratelimit-reset')||'';" +
                "NativeRedditBridge.deliver(token,JSON.stringify({ok:r.ok,status:r.status,body:t,error:'',retryAfter:ra,rateReset:rr}));" +
                "}catch(e){" +
                "if(!(e&&e.name==='AbortError')){" +
                "NativeRedditBridge.deliver(token,JSON.stringify({ok:false,status:0,body:'',error:String(e),retryAfter:'',rateReset:''}));" +
                "}" +
                "}finally{" +
                "try{delete window.__redditMediaControllers[token];}catch(_e){}" +
                "}})();";
        webView.evaluateJavascript(js, null);
    }

    private void abortJavascriptRequest(String token) {
        if (token == null || token.isEmpty()) return;
        String tokenJs = JSONObject.quote(token);
        String js = "(function(){try{" +
                "const m=window.__redditMediaControllers||{};" +
                "const c=m[" + tokenJs + "];" +
                "if(c){c.abort();delete m[" + tokenJs + "];}" +
                "}catch(e){}})();";
        try { webView.evaluateJavascript(js, null); } catch (RuntimeException ignored) {}
    }

    private void abortAllJavascriptRequests() {
        String js = "(function(){try{" +
                "const m=window.__redditMediaControllers||{};" +
                "Object.keys(m).forEach(k=>{try{m[k].abort();}catch(e){}});" +
                "window.__redditMediaControllers={};" +
                "}catch(e){}})();";
        try { webView.evaluateJavascript(js, null); } catch (RuntimeException ignored) {}
    }

    private void handleResult(
            String token,
            ApiResult result,
            String retryAfter,
            String rateReset) {
        PendingRequest pending = inFlight.remove(token);
        if (pending == null) return;

        if (result.status == 429 && pending.rateLimitRetries < MAX_429_RETRIES) {
            pending.rateLimitRetries++;
            long retryMs = retryDelayMs(retryAfter, rateReset, pending.rateLimitRetries);
            requestInFlight = false;
            nextRequestAtMs = Math.max(nextRequestAtMs, SystemClock.elapsedRealtime() + retryMs);
            requestQueue.addFirst(pending);
            scheduleDrain(retryMs);
            return;
        }

        finishRequest(pending, result);
    }

    private void finishRequest(PendingRequest pending, ApiResult result) {
        try {
            if (pending.callback != null) pending.callback.onResult(result);
        } finally {
            requestInFlight = false;
            drainQueue();
        }
    }

    private static long retryDelayMs(String retryAfter, String rateReset, int retryNumber) {
        double seconds = parsePositiveNumber(retryAfter);
        if (seconds <= 0d) seconds = parsePositiveNumber(rateReset);
        if (seconds <= 0d) seconds = 8d * retryNumber;
        long delay = (long) Math.ceil(seconds * 1000d);
        return Math.max(4000L, Math.min(delay, 60000L));
    }

    private static double parsePositiveNumber(String value) {
        if (value == null || value.trim().isEmpty()) return 0d;
        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed > 0d ? parsed : 0d;
        } catch (Exception ignored) {
            return 0d;
        }
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
                ApiResult apiResult;
                String retryAfter = "";
                String rateReset = "";
                try {
                    JSONObject result = new JSONObject(payload);
                    String responseBody = result.optString("body", "");
                    JSONObject parsedBody = null;
                    if (!responseBody.isEmpty()) {
                        try { parsedBody = new JSONObject(responseBody); } catch (Exception ignored) {}
                    }
                    apiResult = new ApiResult(
                            result.optBoolean("ok", false),
                            result.optInt("status", 0),
                            responseBody,
                            result.optString("error", ""),
                            parsedBody
                    );
                    retryAfter = result.optString("retryAfter", "");
                    rateReset = result.optString("rateReset", "");
                } catch (Exception e) {
                    apiResult = new ApiResult(false, 0, "", e.getMessage());
                }

                ApiResult finalResult = apiResult;
                String finalRetryAfter = retryAfter;
                String finalRateReset = rateReset;
                handler.post(() -> handleResult(
                        token, finalResult, finalRetryAfter, finalRateReset));
            });
        }
    }
}
