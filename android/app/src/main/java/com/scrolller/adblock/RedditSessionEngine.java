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

        ApiResult(boolean ok, int status, String body, String error) {
            this.ok = ok;
            this.status = status;
            this.body = body == null ? "" : body;
            this.error = error == null ? "" : error;
        }

        public JSONObject jsonObject() {
            try { return new JSONObject(body); } catch (Exception ignored) { return null; }
        }
    }

    private static final long MIN_REQUEST_GAP_MS = 900L;
    private static final long REQUEST_TIMEOUT_MS = 20000L;
    private static final int MAX_429_RETRIES = 2;

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
            finishRequest(timedOut, new ApiResult(false, 0, "", "Request timed out"));
        }, REQUEST_TIMEOUT_MS);

        String pathJs = JSONObject.quote(pending.path);
        String methodJs = JSONObject.quote(pending.method);
        String bodyJs = JSONObject.quote(pending.body);
        String tokenJs = JSONObject.quote(token);
        String js = "(async()=>{try{" +
                "const m=" + methodJs + ";" +
                "const opts={method:m,credentials:'include',headers:{'Accept':'application/json'}};" +
                "if(m==='POST'){opts.headers['Content-Type']='application/x-www-form-urlencoded; charset=UTF-8';opts.body=" + bodyJs + ";}" +
                "const r=await fetch(" + pathJs + ",opts);" +
                "const t=await r.text();" +
                "const ra=r.headers.get('retry-after')||'';" +
                "const rr=r.headers.get('x-ratelimit-reset')||'';" +
                "NativeRedditBridge.deliver(" + tokenJs + ",JSON.stringify({ok:r.ok,status:r.status,body:t,error:'',retryAfter:ra,rateReset:rr}));" +
                "}catch(e){NativeRedditBridge.deliver(" + tokenJs + ",JSON.stringify({ok:false,status:0,body:'',error:String(e),retryAfter:'',rateReset:''}));}})();";
        webView.evaluateJavascript(js, null);
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
            handler.post(() -> {
                try {
                    JSONObject result = new JSONObject(payload);
                    handleResult(
                            token,
                            new ApiResult(
                                    result.optBoolean("ok", false),
                                    result.optInt("status", 0),
                                    result.optString("body", ""),
                                    result.optString("error", "")
                            ),
                            result.optString("retryAfter", ""),
                            result.optString("rateReset", "")
                    );
                } catch (Exception e) {
                    handleResult(token, new ApiResult(false, 0, "", e.getMessage()), "", "");
                }
            });
        }
    }
}
