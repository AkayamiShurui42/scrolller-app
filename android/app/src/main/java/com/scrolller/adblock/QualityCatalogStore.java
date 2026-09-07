package com.scrolller.adblock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class QualityCatalogStore {
    interface LoadCallback {
        void onLoaded(ArrayList<RedditPost> posts);
    }

    private static final String FILE_NAME = "quality_catalog.json";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private QualityCatalogStore() {}

    static void load(Context context, LoadCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            ArrayList<RedditPost> posts = new ArrayList<>();
            try {
                File file = new File(app.getCacheDir(), FILE_NAME);
                if (file.isFile()) {
                    StringBuilder text = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                        String line;
                        while ((line = reader.readLine()) != null) text.append(line);
                    }
                    JSONArray array = new JSONArray(text.toString());
                    for (int i = 0; i < array.length(); i++) {
                        RedditPost post = fromJson(array.optJSONObject(i));
                        if (post != null && post.id != null && !post.id.isEmpty()) posts.add(post);
                    }
                }
            } catch (Exception ignored) {}
            MAIN.post(() -> callback.onLoaded(posts));
        });
    }

    static void save(Context context, List<RedditPost> input) {
        Context app = context.getApplicationContext();
        ArrayList<RedditPost> snapshot = new ArrayList<>(input);
        EXECUTOR.execute(() -> {
            File dir = app.getCacheDir();
            File target = new File(dir, FILE_NAME);
            File temp = new File(dir, FILE_NAME + ".tmp");
            try {
                JSONArray array = new JSONArray();
                for (RedditPost post : snapshot) {
                    JSONObject encoded = toJson(post);
                    if (encoded != null) array.put(encoded);
                }
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(temp, false))) {
                    writer.write(array.toString());
                }
                if (target.exists() && !target.delete()) {
                    // Best effort; rename below may still replace on some filesystems.
                }
                if (!temp.renameTo(target)) {
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(target, false))) {
                        writer.write(array.toString());
                    }
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            } catch (Exception ignored) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        });
    }

    private static JSONObject toJson(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return null;
        try {
            JSONObject object = new JSONObject();
            object.put("id", post.id);
            object.put("title", post.title);
            object.put("author", post.author);
            object.put("subreddit", post.subreddit);
            object.put("permalink", post.permalink);
            object.put("sourceUrl", post.sourceUrl);
            object.put("score", post.score);
            object.put("comments", post.comments);
            object.put("createdUtc", post.createdUtc);
            object.put("saved", post.saved);
            object.put("nsfw", post.nsfw);
            object.put("mediaKind", post.mediaKind.name());
            JSONArray images = new JSONArray();
            if (post.imageUrls != null) for (String url : post.imageUrls) images.put(url);
            object.put("imageUrls", images);
            object.put("videoUrl", post.videoUrl);
            object.put("posterUrl", post.posterUrl);
            object.put("mediaWidth", post.mediaWidth);
            object.put("mediaHeight", post.mediaHeight);
            return object;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static RedditPost fromJson(JSONObject object) {
        if (object == null) return null;
        try {
            String id = object.optString("id", "");
            if (id.isEmpty()) return null;
            RedditPost.MediaKind kind = RedditPost.MediaKind.valueOf(
                    object.optString("mediaKind", RedditPost.MediaKind.IMAGE.name()));
            ArrayList<String> images = new ArrayList<>();
            JSONArray imageArray = object.optJSONArray("imageUrls");
            if (imageArray != null) {
                for (int i = 0; i < imageArray.length(); i++) {
                    String url = imageArray.optString(i, "");
                    if (!url.isEmpty()) images.add(url);
                }
            }
            return new RedditPost(
                    id,
                    object.optString("title", ""),
                    object.optString("author", ""),
                    object.optString("subreddit", ""),
                    object.optString("permalink", ""),
                    object.optString("sourceUrl", ""),
                    object.optInt("score", 0),
                    object.optInt("comments", 0),
                    object.optLong("createdUtc", 0L),
                    object.optBoolean("saved", false),
                    object.optBoolean("nsfw", false),
                    kind,
                    images,
                    object.optString("videoUrl", ""),
                    object.optString("posterUrl", ""),
                    object.optInt("mediaWidth", 0),
                    object.optInt("mediaHeight", 0));
        } catch (Exception ignored) {
            return null;
        }
    }
}
