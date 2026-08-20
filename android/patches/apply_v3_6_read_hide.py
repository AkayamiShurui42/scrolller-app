from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6 patch target: {label}\n{old[:420]}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# RedditPost: allow MainActivity to reconstruct locally persisted hidden posts.
# ---------------------------------------------------------------------------
post_path = Path('app/src/main/java/com/scrolller/adblock/RedditPost.java')
p = post_path.read_text()
p = replace_required(
    p,
    '    private RedditPost(\n',
    '    RedditPost(\n',
    'package-visible RedditPost constructor')
post_path.write_text(p)

# ---------------------------------------------------------------------------
# Fullscreen adapter: hidden-library mode replaces Save with Restore, and
# exposes a safe local removal method so delayed hides do not reset the pager.
# ---------------------------------------------------------------------------
pager_path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
p = pager_path.read_text()
p = replace_required(
    p,
    '        void onToggleChrome();\n        void onSave(RedditPost post);',
    '        void onToggleChrome();\n        void onRestoreHidden(RedditPost post);\n        void onSave(RedditPost post);',
    'restore-hidden listener')
p = replace_required(
    p,
    '    private boolean muted = true;\n    private boolean chromeVisible = false;',
    '    private boolean muted = true;\n    private boolean chromeVisible = false;\n    private boolean hiddenMode = false;',
    'hidden-mode state')
p = replace_required(
    p,
    '    public boolean isMuted() { return muted; }\n\n    public void setChromeVisible(boolean visible) {',
    '''    public boolean isMuted() { return muted; }

    public void setHiddenMode(boolean hiddenMode) {
        if (this.hiddenMode == hiddenMode) return;
        this.hiddenMode = hiddenMode;
        notifyDataSetChanged();
    }

    public void removePostById(String id) {
        if (id == null || id.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < posts.size(); i++) {
            if (id.equals(posts.get(i).id)) { index = i; break; }
        }
        if (index < 0) return;
        releaseAll();
        posts.remove(index);
        activePosition = Math.max(0, Math.min(activePosition, posts.size() - 1));
        notifyDataSetChanged();
    }

    public void setChromeVisible(boolean visible) {''',
    'hidden mode and remove method')
old_save = '''            Button save = pillButton(post.saved ? "★ Saved" : "☆ Save");
            if (post.saved) {
                save.setTextColor(Color.BLACK);
                save.setBackground(rounded(0xFFF0F0F0, 999));
            }
            actions.addView(save, actionParams());
            save.setOnClickListener(v -> listener.onSave(post));
'''
new_save = '''            if (hiddenMode) {
                Button restore = pillButton("↶ Restore");
                restore.setTextColor(Color.BLACK);
                restore.setBackground(rounded(0xFFF0F0F0, 999));
                actions.addView(restore, actionParams());
                restore.setOnClickListener(v -> listener.onRestoreHidden(post));
            } else {
                Button save = pillButton(post.saved ? "★ Saved" : "☆ Save");
                if (post.saved) {
                    save.setTextColor(Color.BLACK);
                    save.setBackground(rounded(0xFFF0F0F0, 999));
                }
                actions.addView(save, actionParams());
                save.setOnClickListener(v -> listener.onSave(post));
            }
'''
p = replace_required(p, old_save, new_save, 'fullscreen restore action')
pager_path.write_text(p)

# ---------------------------------------------------------------------------
# Stream adapter: Stream never marks posts read, but hidden posts disappear
# from normal Stream data because MainActivity filters them. Add removal only.
# ---------------------------------------------------------------------------
grid_path = Path('app/src/main/java/com/scrolller/adblock/GridPostAdapter.java')
g = grid_path.read_text()
g = replace_required(
    g,
    '''    public void appendPosts(List<RedditPost> items) {
        if (items.isEmpty()) return;
        int start = posts.size();
        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }
''',
    '''    public void appendPosts(List<RedditPost> items) {
        if (items.isEmpty()) return;
        int start = posts.size();
        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public void removePostById(String id) {
        if (id == null || id.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < posts.size(); i++) {
            if (id.equals(posts.get(i).id)) { index = i; break; }
        }
        if (index < 0) return;
        releaseAllPlayers();
        posts.remove(index);
        notifyDataSetChanged();
    }
''',
    'stream local removal')
grid_path.write_text(g)

