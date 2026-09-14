from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
gradle_path = Path("app/build.gradle")
main = main_path.read_text()
gradle = gradle_path.read_text()

# ---------------------------------------------------------------------------
# Version.
# ---------------------------------------------------------------------------
gradle = replace_once(gradle, 'versionCode 52', 'versionCode 53', 'versionCode 53')
gradle = replace_once(gradle, 'versionName "3.9.5"', 'versionName "3.9.6"', 'versionName 3.9.6')

# ---------------------------------------------------------------------------
# Save is now a category command, not an immediate one-tap post mutation.
# ---------------------------------------------------------------------------
main = replace_once(
    main,
    '        saveCommandButton = addNavButton("Save", this::saveCurrentPostFromCommandBar);',
    '        saveCommandButton = addNavButton("Save", this::showSaveCommandSheet);',
    'Save command opens category sheet',
)

old_save = '''    private void saveCurrentPostFromCommandBar() {
        RedditPost post = currentPagerPost();
        if (post == null) {
            setStatus("No current post to save.", false);
            return;
        }
        onSave(post);
    }

    private void updateSaveCommandState() {
        if (saveCommandButton == null) return;
        RedditPost post = currentPagerPost();
        boolean available = post != null && post.id != null && !post.id.isEmpty();
        saveCommandButton.setEnabled(available);
        if (!available) {
            saveCommandButton.setText("Save");
            saveCommandButton.setAlpha(0.45f);
            return;
        }
        boolean saved = post.saved || savedPostIds.contains(post.id);
        saveCommandButton.setText(saved ? "Saved" : "Save");
        saveCommandButton.setAlpha(1f);
    }
'''
new_save = '''    private void showSaveCommandSheet() {
        RedditPost post = currentPagerPost();
        String targetSubreddit = post != null ? cleanSubredditName(post.subreddit) : "";
        if (targetSubreddit.isEmpty()
                && screen == Screen.HOME
                && context.equals("subreddit")
                && subreddit != null) {
            targetSubreddit = cleanSubredditName(subreddit);
        }

        if (post == null && targetSubreddit.isEmpty()) {
            setStatus("Nothing here to save.", false);
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Save");

        if (post != null && post.id != null && !post.id.isEmpty()) {
            boolean saved = post.saved || savedContainsPostId(post.id);
            Button savePost = sheetButton(saved ? "Unsave post" : "Save post");
            body.addView(savePost, sectionButtonParams());
            savePost.setOnClickListener(v -> {
                dialog.dismiss();
                onSave(post);
            });
        }

        if (!targetSubreddit.isEmpty()) {
            final String target = targetSubreddit;
            boolean favorite = favoriteSubreddits.contains(target.toLowerCase(Locale.US));
            Button saveSubreddit = sheetButton(
                    (favorite ? "Remove saved subreddit" : "Save subreddit") + " · r/" + target);
            body.addView(saveSubreddit, sectionButtonParams());
            saveSubreddit.setOnClickListener(v -> {
                dialog.dismiss();
                toggleFavoriteSubreddit(target);
            });
        }

        dialog.setContentView(body);
        dialog.show();
    }

    private void updateSaveCommandState() {
        if (saveCommandButton == null) return;
        RedditPost post = currentPagerPost();
        boolean subredditAvailable = screen == Screen.HOME
                && context.equals("subreddit")
                && subreddit != null
                && !subreddit.isEmpty();
        boolean available = post != null || subredditAvailable;
        saveCommandButton.setEnabled(available);
        saveCommandButton.setText("Save");
        saveCommandButton.setAlpha(available ? 1f : 0.45f);
    }
'''
main = replace_once(main, old_save, new_save, 'Save category sheet')

