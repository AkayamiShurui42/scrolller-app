from pathlib import Path

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one target, found {count}")
    return text.replace(old, new, 1)

main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
gradle_path = Path("app/build.gradle")
main = main_path.read_text()
gradle = gradle_path.read_text()

gradle = replace_once(gradle, 'versionCode 53', 'versionCode 54', 'versionCode')
gradle = replace_once(gradle, 'versionName "3.9.6"', 'versionName "3.9.7"', 'versionName')

# Session-scoped read catalog. Read posts stay in the active pager until the user
# changes subreddit or leaves the current content tab.
main = replace_once(
    main,
    '    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();\n',
    '    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();\n'
    '    private final LinkedHashMap<String, RedditPost> sessionReadCatalog = new LinkedHashMap<>();\n',
    'session read catalog field',
)

old_track = '''private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            return;
        }
        if (lastFullscreenPostId.equals(currentId)) return;

        RedditPost previous = null;
        for (RedditPost candidate : postAdapter.getPosts()) {
            if (candidate != null && lastFullscreenPostId.equals(candidate.id)) {
                previous = candidate;
                break;
            }
        }

        // PostPagerAdapter reports readiness only after the active media meets
        // its view gate: displayed image/GIF/gallery, or rendered video with
        // >= 1 second of actual playback. Time on the pager is not enough.
        boolean actuallyViewed = previous != null
                && mediaReadyPostIds.contains(previous.id);
        if (actuallyViewed
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenContainsPostId(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            readHideStore.hideAsync(previous);
            trimHiddenPostCache();
        }
        lastFullscreenPostId = currentId;
    }
'''

new_track = '''private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            return;
        }
        if (barePostId(lastFullscreenPostId).equals(barePostId(currentId))) return;

        RedditPost previous = null;
        for (RedditPost candidate : postAdapter.getPosts()) {
            if (candidate != null
                    && barePostId(lastFullscreenPostId).equals(barePostId(candidate.id))) {
                previous = candidate;
                break;
            }
        }

        // A completed manual swipe catalogs the page as read, but the active pager
        // stays immutable. The catalog is committed only when the user changes
        // subreddit or leaves this content tab.
        if (previous != null
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedContainsPostId(previous.id)
                && !hiddenContainsPostId(previous.id)) {
            String key = barePostId(previous.id);
            if (!key.isEmpty()) sessionReadCatalog.put(key, previous);
        }
        lastFullscreenPostId = currentId;
    }

    private void commitSessionReadCatalog() {
        if (sessionReadCatalog.isEmpty()) {
            lastFullscreenPostId = "";
            return;
        }
        for (RedditPost post : new ArrayList<>(sessionReadCatalog.values())) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (post.saved || savedContainsPostId(post.id) || hiddenContainsPostId(post.id)) continue;
            hiddenPosts.put(post.id, post);
            readHideStore.hideAsync(post);
        }
        sessionReadCatalog.clear();
        trimHiddenPostCache();
        lastFullscreenPostId = "";
        fullscreenUserGesture = false;
        pendingUserFullscreenPosition = -1;
    }
'''
main = replace_once(main, old_track, new_track, 'session-scoped swipe read catalog')

main = replace_once(
    main,
    '''    private boolean savedContainsPostId(String id) {
        if (id == null || id.isEmpty()) return false;
        if (savedPostIds.contains(id)) return true;
        String bare = barePostId(id);
        if (bare.isEmpty()) return false;
        return savedPostIds.contains(bare) || savedPostIds.contains("t3_" + bare);
    }

    private boolean isSavedForUnread(RedditPost post) {
''',
    '''    private boolean savedContainsPostId(String id) {
        if (id == null || id.isEmpty()) return false;
        if (savedPostIds.contains(id)) return true;
        String bare = barePostId(id);
        if (bare.isEmpty()) return false;
        return savedPostIds.contains(bare) || savedPostIds.contains("t3_" + bare);
    }

    private boolean sessionReadContainsPostId(String id) {
        String bare = barePostId(id);
        return !bare.isEmpty() && sessionReadCatalog.containsKey(bare);
    }

    private boolean isSavedForUnread(RedditPost post) {
''',
    'session catalog ID lookup',
)

main = replace_once(
    main,
    '''    private boolean isReadHiddenForDiscovery(RedditPost post) {
        return !showViewedPosts
                && post != null
                && post.id != null
                && !post.id.isEmpty()
                && hiddenContainsPostId(post.id);
    }
''',
    '''    private boolean isReadHiddenForDiscovery(RedditPost post) {
        return !showViewedPosts
                && post != null
                && post.id != null
                && !post.id.isEmpty()
                && (hiddenContainsPostId(post.id) || sessionReadContainsPostId(post.id));
    }
''',
    'session catalog participates in unread filtering',
)

