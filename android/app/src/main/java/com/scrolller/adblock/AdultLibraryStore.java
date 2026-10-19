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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AdultLibraryStore extends SQLiteOpenHelper {
    interface PostsCallback {
        void onLoaded(ArrayList<RedditPost> posts);
    }

    private static final String DB_NAME = "adult_gallery_library.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "posts";

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "adult-gallery-library");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    AdultLibraryStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "title TEXT,author TEXT,source_name TEXT,permalink TEXT,source_url TEXT,"
                + "score INTEGER,created_utc INTEGER,media_kind TEXT,image_urls TEXT,"
                + "poster_url TEXT,media_width INTEGER,media_height INTEGER,"
                + "search_metadata TEXT,source_origin TEXT,"
                + "saved INTEGER NOT NULL DEFAULT 0,"
                + "hidden INTEGER NOT NULL DEFAULT 0,"
                + "viewed INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX adult_gallery_saved ON " + TABLE + "(saved,updated_at DESC)");
        db.execSQL("CREATE INDEX adult_gallery_hidden ON " + TABLE + "(hidden,updated_at DESC)");
        db.execSQL("CREATE INDEX adult_gallery_viewed ON " + TABLE + "(viewed,updated_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    void cacheAsync(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        io.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            boolean saved = flag(db, post.id, "saved");
            boolean hidden = flag(db, post.id, "hidden");
            boolean viewed = flag(db, post.id, "viewed");
            upsert(db, post, saved, hidden, viewed);
        });
    }

    void setSavedAsync(RedditPost post, boolean saved) {
        mutate(post, "saved", saved);
    }

    void setHiddenAsync(RedditPost post, boolean hidden) {
        mutate(post, "hidden", hidden);
    }

    void setViewedAsync(RedditPost post, boolean viewed) {
        mutate(post, "viewed", viewed);
    }

    Set<String> loadIds(String flag) {
        HashSet<String> ids = new HashSet<>();
        if (!flag.equals("saved") && !flag.equals("hidden") && !flag.equals("viewed")) return ids;
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, new String[]{"id"}, flag + "=1",
                null, null, null, null)) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (id != null && !id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    void loadFlaggedAsync(String flag, int limit, PostsCallback callback) {
        if (!flag.equals("saved") && !flag.equals("hidden") && !flag.equals("viewed")) {
            if (callback != null) main.post(() -> callback.onLoaded(new ArrayList<>()));
            return;
        }
        int bounded = Math.max(1, Math.min(limit, 3000));
        io.execute(() -> {
            ArrayList<RedditPost> posts = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query(
                    TABLE,
                    columns(),
                    flag + "=1",
                    null,
                    null,
                    null,
                    "updated_at DESC",
                    Integer.toString(bounded))) {
                while (cursor.moveToNext()) {
                    RedditPost post = fromCursor(cursor);
                    if (post != null) posts.add(post);
                }
            } catch (Exception ignored) {}
            if (callback != null) main.post(() -> callback.onLoaded(posts));
        });
    }

    void searchSavedAsync(String query, int limit, PostsCallback callback) {
        final String[] tokens = normalizeTokens(query);
        int bounded = Math.max(1, Math.min(limit, 3000));
        io.execute(() -> {
            ArrayList<RedditPost> posts = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query(
                    TABLE,
                    columns(),
                    "saved=1",
                    null,
                    null,
                    null,
                    "updated_at DESC",
                    Integer.toString(bounded * 3))) {
                while (cursor.moveToNext() && posts.size() < bounded) {
                    RedditPost post = fromCursor(cursor);
                    if (post == null) continue;
                    if (!matchesTokens(post, tokens)) continue;
                    posts.add(post);
                }
            } catch (Exception ignored) {}
            if (callback != null) main.post(() -> callback.onLoaded(posts));
        });
    }

    private void mutate(RedditPost post, String flag, boolean value) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        io.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            boolean saved = flag(db, post.id, "saved");
            boolean hidden = flag(db, post.id, "hidden");
            boolean viewed = flag(db, post.id, "viewed");

            if (flag.equals("saved")) saved = value;
            else if (flag.equals("hidden")) hidden = value;
            else if (flag.equals("viewed")) viewed = value;

            upsert(db, post, saved, hidden, viewed);
        });
    }

    private boolean flag(SQLiteDatabase db, String id, String column) {
        try (Cursor cursor = db.query(
                TABLE, new String[]{column}, "id=?",
                new String[]{id}, null, null, null, "1")) {
            return cursor.moveToFirst() && cursor.getInt(0) != 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void upsert(
            SQLiteDatabase db,
            RedditPost post,
            boolean saved,
            boolean hidden,
            boolean viewed) {
        ContentValues v = new ContentValues();
        v.put("id", post.id);
        v.put("title", post.title);
        v.put("author", post.author);
        v.put("source_name", post.subreddit);
        v.put("permalink", post.permalink);
        v.put("source_url", post.sourceUrl);
        v.put("score", post.score);
        v.put("created_utc", post.createdUtc);
        v.put("media_kind", post.mediaKind.name());

        JSONArray images = new JSONArray();
        if (post.imageUrls != null) {
            for (String url : post.imageUrls) images.put(url);
        }
        v.put("image_urls", images.toString());
        v.put("poster_url", post.posterUrl);
        v.put("media_width", post.mediaWidth);
        v.put("media_height", post.mediaHeight);
        v.put("search_metadata", post.searchMetadata);
        v.put("source_origin", post.sourceOrigin);
        v.put("saved", saved ? 1 : 0);
        v.put("hidden", hidden ? 1 : 0);
        v.put("viewed", viewed ? 1 : 0);
        v.put("updated_at", System.currentTimeMillis());

        db.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private String[] columns() {
        return new String[]{
                "id","title","author","source_name","permalink","source_url",
                "score","created_utc","media_kind","image_urls","poster_url",
                "media_width","media_height","search_metadata","source_origin",
                "saved","hidden","viewed"
        };
    }

    private RedditPost fromCursor(Cursor c) {
        try {
            ArrayList<String> images = new ArrayList<>();
            JSONArray array = new JSONArray(c.getString(9) == null ? "[]" : c.getString(9));
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "");
                if (!url.isEmpty()) images.add(url);
            }

            RedditPost.MediaKind kind;
            try { kind = RedditPost.MediaKind.valueOf(c.getString(8)); }
            catch (Exception ignored) {
                kind = images.size() > 1
                        ? RedditPost.MediaKind.GALLERY
                        : RedditPost.MediaKind.IMAGE;
            }

            RedditPost post = new RedditPost(
                    c.getString(0),
                    c.getString(1),
                    c.getString(2),
                    c.getString(3),
                    c.getString(4),
                    c.getString(5),
                    c.getInt(6),
                    0,
                    c.getLong(7),
                    c.getInt(15) != 0,
                    true,
                    kind,
                    images,
                    "",
                    c.getString(10),
                    c.getInt(11),
                    c.getInt(12));
            post.searchMetadata = c.getString(13) == null ? "" : c.getString(13);
            post.sourceOrigin = c.getString(14) == null ? "" : c.getString(14);
            return post;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean matchesTokens(RedditPost post, String[] tokens) {
        if (tokens.length == 0) return true;
        String haystack = ((post.title == null ? "" : post.title) + " "
                + (post.searchMetadata == null ? "" : post.searchMetadata) + " "
                + (post.author == null ? "" : post.author) + " "
                + (post.subreddit == null ? "" : post.subreddit))
                .toLowerCase()
                .replace('_', ' ');
        for (String token : tokens) {
            if (!haystack.contains(token)) return false;
        }
        return true;
    }

    private static String[] normalizeTokens(String query) {
        if (query == null || query.trim().isEmpty()) return new String[0];
        return query.toLowerCase().replace('_', ' ').trim().split("\\s+");
    }
}