# ---------------------------------------------------------------------------
# Restore Join/Leave to the redesigned Home command surface. The old top-bar
# button still owns the API plumbing but is intentionally hidden by the new UI.
# ---------------------------------------------------------------------------
old_home_head = '''        LinearLayout body = sheetBody("Home");
        scroll.addView(body);

        Button home = sheetButton("Go to Home");
'''
new_home_head = '''        LinearLayout body = sheetBody("Home");
        scroll.addView(body);

        if (screen == Screen.HOME && context.equals("subreddit")
                && subreddit != null && !subreddit.isEmpty()) {
            String target = cleanSubredditName(subreddit);
            boolean subscribed = subscriptionNames.contains(target.toLowerCase(Locale.US));
            Button membership = sheetButton(
                    (subscribed ? "Leave subreddit" : "Join subreddit") + " · r/" + target);
            if (subscribed) membership.setTextColor(0xFFFFB0B0);
            body.addView(membership, sectionButtonParams());
            membership.setOnClickListener(v -> {
                dialog.dismiss();
                toggleSubredditSubscription();
            });
        }

        Button home = sheetButton("Go to Home");
'''
main = replace_once(main, old_home_head, new_home_head, 'Home Join/Leave command')

# ---------------------------------------------------------------------------
# Normalize t3_<id> and bare <id> consistently for Read and Saved state. Reddit
# live listings use t3_ IDs while archive/secondary paths can expose the bare ID.
# ---------------------------------------------------------------------------
old_saved = '''    private boolean isSavedForUnread(RedditPost post) {
        return post != null
                && post.id != null
                && !post.id.isEmpty()
                && (post.saved || savedPostIds.contains(post.id));
    }
'''
new_saved = '''    private String barePostId(String id) {
        if (id == null) return "";
        String clean = id.trim().toLowerCase(Locale.US);
        if (clean.startsWith("t3_")) clean = clean.substring(3);
        return clean;
    }

    private boolean hiddenContainsPostId(String id) {
        if (id == null || id.isEmpty()) return false;
        if (hiddenPosts.containsKey(id)) return true;
        String bare = barePostId(id);
        if (bare.isEmpty()) return false;
        return hiddenPosts.containsKey(bare) || hiddenPosts.containsKey("t3_" + bare);
    }

    private boolean savedContainsPostId(String id) {
        if (id == null || id.isEmpty()) return false;
        if (savedPostIds.contains(id)) return true;
        String bare = barePostId(id);
        if (bare.isEmpty()) return false;
        return savedPostIds.contains(bare) || savedPostIds.contains("t3_" + bare);
    }

    private boolean isSavedForUnread(RedditPost post) {
        return post != null
                && post.id != null
                && !post.id.isEmpty()
                && (post.saved || savedContainsPostId(post.id));
    }
'''
main = replace_once(main, old_saved, new_saved, 'canonical saved/read IDs')

old_read = '''    private boolean isReadHiddenForDiscovery(RedditPost post) {
        return !showViewedPosts
                && post != null
                && post.id != null
                && !post.id.isEmpty()
                && hiddenPosts.containsKey(post.id);
    }
'''
new_read = '''    private boolean isReadHiddenForDiscovery(RedditPost post) {
        return !showViewedPosts
                && post != null
                && post.id != null
                && !post.id.isEmpty()
                && hiddenContainsPostId(post.id);
    }
'''
main = replace_once(main, old_read, new_read, 'canonical read lookup')

# Any remaining direct post-ID hidden checks should use the same canonical lookup.
main = main.replace('hiddenPosts.containsKey(post.id)', 'hiddenContainsPostId(post.id)')
main = main.replace('hiddenPosts.containsKey(previous.id)', 'hiddenContainsPostId(previous.id)')
main = main.replace('hiddenPosts.containsKey(savedPost.id)', 'hiddenContainsPostId(savedPost.id)')
main = main.replace('savedPostIds.contains(post.id)', 'savedContainsPostId(post.id)')

# ---------------------------------------------------------------------------
# Media-level dedupe now applies to every unread/discovery collection, not only
# Random. This prevents the same asset from showing repeatedly through crossposts,
# archive supplementation, Search lead-ins, or source mirrors.
# ---------------------------------------------------------------------------
old_replace_mode = '''        boolean hiddenLibrary = showingHiddenLibrary();
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        boolean randomFeed = screen == Screen.HOME && sort.equals("random");
        postAdapter.setHiddenMode(hiddenLibrary);
'''
new_replace_mode = '''        boolean hiddenLibrary = showingHiddenLibrary();
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        boolean dedupeMedia = !hiddenLibrary && !favoritesSaved;
        postAdapter.setHiddenMode(hiddenLibrary);
'''
main = replace_once(main, old_replace_mode, new_replace_mode, 'replacePosts media dedupe mode')