# Commit the current browsing catalog only at a true navigation boundary.
for old, new, label in [
    ('    private void navigateHome(String which, boolean pushHistory) {\n',
     '    private void navigateHome(String which, boolean pushHistory) {\n        commitSessionReadCatalog();\n',
     'commit catalog on home switch'),
    ('    private void openSubredditFeed(String name) {\n        if (name == null || name.isEmpty()) return;\n',
     '    private void openSubredditFeed(String name) {\n        if (name == null || name.isEmpty()) return;\n        commitSessionReadCatalog();\n',
     'commit catalog on subreddit switch'),
    ('    private void openSearchScreen() {\n',
     '    private void openSearchScreen() {\n        commitSessionReadCatalog();\n',
     'commit catalog on search tab'),
    ('    private void openUserProfile(String name) {\n        if (name == null || name.isEmpty()) return;\n',
     '    private void openUserProfile(String name) {\n        if (name == null || name.isEmpty()) return;\n        commitSessionReadCatalog();\n',
     'commit catalog on user tab'),
    ('    private void loadFavorites() {\n',
     '    private void loadFavorites() {\n        commitSessionReadCatalog();\n',
     'commit catalog on collections tab'),
    ('    private void showAccount() {\n',
     '    private void showAccount() {\n        commitSessionReadCatalog();\n',
     'commit catalog on account tab'),
    ('    private void openMultiSubredditFeed(String presetName, List<String> communities) {\n',
     '    private void openMultiSubredditFeed(String presetName, List<String> communities) {\n        commitSessionReadCatalog();\n',
     'commit catalog on preset switch'),
]:
    main = replace_once(main, old, new, label)

# If a catalogued post is explicitly saved, it must never later be committed as read.
main = replace_once(
    main,
    '                if (post.saved) {\n                    if (post.id != null && !post.id.isEmpty()) savedPostIds.add(post.id);\n',
    '                if (post.saved) {\n'
    '                    if (post.id != null && !post.id.isEmpty()) {\n'
    '                        savedPostIds.add(post.id);\n'
    '                        sessionReadCatalog.remove(barePostId(post.id));\n'
    '                    }\n',
    'saved post leaves session read catalog',
)

# Preserve the catalog if the activity merely pauses for a browser/share action, but
# do not lose it if Android actually destroys the activity.
main = replace_once(
    main,
    '    protected void onDestroy() {\n        if (postAdapter != null) postAdapter.releaseAll();\n',
    '    protected void onDestroy() {\n        commitSessionReadCatalog();\n        if (postAdapter != null) postAdapter.releaseAll();\n',
    'commit catalog on destroy',
)

old_first = '''            // Make subreddit/feed entry feel immediate. Final ordering still runs
            // once the small reservoir is complete.
            if (reset && page == 0 && !collected.isEmpty()) {
                replacePosts(new ArrayList<>(collected));
                hideStatus();
                updateChrome();
            }
'''
new_first = '''            // Make subreddit/feed entry feel immediate, but honor Random before
            // anything reaches the screen. Previously page 0 was shown in Reddit's
            // remote "new" order and only later pages were shuffled.
            if (reset && page == 0 && !collected.isEmpty()) {
                ArrayList<RedditPost> firstVisible = new ArrayList<>(collected);
                if (sort.equals("random")) Collections.shuffle(firstVisible);
                replacePosts(firstVisible);
                hideStatus();
                updateChrome();
            }
'''
main = replace_once(main, old_first, new_first, 'randomize first visible batch')

old_multi = '''                // Show the first successful subreddit immediately while the remaining
                // preset members continue through the serialized request queue.
                if (reset && postAdapter.getItemCount() == 0 && !collected.isEmpty()) {
                    replacePosts(new ArrayList<>(collected));
                    hideStatus();
                    updateChrome();
                }
'''
new_multi = '''                // Show the first successful subreddit immediately while the remaining
                // preset members continue through the serialized request queue.
                if (reset && postAdapter.getItemCount() == 0 && !collected.isEmpty()) {
                    ArrayList<RedditPost> firstVisible = new ArrayList<>(collected);
                    if (sort.equals("random")) Collections.shuffle(firstVisible);
                    replacePosts(firstVisible);
                    hideStatus();
                    updateChrome();
                }
'''
main = replace_once(main, old_multi, new_multi, 'randomize multi fallback first batch')

main_path.write_text(main)
gradle_path.write_text(gradle)
print("Applied Reddit Media v3.9.7 session read catalog + immediate Random hotfix")
