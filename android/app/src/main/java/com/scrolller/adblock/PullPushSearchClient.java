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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PullPushSearchClient {
    interface Callback {
        void onComplete(ArrayList<String> submissionIds);
        void onError(String error);
    }

    private static final String BASE = "https://api.pullpush.io";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PullPushSearchClient() {}

    static void searchLeadIns(
            String query,
            Set<String> allowedSubreddits,
            String exactSubreddit,
            Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                Set<String> allowed = new HashSet<>();
                if (allowedSubreddits != null) {
                    for (String value : allowedSubreddits) {
                        if (value != null && !value.trim().isEmpty()) {
                            allowed.add(value.trim().toLowerCase(Locale.US));
                        }
                    }
                }

                String subredditArg = exactSubreddit == null ? "" : exactSubreddit.trim();
                collectTopics(request("/topic", query, subredditArg), allowed, ids);
                collectComments(request("/comment", query, subredditArg), allowed, ids);

                ArrayList<String> result = new ArrayList<>(ids);
                MAIN.post(() -> callback.onComplete(result));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "PullPush search failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static JSONObject request(String endpoint, String query, String subreddit) throws Exception {
        StringBuilder url = new StringBuilder(BASE)
                .append(endpoint)
                .append("?q=")
                .append(enc(query))
                .append("&size=100&sort=desc&lang_id=regex");
        if (subreddit != null && !subreddit.isEmpty()) {
            url.append("&subreddit=").append(enc(subreddit));
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "RedditMedia/3.7.5 hidden-search-index");

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

    private static void collectTopics(
            JSONObject root, Set<String> allowed, LinkedHashSet<String> ids) {
        JSONArray data = root != null ? root.optJSONArray("data") : null;
        if (data == null) return;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !allowed(item, allowed)) continue;
            String id = item.optString("id", "").trim();
            if (id.startsWith("t3_")) id = id.substring(3);
            id = id.replaceAll("[^A-Za-z0-9]", "");
            if (!id.isEmpty()) ids.add("t3_" + id);
        }
    }

    private static void collectComments(
            JSONObject root, Set<String> allowed, LinkedHashSet<String> ids) {
        JSONArray data = root != null ? root.optJSONArray("data") : null;
        if (data == null) return;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !allowed(item, allowed)) continue;
            String linkId = item.optString("link_id", "").trim();
            if (linkId.isEmpty()) continue;
            if (!linkId.startsWith("t3_")) linkId = "t3_" + linkId;
            String suffix = linkId.substring(3).replaceAll("[^A-Za-z0-9]", "");
            if (!suffix.isEmpty()) ids.add("t3_" + suffix);
        }
    }

    private static boolean allowed(JSONObject item, Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) return true;
        String subreddit = item.optString("subreddit", "").trim().toLowerCase(Locale.US);
        return !subreddit.isEmpty() && allowed.contains(subreddit);
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.toString());
    }
}
