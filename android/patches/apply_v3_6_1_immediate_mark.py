from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing immediate-mark patch target: {label}\n{old[:520]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

s = replace_required(
    s,
    'import java.util.LinkedHashMap;\nimport java.util.LinkedHashSet;\nimport java.util.List;\nimport java.util.Locale;\nimport java.util.Map;\nimport java.util.Set;',
    'import java.util.LinkedHashMap;\nimport java.util.List;\nimport java.util.Locale;\nimport java.util.Set;',
    'remove five-post imports')

s = replace_required(
    s,
    '''    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();
    private final LinkedHashMap<String, RedditPost> pendingReadPosts = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> pendingReadAfter = new LinkedHashMap<>();
    private String lastFullscreenPostId = "";
    private boolean fullscreenUserGesture = false;
    private int pendingUserFullscreenPosition = -1;''',
    '''    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();
    private String lastFullscreenPostId = "";
    private boolean fullscreenUserGesture = false;
    private int pendingUserFullscreenPosition = -1;''',
    'remove five-post fields')

old_tracking = '''    private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            return;
        }
        if (lastFullscreenPostId.equals(currentId)) return;

        RedditPost previous = null;
        for (RedditPost post : postAdapter.getPosts()) {
            if (lastFullscreenPostId.equals(post.id)) { previous = post; break; }
        }
        if (previous != null && !hiddenPosts.containsKey(previous.id)) {
            pendingReadPosts.putIfAbsent(previous.id, previous);
            pendingReadAfter.putIfAbsent(previous.id, new LinkedHashSet<>());
        }

        ArrayList<String> readyToHide = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : pendingReadAfter.entrySet()) {
            if (!entry.getKey().equals(currentId)) entry.getValue().add(currentId);
            if (entry.getValue().size() >= 5) readyToHide.add(entry.getKey());
        }

        lastFullscreenPostId = currentId;
        for (String id : readyToHide) hideReadPost(id);
        saveReadHideState();
    }

    private void hideReadPost(String id) {
        RedditPost post = pendingReadPosts.remove(id);
        pendingReadAfter.remove(id);
        if (post == null) {
            for (RedditPost candidate : postAdapter.getPosts()) {
                if (id.equals(candidate.id)) { post = candidate; break; }
            }
        }
        if (post == null || hiddenPosts.containsKey(id)) return;
        hiddenPosts.put(id, post);
        // Deliberately do not mutate postAdapter/gridAdapter here. ViewPager2 must
        // keep exactly the same list and indices for the entire current collection.
        // replacePosts()/appendUnique() apply hiddenPosts only after the user leaves,
        // changes destination/filter/sort, or otherwise reloads a collection.
    }
'''

new_tracking = '''    private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            return;
        }
        if (lastFullscreenPostId.equals(currentId)) return;

        RedditPost previous = null;
        for (RedditPost post : postAdapter.getPosts()) {
            if (lastFullscreenPostId.equals(post.id)) { previous = post; break; }
        }
        if (previous != null && previous.id != null && !previous.id.isEmpty()
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            saveReadHideState();
        }

        // The current collection is immutable. Mark now, filter next time the user
        // leaves/reloads/switches destination. Never delete from the live pager.
        lastFullscreenPostId = currentId;
    }
'''

s = replace_required(s, old_tracking, new_tracking, 'replace five-post tracking with immediate marking')

s = replace_required(
    s,
    '''        hiddenPosts.remove(post.id);
        pendingReadPosts.remove(post.id);
        pendingReadAfter.remove(post.id);
        feedSeenPostIds.remove(post.id);''',
    '''        hiddenPosts.remove(post.id);
        feedSeenPostIds.remove(post.id);''',
    'restore only hidden state')

old_load = '''    private void loadReadHideState() {
        hiddenPosts.clear();
        pendingReadPosts.clear();
        pendingReadAfter.clear();
        try {
            JSONArray hidden = new JSONArray(prefs.getString("hiddenPosts", "[]"));
            for (int i = 0; i < hidden.length(); i++) {
                RedditPost post = postFromJson(hidden.optJSONObject(i));
                if (post != null && !post.id.isEmpty()) hiddenPosts.put(post.id, post);
            }

            JSONArray pending = new JSONArray(prefs.getString("pendingReadPosts", "[]"));
            for (int i = 0; i < pending.length(); i++) {
                JSONObject item = pending.optJSONObject(i);
                if (item == null) continue;
                RedditPost post = postFromJson(item.optJSONObject("post"));
                if (post == null || post.id.isEmpty() || hiddenPosts.containsKey(post.id)) continue;
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                JSONArray seenArray = item.optJSONArray("seenAfter");
                if (seenArray != null) {
                    for (int j = 0; j < seenArray.length(); j++) {
                        String seenId = seenArray.optString(j, "");
                        if (!seenId.isEmpty() && !seenId.equals(post.id)) seen.add(seenId);
                    }
                }
                pendingReadPosts.put(post.id, post);
                pendingReadAfter.put(post.id, seen);
            }
        } catch (Exception ignored) {}
    }
'''

new_load = '''    private void loadReadHideState() {
        hiddenPosts.clear();
        try {
            JSONArray hidden = new JSONArray(prefs.getString("hiddenPosts", "[]"));
            for (int i = 0; i < hidden.length(); i++) {
                RedditPost post = postFromJson(hidden.optJSONObject(i));
                if (post != null && !post.id.isEmpty()) hiddenPosts.put(post.id, post);
            }
            prefs.edit().remove("pendingReadPosts").apply();
        } catch (Exception ignored) {}
    }
'''

s = replace_required(s, old_load, new_load, 'remove persisted five-post queue')

old_save = '''    private void saveReadHideState() {
        try {
            JSONArray hidden = new JSONArray();
            for (RedditPost post : hiddenPosts.values()) hidden.put(postToJson(post));

            JSONArray pending = new JSONArray();
            for (Map.Entry<String, RedditPost> entry : pendingReadPosts.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("post", postToJson(entry.getValue()));
                JSONArray seen = new JSONArray();
                LinkedHashSet<String> values = pendingReadAfter.get(entry.getKey());
                if (values != null) for (String id : values) seen.put(id);
                item.put("seenAfter", seen);
                pending.put(item);
            }

            prefs.edit()
                    .putString("hiddenPosts", hidden.toString())
                    .putString("pendingReadPosts", pending.toString())
                    .apply();
        } catch (Exception ignored) {}
    }
'''

new_save = '''    private void saveReadHideState() {
        try {
            JSONArray hidden = new JSONArray();
            for (RedditPost post : hiddenPosts.values()) hidden.put(postToJson(post));
            prefs.edit()
                    .putString("hiddenPosts", hidden.toString())
                    .remove("pendingReadPosts")
                    .apply();
        } catch (Exception ignored) {}
    }
'''

s = replace_required(s, old_save, new_save, 'persist hidden snapshots only')

s = s.replace('advance another post\'s five-item delay.', 'mark a post read.')

path.write_text(s)
print('Applied v3.6.1 immediate fullscreen mark with deferred collection filtering')
