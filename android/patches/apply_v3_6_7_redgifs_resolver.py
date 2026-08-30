from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/RedgifsResolver.java')
path.write_text(r'''package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RedgifsResolver {
    interface Callback {
        void onResolved(String url);
        void onError(String error);
    }

    private static final String API = "https://api.redgifs.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 16) RedditMedia/3.6.7";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile String token = "";
    private static volatile long tokenRefreshAt = 0L;

    private RedgifsResolver() {}

    static void resolve(String id, Callback callback) {
        if (id == null || id.isEmpty()) {
            callback.onError("missing RedGIFs id");
            return;
        }
        String key = id.toLowerCase();
        String cached = CACHE.get(key);
        if (cached != null && !cached.isEmpty()) {
            callback.onResolved(cached);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                String auth = temporaryToken();
                JSONObject root = requestJson(
                        API + "/v2/gifs/" + key,
                        auth,
                        "https://www.redgifs.com/watch/" + key);
                JSONObject gif = root.optJSONObject("gif");
                JSONObject urls = gif != null ? gif.optJSONObject("urls") : null;
                String hd = urls != null ? urls.optString("hd", "") : "";
                String sd = urls != null ? urls.optString("sd", "") : "";
                String resolved = !hd.isEmpty() ? hd : sd;
                if (resolved.isEmpty()) throw new IllegalStateException("no playable RedGIFs URL");
                CACHE.put(key, resolved);
                MAIN.post(() -> callback.onResolved(resolved));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "RedGIFs resolve failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static synchronized String temporaryToken() throws Exception {
        long now = System.currentTimeMillis();
        if (!token.isEmpty() && now < tokenRefreshAt) return token;
        JSONObject auth = requestJson(API + "/v2/auth/temporary", "", "https://www.redgifs.com/");
        String next = auth.optString("token", "");
        if (next.isEmpty()) throw new IllegalStateException("RedGIFs token unavailable");
        token = next;
        tokenRefreshAt = now + 8L * 60L * 1000L;
        return token;
    }

    private static JSONObject requestJson(String address, String bearer, String customHeader)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://www.redgifs.com/");
        connection.setRequestProperty("Origin", "https://www.redgifs.com");
        if (customHeader != null && !customHeader.isEmpty()) {
            connection.setRequestProperty("x-customheader", customHeader);
        }
        if (bearer != null && !bearer.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            if (status == 401) {
                token = "";
                tokenRefreshAt = 0L;
            }
            throw new IllegalStateException("RedGIFs HTTP " + status);
        }
        return new JSONObject(body.toString());
    }
}
''')
print('Wrote v3.6.7 RedGIFs HD resolver')
