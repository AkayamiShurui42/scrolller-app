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
    '''    private ProgressBar progress;\n    private ScrollView accountView;''',
    '''    private ProgressBar progress;\n    private int statusGeneration = 0;\n    private Runnable statusDismissRunnable;\n    private ScrollView accountView;''',
    "status fields")

old_panel = '''        statusPanel = new LinearLayout(this);\n        statusPanel.setOrientation(LinearLayout.VERTICAL);\n        statusPanel.setGravity(Gravity.CENTER);\n        statusPanel.setPadding(dp(18), dp(15), dp(18), dp(15));\n        statusPanel.setBackground(rounded(0xE6151515, 16));\n        progress = new ProgressBar(this);\n        statusPanel.addView(progress, new LinearLayout.LayoutParams(dp(38), dp(38)));\n        statusText = new TextView(this);\n        statusText.setText("Connecting to Reddit…");\n        statusText.setTextColor(Color.WHITE);\n        statusText.setTextSize(13);\n        statusText.setGravity(Gravity.CENTER);\n        statusText.setPadding(0, dp(10), 0, 0);\n        statusPanel.addView(statusText, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n        appLayer.addView(statusPanel, new FrameLayout.LayoutParams(\n                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));'''

new_panel = '''        // Status is intentionally a small transient bottom banner, never a\n        // blocking center-screen loading card. Loading work happens silently.\n        statusPanel = new LinearLayout(this);\n        statusPanel.setOrientation(LinearLayout.HORIZONTAL);\n        statusPanel.setGravity(Gravity.CENTER_VERTICAL);\n        statusPanel.setPadding(dp(14), dp(8), dp(14), dp(8));\n        statusPanel.setBackground(rounded(0xE6151515, 999));\n        progress = new ProgressBar(this);\n        progress.setVisibility(View.GONE);\n        statusText = new TextView(this);\n        statusText.setTextColor(Color.WHITE);\n        statusText.setTextSize(12);\n        statusText.setGravity(Gravity.CENTER);\n        statusPanel.addView(statusText, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(\n                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,\n                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);\n        statusParams.leftMargin = dp(12);\n        statusParams.rightMargin = dp(12);\n        statusParams.bottomMargin = dp(18);\n        appLayer.addView(statusPanel, statusParams);\n        statusPanel.setVisibility(View.GONE);'''

main = replace_once(main, old_panel, new_panel, "status panel")

old_methods = '''    private void setStatus(String message, boolean spinning) {\n        statusPanel.setVisibility(View.VISIBLE);\n        statusText.setText(message);\n        progress.setVisibility(spinning ? View.VISIBLE : View.GONE);\n    }\n\n    private void hideStatus() {\n        statusPanel.setVisibility(View.GONE);\n    }'''

new_methods = '''    private void setStatus(String message, boolean spinning) {\n        if (statusPanel == null || statusText == null) return;\n\n        final int token = ++statusGeneration;\n        if (root != null && statusDismissRunnable != null) {\n            root.removeCallbacks(statusDismissRunnable);\n        }\n\n        // Never cover the feed with a loading card/spinner. The pager remains\n        // interactive while network/archive/media work continues in the background.\n        if (spinning) {\n            progress.setVisibility(View.GONE);\n            statusText.setText("");\n            statusPanel.setVisibility(View.GONE);\n            statusDismissRunnable = null;\n            return;\n        }\n\n        String text = message == null ? "" : message.trim();\n        if (text.isEmpty()) {\n            hideStatus();\n            return;\n        }\n\n        progress.setVisibility(View.GONE);\n        statusText.setText(text);\n        statusPanel.setVisibility(View.VISIBLE);\n\n        // Informational/error banners are self-clearing so a late successful\n        // async result can never leave a stale failure message stuck on screen.\n        statusDismissRunnable = () -> {\n            if (token != statusGeneration || statusPanel == null) return;\n            statusPanel.setVisibility(View.GONE);\n            statusText.setText("");\n            statusDismissRunnable = null;\n        };\n        if (root != null) root.postDelayed(statusDismissRunnable, 3500L);\n    }\n\n    private void hideStatus() {\n        statusGeneration++;\n        if (root != null && statusDismissRunnable != null) {\n            root.removeCallbacks(statusDismissRunnable);\n        }\n        statusDismissRunnable = null;\n        if (progress != null) progress.setVisibility(View.GONE);\n        if (statusText != null) statusText.setText("");\n        if (statusPanel != null) statusPanel.setVisibility(View.GONE);\n    }'''

main = replace_once(main, old_methods, new_methods, "status methods")

main = replace_once(
    main,
    '''        postAdapter.appendPosts(unique);\n        gridAdapter.appendPosts(unique);''',
    '''        postAdapter.appendPosts(unique);\n        gridAdapter.appendPosts(unique);\n        if (!unique.isEmpty()) hideStatus();''',
    "append status clear")

main = replace_once(
    main,
    '''        postAdapter.setPosts(visible);\n        gridAdapter.setPosts(visible);''',
    '''        postAdapter.setPosts(visible);\n        gridAdapter.setPosts(visible);\n        if (!visible.isEmpty()) hideStatus();''',
    "replace status clear")

# Keep the transient banner above the system navigation inset.
anchor = '''        if (compactMenuButton != null && compactMenuButton.getLayoutParams() instanceof FrameLayout.LayoutParams) {'''
insert = '''        if (statusPanel != null && statusPanel.getLayoutParams() instanceof FrameLayout.LayoutParams) {\n            FrameLayout.LayoutParams statusParams = (FrameLayout.LayoutParams) statusPanel.getLayoutParams();\n            statusParams.bottomMargin = systemBottomPx + dp(18);\n            statusPanel.setLayoutParams(statusParams);\n        }\n\n'''
if insert not in main:
    if main.count(anchor) != 1:
        raise SystemExit(f"status inset anchor: expected 1, found {main.count(anchor)}")
    main = main.replace(anchor, insert + anchor, 1)

main_path.write_text(main)

build_path = Path("app/build.gradle")
build = build_path.read_text()
if 'versionCode 45' not in build:
    build = replace_once(build, 'versionCode 44', 'versionCode 45', 'versionCode')
if 'versionName "3.8.11"' not in build:
    build = replace_once(build, 'versionName "3.8.10"', 'versionName "3.8.11"', 'versionName')
build_path.write_text(build)

print("Applied v3.8.11 nonblocking transient status hotfix")
