package com.scrolller.adblock;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SeenPostStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "seen_posts.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "seen_posts";

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seen-post-store");
        t.setDaemon(true);
        return t;
    });

    SeenPostStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "subreddit TEXT,"
                + "seen_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX seen_posts_subreddit ON " + TABLE
                + "(subreddit COLLATE NOCASE, seen_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    Set<String> loadIds() {
        HashSet<String> ids = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, new String[]{"id"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (id != null && !id.isEmpty()) ids.add(normalize(id));
            }
        }
        return ids;
    }

    void markSeenAsync(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        final String id = normalize(post.id);
        final String subreddit = post.subreddit == null ? "" : post.subreddit;
        io.execute(() -> {
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("subreddit", subreddit);
            values.put("seen_at", System.currentTimeMillis());
            getWritableDatabase().insertWithOnConflict(
                    TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    private static String normalize(String id) {
        String clean = id == null ? "" : id.trim().toLowerCase();
        if (clean.startsWith("t3_")) clean = clean.substring(3);
        return clean;
    }
}