# ---------------------------------------------------------------------------
# MainActivity: persistent read queue + hidden library, fullscreen-only read
# tracking, universal hidden filtering, Favorites Hidden view, and exit confirm.
# ---------------------------------------------------------------------------
main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

s = replace_required(
    s,
    'import java.util.HashSet;\nimport java.util.List;\nimport java.util.Locale;\nimport java.util.Set;',
    'import java.util.HashSet;\nimport java.util.LinkedHashMap;\nimport java.util.LinkedHashSet;\nimport java.util.List;\nimport java.util.Locale;\nimport java.util.Map;\nimport java.util.Set;',
    'read-hide collection imports')

# Nav state keeps Favorites Saved/Hidden view when backing through history.
s = replace_required(
    s,
    '        final String favoriteSort;\n        final int pagerPosition;',
    '        final String favoriteSort;\n        final String favoritesView;\n        final int pagerPosition;',
    'favorites view nav field')
s = replace_required(
    s,
    '                String searchScope,\n                String favoriteSort,\n                int pagerPosition',
    '                String searchScope,\n                String favoriteSort,\n                String favoritesView,\n                int pagerPosition',
    'favorites view nav constructor arg')
s = replace_required(
    s,
    '            this.favoriteSort = favoriteSort;\n            this.pagerPosition = pagerPosition;',
    '            this.favoriteSort = favoriteSort;\n            this.favoritesView = favoritesView;\n            this.pagerPosition = pagerPosition;',
    'favorites view nav assignment')

s = replace_required(
    s,
    '    private String favoriteSort = "random";\n    private String query = "";',
    '    private String favoriteSort = "random";\n    private String favoritesView = "saved";\n    private String query = "";',
    'favorites view state')
s = replace_required(
    s,
    '    private final Set<String> feedSeenCursors = new HashSet<>();\n    private final Deque<NavState> history = new ArrayDeque<>();',
    '''    private final Set<String> feedSeenCursors = new HashSet<>();
    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();
    private final LinkedHashMap<String, RedditPost> pendingReadPosts = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> pendingReadAfter = new LinkedHashMap<>();
    private String lastFullscreenPostId = "";
    private final Deque<NavState> history = new ArrayDeque<>();''',
    'persistent read-hide state')

s = replace_required(
    s,
    '        muted = prefs.getBoolean("muted", true);\n\n        root = new FrameLayout(this);',
    '        muted = prefs.getBoolean("muted", true);\n        loadReadHideState();\n\n        root = new FrameLayout(this);',
    'load persistent read-hide state')

# Fullscreen page changes are the ONLY source of read progression.
s = replace_required(
    s,
    '''                postAdapter.setActivePosition(layoutMode.equals("fullscreen") ? position : -1);
                if (layoutMode.equals("fullscreen") && fullscreenChromeVisible) {''',
    '''                postAdapter.setActivePosition(layoutMode.equals("fullscreen") ? position : -1);
                if (layoutMode.equals("fullscreen")
                        && screen != Screen.ACCOUNT
                        && !(screen == Screen.FAVORITES && favoritesView.equals("hidden"))) {
                    trackFullscreenVisit(position);
                } else {
                    lastFullscreenPostId = "";
                }
                if (layoutMode.equals("fullscreen") && fullscreenChromeVisible) {''',
    'fullscreen-only read tracking')

s = replace_required(
    s,
    '                favoriteSort,\n                position);',
    '                favoriteSort,\n                favoritesView,\n                position);',
    'capture favorites view')
s = replace_required(
    s,
    '                && a.favoriteSort.equals(b.favoriteSort);',
    '                && a.favoriteSort.equals(b.favoriteSort)\n                && a.favoritesView.equals(b.favoritesView);',
    'same-state favorites view')
s = replace_required(
    s,
    '        favoriteSort = state.favoriteSort;\n        pendingRestorePosition',
    '        favoriteSort = state.favoriteSort;\n        favoritesView = state.favoritesView;\n        pendingRestorePosition',
    'restore favorites view')

# Fresh Favorites opens Saved; internal history restore can reopen Hidden.
s = replace_required(
    s,
    '        favoriteSort = "random";\n        screen = Screen.FAVORITES;',
    '        favoriteSort = "random";\n        favoritesView = "saved";\n        screen = Screen.FAVORITES;',
    'fresh Favorites saved view')

