from pathlib import Path
import re


def require_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Missing v3.6.9 target: {label}\n{old[:900]}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# MainActivity: explicit Unread/Saved/Hidden state, contextual content filters,
# and deferred adapter inserts while ViewPager2 is moving.
# ---------------------------------------------------------------------------
path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# Persistent preferences and saved-id state. Saved IDs are kept separately from
# Hidden so Save means "move out of Unread into Saved", never "also Hidden".
s = require_replace(
    s,
    '    private boolean muted = true;\n    private boolean fullscreenChromeVisible = true;',
    '    private boolean muted = true;\n    private boolean blockLgbtTopics = false;\n    private boolean blockGore = false;\n    private boolean fullscreenChromeVisible = true;',
    'content filter preference fields')

s = require_replace(
    s,
    '    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();',
    '    private final Set<String> savedPostIds = new HashSet<>();\n'
    '    private final ArrayList<RedditPost> deferredAppends = new ArrayList<>();\n'
    '    private boolean deferredAppendScheduled = false;\n'
    '    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();',
    'saved IDs and deferred append state')

s = require_replace(
    s,
    '        muted = prefs.getBoolean("muted", true);\n        loadReadHideState();',
    '        muted = prefs.getBoolean("muted", true);\n'
    '        blockLgbtTopics = prefs.getBoolean("blockLgbtTopics", false);\n'
    '        blockGore = prefs.getBoolean("blockGore", false);\n'
    '        Set<String> persistedSaved = prefs.getStringSet("savedPostIds", null);\n'
    '        if (persistedSaved != null) savedPostIds.addAll(persistedSaved);\n'
    '        loadReadHideState();',
    'load filter and saved preferences')

# Replace media matching with one state-aware gate used by Reddit, Search,
# profiles and supplemental Scrolller discovery. Favorites is a management
# library and deliberately bypasses contextual/state filtering.
match = re.search(
    r'    private boolean matchesMedia\(RedditPost post\) \{.*?\n    \}\n\n    private void replacePosts',
    s,
    flags=re.S)
if not match:
    raise SystemExit('Missing v3.6.9 target: matchesMedia block')
new_match = r'''    private boolean matchesMediaType(RedditPost post) {
        if (post == null) return false;
        if (media.equals("all")) return true;
        if (media.equals("image")) {
            return post.mediaKind == RedditPost.MediaKind.IMAGE
                    || post.mediaKind == RedditPost.MediaKind.GALLERY;
        }
        if (media.equals("video")) {
            return post.mediaKind == RedditPost.MediaKind.VIDEO
                    || post.mediaKind == RedditPost.MediaKind.GIF;
        }
        return false;
    }

    private boolean matchesMedia(RedditPost post) {
        if (!matchesMediaType(post)) return false;

        // Saved and Hidden are management libraries, not Unread discovery feeds.
        if (screen == Screen.FAVORITES) return true;

        if (post.id != null && !post.id.isEmpty()) {
            if (post.saved) {
                if (savedPostIds.add(post.id)) saveSavedPostIds();
                return false;
            }
            if (savedPostIds.contains(post.id) || hiddenPosts.containsKey(post.id)) return false;
        }
        return !blockedByContextFilter(post);
    }

    private boolean blockedByContextFilter(RedditPost post) {
        String title = post.title == null ? "" : post.title.toLowerCase(Locale.US);
        String sr = post.subreddit == null ? "" : post.subreddit.toLowerCase(Locale.US)
                .replace("_", "").replace("-", "");

        if (blockLgbtTopics) {
            boolean titleMatch = title.matches(
                    ".*\\b(gay|lesbian|trans|transgender|lgbt|lgbtq|queer|bisexual|nonbinary|non-binary|mtf|ftm)\\b.*");
            boolean subredditMatch = containsAny(sr,
                    "gay", "lesbian", "transgender", "transsexual", "lgbt", "lgbtq",
                    "queer", "bisexual", "nonbinary", "asktransgender", "transgone",
                    "transgirl", "transgirls", "transwomen", "transmen", "transpassing",
                    "mtf", "ftm");
            if (titleMatch || subredditMatch) return true;
        }

        if (blockGore) {
            boolean titleMatch = title.matches(
                    ".*\\b(gore|gory|blood|bloody|bleeding|dismembered|dismemberment|decapitated|decapitation|beheaded|severed|mutilated|mutilation|entrails|guts|corpse|corpses)\\b.*")
                    || title.contains("graphic injury")
                    || title.contains("exposed organ")
                    || title.contains("dead body");
            boolean subredditMatch = containsAny(sr,
                    "gore", "medicalgore", "blood", "dismember", "decapitat",
                    "mutilat", "eyeblech", "watchpeopledie");
            if (titleMatch || subredditMatch) return true;
        }
        return false;
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && value.contains(needle)) return true;
        }
        return false;
    }

    private void saveSavedPostIds() {
        prefs.edit().putStringSet("savedPostIds", new HashSet<>(savedPostIds)).apply();
    }

    private void replacePosts'''
