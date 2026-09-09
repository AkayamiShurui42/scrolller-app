from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
text = path.read_text()

text = replace_once(
    text,
    "    private RedditSessionEngine engine;\n    private SharedPreferences prefs;",
    "    private RedditSessionEngine engine;\n    private SharedPreferences prefs;\n    private ReadHideStore readHideStore;",
    "read-hide store field")

text = replace_once(
    text,
    "        prefs = getSharedPreferences(\"native-redview\", MODE_PRIVATE);\n        loadSubredditPresets();",
    "        prefs = getSharedPreferences(\"native-redview\", MODE_PRIVATE);\n        readHideStore = new ReadHideStore(this);\n        loadSubredditPresets();",
    "read-hide store init")

old_communities = '''        ArrayList<String> communities = new ArrayList<>();
        for (RedditPost post : hiddenPosts.values()) {
            if (post == null || post.subreddit == null || post.subreddit.isEmpty()) continue;
            boolean exists = false;
            for (String existing : communities) {
                if (existing.equalsIgnoreCase(post.subreddit)) { exists = true; break; }
            }
            if (!exists) communities.add(post.subreddit);
        }
        communities.sort(String.CASE_INSENSITIVE_ORDER);'''
new_communities = '''        ArrayList<String> communities = new ArrayList<>(readHideStore.listCommunities());
        communities.sort(String.CASE_INSENSITIVE_ORDER);'''
text = replace_once(text, old_communities, new_communities, "hidden communities")

restore_pattern = re.compile(r'''    private void restoreHiddenGroup\(String kind, String community\) \{.*?\n    \}\n\n    private void showFavoriteSortSheet\(\) \{''', re.S)
restore_repl = '''    private void restoreHiddenGroup(String kind, String community) {
        ArrayList<String> removeIds = new ArrayList<>(readHideStore.deleteGroup(kind, community));
        for (String id : removeIds) {
            hiddenPosts.remove(id);
            feedSeenPostIds.remove(id);
        }
        loadHiddenPostsView();
    }

    private void showFavoriteSortSheet() {'''
text, n = restore_pattern.subn(restore_repl, text, count=1)
if n != 1:
    raise SystemExit(f"restoreHiddenGroup: expected 1 match, found {n}")

old_hidden_view = '''    private void loadHiddenPostsView() {
        loading = false;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        ArrayList<RedditPost> items = new ArrayList<>();
        for (RedditPost post : hiddenPosts.values()) {
            if (matchesMedia(post)) items.add(post);
        }
        applyFavoriteOrdering(items);
        replacePosts(items);
        if (items.isEmpty()) setStatus("No locally hidden posts.", false);
        else hideStatus();
        updateChrome();
        restorePendingPosition();
    }'''
new_hidden_view = '''    private void loadHiddenPostsView() {
        loading = false;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        ArrayList<RedditPost> items = new ArrayList<>();
        for (RedditPost post : readHideStore.loadRecent(1000)) {
            if (post != null && matchesMedia(post)) items.add(post);
        }
        applyFavoriteOrdering(items);
        replacePosts(items);
        if (items.isEmpty()) {
            setStatus("No locally hidden posts.", false);
        } else if (hiddenPosts.size() > 1000) {
            setStatus("Showing the 1,000 most recent hidden posts of " + hiddenPosts.size() + ".", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }'''
text = replace_once(text, old_hidden_view, new_hidden_view, "hidden library")

old_track = '''        if (actuallyViewed
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            saveReadHideState();
        }'''
new_track = '''        if (actuallyViewed
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            readHideStore.hideAsync(previous);
            trimHiddenPostCache();
        }'''
text = replace_once(text, old_track, new_track, "track hidden")

old_restore = '''    private void restoreHiddenPost(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        hiddenPosts.remove(post.id);
        feedSeenPostIds.remove(post.id);
        saveReadHideState();
        if (showingHiddenLibrary()) loadHiddenPostsView();
        else reloadCurrent();
    }'''
new_restore = '''    private void restoreHiddenPost(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        hiddenPosts.remove(post.id);
        feedSeenPostIds.remove(post.id);
        readHideStore.deleteAsync(post.id);
        if (showingHiddenLibrary()) loadHiddenPostsView();
        else reloadCurrent();
    }'''
text = replace_once(text, old_restore, new_restore, "restore hidden")

