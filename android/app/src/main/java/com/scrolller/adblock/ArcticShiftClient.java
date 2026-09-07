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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArcticShiftClient {
    interface CrawlCallback {
        void onBatch(JSONArray items);
        void onComplete();
        void onError(String error);
    }

    interface LookupCallback {
        void onComplete(JSONArray items);
        void onError(String error);
    }

    private static final String BASE = "https://arctic-shift.photon-reddit.com/api";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private ArcticShiftClient() {}

    static void crawlSubreddit(String subreddit, int maxItems, CrawlCallback callback) {
        crawl("subreddit", subreddit, maxItems, callback);
    }

    static void crawlAuthor(String author, int maxItems, CrawlCallback callback) {
        crawl("author", author, maxItems, callback);
    }

    static void lookupPost(String redditId, LookupCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                String clean = cleanPostId(redditId);
                if (clean.isEmpty()) throw new IllegalArgumentException("Invalid Reddit post ID");
                String url = BASE + "/posts/ids?ids=" + enc(clean);
                JSONObject root = getJson(url);
                JSONArray data = root.optJSONArray("data");
                JSONArray result = data != null ? data : new JSONArray();
                MAIN.post(() -> callback.onComplete(result));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Arctic Shift lookup failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static void crawl(String field, String value, int maxItems, CrawlCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                int delivered = 0;
                String before = "";
                Set<String> seenBefore = new HashSet<>();
                while (delivered < maxItems) {
                    int limit = Math.min(100, maxItems - delivered);
                    StringBuilder url = new StringBuilder(BASE)
                            .append("/posts/search?")
                            .append(field).append('=').append(enc(value))
                            .append("&limit=").append(limit)
                            .append("&sort=desc");
                    if (!before.isEmpty()) url.append("&before=").append(enc(before));

                    JSONObject root = getJson(url.toString());
                    JSONArray data = root.optJSONArray("data");
                    if (data == null || data.length() == 0) break;

                    delivered += data.length();
                    JSONArray batch = data;
                    MAIN.post(() -> callback.onBatch(batch));

                    long oldest = Long.MAX_VALUE;
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.optJSONObject(i);
                        if (item == null) continue;
                        long created = createdUtc(item.opt("created_utc"));
                        if (created > 0 && created < oldest) oldest = created;
                    }
                    if (oldest == Long.MAX_VALUE || data.length() < limit) break;
                    String nextBefore = isoUtc(Math.max(1L, oldest - 1L));
                    if (nextBefore.isEmpty() || !seenBefore.add(nextBefore)) break;
                    before = nextBefore;

                    try { Thread.sleep(220L); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                MAIN.post(callback::onComplete);
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Arctic Shift request failed" : e.getMessage();
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
        connection.setRequestProperty("User-Agent", "RedditMedia/3.7.0 Android historical-access");

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
            throw new IllegalStateException("Arctic Shift HTTP " + status);
        }
        return new JSONObject(text.toString());
    }

    private static long createdUtc(Object value) {
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
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date(epochSeconds * 1000L));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String cleanPostId(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.startsWith("t3_")) clean = clean.substring(3);
        int comments = clean.indexOf("/comments/");
        if (comments >= 0) {
            String tail = clean.substring(comments + "/comments/".length());
            int slash = tail.indexOf('/');
            clean = slash >= 0 ? tail.substring(0, slash) : tail;
        }
        return clean.matches("[A-Za-z0-9]+") ? clean : "";
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }
}
