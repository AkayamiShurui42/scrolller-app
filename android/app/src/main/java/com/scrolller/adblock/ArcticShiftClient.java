package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArcticShiftClient {
    interface PageCallback {
        void onComplete(JSONArray items, String nextBefore);
        void onError(String error);
    }

    private static final String BASE = "https://arctic-shift.photon-reddit.com/api/posts/search";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "arctic-shift");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ArcticShiftClient() {}

    static void fetchSubredditPage(
            String subreddit, String before, int limit, PageCallback callback) {
        fetchPage("subreddit", subreddit, "", before, limit, callback);
    }

    static void searchPage(
            String query, String before, int limit, PageCallback callback) {
        fetchPage("", "", query, before, limit, callback);
    }

    private static void fetchPage(
            String field,
            String value,
            String query,
            String before,
            int limit,
            PageCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                int bounded = Math.max(1, Math.min(limit, 100));
                StringBuilder url = new StringBuilder(BASE)
                        .append("?limit=").append(bounded)
                        .append("&sort=desc");
                if (field != null && !field.isEmpty()) {
                    url.append('&').append(field).append('=').append(enc(value));
                }
                if (query != null && !query.isEmpty()) {
                    url.append("&query=").append(enc(query));
                }
                if (before != null && !before.isEmpty()) {
                    url.append("&before=").append(enc(before));
                }

                JSONObject root = getJson(url.toString());
                JSONArray data = root.optJSONArray("data");
                JSONArray items = data != null ? data : new JSONArray();

                long oldest = Long.MAX_VALUE;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    long created = createdUtc(item.opt("created_utc"));
                    if (created > 0L && created < oldest) oldest = created;
                }

                String next = "";
                if (items.length() >= bounded && oldest != Long.MAX_VALUE) {
                    next = isoUtc(Math.max(1L, oldest - 1L));
                }
                final String nextBefore = next;
                MAIN.post(() -> callback.onComplete(items, nextBefore));
            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Archive request failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "RedditMedia/4.0 Android exhaustive-feed");

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Archive HTTP " + status);
        }
        return new JSONObject(text.toString());
    }

    static long createdUtc(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        String text = String.valueOf(value).trim();
        try { return (long) Double.parseDouble(text); } catch (Exception ignored) {}
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd HH:mm:ss"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = parser.parse(text);
                if (parsed != null) return parsed.getTime() / 1000L;
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private static String isoUtc(long epochSeconds) {
        try {
            SimpleDateFormat format =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date(epochSeconds * 1000L));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(
                    value == null ? "" : value,
                    StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }
}
