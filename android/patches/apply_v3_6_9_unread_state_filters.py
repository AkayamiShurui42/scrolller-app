from pathlib import Path
import re


MAIN = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = MAIN.read_text()


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.9 target: {label}\n{old[:700]}')
    return text.replace(old, new, 1)


def regex_required(text, pattern, replacement, label, flags=0):
    out, count = re.subn(pattern, lambda m: replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f'Missing v3.6.9 regex target: {label}')
    return out


# ---------------------------------------------------------------------------
# Persistent unread-state exclusions and independent content preferences.
# Saved and Hidden/Read are mutually exclusive terminal states for Unread.
# Content filters reject discovery results without marking them read.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();',
    '''    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();
    private final Set<String> savedPostIds = new HashSet<>();
    private boolean blockLgbtqTopics = false;
    private boolean blockGoreBlood = false;''',
    'saved/content-filter state fields')

s = replace_required(
    s,
    '''        muted = prefs.getBoolean("muted", true);
        loadReadHideState();''',
    '''        muted = prefs.getBoolean("muted", true);
        blockLgbtqTopics = prefs.getBoolean("blockLgbtqTopics", false);
        blockGoreBlood = prefs.getBoolean("blockGoreBlood", false);
        Set<String> persistedSaved = prefs.getStringSet("savedPostIds", null);
        if (persistedSaved != null) savedPostIds.addAll(persistedSaved);
        loadReadHideState();''',
    'load saved/content-filter preferences')

# Rebuild media matching around an explicit Unread eligibility predicate.
new_matches = r'''    private boolean matchesMedia(RedditPost post) {
        if (post == null) return false;
        boolean library = screen == Screen.FAVORITES;
        if (!library && !isUnreadEligible(post)) return false;

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

    private boolean isUnreadEligible(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return false;
        if (hiddenPosts.containsKey(post.id)) return false;
        if (post.saved || savedPostIds.contains(post.id)) return false;
        return matchesContentPreferences(post);
    }

    private boolean matchesContentPreferences(RedditPost post) {
        String text = normalizeFilterText(
                (post.subreddit == null ? "" : post.subreddit) + " "
                        + (post.title == null ? "" : post.title));

        if (blockLgbtqTopics && containsFilterPhrase(text,
                "trans", "transgender", "transsexual", "mtf", "ftm",
                "gay", "lesbian", "lgbt", "lgbtq", "queer", "bisexual",
                "nonbinary", "non binary", "genderfluid", "gender fluid",
                "pansexual", "pride")) {
            return false;
        }

        if (blockGoreBlood && containsFilterPhrase(text,
                "gore", "gory", "blood", "bloody", "bloodshed",
                "dismemberment", "dismembered", "decapitation", "decapitated",
                "beheading", "beheaded", "mutilation", "mutilated", "severed",
                "corpse", "corpses", "dead body", "dead bodies",
                "graphic injury", "graphic injuries", "exposed organs",
                "organ exposure", "guts", "brain matter", "open wound",
                "open wounds")) {
            return false;
        }

        return true;
    }

    private static String normalizeFilterText(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static boolean containsFilterPhrase(String normalizedText, String... phrases) {
        if (normalizedText == null || normalizedText.isEmpty()) return false;
        String padded = " " + normalizedText + " ";
        for (String phrase : phrases) {
            String needle = normalizeFilterText(phrase);
            if (!needle.isEmpty() && padded.contains(" " + needle + " ")) return true;
        }
        return false;
    }

    private void persistSavedPostIds() {
        prefs.edit().putStringSet("savedPostIds", new HashSet<>(savedPostIds)).apply();
    }

'''
s = regex_required(
    s,
    r'    private boolean matchesMedia\(RedditPost post\) \{.*?\n    \}\n\n(?=    private void replacePosts\()',
    new_matches,
    'replace media matcher with unread eligibility',
    flags=re.S)