# Favorites Hidden is local and works even if the Reddit account is logged out.
s = replace_required(
    s,
    '''    private void loadFavoritesInternal() {
        if (username.isEmpty()) {''',
    '''    private void loadFavoritesInternal() {
        if (favoritesView.equals("hidden")) {
            loadHiddenPostsView();
            return;
        }
        if (username.isEmpty()) {''',
    'Favorites hidden local route')

# All normal data paths respect local hidden state. Hidden library deliberately bypasses it.
old_replace = '''    private void replacePosts(List<RedditPost> items) {
        postAdapter.setPosts(items);
        gridAdapter.setPosts(items);
    }

    private void appendUnique(List<RedditPost> incoming) {
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) ids.add(post.id);
        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) if (ids.add(post.id)) unique.add(post);
        postAdapter.appendPosts(unique);
        gridAdapter.appendPosts(unique);
    }
'''
new_replace = '''    private boolean showingHiddenLibrary() {
        return screen == Screen.FAVORITES && favoritesView.equals("hidden");
    }

    private void replacePosts(List<RedditPost> items) {
        lastFullscreenPostId = "";
        boolean hiddenLibrary = showingHiddenLibrary();
        postAdapter.setHiddenMode(hiddenLibrary);
        ArrayList<RedditPost> visible = new ArrayList<>();
        for (RedditPost post : items) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (hiddenLibrary || !hiddenPosts.containsKey(post.id)) visible.add(post);
        }
        postAdapter.setPosts(visible);
        gridAdapter.setPosts(visible);
    }

    private void appendUnique(List<RedditPost> incoming) {
        if (showingHiddenLibrary()) return;
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) ids.add(post.id);
        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (hiddenPosts.containsKey(post.id)) continue;
            if (ids.add(post.id)) unique.add(post);
        }
        postAdapter.appendPosts(unique);
        gridAdapter.appendPosts(unique);
    }
'''
s = replace_required(s, old_replace, new_replace, 'universal hidden filtering')

# Favorites gets a Saved/Hidden selector in the formerly Feed control slot.
s = replace_required(
    s,
    '''        feedButton.setOnClickListener(v -> {
            if (screen == Screen.SEARCH) showScopeSheet();
            else showFeedSheet();
        });''',
    '''        feedButton.setOnClickListener(v -> {
            if (screen == Screen.SEARCH) showScopeSheet();
            else if (screen == Screen.FAVORITES) showFavoritesViewSheet();
            else showFeedSheet();
        });''',
    'Favorites view button click')

s = replace_required(
    s,
    '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : "Feed");''',
    '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : "Feed");''',
    'Favorites view chrome label')
s = replace_required(
    s,
    '''        feedButton.setVisibility(
                screen == Screen.HOME || screen == Screen.SEARCH ? View.VISIBLE : View.GONE);''',
    '''        feedButton.setVisibility(
                screen == Screen.HOME || screen == Screen.SEARCH || screen == Screen.FAVORITES
                        ? View.VISIBLE : View.GONE);''',
    'show Favorites view selector')

# Insert Favorites view sheet before sort sheet.
anchor = '    private void showSortSheet() {'
favorites_sheet = '''    private void showFavoritesViewSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Favorites library");
        Button saved = sheetButton("Saved posts" + (favoritesView.equals("saved") ? "  ✓" : ""));
        Button hidden = sheetButton("Hidden posts · " + hiddenPosts.size()
                + (favoritesView.equals("hidden") ? "  ✓" : ""));
        body.addView(saved, sectionButtonParams());
        body.addView(hidden, sectionButtonParams());
        saved.setOnClickListener(v -> {
            favoritesView = "saved";
            dialog.dismiss();
            loadFavoritesInternal();
            updateChrome();
        });
        hidden.setOnClickListener(v -> {
            favoritesView = "hidden";
            dialog.dismiss();
            loadHiddenPostsView();
            updateChrome();
        });
        dialog.setContentView(body);
        dialog.show();
    }

'''
if anchor not in s:
    raise SystemExit('Missing v3.6 patch target: Favorites view sheet anchor')
s = s.replace(anchor, favorites_sheet + anchor, 1)

