from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.9 target: {label}\n{old[:700]}')
    return text.replace(old, new, 1)


path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# ---------------------------------------------------------------------------
# State: Saved is a terminal unread state; content filters are separate prefs.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '    private boolean scrolllerPrefetchDone = false;\n    private int archivePrefetchGeneration = 0;',
    '    private boolean scrolllerPrefetchDone = false;\n    private boolean blockLgbtqTopics = false;\n    private boolean blockGoreBlood = false;\n    private final Set<String> savedPostIds = new HashSet<>();\n    private int archivePrefetchGeneration = 0;',
    'state fields')

s = replace_required(
    s,
    '        loadReadHideState();',
    '''        loadReadHideState();
        blockLgbtqTopics = prefs.getBoolean("blockLgbtqTopics", false);
        blockGoreBlood = prefs.getBoolean("blockGoreBlood", false);
        Set<String> persistedSavedIds = prefs.getStringSet("savedPostIds", new HashSet<>());
        if (persistedSavedIds != null) savedPostIds.addAll(persistedSavedIds);''',
    'load filter and saved state')

# ---------------------------------------------------------------------------
# Unread filtering helpers. Topic filters never mutate Hidden/Read state.
# ---------------------------------------------------------------------------
anchor = '    private boolean matchesMedia(RedditPost post) {'
if anchor not in s:
    raise SystemExit('Missing v3.6.9 target: matchesMedia anchor')

helpers = r'''    private void persistSavedPostIds() {
        prefs.edit().putStringSet("savedPostIds", new HashSet<>(savedPostIds)).apply();
    }

    private boolean isSavedForUnread(RedditPost post) {
        return post != null && post.id != null && !post.id.isEmpty()
                && (post.saved || savedPostIds.contains(post.id));
    }

    private boolean passesContentFilters(RedditPost post) {
        if (post == null) return false;
        if (blockLgbtqTopics && matchesLgbtqTopic(post)) return false;
        if (blockGoreBlood && matchesGoreBloodTopic(post)) return false;
        return true;
    }

    private boolean matchesLgbtqTopic(RedditPost post) {
        String subredditValue = safeLower(post.subreddit);
        String titleValue = normalizedWords(post.title);
        if (containsAny(subredditValue,
                "transgender", "transgirl", "transgirls", "transwoman", "transwomen",
                "transman", "transmen", "transmasc", "transfem", "mtf", "ftm",
                "gay", "lesbian", "lgbt", "lgbtq", "queer", "bisexual", "pride")) {
            return true;
        }
        return containsAnyWord(titleValue,
                "trans", "transgender", "mtf", "ftm", "gay", "lesbian", "lgbt",
                "lgbtq", "queer", "bisexual", "bisexuality", "pride");
    }

    private boolean matchesGoreBloodTopic(RedditPost post) {
        String subredditValue = safeLower(post.subreddit);
        String titleValue = normalizedWords(post.title);
        if (containsAny(subredditValue,
                "gore", "guro", "blood", "bloody", "dismember", "decapitat",
                "beheading", "mutilat", "fatalaccident", "deathfootage", "medicalgore")) {
            return true;
        }
        return containsAnyWord(titleValue,
                "gore", "gory", "blood", "bloody", "dismembered", "dismemberment",
                "decapitated", "decapitation", "beheaded", "beheading", "mutilated",
                "mutilation", "entrails", "organs", "corpse", "cadaver");
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static String normalizedWords(String value) {
        return " " + safeLower(value).replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static boolean containsAnyWord(String normalized, String... words) {
        if (normalized == null || normalized.isEmpty()) return false;
        for (String word : words) {
            if (normalized.contains(" " + word + " ")) return true;
        }
        return false;
    }

'''
s = s.replace(anchor, helpers + anchor, 1)

# ---------------------------------------------------------------------------
# Collection rendering: Saved/Hidden/Filtered are disjoint from Unread.
# Saved and Hidden management libraries remain visible regardless of topic filter.
# ---------------------------------------------------------------------------
old_visible = '            if (hiddenLibrary || favoritesSaved || !hiddenPosts.containsKey(post.id)) visible.add(post);'
new_visible = '''            if (post.saved) savedPostIds.add(post.id);
            if (hiddenLibrary || favoritesSaved) {
                visible.add(post);
            } else if (!hiddenPosts.containsKey(post.id)
                    && !isSavedForUnread(post)
                    && passesContentFilters(post)) {
                visible.add(post);
            }'''
s = replace_required(s, old_visible, new_visible, 'replacePosts unread filtering')

old_append = '''            if (!favoritesSaved && hiddenPosts.containsKey(post.id)) continue;
            if (ids.add(post.id)) unique.add(post);'''
