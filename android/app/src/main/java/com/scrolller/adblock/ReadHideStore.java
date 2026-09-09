package com.scrolller.adblock;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ReadHideStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "read_hide.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "hidden_posts";

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "read-hide-store");
        t.setDaemon(true);
        return t;
    });

    ReadHideStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "title TEXT,author TEXT,subreddit TEXT,permalink TEXT,source_url TEXT,"
                + "score INTEGER,comments INTEGER,created_utc INTEGER,saved INTEGER,nsfw INTEGER,"
                + "media_kind TEXT,image_urls TEXT,video_url TEXT,poster_url TEXT,"
                + "media_width INTEGER,media_height INTEGER,hidden_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX hidden_posts_time ON " + TABLE + "(hidden_at DESC)");
        db.execSQL("CREATE INDEX hidden_posts_subreddit ON " + TABLE + "(subreddit COLLATE NOCASE)");
        db.execSQL("CREATE INDEX hidden_posts_kind ON " + TABLE + "(media_kind)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 only.
    }

    void hideAsync(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        io.execute(() -> upsertPost(post));
    }

    void deleteAsync(String id) {
        if (id == null || id.isEmpty()) return;
        io.execute(() -> getWritableDatabase().delete(TABLE, "id=?", new String[]{id}));
    }

    void importIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            for (String id : ids) {
                if (id == null || id.isEmpty()) continue;
                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("hidden_at", now++);
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void importPosts(List<RedditPost> posts) {
        if (posts == null || posts.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (RedditPost post : posts) upsertPost(db, post);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    Set<String> loadIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, new String[]{"id"}, null, null, null, null, "hidden_at ASC")) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (id != null && !id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    List<RedditPost> loadRecent(int limit) {
        ArrayList<RedditPost> posts = new ArrayList<>();
        String limitString = limit > 0 ? Integer.toString(limit) : null;
        try (Cursor cursor = getReadableDatabase().query(
                TABLE,
                new String[]{"id","title","author","subreddit","permalink","source_url",
                        "score","comments","created_utc","saved","nsfw","media_kind","image_urls",
                        "video_url","poster_url","media_width","media_height"},
                "title IS NOT NULL", null, null, null, "hidden_at DESC", limitString)) {
            while (cursor.moveToNext()) {
                RedditPost post = fromCursor(cursor);
                if (post != null) posts.add(post);
            }
        }
        return posts;
    }

    List<String> listCommunities() {
        ArrayList<String> communities = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT DISTINCT subreddit FROM " + TABLE
                        + " WHERE subreddit IS NOT NULL AND subreddit<>'' ORDER BY subreddit COLLATE NOCASE",
                null)) {
            while (cursor.moveToNext()) {
                String value = cursor.getString(0);
                if (value != null && !value.isEmpty()) communities.add(value);
            }
        }
        return communities;
    }

    List<String> deleteGroup(String kind, String community) {
        String where;
        String[] args = null;
        if ("all".equals(kind)) {
            where = null;
        } else if ("images".equals(kind)) {
            where = "media_kind IN ('IMAGE','GALLERY')";
        } else if ("videos".equals(kind)) {
            where = "media_kind IN ('VIDEO','GIF')";
        } else {
            where = "subreddit=? COLLATE NOCASE";
            args = new String[]{community == null ? "" : community};
        }

        ArrayList<String> ids = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor cursor = db.query(TABLE, new String[]{"id"}, where, args, null, null, null)) {
                while (cursor.moveToNext()) ids.add(cursor.getString(0));
            }
            db.delete(TABLE, where, args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return ids;
    }

    private void upsertPost(RedditPost post) {
        upsertPost(getWritableDatabase(), post);
    }

    private void upsertPost(SQLiteDatabase db, RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
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
        values.put("saved", post.saved ? 1 : 0);
        values.put("nsfw", post.nsfw ? 1 : 0);
        values.put("media_kind", post.mediaKind != null ? post.mediaKind.name() : "EXTERNAL");
        JSONArray images = new JSONArray();
        if (post.imageUrls != null) {
            for (String url : post.imageUrls) images.put(url);
        }
        values.put("image_urls", images.toString());
        values.put("video_url", post.videoUrl);
        values.put("poster_url", post.posterUrl);
        values.put("media_width", post.mediaWidth);
        values.put("media_height", post.mediaHeight);
        values.put("hidden_at", System.currentTimeMillis());
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private RedditPost fromCursor(Cursor c) {
        try {
            ArrayList<String> images = new ArrayList<>();
            String imageJson = c.getString(12);
            if (imageJson != null && !imageJson.isEmpty()) {
                JSONArray array = new JSONArray(imageJson);
                for (int i = 0; i < array.length(); i++) {
                    String url = array.optString(i, "");
                    if (!url.isEmpty()) images.add(url);
                }
            }
            RedditPost.MediaKind kind;
            try {
                kind = RedditPost.MediaKind.valueOf(c.getString(11));
            } catch (Exception ignored) {
                kind = RedditPost.MediaKind.EXTERNAL;
            }
            return new RedditPost(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getInt(6), c.getInt(7), c.getLong(8),
                    c.getInt(9) != 0, c.getInt(10) != 0, kind, images,
                    c.getString(13), c.getString(14), c.getInt(15), c.getInt(16));
        } catch (Exception ignored) {
            return null;
        }
    }
}