# Keep libraries complete, but every normal collection is strictly Unread.
new_replace = r'''    private void replacePosts(List<RedditPost> items) {
        lastFullscreenPostId = "";
        mediaReadyPostIds.clear();
        mediaFailedPostIds.clear();
        boolean hiddenLibrary = showingHiddenLibrary();
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        postAdapter.setHiddenMode(hiddenLibrary);
        ArrayList<RedditPost> visible = new ArrayList<>();
        for (RedditPost post : items) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (hiddenLibrary || favoritesSaved || isUnreadEligible(post)) visible.add(post);
        }
        postAdapter.setPosts(visible);
        gridAdapter.setPosts(visible);
    }

'''
s = regex_required(
    s,
    r'    private void replacePosts\(List<RedditPost> items\) \{.*?\n    \}\n\n(?=    private void appendUnique\()',
    new_replace,
    'replace collection visibility logic',
    flags=re.S)

new_append = r'''    private void appendUnique(List<RedditPost> incoming) {
        if (showingHiddenLibrary()) return;
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) ids.add(post.id);
        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (!favoritesSaved && !isUnreadEligible(post)) continue;
            if (ids.add(post.id)) unique.add(post);
        }
        postAdapter.appendPosts(unique);
        gridAdapter.appendPosts(unique);
    }

'''
s = regex_required(
    s,
    r'    private void appendUnique\(List<RedditPost> incoming\) \{.*?\n    \}\n\n(?=    private void)',
    new_append,
    'append only unread-eligible posts',
    flags=re.S)

# A Saved post must never become Hidden/Read when the user swipes away.
s = replace_required(
    s,
    '''                && mediaReadyPostIds.contains(previous.id)
                && !previous.saved
                && !hiddenPosts.containsKey(previous.id)) {''',
    '''                && mediaReadyPostIds.contains(previous.id)
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {''',
    'saved IDs cannot enter Hidden')

