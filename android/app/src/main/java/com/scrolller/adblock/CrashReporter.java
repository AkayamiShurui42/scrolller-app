package com.scrolller.adblock;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

final class CrashReporter {
    private static final String FILE_NAME = "last_crash.txt";
    private static boolean installed;

    private CrashReporter() {}

    static synchronized void install(Activity activity) {
        if (installed) {
            showPreviousExitIfUseful(activity);
            return;
        }
        installed = true;

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("Reddit Media uncaught exception");
                pw.println("Time: " + DateFormat.getDateTimeInstance().format(new Date()));
                pw.println("Thread: " + thread.getName());
                pw.println("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
                pw.println("App build at crash: " + versionLabel(activity));
                pw.println();
                throwable.printStackTrace(pw);
                pw.flush();
                write(activity.getApplicationContext(), sw.toString());
            } catch (Throwable ignored) {}

            if (previous != null) previous.uncaughtException(thread, throwable);
        });

        showPreviousExitIfUseful(activity);
    }

    private static void showPreviousExitIfUseful(Activity activity) {
        activity.getWindow().getDecorView().post(() -> {
            String report = read(activity);
            String exit = previousExitSummary(activity);
            if (!exit.isEmpty()) {
                if (!report.isEmpty()) report += "\n\n";
                report += exit;
            }
            if (report.isEmpty()) return;

            String currentBuild = "Current installed build: " + versionLabel(activity);
            if (!report.contains("App build at crash:")) {
                report = "Stored crash report predates build-tagged reporting; "
                        + "it may have been recorded by an older installed APK.\n\n" + report;
            }
            report = currentBuild + "\n\n" + report;

            final String finalReport = report;
            new AlertDialog.Builder(activity)
                    .setTitle("Previous app crash captured")
                    .setMessage(finalReport.length() > 12000
                            ? finalReport.substring(0, 12000) + "\n…"
                            : finalReport)
                    .setPositiveButton("Copy", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) activity
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                    "Reddit Media crash report", finalReport));
                        }
                    })
                    .setNegativeButton("Clear", (dialog, which) -> clear(activity))
                    .setNeutralButton("Close", null)
                    .show();
        });
    }

    private static String previousExitSummary(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "";
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return "";
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 1);
            if (exits == null || exits.isEmpty()) return "";
            ApplicationExitInfo info = exits.get(0);
            int reason = info.getReason();
            if (reason != ApplicationExitInfo.REASON_CRASH
                    && reason != ApplicationExitInfo.REASON_CRASH_NATIVE
                    && reason != ApplicationExitInfo.REASON_ANR
                    && reason != ApplicationExitInfo.REASON_LOW_MEMORY
                    && reason != ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                    && reason != ApplicationExitInfo.REASON_SIGNALED) {
                return "";
            }
            return "Android process-exit record\n"
                    + "Reason: " + reasonName(reason) + " (" + reason + ")\n"
                    + "Timestamp: " + DateFormat.getDateTimeInstance().format(new Date(info.getTimestamp())) + "\n"
                    + "Status: " + info.getStatus() + "\n"
                    + "Importance: " + info.getImportance() + "\n"
                    + "Description: " + String.valueOf(info.getDescription());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String versionLabel(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            String name = info.versionName == null ? "?" : info.versionName;
            return name + " (versionCode " + code + ")";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "Native crash";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "Low memory";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "Excessive resource usage";
            case ApplicationExitInfo.REASON_SIGNALED: return "Signal";
            default: return "Unknown";
        }
    }

    private static void write(Context context, String text) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static String read(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.isFile()) return "";
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void clear(Context context) {
        try { new File(context.getFilesDir(), FILE_NAME).delete(); } catch (Exception ignored) {}
    }
}
