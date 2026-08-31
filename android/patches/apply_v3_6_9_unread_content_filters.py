from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.9 target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)


path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# ---------------------------------------------------------------------------
# Persistent content preferences. These are feed rejection rules only; they do
# not create read/hidden records and do not alter Saved/Hidden libraries.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '    private boolean fullscreenChromeVisible = true;\n    private int systemTopPx;',
    '    private boolean fullscreenChromeVisible = true;\n    private boolean blockLgbtTopics = false;\n    private boolean blockGoreContent = false;\n    private int systemTopPx;',
    'content filter fields')

s = replace_required(
    s,
    '    private final Set<String> feedSeenCursors = new HashSet<>();',
    '    private final Set<String> feedSeenCursors = new HashSet<>();\n    private final Set<String> savedPostIds = new HashSet<>();',
    'persistent saved-id set')

s = replace_required(
    s,
    '        muted = prefs.getBoolean("muted", true);\n        loadReadHideState();',
    '''        muted = prefs.getBoolean("muted", true);
        blockLgbtTopics = prefs.getBoolean("blockLgbtTopics", false);
        blockGoreContent = prefs.getBoolean("blockGoreContent", false);
        Set<String> persistedSavedIds = prefs.getStringSet("savedPostIds", null);
        if (persistedSavedIds != null) savedPostIds.addAll(persistedSavedIds);
        loadReadHideState();''',
    'load saved/filter preferences')

# ---------------------------------------------------------------------------
# Unread population and content filters.
# Saved and Hidden are explicit libraries and bypass these rejection rules.
# ---------------------------------------------------------------------------
anchor = '    private boolean matchesMedia(RedditPost post) {'
if anchor not in s:
    raise SystemExit('Missing v3.6.9 target: matchesMedia anchor')

helpers = r'''    private boolean isSavedForUnread(RedditPost post) {
        return post != null
                && post.id != null
                && !post.id.isEmpty()
                && (post.saved || savedPostIds.contains(post.id));
    }

    private boolean isContentBlocked(RedditPost post) {
        if (post == null) return false;
        String title = post.title == null ? "" : post.title;
        String community = post.subreddit == null ? "" : post.subreddit;
        String normalized = normalizeFilterText(title + " " + community);
        String communityKey = community.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");

        if (blockLgbtTopics) {
            if (containsFilterPhrase(normalized,
                    "gay", "lesbian", "bisexual", "trans", "transgender", "transsexual",
                    "lgbt", "lgbtq", "queer", "nonbinary", "non binary", "mtf", "ftm",
                    "trans woman", "trans women", "trans man", "trans men")) {
                return true;
            }
            if (communityKey.contains("transgender")
                    || communityKey.contains("transporn")
                    || communityKey.contains("transgonewild")
                    || communityKey.contains("gayporn")
                    || communityKey.contains("gaybros")
                    || communityKey.contains("lesbian")
                    || communityKey.contains("bisexual")
                    || communityKey.contains("nonbinary")
                    || communityKey.contains("lgbt")
                    || communityKey.contains("queer")) {
                return true;
            }
        }

        if (blockGoreContent) {
            if (containsFilterPhrase(normalized,
                    "gore", "gory", "blood", "bloody", "dismemberment", "dismembered",
                    "decapitation", "decapitated", "beheading", "beheaded", "mutilation",
                    "mutilated", "exposed organs", "open wound", "open wounds",
                    "graphic injury", "graphic injuries", "dead body", "dead bodies",
                    "corpse", "corpses")) {
                return true;
            }
            if (communityKey.contains("gore")
                    || communityKey.contains("medicalgore")
                    || communityKey.contains("eyeblech")
                    || communityKey.contains("watchpeopledie")
                    || communityKey.contains("deadorvegetable")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeFilterText(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return " " + clean + " ";
    }

    private static boolean containsFilterPhrase(String normalized, String... phrases) {
        for (String phrase : phrases) {
            String needle = normalizeFilterText(phrase);
            if (normalized.contains(needle)) return true;
        }
        return false;
    }

    private void persistSavedPostIds() {
        prefs.edit().putStringSet("savedPostIds", new HashSet<>(savedPostIds)).apply();
    }

'''
s = s.replace(anchor, helpers + anchor, 1)

# Discovery loops that already reject Hidden should also reject Saved and
# content-filtered items before they count toward feed/search reservoir targets.
s = s.replace(
    'if (hiddenPosts.containsKey(post.id)) continue;',
    'if (hiddenPosts.containsKey(post.id) || isSavedForUnread(post) || isContentBlocked(post)) continue;')

# Replacement collections: normal screens are Unread. Saved and Hidden are
# management libraries and deliberately bypass unread/content rejection.
s = replace_required(
    s,
    '''            if (hiddenLibrary || favoritesSaved || !hiddenPosts.containsKey(post.id)) visible.add(post);''',
    '''            if (hiddenLibrary || favoritesSaved
                    || (!hiddenPosts.containsKey(post.id)
                    && !isSavedForUnread(post)
                    && !isContentBlocked(post))) {
                visible.add(post);
            }''',
    'replacePosts unread population')