s = s[:match.start()] + new_match + s[match.end():]

# Rebuild replacePosts/appendUnique as a three-state filter and defer adapter
# insertion notifications until the pager is idle. This keeps discovery batches
# from competing with swipe animation/layout work.
block = re.search(
    r'    private void replacePosts\(List<RedditPost> items\) \{.*?\n    \}\n\n    private void appendUnique\(List<RedditPost> incoming\) \{.*?\n    \}\n',
    s,
    flags=re.S)
if not block:
    raise SystemExit('Missing v3.6.9 target: replacePosts/appendUnique block')
new_block = r'''    private void replacePosts(List<RedditPost> items) {
        lastFullscreenPostId = "";
        mediaReadyPostIds.clear();
        mediaFailedPostIds.clear();
        boolean hiddenLibrary = showingHiddenLibrary();
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        postAdapter.setHiddenMode(hiddenLibrary);
        ArrayList<RedditPost> visible = new ArrayList<>();
        for (RedditPost post : items) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (hiddenLibrary) {
                if (!savedPostIds.contains(post.id) && !post.saved) visible.add(post);
            } else if (favoritesSaved) {
                visible.add(post);
            } else if (matchesMedia(post)) {
                visible.add(post);
            }
        }
        postAdapter.setPosts(visible);
        gridAdapter.setPosts(visible);
    }

    private void appendUnique(List<RedditPost> incoming) {
        if (incoming == null || incoming.isEmpty() || showingHiddenLibrary()) return;
        if (layoutMode.equals("fullscreen") && pager != null
                && pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            deferredAppends.addAll(incoming);
            scheduleDeferredAppend();
            return;
        }
        appendUniqueNow(incoming);
    }

    private void scheduleDeferredAppend() {
        if (deferredAppendScheduled || root == null) return;
        deferredAppendScheduled = true;
        root.postDelayed(this::flushDeferredAppends, 120L);
    }

    private void flushDeferredAppends() {
        deferredAppendScheduled = false;
        if (deferredAppends.isEmpty()) return;
        if (layoutMode.equals("fullscreen") && pager != null
                && pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            scheduleDeferredAppend();
            return;
        }
        ArrayList<RedditPost> batch = new ArrayList<>(deferredAppends);
        deferredAppends.clear();
        appendUniqueNow(batch);
    }

    private void appendUniqueNow(List<RedditPost> incoming) {
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) ids.add(post.id);
        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (!favoritesSaved && !matchesMedia(post)) continue;
            if (ids.add(post.id)) unique.add(post);
        }
        postAdapter.appendPosts(unique);
        gridAdapter.appendPosts(unique);
    }
'''
s = s[:block.start()] + new_block + s[block.end():]

