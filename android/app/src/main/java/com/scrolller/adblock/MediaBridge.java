package com.scrolller.adblock;

import android.app.Activity;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/**
 * Small native network bridge used by the bundled UI so provider requests are
 * not dependent on WebView CORS behavior. It is deliberately host-restricted.
 */
public final class MediaBridge {
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>();
    static {
        ALLOWED_HOSTS.add("api.scrolller.com");
        ALLOWED_HOSTS.add("scrolller.com");
        ALLOWED_HOSTS.add("www.scrolller.com");
        ALLOWED_HOSTS.add("www.reddit.com");
        ALLOWED_HOSTS.add("reddit.com");
        ALLOWED_HOSTS.add("old.reddit.com");
        ALLOWED_HOSTS.add("oauth.reddit.com");
        ALLOWED_HOSTS.add("api.redgifs.com");
        ALLOWED_HOSTS.add("v3.redgifs.com");
    }

    private final Activity activity;
    private final WebView webView;

    public MediaBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void get(String requestId, String url, String headersJson) {
        runRequest(requestId, "GET", url, null, headersJson);
    }

    @JavascriptInterface
    public void postJson(String requestId, String url, String body, String headersJson) {
        runRequest(requestId, "POST", url, body, headersJson);
    }

    private void runRequest(String requestId, String method, String url, String body, String headersJson) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            int status = 0;
            try {
                URL target = new URL(url);
                String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.US);
                if (!"https".equalsIgnoreCase(target.getProtocol()) || !isAllowedHost(host)) {
                    deliver(requestId, false, "Blocked provider URL", 0);
                    return;
                }

                connection = (HttpURLConnection) target.openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(25000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "ScrolllerPro/2.0 Android (media browser)");
                connection.setRequestProperty("Accept", "application/json, text/plain, */*");

                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookies);
                }

                applyHeaders(connection, headersJson);

                if ("POST".equals(method)) {
                    connection.setDoOutput(true);
                    if (connection.getRequestProperty("Content-Type") == null) {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    }
                    byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(bytes);
                    }
                }

                status = connection.getResponseCode();
                captureCookies(url, connection);
                InputStream stream = status >= 200 && status < 400
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String responseBody = readFully(stream);
                deliver(requestId, status >= 200 && status < 300, responseBody, status);
            } catch (Exception e) {
                deliver(requestId, false, e.getClass().getSimpleName() + ": " + e.getMessage(), status);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "ScrolllerProviderRequest").start();
    }

    private boolean isAllowedHost(String host) {
        if (ALLOWED_HOSTS.contains(host)) return true;
        return host.endsWith(".scrolller.com") || host.endsWith(".reddit.com") || host.endsWith(".redgifs.com");
    }

    private void applyHeaders(HttpURLConnection connection, String headersJson) {
        if (headersJson == null || headersJson.trim().isEmpty()) return;
        try {
            JSONObject headers = new JSONObject(headersJson);
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headers.optString(key, null);
                if (value != null && !key.equalsIgnoreCase("Cookie") && !key.equalsIgnoreCase("Host")) {
                    connection.setRequestProperty(key, value);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void captureCookies(String url, HttpURLConnection connection) {
        try {
            for (String value : connection.getHeaderFields().getOrDefault("Set-Cookie", java.util.Collections.emptyList())) {
                CookieManager.getInstance().setCookie(url, value);
            }
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
    }

    private String readFully(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private void deliver(String requestId, boolean ok, String body, int status) {
        String script = "window.__nativeMediaResult && window.__nativeMediaResult(" +
                JSONObject.quote(requestId == null ? "" : requestId) + "," +
                (ok ? "true" : "false") + "," +
                JSONObject.quote(body == null ? "" : body) + "," + status + ");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }
}