state_pattern = re.compile(r'''    private void loadReadHideState\(\) \{.*?\n    \}\n\n    private JSONObject postToJson''', re.S)
state_repl = '''    private void loadReadHideState() {
        hiddenPosts.clear();
        try {
            // One-time migration from the old giant SharedPreferences JSON. For a
            // very large legacy value, scan IDs directly instead of constructing a
            // JSONArray/JSONObject graph and duplicating tens of MB on the heap.
            String legacy = prefs.getString("hiddenPosts", "");
            if (legacy != null && !legacy.isEmpty() && !legacy.equals("[]")) {
                LinkedHashSet<String> legacyIds = scanLegacyHiddenIds(legacy);
                if (legacy.length() <= 2_000_000) {
                    ArrayList<RedditPost> legacyPosts = new ArrayList<>();
                    try {
                        JSONArray hidden = new JSONArray(legacy);
                        for (int i = 0; i < hidden.length(); i++) {
                            RedditPost post = postFromJson(hidden.optJSONObject(i));
                            if (post != null && post.id != null && !post.id.isEmpty()) {
                                legacyPosts.add(post);
                            }
                        }
                    } catch (Exception ignored) {}
                    readHideStore.importPosts(legacyPosts);
                }
                readHideStore.importIds(legacyIds);
                // commit() is intentional for the one-time migration so the huge
                // legacy string is removed from the in-memory preference map now.
                prefs.edit()
                        .remove("hiddenPosts")
                        .remove("pendingReadPosts")
                        .commit();
            } else {
                prefs.edit().remove("pendingReadPosts").apply();
            }

            for (String id : readHideStore.loadIds()) {
                if (id != null && !id.isEmpty()) hiddenPosts.put(id, null);
            }
            for (RedditPost post : readHideStore.loadRecent(400)) {
                if (post != null && post.id != null && !post.id.isEmpty()) {
                    hiddenPosts.put(post.id, post);
                }
            }
            trimHiddenPostCache();
        } catch (Exception ignored) {
            prefs.edit().remove("hiddenPosts").remove("pendingReadPosts").apply();
        }
    }

    private LinkedHashSet<String> scanLegacyHiddenIds(String legacy) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (legacy == null || legacy.isEmpty()) return ids;
        final String marker = "\\\"id\\\":\\\"";
        int from = 0;
        while (from < legacy.length()) {
            int start = legacy.indexOf(marker, from);
            if (start < 0) break;
            start += marker.length();
            int end = legacy.indexOf('"', start);
            if (end < 0) break;
            String id = legacy.substring(start, end);
            if (!id.isEmpty()) ids.add(id);
            from = end + 1;
        }
        return ids;
    }

    private void trimHiddenPostCache() {
        int materialized = 0;
        for (RedditPost post : hiddenPosts.values()) if (post != null) materialized++;
        if (materialized <= 400) return;
        for (Map.Entry<String, RedditPost> entry : hiddenPosts.entrySet()) {
            if (materialized <= 400) break;
            if (entry.getValue() != null) {
                entry.setValue(null);
                materialized--;
            }
        }
    }

    private void saveReadHideState() {
        // v3.8.9 intentionally never serializes the full hidden library. Each hide
        // or restore is persisted incrementally by ReadHideStore.
        prefs.edit().remove("hiddenPosts").remove("pendingReadPosts").apply();
        trimHiddenPostCache();
    }

    private JSONObject postToJson'''
text, n = state_pattern.subn(state_repl, text, count=1)
if n != 1:
    raise SystemExit(f"read-hide state block: expected 1 match, found {n}")

old_saved_collect = '''        boolean hiddenChanged = false;
        for (RedditPost savedPost : collected) {
            if (savedPost != null && savedPost.id != null && hiddenPosts.remove(savedPost.id) != null) {
                hiddenChanged = true;
            }
        }
        if (hiddenChanged) saveReadHideState();'''
new_saved_collect = '''        boolean hiddenChanged = false;
        for (RedditPost savedPost : collected) {
            if (savedPost != null && savedPost.id != null && hiddenPosts.containsKey(savedPost.id)) {
                hiddenPosts.remove(savedPost.id);
                readHideStore.deleteAsync(savedPost.id);
                hiddenChanged = true;
            }
        }
        if (hiddenChanged) trimHiddenPostCache();'''
text = replace_once(text, old_saved_collect, new_saved_collect, "saved collection unhide")

old_toggle = '''                    if (post.id != null && hiddenPosts.remove(post.id) != null) {
                        saveReadHideState();
                        if (showingHiddenLibrary()) {'''
new_toggle = '''                    if (post.id != null && hiddenPosts.containsKey(post.id)) {
                        hiddenPosts.remove(post.id);
                        readHideStore.deleteAsync(post.id);
                        if (showingHiddenLibrary()) {'''
text = replace_once(text, old_toggle, new_toggle, "save post unhide")

path.write_text(text)

build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, "versionCode 42", "versionCode 43", "versionCode")
build = replace_once(build, 'versionName "3.8.8"', 'versionName "3.8.9"', "versionName")
build_path.write_text(build)

print("Applied v3.8.9 incremental read-hide persistence hotfix")