# Hidden is never allowed to retain a Saved item. Also learn the complete set of
# saved media IDs whenever Favorites is refreshed.
old_finish = '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        boolean hiddenChanged = false;
        for (RedditPost savedPost : collected) {
            if (savedPost != null && savedPost.id != null && hiddenPosts.remove(savedPost.id) != null) {
                hiddenChanged = true;
            }
        }
        if (hiddenChanged) saveReadHideState();
        applyFavoriteOrdering(collected);'''
new_finish = '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        boolean hiddenChanged = false;
        boolean savedChanged = false;
        for (RedditPost savedPost : collected) {
            if (savedPost == null || savedPost.id == null || savedPost.id.isEmpty()) continue;
            savedPost.saved = true;
            if (savedPostIds.add(savedPost.id)) savedChanged = true;
            if (hiddenPosts.remove(savedPost.id) != null) hiddenChanged = true;
        }
        if (hiddenChanged) saveReadHideState();
        if (savedChanged) saveSavedPostIds();
        applyFavoriteOrdering(collected);'''
s = require_replace(s, old_finish, new_finish, 'Favorites synchronizes Saved IDs')

# Hidden library should never display a post that has moved to Saved.
s = require_replace(
    s,
    '''        for (RedditPost post : hiddenPosts.values()) {
            if (matchesMedia(post)) items.add(post);
        }''',
    '''        for (RedditPost post : hiddenPosts.values()) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (savedPostIds.contains(post.id) || post.saved) continue;
            if (matchesMediaType(post)) items.add(post);
        }''',
    'Hidden excludes Saved')

# Explicit Save/Unsave is the only live collection removal. It is isolated from
# swipe-based read marking and resets the baseline before modifying adapter data.
save_match = re.search(
    r'    @Override\n    public void onSave\(RedditPost post\) \{.*?\n    \}\n\n    @Override\n    public void onComments',
    s,
    flags=re.S)
if not save_match:
    raise SystemExit('Missing v3.6.9 target: onSave method')
new_save = r'''    @Override
    public void onSave(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        if (username.isEmpty()) {
            openBrowser(
                    REDDIT + "/login/?dest=" + enc(REDDIT + "/"),
                    BrowserPurpose.LOGIN);
            return;
        }
        String body = "id=" + enc(post.id) + "&uh=" + enc(modhash);
        boolean wasSaved = post.saved || savedPostIds.contains(post.id);
        engine.postForm(wasSaved ? "/api/unsave" : "/api/save", body, result -> {
            if (!result.ok) {
                setStatus("Save failed: " + friendlyError(result), false);
                return;
            }

            post.saved = !wasSaved;
            if (post.saved) {
                savedPostIds.add(post.id);
                hiddenPosts.remove(post.id);
                saveSavedPostIds();
                saveReadHideState();
                removePostFromCurrentLibrary(post.id);
            } else {
                savedPostIds.remove(post.id);
                saveSavedPostIds();
                if (screen == Screen.FAVORITES && favoritesView.equals("saved")) {
                    removePostFromCurrentLibrary(post.id);
                } else {
                    postAdapter.refreshPost(post);
                }
            }
        });
    }

    private void removePostFromCurrentLibrary(String id) {
        if (id == null || id.isEmpty()) return;
        int previousPosition = pager != null ? pager.getCurrentItem() : 0;
        fullscreenUserGesture = false;
        pendingUserFullscreenPosition = -1;
        lastFullscreenPostId = "";
        postAdapter.removePostById(id);
        gridAdapter.removePostById(id);
        int count = postAdapter.getItemCount();
        if (count <= 0) {
            setStatus(screen == Screen.FAVORITES ? "No saved media posts." : "No unread media posts.", false);
            return;
        }
        int target = Math.max(0, Math.min(previousPosition, count - 1));
        pager.setCurrentItem(target, false);
        if (layoutMode.equals("grid")) gridView.scrollToPosition(target);
        else setFullscreenReadBaseline(target);
    }

    @Override
    public void onComments'''
s = s[:save_match.start()] + new_save + s[save_match.end():]