new_append = '''            if (post.saved) savedPostIds.add(post.id);
            if (!favoritesSaved && (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || !passesContentFilters(post))) continue;
            if (ids.add(post.id)) unique.add(post);'''
s = replace_required(s, old_append, new_append, 'appendUnique unread filtering')

# A swipe can never move a Saved post into Hidden, including Scrolller items whose
# local object does not carry Reddit's saved=true flag.
s = replace_required(
    s,
    '''                && mediaReadyPostIds.contains(previous.id)
                && !previous.saved
                && !hiddenPosts.containsKey(previous.id)) {''',
    '''                && mediaReadyPostIds.contains(previous.id)
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {''',
    'Saved IDs excluded from read/hide')

# Saving/unsaving updates local durable Saved membership immediately. Existing
# v3.6.2 logic still removes any legacy Hidden overlap when a post is saved.
s = replace_required(
    s,
    '                post.saved = !post.saved;\n                if (post.saved && post.id != null && hiddenPosts.remove(post.id) != null) {',
    '''                post.saved = !post.saved;
                if (post.id != null && !post.id.isEmpty()) {
                    if (post.saved) savedPostIds.add(post.id);
                    else {
                        savedPostIds.remove(post.id);
                        feedSeenPostIds.remove(post.id);
                    }
                    persistSavedPostIds();
                }
                if (post.saved && post.id != null && hiddenPosts.remove(post.id) != null) {''',
    'persist Saved transitions')

# Whenever the Saved library is fetched, learn every Reddit Saved ID. This also
# migrates users who already had saves before v3.6.9 into the local unread model.
s = replace_required(
    s,
    '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        boolean hiddenChanged = false;''',
    '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        boolean savedChanged = false;
        for (RedditPost savedPost : collected) {
            if (savedPost == null || savedPost.id == null || savedPost.id.isEmpty()) continue;
            savedPost.saved = true;
            if (savedPostIds.add(savedPost.id)) savedChanged = true;
        }
        if (savedChanged) persistSavedPostIds();
        boolean hiddenChanged = false;''',
    'learn Saved IDs from Favorites')

# Persist any saved=true discoveries learned during normal Reddit rendering.
# This is deliberately after adapters are updated, so it does not disturb the pager.
s = replace_required(
    s,
    '        postAdapter.setPosts(visible);\n        gridAdapter.setPosts(visible);',
    '        postAdapter.setPosts(visible);\n        gridAdapter.setPosts(visible);\n        persistSavedPostIds();',
    'persist discovered Saved IDs')

# ---------------------------------------------------------------------------
# Filter UI: independent toggles under the existing Filter bottom sheet.
# ---------------------------------------------------------------------------
start = s.find('    private void showMediaSheet() {')
end = s.find('    private void showLayoutSheet() {', start)
if start < 0 or end < 0:
    raise SystemExit('Missing v3.6.9 target: showMediaSheet block')
segment = s[start:end]
needle = '        dialog.setContentView(body);\n        dialog.show();'
if needle not in segment:
    raise SystemExit('Missing v3.6.9 target: showMediaSheet dialog tail')
addition = '''        TextView contentTitle = sectionTitle("Content filters");
        LinearLayout.LayoutParams contentTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentTitleParams.topMargin = dp(14);
        body.addView(contentTitle, contentTitleParams);

        Button lgbtqFilter = sheetButton("LGBTQ topics · "
                + (blockLgbtqTopics ? "Blocked" : "Allowed"));
        Button goreFilter = sheetButton("Gore / blood · "
                + (blockGoreBlood ? "Blocked" : "Allowed"));
        body.addView(lgbtqFilter, sectionButtonParams());
        body.addView(goreFilter, sectionButtonParams());

        lgbtqFilter.setOnClickListener(v -> {
            blockLgbtqTopics = !blockLgbtqTopics;
            prefs.edit().putBoolean("blockLgbtqTopics", blockLgbtqTopics).apply();
            dialog.dismiss();
            reloadCurrent();
        });
        goreFilter.setOnClickListener(v -> {
            blockGoreBlood = !blockGoreBlood;
            prefs.edit().putBoolean("blockGoreBlood", blockGoreBlood).apply();
            dialog.dismiss();
            reloadCurrent();
        });

'''
segment = segment.replace(needle, addition + needle, 1)
s = s[:start] + segment + s[end:]

# Blank Search status must reflect the rendered unread collection, not merely raw
# collected results that were later excluded by Saved/topic filters.
s = s.replace(
    '        if (collected.isEmpty()) {\n            setStatus("No matching media found for “" + query + "”.", false);',
    '        if (postAdapter.getItemCount() == 0) {\n            setStatus("No matching unread media found for “" + query + "”.", false);',
    1)

path.write_text(s)
print('Applied v3.6.9 unread/saved separation + independent LGBTQ and gore/blood topic filters')
