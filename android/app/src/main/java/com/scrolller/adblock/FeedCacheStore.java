package com.scrolller.adblock;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class FeedCacheStore extends SQLiteOpenHelper {
    interface LoadCallback {
        void onLoaded(ArrayList<RedditPost> posts);
    }

    private static final String DB_NAME = "feed_cache.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "cached_posts";
    private static final long MAX_AGE_MS = 120L * 24L * 60L * 60L * 1000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feed-cache-store");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    FeedCacheStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "title TEXT,author TEXT,subreddit TEXT,permalink TEXT,source_url TEXT,"
                + "score INTEGER,comments INTEGER,created_utc INTEGER,nsfw INTEGER,"
                + "media_kind TEXT,image_urls TEXT,video_url TEXT,poster_url TEXT,"
                + "media_width INTEGER,media_height INTEGER,duration_seconds INTEGER,"
                + "search_metadata TEXT,source_origin TEXT,cached_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX cached_posts_subreddit ON " + TABLE
                + "(subreddit COLLATE NOCASE,cached_at DESC)");
        db.execSQL("CREATE INDEX cached_posts_created ON " + TABLE + "(created_utc DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    void cacheAsync(List<RedditPost> posts) {
        if (posts == null || posts.isEmpty()) return;
        ArrayList<RedditPost> copy = new ArrayList<>(posts);
        io.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                long now = System.currentTimeMillis();
                for (RedditPost post : copy) upsert(db, post, now);
                db.delete(TABLE, "cached_at<?",
                        new String[]{Long.toString(now - MAX_AGE_MS)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        });
    }

    void loadSubredditAsync(String subreddit, int limit, LoadCallback callback) {
        final String target = subreddit == null ? "" : subreddit.trim();
        if (target.isEmpty()) {
            if (callback != null) main.post(() -> callback.onLoaded(new ArrayList<>()));
            return;
        }
        final int boundedLimit = Math.max(1, Math.min(limit, 2000));
        io.execute(() -> {
            ArrayList<RedditPost> posts = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query(
                    TABLE,
                    new String[]{
                            "id","title","author","subreddit","permalink","source_url",
                            "score","comments","created_utc","nsfw","media_kind","image_urls",
                            "video_url","poster_url","media_width","media_height",
                            "duration_seconds","search_metadata","source_origin"
                    },
                    "subreddit=? COLLATE NOCASE",
                    new String[]{target},
                    null,
                    null,
                    "cached_at DESC",
                    Integer.toString(boundedLimit))) {
                while (cursor.moveToNext()) {
                    RedditPost post = fromCursor(cursor);
                    if (post != null) posts.add(post);
                }
            } catch (Exception ignored) {}
            if (callback != null) main.post(() -> callback.onLoaded(posts));
        });
    }

    void searchAllAsync(
            String query,
            int limit,
            LoadCallback callback) {
        final String needle = query == null ? "" : query.trim();
        final int boundedLimit = Math.max(1, Math.min(limit, 2000));

        io.execute(() -> {
            ArrayList<RedditPost> matches = new ArrayList<>();
            String normalized = FuzzySearch.normalize(needle);
            int threshold = FuzzySearch.thresholdFor(normalized.length());

            try (Cursor cursor = getReadableDatabase().query(
                    TABLE,
                    new String[]{
                            "id","title","author","subreddit","permalink","source_url",
                            "score","comments","created_utc","nsfw","media_kind","image_urls",
                            "video_url","poster_url","media_width","media_height",
                            "duration_seconds","search_metadata","source_origin"
                    },
                    null,
                    null,
                    null,
                    null,
                    "cached_at DESC",
                    "12000")) {
                while (cursor.moveToNext() && matches.size() < boundedLimit) {
                    RedditPost post = fromCursor(cursor);
                    if (post == null) continue;
                    if (!normalized.isEmpty()) {
                        int score = cachedSearchScore(post, needle);
                        if (score < threshold) continue;
                    }
                    matches.add(post);
                }
            } catch (Exception ignored) {}

            if (callback != null) main.post(() -> callback.onLoaded(matches));
        });
    }

    void searchSubredditsAsync(
            Set<String> subreddits,
            String query,
            int limit,
            LoadCallback callback) {
        final HashSet<String> allowed = new HashSet<>();
        if (subreddits != null) {
            for (String value : subreddits) {
                if (value == null) continue;
                String clean = value.trim().toLowerCase(Locale.US);
                if (!clean.isEmpty()) allowed.add(clean);
            }
        }
        final String needle = query == null ? "" : query.trim();
        final int boundedLimit = Math.max(1, Math.min(limit, 1000));

        if (allowed.isEmpty()) {
            if (callback != null) main.post(() -> callback.onLoaded(new ArrayList<>()));
            return;
        }

        io.execute(() -> {
            ArrayList<RedditPost> matches = new ArrayList<>();
            String normalized = FuzzySearch.normalize(needle);
            int threshold = FuzzySearch.thresholdFor(normalized.length());

            try (Cursor cursor = getReadableDatabase().query(
                    TABLE,
                    new String[]{
                            "id","title","author","subreddit","permalink","source_url",
                            "score","comments","created_utc","nsfw","media_kind","image_urls",
                            "video_url","poster_url","media_width","media_height",
                            "duration_seconds","search_metadata","source_origin"
                    },
                    null,
                    null,
                    null,
                    null,
                    "cached_at DESC",
                    "8000")) {
                while (cursor.moveToNext() && matches.size() < boundedLimit) {
                    RedditPost post = fromCursor(cursor);
                    if (post == null || post.subreddit == null) continue;
                    String community = post.subreddit.trim().toLowerCase(Locale.US);
                    if (!allowed.contains(community)) continue;

                    if (!normalized.isEmpty()) {
                        int score = cachedSearchScore(post, needle);
                        if (score < threshold) continue;
                    }
                    matches.add(post);
                }
            } catch (Exception ignored) {}

            if (callback != null) main.post(() -> callback.onLoaded(matches));
        });
    }

    private static int cachedSearchScore(RedditPost post, String query) {
        if (post == null) return 0;
        int subredditScore = FuzzySearch.score(query, post.subreddit);
        int titleScore = FuzzySearch.score(query, post.title);
        int authorScore = FuzzySearch.score(query, post.author);
        int metadataScore = FuzzySearch.score(query, post.searchMetadata);
        int urlScore = Math.max(
                FuzzySearch.score(query, post.permalink),
                FuzzySearch.score(query, post.sourceUrl));
        int best = Math.max(titleScore,
                Math.max(authorScore, Math.max(metadataScore, urlScore)));
        if (subredditScore > 0) {
            best = Math.max(best, Math.min(1000, subredditScore + 90));
        }
        return best;
    }

    private void upsert(SQLiteDatabase db, RedditPost post, long now) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        if (post.subreddit == null || post.subreddit.isEmpty()) return;

        ContentValues values = new ContentValues();
        values.put("id", post.id);
        values.put("title", post.title);
        values.put("author", post.author);
        values.put("subreddit", post.subreddit);
        values.put("permalink", post.permalink);
        values.put("source_url", post.sourceUrl);
        values.put("score", post.score);
        values.put("comments", post.comments);
        values.put("created_utc", post.createdUtc);
        values.put("nsfw", post.nsfw ? 1 : 0);
        values.put("media_kind", post.mediaKind != null
                ? post.mediaKind.name() : RedditPost.MediaKind.EXTERNAL.name());

        JSONArray images = new JSONArray();
        if (post.imageUrls != null) {
            for (String url : post.imageUrls) images.put(url);
        }
        values.put("image_urls", images.toString());
        values.put("video_url", post.videoUrl);
        values.put("poster_url", post.posterUrl);
        values.put("media_width", post.mediaWidth);
        values.put("media_height", post.mediaHeight);
        values.put("duration_seconds", post.durationSeconds);
        values.put("search_metadata", post.searchMetadata);
        values.put("source_origin", post.sourceOrigin);
        values.put("cached_at", now);

        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private RedditPost fromCursor(Cursor c) {
        try {
            ArrayList<String> images = new ArrayList<>();
            String rawImages = c.getString(11);
            if (rawImages != null && !rawImages.isEmpty()) {
                JSONArray array = new JSONArray(rawImages);
                for (int i = 0; i < array.length(); i++) {
                    String url = array.optString(i, "");
                    if (!url.isEmpty()) images.add(url);
                }
            }

            RedditPost.MediaKind kind;
            try {
                kind = RedditPost.MediaKind.valueOf(c.getString(10));
            } catch (Exception ignored) {
                kind = RedditPost.MediaKind.EXTERNAL;
            }

            RedditPost post = new RedditPost(
                    c.getString(0),
                    c.getString(1),
                    c.getString(2),
                    c.getString(3),
                    c.getString(4),
                    c.getString(5),
                    c.getInt(6),
                    c.getInt(7),
                    c.getLong(8),
                    false,
                    c.getInt(9) != 0,
                    kind,
                    images,
                    c.getString(12),
                    c.getString(13),
                    c.getInt(14),
                    c.getInt(15));
            post.durationSeconds = Math.max(0, c.getInt(16));
            post.searchMetadata = c.getString(17) == null ? "" : c.getString(17);
            post.sourceOrigin = c.getString(18) == null ? "cache" : c.getString(18);
            return post;
        } catch (Exception ignored) {
            return null;
        }
    }
}
