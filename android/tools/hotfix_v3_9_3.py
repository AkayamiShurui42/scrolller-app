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

old_install = '''private void installCompactNavigation() {
        if (compactMenuButton != null) return;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        compactMenuButton = new Button(this);'''
new_install = '''private void detachLegacyChrome() {
        // The compact UI replaces the old top/bottom chrome. GONE is not enough:
        // remove the legacy views from appLayer so no later state/inset update can
        // make them draw underneath the replacement interface.
        if (topBar != null) {
            topBar.setVisibility(View.GONE);
            if (topBar.getParent() instanceof ViewGroup) {
                ((ViewGroup) topBar.getParent()).removeView(topBar);
            }
        }
        if (bottomBar != null) {
            bottomBar.setVisibility(View.GONE);
            if (bottomBar.getParent() instanceof ViewGroup) {
                ((ViewGroup) bottomBar.getParent()).removeView(bottomBar);
            }
        }
    }

private void installCompactNavigation() {
        detachLegacyChrome();
        if (compactMenuButton != null) return;
        compactMenuButton = new Button(this);'''
main = replace_once(main, old_install, new_install, "detach legacy chrome")

old_insets = '''        if (topBar == null) return;

        boolean compactTop = screen == Screen.ACCOUNT;
        int topContent = compactTop ? dp(52) : dp(96);
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        tp.height = systemTopPx + topContent;
        topBar.setLayoutParams(tp);
        topBar.setPadding(dp(8), systemTopPx + dp(4), dp(8), dp(4));

        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
        bp.height = systemBottomPx + dp(64);
        bottomBar.setLayoutParams(bp);
        bottomBar.setPadding(dp(5), dp(3), dp(5), systemBottomPx + dp(3));

        gridView.setPadding(0, systemTopPx + dp(96), 0, systemBottomPx + dp(64));

        FrameLayout.LayoutParams ap = (FrameLayout.LayoutParams) accountView.getLayoutParams();
        ap.topMargin = systemTopPx + dp(52);
        ap.bottomMargin = systemBottomPx + dp(64);
        accountView.setLayoutParams(ap);'''
new_insets = '''        if (gridView == null || accountView == null || browserBack == null || postAdapter == null) return;

        // Legacy chrome is detached in compact mode. Only size it if it is ever
        // deliberately attached again; otherwise do not reserve its old 96/64dp
        // bands beneath the replacement UI.
        if (topBar != null && topBar.getParent() != null
                && topBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            boolean compactTop = screen == Screen.ACCOUNT;
            int topContent = compactTop ? dp(52) : dp(96);
            FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
            tp.height = systemTopPx + topContent;
            topBar.setLayoutParams(tp);
            topBar.setPadding(dp(8), systemTopPx + dp(4), dp(8), dp(4));
        }

        if (bottomBar != null && bottomBar.getParent() != null
                && bottomBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            bp.height = systemBottomPx + dp(64);
            bottomBar.setLayoutParams(bp);
            bottomBar.setPadding(dp(5), dp(3), dp(5), systemBottomPx + dp(3));
        }

        gridView.setPadding(0, systemTopPx + dp(8), 0, systemBottomPx + dp(8));

        FrameLayout.LayoutParams ap = (FrameLayout.LayoutParams) accountView.getLayoutParams();
        ap.topMargin = systemTopPx + dp(8);
        ap.bottomMargin = systemBottomPx + dp(8);
        accountView.setLayoutParams(ap);'''
main = replace_once(main, old_insets, new_insets, "remove legacy inset reservations")

main_path.write_text(main)

build_path = Path("app/build.gradle")
build = build_path.read_text()
if 'versionCode 50' not in build:
    build = replace_once(build, 'versionCode 49', 'versionCode 50', 'versionCode')
if 'versionName "3.9.3"' not in build:
    build = replace_once(build, 'versionName "3.9.2"', 'versionName "3.9.3"', 'versionName')
build_path.write_text(build)

print("Applied v3.9.3 legacy chrome removal hotfix")
