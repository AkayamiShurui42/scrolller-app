package com.scrolller.adblock;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Private, bounded, first-party event journal. Does not need ADB, READ_LOGS,
 * root, a remote service, or access to other applications' logs.
 *
 * Events intentionally contain NO Reddit post IDs, URLs, usernames,
 * headers, tokens, cookies, search terms, or media content.
 */
final class AppDiagnostics {
    private static final String TAG = "RedviewDiagnostics";
    private static final String ACTIVE_FILE = "redview-events.log";
    private static final String ARCHIVE_FILE = "redview-events.previous.log";
    private static final long MAX_FILE_BYTES = 128 * 1024L;
    private static final int MAX_EVENT_CHARS = 450;
    private static final Object LOCK = new Object();
    private static Context appContext;

    private AppDiagnostics() {}

    static void init(Context context) {
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            appendLocked("diagnostics", "logger initialized; SDK=" + Build.VERSION.SDK_INT
                    + " pid=" + Process.myPid());
        }
    }

    static void record(String category, String message) {
        synchronized (LOCK) {
            appendLocked(category, message);
        }
    }

    static void failure(String category, Throwable error) {
        if (error == null) {
            record(category, "Unknown exception");
            return;
        }
        StringBuilder detail = new StringBuilder(error.getClass().getName());
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < Math.min(5, frames.length); i++) {
            StackTraceElement frame = frames[i];
            detail.append(" | ").append(frame.getClassName()).append('.')
                    .append(frame.getMethodName()).append(':')
                    .append(frame.getLineNumber());
        }
        // Exception messages can contain private request URLs, so omit them.
        record(category, detail.toString());
    }

    static String snapshot(Context context) {
        synchronized (LOCK) {
            File folder = context.getApplicationContext().getFilesDir();
            String earlier = read(new File(folder, ARCHIVE_FILE));
            String recent = read(new File(folder, ACTIVE_FILE));
            if (earlier.isEmpty() && recent.isEmpty()) return "No app events captured yet.\n";
            return (earlier.isEmpty() ? "" : "=== Earlier app events ===\n" + earlier + "\n")
                    + "=== Recent app events ===\n" + recent;
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            File folder = context.getApplicationContext().getFilesDir();
            new File(folder, ACTIVE_FILE).delete();
            new File(folder, ARCHIVE_FILE).delete();
            appendLocked("diagnostics", "log cleared by user");
        }
    }

    private static void appendLocked(String category, String message) {
        if (appContext == null) return;
        try {
            File folder = appContext.getFilesDir();
            File current = new File(folder, ACTIVE_FILE);
            if (current.length() >= MAX_FILE_BYTES) {
                File previous = new File(folder, ARCHIVE_FILE);
                if (previous.exists() && !previous.delete()) {
                    Log.w(TAG, "Could not rotate previous diagnostics");
                }
                if (!current.renameTo(previous)) {
                    Log.w(TAG, "Could not rotate current diagnostics");
                    return;
                }
            }
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date())
                    + " +" + SystemClock.elapsedRealtime() + "ms"
                    + " [" + clean(category) + "] " + clean(message) + "\n";
            try (FileOutputStream output = new FileOutputStream(current, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            // Logging must never make an otherwise working application crash.
            Log.w(TAG, "Could not write app diagnostic event", error);
        }
    }

    private static String clean(String input) {
        if (input == null) return "";
        String value = input.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return value.length() > MAX_EVENT_CHARS
                ? value.substring(0, MAX_EVENT_CHARS) + "…" : value;
    }

    private static String read(File file) {
        try {
            if (!file.isFile()) return "";
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "(App event file unavailable: " + error.getClass().getSimpleName() + ")\n";
        }
    }
}
