package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ScrolllerClient {
    interface Callback {
        void onBatch(JSONArray items);
        void onComplete();
        void onError(String error);
    }

    private static final String ENDPOINT = "https://api.scrolller.com/admin";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static final String SUBREDDIT_QUERY =
            "query Q($url:String!,$limit:Int!){getSubreddit(data:{url:$url,limit:$limit,sortBy:RANDOM})" +
            "{id isNsfw children{iterator items{" +
            "id title subredditTitle redditPath isNsfw hasAudio commentsCount username " +
            "mediaSources{url width height isOptimized} " +
            "albumContent{mediaSources{url width height isOptimized}}" +
            "}}}}";

    private static final String CHILDREN_QUERY =
            "query Q($subredditId:Int!,$iterator:String,$limit:Int!,$isNsfw:Boolean)" +
            "{getSubredditChildren(data:{subredditId:$subredditId,iterator:$iterator,limit:$limit," +
            "sortBy:RANDOM,isNsfw:$isNsfw}){iterator items{" +
            "id title subredditTitle redditPath isNsfw hasAudio commentsCount username " +
            "mediaSources{url width height isOptimized} " +
            "albumContent{mediaSources{url width height isOptimized}}" +
            "}}}";

    private ScrolllerClient() {}

    static void crawlSubreddit(String subreddit, int maxItems, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                int delivered = 0;
                JSONObject vars = new JSONObject();
                vars.put("url", "/r/" + subreddit);
                vars.put("limit", 50);
                JSONObject first = request(SUBREDDIT_QUERY, vars);
                JSONObject data = first.optJSONObject("data");
                JSONObject sr = data != null ? data.optJSONObject("getSubreddit") : null;
                if (sr == null) throw new IllegalStateException("Scrolller subreddit unavailable");

                int subredditId = sr.optInt("id", 0);
                boolean nsfw = sr.optBoolean("isNsfw", false);
                JSONObject children = sr.optJSONObject("children");
                if (children == null) {
                    MAIN.post(callback::onComplete);
                    return;
                }

                JSONArray items = children.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    delivered += items.length();
                    JSONArray batch = items;
                    MAIN.post(() -> callback.onBatch(batch));
                }
                String iterator = children.optString("iterator", "");

                while (!iterator.isEmpty() && delivered < maxItems && subredditId > 0) {
                    try { Thread.sleep(650L); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    JSONObject nextVars = new JSONObject();
                    nextVars.put("subredditId", subredditId);
                    nextVars.put("iterator", iterator);
                    nextVars.put("limit", Math.min(50, maxItems - delivered));
                    nextVars.put("isNsfw", nsfw);
                    JSONObject next = request(CHILDREN_QUERY, nextVars);
                    JSONObject nextData = next.optJSONObject("data");
                    JSONObject listing = nextData != null
                            ? nextData.optJSONObject("getSubredditChildren") : null;
                    if (listing == null) break;
                    JSONArray nextItems = listing.optJSONArray("items");
                    if (nextItems != null && nextItems.length() > 0) {
                        delivered += nextItems.length();
                        JSONArray batch = nextItems;
                        MAIN.post(() -> callback.onBatch(batch));
                    }
                    String nextIterator = listing.optString("iterator", "");
                    if (nextIterator.isEmpty() || nextIterator.equals(iterator)) break;
                    iterator = nextIterator;
                }
                MAIN.post(callback::onComplete);
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Scrolller request failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static JSONObject request(String query, JSONObject variables) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("variables", variables);
        body.put("authorization", JSONObject.NULL);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Origin", "https://scrolller.com");
        connection.setRequestProperty("Referer", "https://scrolller.com/");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) RedditMedia/3.6.8");
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }

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
            throw new IllegalStateException("Scrolller HTTP " + status);
        }
        JSONObject result = new JSONObject(text.toString());
        JSONArray errors = result.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            throw new IllegalStateException("Scrolller GraphQL error");
        }
        return result;
    }
}
