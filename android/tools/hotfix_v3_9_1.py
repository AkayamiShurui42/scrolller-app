from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()

old_joined = '''        if (joinedOnlyFilter && !subscriptionNames.isEmpty()
                && !subscriptionNames.contains(community)) return false;'''
new_joined = '''        // Joined-only is a discovery constraint, not permission to erase a feed
        // the user explicitly opened. Direct subreddit, multi-preset, and
        // subreddit-scoped search contexts always honor the explicit selection.
        if (shouldApplyJoinedOnlyFilter() && !subscriptionNames.isEmpty()
                && !subscriptionNames.contains(community)) return false;'''
main = replace_once(main, old_joined, new_joined, "joined-only filter context")

marker = '''    private boolean passesDiscoveryFilters(RedditPost post) {'''
helper = '''    private boolean shouldApplyJoinedOnlyFilter() {
        if (!joinedOnlyFilter) return false;
        if (screen == Screen.HOME) {
            if ("subreddit".equals(context) || "multi".equals(context)) return false;
        }
        if (screen == Screen.SEARCH && "subreddit".equals(searchScope)) return false;
        return true;
    }

'''
if helper not in main:
    at = main.find(marker)
    if at < 0:
        raise SystemExit("joined-only helper insertion marker missing")
    main = main[:at] + helper + main[at:]

old_diag = '''        out.append("Subreddit scope: ").append(joinedOnlyFilter ? "Joined only" : "Any").append('\\n');'''
new_diag = '''        out.append("Subreddit scope: ");
        if (!joinedOnlyFilter) out.append("Any");
        else if (!shouldApplyJoinedOnlyFilter()) out.append("Joined only (ignored for explicit selection)");
        else out.append("Joined only");
        out.append('\\n');'''
main = replace_once(main, old_diag, new_diag, "diagnostic joined-only context")

main_path.write_text(main)

build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, 'versionCode 47', 'versionCode 48', 'versionCode')
build = replace_once(build, 'versionName "3.9.0"', 'versionName "3.9.1"', 'versionName')
build_path.write_text(build)

print("Applied v3.9.1 explicit subreddit scope fix")