# Synchronize the local Saved exclusion set whenever the complete Favorites
# collection is successfully fetched. This also repairs old Saved/Hidden overlap.
s = replace_required(
    s,
    '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;''',
    '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        HashSet<String> freshSavedIds = new HashSet<>();
        for (RedditPost savedPost : collected) {
            if (savedPost == null || savedPost.id == null || savedPost.id.isEmpty()) continue;
            savedPost.saved = true;
            freshSavedIds.add(savedPost.id);
        }
        if (!savedPostIds.equals(freshSavedIds)) {
            savedPostIds.clear();
            savedPostIds.addAll(freshSavedIds);
            persistSavedPostIds();
        }''',
    'sync Saved IDs from Favorites')

# Saving moves the post into Saved and out of Unread. We deliberately do not
# mutate the live pager collection because doing that previously caused synthetic
# page changes/skips. The current card remains stable until the user swipes/leaves;
# every subsequent collection excludes the Saved ID.
old_save_pattern = r'''            if \(result\.ok\) \{\n                post\.saved = !post\.saved;\n                if \(post\.saved && post\.id != null && hiddenPosts\.remove\(post\.id\) != null\) \{.*?\n                \}\n                postAdapter\.refreshPost\(post\);\n            \} else \{'''
new_save_block = r'''            if (result.ok) {
                post.saved = !post.saved;
                boolean hiddenChanged = false;
                if (post.saved) {
                    if (post.id != null && !post.id.isEmpty()) savedPostIds.add(post.id);
                    if (post.id != null && hiddenPosts.remove(post.id) != null) hiddenChanged = true;
                } else if (post.id != null) {
                    savedPostIds.remove(post.id);
                }
                persistSavedPostIds();
                if (hiddenChanged) saveReadHideState();

                if (screen == Screen.FAVORITES && favoritesView.equals("saved")) {
                    loadFavoritesInternal();
                    return;
                }
                postAdapter.refreshPost(post);
            } else {'''
s = regex_required(
    s,
    old_save_pattern,
    new_save_block,
    'Saved terminal-state callback',
    flags=re.S)

# Content filters live with Media controls, but are independent of media type.
new_media_sheet = r'''    private void showMediaSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Media and content filters");
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

        TextView filtersTitle = sectionTitle("Hide from Unread");
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(16);
        body.addView(filtersTitle, fp);

        Button lgbtq = sheetButton((blockLgbtqTopics ? "✓  " : "")
                + "Block LGBTQ-topic posts");
        Button gore = sheetButton((blockGoreBlood ? "✓  " : "")
                + "Block gore / blood posts");
        body.addView(lgbtq, sectionButtonParams());
        body.addView(gore, sectionButtonParams());

        lgbtq.setOnClickListener(v -> {
            blockLgbtqTopics = !blockLgbtqTopics;
            prefs.edit().putBoolean("blockLgbtqTopics", blockLgbtqTopics).apply();
            dialog.dismiss();
            reloadCurrent();
        });
        gore.setOnClickListener(v -> {
            blockGoreBlood = !blockGoreBlood;
            prefs.edit().putBoolean("blockGoreBlood", blockGoreBlood).apply();
            dialog.dismiss();
            reloadCurrent();
        });

        dialog.setContentView(body);
        dialog.show();
    }

'''
s = regex_required(
    s,
    r'    private void showMediaSheet\(\) \{.*?\n    \}\n\n(?=    private void showLayoutSheet\()',
    new_media_sheet,
    'content-filter controls',
    flags=re.S)

# Keep a current Saved-ID set even before the user manually opens Favorites.
# This is metadata-only state synchronization; it does not alter feed discovery.
s = replace_required(
    s,
    '''        refreshIdentity(() -> loadSubscriptions(null));
        loadFeed(true);''',
    '''        refreshIdentity(() -> {
            loadSubscriptions(null);
            syncSavedPostIdsFromReddit();
        });
        loadFeed(true);''',
    'startup Saved-ID sync')

# Login refresh path also needs to seed Saved exclusions.
s = replace_required(
    s,
    '''                            closeBrowser();
                            loadSubscriptions(null);
                            if (screen == Screen.FAVORITES) loadFavoritesInternal();''',
    '''                            closeBrowser();
                            loadSubscriptions(null);
                            syncSavedPostIdsFromReddit();
                            if (screen == Screen.FAVORITES) loadFavoritesInternal();''',
    'post-login Saved-ID sync')

sync_anchor = '    private void showAccount() {'
sync_methods = r'''    private void syncSavedPostIdsFromReddit() {
        if (username == null || username.isEmpty() || !engine.isReady()) return;
        fetchSavedIdPage("", new HashSet<>(), new HashSet<>(), 0);
    }

    private void fetchSavedIdPage(
            String cursor,
            Set<String> collected,
            Set<String> seenCursors,
            int page) {
        if (cursor != null && !cursor.isEmpty() && !seenCursors.add(cursor)) {
            finishSavedIdSync(collected);
            return;
        }

        String path = "/user/" + enc(username)
                + "/saved.json?limit=100&raw_json=1&show=all";
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        engine.get(path, result -> {
            if (!result.ok) return;
            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject child = children.optJSONObject(i);
                    JSONObject postData = child != null ? child.optJSONObject("data") : null;
                    if (postData == null) continue;
                    String fullname = postData.optString("name", "");
                    if (fullname.isEmpty()) {
                        String id = postData.optString("id", "");
                        if (!id.isEmpty()) fullname = "t3_" + id;
                    }
                    if (!fullname.isEmpty()) collected.add(fullname);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            if (!next.isEmpty() && !seenCursors.contains(next) && page < 49) {
                fetchSavedIdPage(next, collected, seenCursors, page + 1);
            } else {
                finishSavedIdSync(collected);
            }
        });
    }

    private void finishSavedIdSync(Set<String> collected) {
        if (collected == null) return;
        if (!savedPostIds.equals(collected)) {
            savedPostIds.clear();
            savedPostIds.addAll(collected);
            persistSavedPostIds();
        }
    }

'''
if sync_anchor not in s:
    raise SystemExit('Missing v3.6.9 target: Saved sync insertion anchor')
s = s.replace(sync_anchor, sync_methods + sync_anchor, 1)

MAIN.write_text(s)
print('Applied v3.6.9 strict Unread/Saved/Hidden state + independent topic/gore filters')