# Insert local hidden-library and persistence/tracking methods before reloadCurrent.
anchor = '    private void reloadCurrent() {'
methods = r'''    private void loadHiddenPostsView() {
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
    }

    private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;
        if (hiddenPosts.containsKey(currentId)) return;

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
        postAdapter.removePostById(id);
        gridAdapter.removePostById(id);
    }

    private void restoreHiddenPost(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        hiddenPosts.remove(post.id);
        pendingReadPosts.remove(post.id);
        pendingReadAfter.remove(post.id);
        feedSeenPostIds.remove(post.id);
        saveReadHideState();
        if (showingHiddenLibrary()) loadHiddenPostsView();
        else reloadCurrent();
    }

    private void loadReadHideState() {
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

    private void saveReadHideState() {
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

    private JSONObject postToJson(RedditPost post) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", post.id);
        o.put("title", post.title);
        o.put("author", post.author);
        o.put("subreddit", post.subreddit);
        o.put("permalink", post.permalink);
        o.put("sourceUrl", post.sourceUrl);
        o.put("score", post.score);
        o.put("comments", post.comments);
        o.put("createdUtc", post.createdUtc);
        o.put("saved", post.saved);
        o.put("nsfw", post.nsfw);
        o.put("mediaKind", post.mediaKind.name());
        JSONArray images = new JSONArray();
        for (String url : post.imageUrls) images.put(url);
        o.put("imageUrls", images);
        o.put("videoUrl", post.videoUrl);
        o.put("posterUrl", post.posterUrl);
        o.put("mediaWidth", post.mediaWidth);
        o.put("mediaHeight", post.mediaHeight);
        return o;
    }

    private RedditPost postFromJson(JSONObject o) {
        if (o == null) return null;
        try {
            ArrayList<String> images = new ArrayList<>();
            JSONArray imageArray = o.optJSONArray("imageUrls");
            if (imageArray != null) {
                for (int i = 0; i < imageArray.length(); i++) {
                    String url = imageArray.optString(i, "");
                    if (!url.isEmpty()) images.add(url);
                }
            }
            RedditPost.MediaKind kind = RedditPost.MediaKind.valueOf(
                    o.optString("mediaKind", RedditPost.MediaKind.IMAGE.name()));
            return new RedditPost(
                    o.optString("id", ""),
                    o.optString("title", ""),
                    o.optString("author", ""),
                    o.optString("subreddit", ""),
                    o.optString("permalink", ""),
                    o.optString("sourceUrl", ""),
                    o.optInt("score", 0),
                    o.optInt("comments", 0),
                    o.optLong("createdUtc", 0L),
                    o.optBoolean("saved", false),
                    o.optBoolean("nsfw", false),
                    kind,
                    images,
                    o.optString("videoUrl", ""),
                    o.optString("posterUrl", ""),
                    o.optInt("mediaWidth", 0),
                    o.optInt("mediaHeight", 0));
        } catch (Exception ignored) {
            return null;
        }
    }

'''
if anchor not in s:
    raise SystemExit('Missing v3.6 patch target: read-hide methods anchor')
s = s.replace(anchor, methods + anchor, 1)

# MainActivity implements Restore from the fullscreen Hidden library.
s = replace_required(
    s,
    '''    @Override
    public void onToggleChrome() {
        toggleFullscreenChrome();
    }

    @Override
    public void onSave(RedditPost post) {''',
    '''    @Override
    public void onToggleChrome() {
        toggleFullscreenChrome();
    }

    @Override
    public void onRestoreHidden(RedditPost post) {
        restoreHiddenPost(post);
    }

    @Override
    public void onSave(RedditPost post) {''',
    'restore-hidden callback')

# Android 16 Back: previous app state first; root Home requires explicit confirmation.
old_back_tail = '''        // At the real root, preserve the task and send it to Recents/Home instead
        // of finishing MainActivity. Reopening the app resumes the existing task.
        moveTaskToBack(true);
    }
}'''
new_back_tail = '''        // Only root Home can leave the app, and even there require confirmation.
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Exit Reddit Media?")
                .setMessage("There are no previous pages in this app history.")
                .setNegativeButton("Stay", null)
                .setPositiveButton("Exit", (dialog, which) -> moveTaskToBack(true))
                .show();
    }
}'''
s = replace_required(s, old_back_tail, new_back_tail, 'root exit confirmation')

main_path.write_text(s)
print('Applied v3.6 fullscreen read/delayed-hide + Hidden library + exit confirmation')