# Content-filter UI lives alongside media type, but Saved/Hidden management
# libraries remain intact and visible regardless of these contextual toggles.
media_sheet = re.search(
    r'    private void showMediaSheet\(\) \{.*?\n    \}\n\n    private void showLayoutSheet',
    s,
    flags=re.S)
if not media_sheet:
    raise SystemExit('Missing v3.6.9 target: showMediaSheet')
new_sheet = r'''    private void showMediaSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Media & content filters");
        scroll.addView(body);

        String[][] values = {
                {"all", "All media"},
                {"image", "Images"},
                {"video", "Videos / GIFs"}
        };
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (media.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                media = pair[0];
                prefs.edit().putString("media", media).apply();
                dialog.dismiss();
                reloadCurrent();
            });
        }

        TextView contentTitle = sectionTitle("Contextual blocking");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(16);
        body.addView(contentTitle, cp);

        Button lgbt = sheetButton("Hide LGBTQ-related posts" + (blockLgbtTopics ? "  ✓" : ""));
        Button gore = sheetButton("Hide gore / blood posts" + (blockGore ? "  ✓" : ""));
        body.addView(lgbt, sectionButtonParams());
        body.addView(gore, sectionButtonParams());

        lgbt.setOnClickListener(v -> {
            blockLgbtTopics = !blockLgbtTopics;
            prefs.edit().putBoolean("blockLgbtTopics", blockLgbtTopics).apply();
            dialog.dismiss();
            reloadCurrent();
        });
        gore.setOnClickListener(v -> {
            blockGore = !blockGore;
            prefs.edit().putBoolean("blockGore", blockGore).apply();
            dialog.dismiss();
            reloadCurrent();
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showLayoutSheet'''
s = s[:media_sheet.start()] + new_sheet + s[media_sheet.end():]

# Compact chrome summary makes it obvious that contextual blocking is enabled.
filter_line = re.search(
    r'        filterButton\.setText\(media\.equals\("all"\).*?;\n',
    s,
    flags=re.S)
if not filter_line:
    raise SystemExit('Missing v3.6.9 target: filterButton label')
replacement = '''        String mediaLabel = media.equals("all") ? "All media"
                : media.equals("image") ? "Images" : "Video/GIF";
        int blockedFilters = (blockLgbtTopics ? 1 : 0) + (blockGore ? 1 : 0);
        filterButton.setText(blockedFilters == 0 ? mediaLabel : mediaLabel + " · " + blockedFilters + " blocked");
'''
s = s[:filter_line.start()] + replacement + s[filter_line.end():]

path.write_text(s)


# ---------------------------------------------------------------------------
# Media3: undo force-highest bitrate. Adaptive playback still selects HD when
# bandwidth permits, while adjacent preloaded pages no longer compete for the
# maximum stream at once.
# ---------------------------------------------------------------------------
hq = Path('app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java')
hq.write_text(r'''package com.scrolller.adblock;

import android.content.Context;

import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.util.HashMap;
import java.util.Map;

final class HighQualityPlayerFactory {
    private HighQualityPlayerFactory() {}

    static ExoPlayer create(Context context, String mediaUrl) {
        DefaultTrackSelector selector = new DefaultTrackSelector(context);
        selector.setParameters(
                selector.buildUponParameters()
                        .setForceHighestSupportedBitrate(false));

        ExoPlayer.Builder builder = new ExoPlayer.Builder(context)
                .setTrackSelector(selector);

        if (isRedgifsMedia(mediaUrl)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.redgifs.com/");
            headers.put("Origin", "https://www.redgifs.com");
            headers.put("Accept", "*/*");

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 16) RedditMedia/3.6.9")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http);
            builder.setMediaSourceFactory(mediaSourceFactory);
        }

        return builder.build();
    }

    private static boolean isRedgifsMedia(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("redgifs.com") || lower.contains("redgifsusercontent.com");
    }
}
''')

print('Applied v3.6.9: adaptive playback, idle-only appends, Unread/Saved/Hidden state, contextual filters')