s = replace_required(
    s,
    '''            if (!favoritesSaved && hiddenPosts.containsKey(post.id)) continue;
            if (ids.add(post.id)) unique.add(post);''',
    '''            if (!favoritesSaved
                    && (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post))) continue;
            if (ids.add(post.id)) unique.add(post);''',
    'appendUnique unread population')

# Sync the local Saved identity set from Reddit whenever the exhaustive Saved
# library is fetched. This also reconciles saves/unsaves performed elsewhere.
s = replace_required(
    s,
    '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        boolean hiddenChanged = false;''',
    '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        savedPostIds.clear();
        for (RedditPost savedPost : collected) {
            if (savedPost != null && savedPost.id != null && !savedPost.id.isEmpty()) {
                savedPostIds.add(savedPost.id);
            }
        }
        persistSavedPostIds();
        boolean hiddenChanged = false;''',
    'sync Saved identities from Favorites')

# Saving is an immediate move out of Unread. Unsave removes it from the local
# Saved identity set; in the Saved library the card disappears immediately.
old_save = '''            if (result.ok) {
                post.saved = !post.saved;
                if (post.saved && post.id != null && hiddenPosts.remove(post.id) != null) {
                    feedSeenPostIds.remove(post.id);
                    saveReadHideState();
                    if (showingHiddenLibrary()) {
                        loadHiddenPostsView();
                        return;
                    }
                }
                postAdapter.refreshPost(post);
            } else {'''
new_save = '''            if (result.ok) {
                post.saved = !post.saved;
                if (post.saved) {
                    if (post.id != null && !post.id.isEmpty()) savedPostIds.add(post.id);
                    persistSavedPostIds();
                    if (post.id != null && hiddenPosts.remove(post.id) != null) {
                        saveReadHideState();
                        if (showingHiddenLibrary()) {
                            loadHiddenPostsView();
                            return;
                        }
                    }
                    if (screen != Screen.FAVORITES) {
                        int position = pager.getCurrentItem();
                        postAdapter.removePostById(post.id);
                        gridAdapter.removePostById(post.id);
                        if (postAdapter.getItemCount() > 0) {
                            int target = Math.min(position, postAdapter.getItemCount() - 1);
                            pager.setCurrentItem(target, false);
                            setFullscreenReadBaseline(target);
                            hideStatus();
                        } else {
                            lastFullscreenPostId = "";
                            setStatus("No unread media remains in this collection.", false);
                        }
                        return;
                    }
                } else {
                    savedPostIds.remove(post.id);
                    persistSavedPostIds();
                    if (screen == Screen.FAVORITES && favoritesView.equals("saved")) {
                        int position = pager.getCurrentItem();
                        postAdapter.removePostById(post.id);
                        gridAdapter.removePostById(post.id);
                        if (postAdapter.getItemCount() > 0) {
                            pager.setCurrentItem(Math.min(position, postAdapter.getItemCount() - 1), false);
                            hideStatus();
                        } else {
                            setStatus("No saved media posts yet.", false);
                        }
                        return;
                    }
                }
                postAdapter.refreshPost(post);
            } else {'''
s = replace_required(s, old_save, new_save, 'Save moves out of Unread')

# Content controls live in Account and are available regardless of login state.
# They only affect discovery/unread views, never Saved/Hidden libraries.
s = replace_required(
    s,
    '''        body.addView(sectionTitle(username.isEmpty() ? "Reddit account" : "u/" + username));

        if (username.isEmpty()) {''',
    '''        body.addView(sectionTitle(username.isEmpty() ? "Reddit account" : "u/" + username));

        TextView filterTitle = sectionTitle("Content filters");
        LinearLayout.LayoutParams filterTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        filterTitleParams.topMargin = dp(14);
        body.addView(filterTitle, filterTitleParams);
        body.addView(bodyText("These filters reject posts from Unread discovery only. Saved and Hidden libraries are unchanged."));

        Button lgbtFilter = sheetButton("LGBTQ topics · " + (blockLgbtTopics ? "Blocked" : "Allowed"));
        body.addView(lgbtFilter, sectionButtonParams());
        lgbtFilter.setOnClickListener(v -> {
            blockLgbtTopics = !blockLgbtTopics;
            prefs.edit().putBoolean("blockLgbtTopics", blockLgbtTopics).apply();
            renderAccount();
        });

        Button goreFilter = sheetButton("Gore / blood · " + (blockGoreContent ? "Blocked" : "Allowed"));
        body.addView(goreFilter, sectionButtonParams());
        goreFilter.setOnClickListener(v -> {
            blockGoreContent = !blockGoreContent;
            prefs.edit().putBoolean("blockGoreContent", blockGoreContent).apply();
            renderAccount();
        });

        if (username.isEmpty()) {''',
    'Account content filter controls')

path.write_text(s)
print('Applied v3.6.9 unread Saved state + independent content filters')
