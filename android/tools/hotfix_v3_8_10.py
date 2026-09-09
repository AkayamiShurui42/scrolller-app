from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()
main = replace_once(
    main,
    '''        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);\n        readHideStore = new ReadHideStore(this);\n        loadSubredditPresets();''',
    '''        readHideStore = new ReadHideStore(this);\n        LegacyHiddenPrefsPreflight.migrateIfNeeded(this, readHideStore);\n        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);\n        loadSubredditPresets();''',
    "preference preflight order")
main_path.write_text(main)

crash_path = Path("app/src/main/java/com/scrolller/adblock/CrashReporter.java")
crash = crash_path.read_text()
crash = replace_once(
    crash,
    'import android.content.Context;\nimport android.os.Build;',
    'import android.content.Context;\nimport android.content.pm.PackageInfo;\nimport android.os.Build;',
    "package info import")
crash = replace_once(
    crash,
    '''                pw.println("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");\n                pw.println();''',
    '''                pw.println("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");\n                pw.println("App build at crash: " + versionLabel(activity));\n                pw.println();''',
    "crash build label")
crash = replace_once(
    crash,
    '''            if (report.isEmpty()) return;\n\n            final String finalReport = report;''',
    '''            if (report.isEmpty()) return;\n\n            String currentBuild = "Current installed build: " + versionLabel(activity);\n            if (!report.contains("App build at crash:")) {\n                report = "Stored crash report predates build-tagged reporting; "\n                        + "it may have been recorded by an older installed APK.\\n\\n" + report;\n            }\n            report = currentBuild + "\\n\\n" + report;\n\n            final String finalReport = report;''',
    "current build report label")

helper_anchor = '''    private static String reasonName(int reason) {'''
helper = '''    private static String versionLabel(Context context) {\n        try {\n            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);\n            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P\n                    ? info.getLongVersionCode()\n                    : info.versionCode;\n            String name = info.versionName == null ? "?" : info.versionName;\n            return name + " (versionCode " + code + ")";\n        } catch (Exception ignored) {\n            return "unknown";\n        }\n    }\n\n'''
if helper not in crash:
    if crash.count(helper_anchor) != 1:
        raise SystemExit(f"version helper anchor: expected 1, found {crash.count(helper_anchor)}")
    crash = crash.replace(helper_anchor, helper + helper_anchor, 1)
crash_path.write_text(crash)

build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, "versionCode 43", "versionCode 44", "versionCode")
build = replace_once(build, 'versionName "3.8.9"', 'versionName "3.8.10"', "versionName")
build_path.write_text(build)

print("Applied v3.8.10 oversized legacy preference preflight + crash build tagging")