old_append_mode = '''        if (showingHiddenLibrary() || incoming == null || incoming.isEmpty()) return;
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        boolean randomFeed = screen == Screen.HOME && sort.equals("random");

        Set<String> ids = new HashSet<>();
'''
new_append_mode = '''        if (showingHiddenLibrary() || incoming == null || incoming.isEmpty()) return;
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        boolean dedupeMedia = !favoritesSaved;

        Set<String> ids = new HashSet<>();
'''
main = replace_once(main, old_append_mode, new_append_mode, 'append media dedupe mode')

random_blocks = main.count('if (randomFeed) {')
if random_blocks == 0 and 'if (dedupeMedia) {' in main:
    pass
elif random_blocks != 4:
    raise SystemExit(f'expected 4 randomFeed media blocks, found {random_blocks}')
else:
    main = main.replace('if (randomFeed) {', 'if (dedupeMedia) {')

# Canonicalize known Reddit/RedGIFs media URL families so CDN/manifest variants
# collapse to one asset key instead of masquerading as different posts.
old_media_key = '''    private String canonicalMediaKey(RedditPost post) {
        if (post == null) return "";
        String value = post.videoUrl != null && !post.videoUrl.isEmpty()
                ? post.videoUrl : post.sourceUrl;
        if ((value == null || value.isEmpty()) && post.imageUrls != null && !post.imageUrls.isEmpty()) {
            value = post.imageUrls.get(0);
        }
        if (value == null) return "";
        String clean = value.trim().toLowerCase(Locale.US);
        int queryAt = clean.indexOf('?');
        if (queryAt >= 0) clean = clean.substring(0, queryAt);
        int hashAt = clean.indexOf('#');
        if (hashAt >= 0) clean = clean.substring(0, hashAt);
        return clean;
    }
'''
new_media_key = '''    private String canonicalMediaKey(RedditPost post) {
        if (post == null) return "";
        String value = post.videoUrl != null && !post.videoUrl.isEmpty()
                ? post.videoUrl : post.sourceUrl;
        if ((value == null || value.isEmpty()) && post.imageUrls != null && !post.imageUrls.isEmpty()) {
            value = post.imageUrls.get(0);
        }
        if (value == null) return "";

        String clean = value.trim().toLowerCase(Locale.US);
        int queryAt = clean.indexOf('?');
        if (queryAt >= 0) clean = clean.substring(0, queryAt);
        int hashAt = clean.indexOf('#');
        if (hashAt >= 0) clean = clean.substring(0, hashAt);
        if (clean.startsWith("redgifs:")) return clean;

        try {
            Uri uri = Uri.parse(clean);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || host.isEmpty()) return clean;
            host = host.toLowerCase(Locale.US);
            path = path == null ? "" : path.toLowerCase(Locale.US);

            String[] parts = path.split("/");
            String first = "";
            String last = "";
            for (String part : parts) {
                if (part == null || part.isEmpty()) continue;
                if (first.isEmpty()) first = part;
                last = part;
            }

            if (host.equals("v.redd.it") && !first.isEmpty()) {
                return "vreddit:" + first;
            }
            if ((host.equals("i.redd.it") || host.equals("preview.redd.it")) && !last.isEmpty()) {
                return "reddit-image:" + last;
            }
            if (host.endsWith("redgifs.com") || host.endsWith("redgifsusercontent.com")) {
                if (!last.isEmpty()) {
                    int dot = last.lastIndexOf('.');
                    if (dot > 0) last = last.substring(0, dot);
                    return "redgifs:" + last;
                }
            }
            return host + path;
        } catch (Exception ignored) {
            return clean;
        }
    }
'''
main = replace_once(main, old_media_key, new_media_key, 'canonical media families')

main_path.write_text(main)
gradle_path.write_text(gradle)
print('Applied Reddit Media v3.9.6 subscribe/save/dedupe hotfix')
