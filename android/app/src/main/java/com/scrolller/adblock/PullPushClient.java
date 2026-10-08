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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PullPushClient {
    interface Callback {
        void onComplete(JSONArray items);
        void onError(String error);
    }

    private static final String BASE =
            "https://api.pullpush.io/reddit/search/submission/";
    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "pullpush-client");
                t.setDaemon(true);
                return t;
            });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PullPushClient() {}

    static void fetchSubreddit(
            String subreddit,
            int size,
            long beforeUtc,
            Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                int bounded = Math.max(1, Math.min(size, 100));
                StringBuilder url = new StringBuilder(BASE)
                        .append("?subreddit=").append(enc(subreddit))
                        .append("&size=").append(bounded)
                        .append("&sort=desc")
                        .append("&sort_type=created_utc");
                if (beforeUtc > 0L) url.append("&before=").append(beforeUtc);

                JSONObject root = getJson(url.toString());
                JSONArray data = root.optJSONArray("data");
                JSONArray result = data != null ? data : new JSONArray();
                MAIN.post(() -> callback.onComplete(result));
            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "PullPush request failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty(
                "User-Agent", "RedditMedia/3.9.22 Android archive-fallback");

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
            throw new IllegalStateException("PullPush HTTP " + status);
        }
        return new JSONObject(text.toString());
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
