from pathlib import Path
import re


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.7.0 search target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)


def replace_between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0:
        raise SystemExit(f'Missing v3.7.0 search start: {label}: {start}')
    b = text.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f'Missing v3.7.0 search end: {label}: {end}')
    return text[:a] + replacement + text[b:]


path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# Search source state is deliberately separate from the ephemeral query itself.
s = replace_required(
    s,
    '    private String searchScope = "global";\n    private String query = "";',
    '    private String searchScope = "global";\n'
    '    private String searchSubreddit = "";\n'
    '    private final ArrayList<RedditPost> searchCollectorSnapshot = new ArrayList<>();\n'
    '    private int searchGeneration = 0;\n'
    '    private String query = "";',
    'search source fields')

s = replace_required(
    s,
    '        searchScope = prefs.getString("searchScope", "global");\n        query = "";',
    '        searchScope = prefs.getString("searchScope", "global");\n'
    '        searchSubreddit = prefs.getString("searchSubreddit", "");\n'
    '        query = "";',
    'load search source state')

# Capture the exact app-side collection before entering Search. If Search was
# opened from a subreddit, also adopt that subreddit as the natural target.
open_search = '''    private void openSearchScreen() {
        if (screen != Screen.SEARCH) {
            searchCollectorSnapshot.clear();
            searchCollectorSnapshot.addAll(postAdapter.getPosts());
            if (screen == Screen.HOME && context.equals("subreddit")
                    && subreddit != null && !subreddit.isEmpty()) {
                searchSubreddit = cleanSubredditName(subreddit);
                prefs.edit().putString("searchSubreddit", searchSubreddit).apply();
            }
            pushCurrentState();
        }
        query = "";
        prefs.edit().remove("lastSearch").apply();
        screen = Screen.SEARCH;
        context = "search";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        searchInput.setText("");
        updateChrome();
        setStatus("Search " + searchScopeDescription() + " by title, community, or creator.", false);
        searchInput.requestFocus();
    }

'''
s = replace_between(
    s,
    '    private void openSearchScreen() {',
    '    private void beginSearch() {',
    open_search,
    'openSearchScreen')

# Five independent search sources. Subreddit gets an explicit target field so
# users can search any subreddit, not only a preselected subscription.
scope_sheet = '''    private void showScopeSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Search source");
        scroll.addView(body);

        String[][] values = {
                {"global", "Global Reddit"},
                {"subscribed", "Subscriptions"},
                {"favorites", "Favorites / Saved"},
                {"collector", "Collector · current app collection"}
        };
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (searchScope.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                searchScope = pair[0];
                prefs.edit().putString("searchScope", searchScope).apply();
                dialog.dismiss();
                if (!query.isEmpty()) loadSearchInternal();
                updateChrome();
            });
        }

        TextView subTitle = sectionTitle("Subreddit search");
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(16);
        body.addView(subTitle, stp);

        EditText subredditInput = new EditText(this);
        subredditInput.setSingleLine(true);
        subredditInput.setHint("Subreddit name");
        subredditInput.setText(searchSubreddit);
        subredditInput.setTextColor(Color.WHITE);
        subredditInput.setHintTextColor(0xFF8E8E8E);
        subredditInput.setTextSize(14);
        subredditInput.setPadding(dp(12), 0, dp(12), 0);
        subredditInput.setBackground(rounded(0xE51A1A1A, 14));
        body.addView(subredditInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        Button subredditButton = sheetButton(
                searchSubreddit.isEmpty()
                        ? "Use subreddit"
                        : "Subreddit · r/" + searchSubreddit
                        + (searchScope.equals("subreddit") ? "  ✓" : ""));
        body.addView(subredditButton, sectionButtonParams());
        subredditButton.setOnClickListener(v -> {
            String target = cleanSubredditName(subredditInput.getText().toString());
            if (target.isEmpty()) {
                subredditInput.requestFocus();
                return;
            }
            searchSubreddit = target;
            searchScope = "subreddit";
            prefs.edit()
                    .putString("searchScope", searchScope)
                    .putString("searchSubreddit", searchSubreddit)
                    .apply();
            dialog.dismiss();
            if (!query.isEmpty()) loadSearchInternal();
            updateChrome();
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private String cleanSubredditName(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.toLowerCase(Locale.US).startsWith("r/")) clean = clean.substring(2);
        return clean.replaceAll("[^A-Za-z0-9_]", "");
    }

    private String searchScopeDescription() {
        if (searchScope.equals("subscribed")) return "subscriptions";
        if (searchScope.equals("favorites")) return "Favorites";
        if (searchScope.equals("collector")) return "Collector";
        if (searchScope.equals("subreddit")) {
            return searchSubreddit.isEmpty() ? "a subreddit" : "r/" + searchSubreddit;
        }
        return "global Reddit";
    }

    private String searchScopeLabel() {
        if (searchScope.equals("subscribed")) return "Subs";
        if (searchScope.equals("favorites")) return "Saved";
        if (searchScope.equals("collector")) return "Collector";
        if (searchScope.equals("subreddit")) {
            return searchSubreddit.isEmpty() ? "Subreddit" : "r/" + searchSubreddit;
        }
        return "Global";
    }

'''
s = replace_between(
    s,
    '    private void showScopeSheet() {',
    '    private void reloadCurrent() {',
    scope_sheet,
    'showScopeSheet')

# Chrome always names the actual source being searched.
s = replace_required(
    s,
    '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : "Feed");''',
    '''        feedButton.setText(screen == Screen.SEARCH
                ? searchScopeLabel()
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : "Feed");''',
    'search source chrome label')

# Replace the old single-endpoint search implementation. Remote sources use
# Reddit's own listing/search ordering. Collector and Favorites are local
# searches with matching local sort semantics.
search_methods = r'''    private void loadSearchInternal() {
        if (!engine.isReady() || query.isEmpty()) return;
        screen = Screen.SEARCH;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);

        final int generation = ++searchGeneration;
        loading = true;
        setStatus("Searching " + searchScopeDescription() + " for “" + query + "”…", true);
        updateChrome();

        if (searchScope.equals("collector")) {
            ArrayList<RedditPost> matches = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (RedditPost post : searchCollectorSnapshot) {
                if (post == null || post.id == null || post.id.isEmpty()) continue;
                if (!matchesMedia(post) || !matchesLocalSearch(post, query)) continue;
                if (!localWithinTopTime(post)) continue;
                if (ids.add(post.id)) matches.add(post);
            }
            sortLocalSearch(matches);
            finishLocalSearchCollection(generation, matches);
            return;
        }

        if (searchScope.equals("favorites")) {
            if (username.isEmpty()) {
                loading = false;
                setStatus("Sign in to search Favorites.", false);
                return;
            }
            fetchFavoritesSearchPage(
                    generation, "", new ArrayList<>(), new HashSet<>(), new HashSet<>(), 0, 0);
            return;
        }

        if (searchScope.equals("subreddit")) {
            String target = cleanSubredditName(searchSubreddit);
            if (target.isEmpty()) {
                loading = false;
                setStatus("Choose a subreddit in Search source first.", false);
                return;
            }
            ArrayList<String> groups = new ArrayList<>();
            groups.add(target);
            fetchRemoteSearchGroup(
                    generation, groups, 0, "", new ArrayList<>(),
                    new HashSet<>(), new HashSet<>(), 0, 0);
            return;
        }

        if (searchScope.equals("subscribed")) {
            if (username.isEmpty()) {
                loading = false;
                setStatus("Sign in to search subscriptions.", false);
                return;
            }
            ArrayList<String> groups = subscriptionSearchGroups();
            if (groups.isEmpty()) {
                loading = false;
                setStatus("No subscribed communities are available to search.", false);
                return;
            }
            fetchRemoteSearchGroup(
                    generation, groups, 0, "", new ArrayList<>(),
                    new HashSet<>(), new HashSet<>(), 0, 0);
            return;
        }

        ArrayList<String> global = new ArrayList<>();
        global.add("__global__");
        fetchRemoteSearchGroup(
                generation, global, 0, "", new ArrayList<>(),
                new HashSet<>(), new HashSet<>(), 0, 0);
    }

    private boolean searchStillValid(int generation) {
        return generation == searchGeneration && screen == Screen.SEARCH;
    }

    private ArrayList<String> subscriptionSearchGroups() {
        ArrayList<String> names = new ArrayList<>();
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) names.add(clean);
        }
        ArrayList<String> groups = new ArrayList<>();
        for (int i = 0; i < names.size(); i += 10) {
            StringBuilder group = new StringBuilder();
            int end = Math.min(names.size(), i + 10);
            for (int j = i; j < end; j++) {
                if (group.length() > 0) group.append('+');
                group.append(names.get(j));
            }
            if (group.length() > 0) groups.add(group.toString());
        }
        return groups;
    }

    private void fetchRemoteSearchGroup(
            int generation,
            ArrayList<String> groups,
            int groupIndex,
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors,
            int page,
            int rawFetched) {
        if (!searchStillValid(generation)) return;
        if (groupIndex >= groups.size()) {
            finishSearchCollection(generation, collected);
            return;
        }
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            fetchRemoteSearchGroup(
                    generation, groups, groupIndex + 1, "", collected,
                    seenPostIds, new HashSet<>(), 0, 0);
            return;
        }

        String group = groups.get(groupIndex);
        String path = remoteSearchPath(group, cursor, rawFetched);
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (!result.ok) {
                if (searchScope.equals("subscribed")) {
                    fetchRemoteSearchGroup(
                            generation, groups, groupIndex + 1, "", collected,
                            seenPostIds, new HashSet<>(), 0, 0);
                } else {
                    loading = false;
                    setStatus("Search failed: " + friendlyError(result), false);
                }
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            int pageRawCount = children != null ? children.length() : 0;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!seenPostIds.add(post.id)) continue;
                    if (hiddenPosts.containsKey(post.id)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            int nextRawFetched = rawFetched + pageRawCount;
            boolean canContinue = !next.isEmpty()
                    && !seenCursors.contains(next)
                    && page < 49;
            if (canContinue) {
                fetchRemoteSearchGroup(
                        generation, groups, groupIndex, next, collected,
                        seenPostIds, seenCursors, page + 1, nextRawFetched);
                return;
            }

            if (groupIndex + 1 < groups.size()) {
                final int nextGroup = groupIndex + 1;
                setStatus("Searching subscriptions · " + (nextGroup + 1)
                        + "/" + groups.size() + "…", true);
                root.postDelayed(() -> fetchRemoteSearchGroup(
                        generation, groups, nextGroup, "", collected,
                        seenPostIds, new HashSet<>(), 0, 0), 120L);
                return;
            }
            finishSearchCollection(generation, collected);
        });
    }

    private String remoteSearchPath(String group, String cursor, int rawFetched) {
        String searchSort = sort.equals("random") ? "new"
                : sort.equals("best") ? "relevance"
                : sort.equals("rising") ? "new" : sort;
        boolean global = group.equals("__global__");
        String base = global ? "/search.json" : "/r/" + group + "/search.json";
        String path = base + "?q=" + enc(query)
                + "&type=link&limit=100&raw_json=1&show=all&sort=" + enc(searchSort)
                + "&restrict_sr=" + (global ? "off" : "on");
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        if (rawFetched > 0) path += "&count=" + rawFetched;
        return path;
    }

    private void finishSearchCollection(int generation, ArrayList<RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        loading = false;
        if (sort.equals("random")) Collections.shuffle(collected);
        replacePosts(collected);
        finishSearchUi(collected.size());
    }

    private void fetchFavoritesSearchPage(
            int generation,
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors,
            int page,
            int rawFetched) {
        if (!searchStillValid(generation)) return;
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            sortLocalSearch(collected);
            finishLocalSearchCollection(generation, collected);
            return;
        }

        String path = "/user/" + enc(username)
                + "/saved.json?limit=100&raw_json=1&show=all";
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        if (rawFetched > 0) path += "&count=" + rawFetched;
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (!result.ok) {
                loading = false;
                setStatus("Favorites search failed: " + friendlyError(result), false);
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            int pageRawCount = children != null ? children.length() : 0;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!seenPostIds.add(post.id)) continue;
                    if (!matchesLocalSearch(post, query)) continue;
                    if (!localWithinTopTime(post)) continue;
                    collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            int nextRawFetched = rawFetched + pageRawCount;
            boolean canContinue = !next.isEmpty()
                    && !seenCursors.contains(next)
                    && page < 49;
            if (canContinue) {
                fetchFavoritesSearchPage(
                        generation, next, collected, seenPostIds, seenCursors,
                        page + 1, nextRawFetched);
                return;
            }
            sortLocalSearch(collected);
            finishLocalSearchCollection(generation, collected);
        });
    }

    private boolean matchesLocalSearch(RedditPost post, String value) {
        if (post == null) return false;
        String normalizedQuery = normalizeLocalSearch(value);
        if (normalizedQuery.isEmpty()) return true;
        String haystack = normalizeLocalSearch(
                (post.title == null ? "" : post.title) + " "
                + (post.subreddit == null ? "" : post.subreddit) + " "
                + (post.author == null ? "" : post.author) + " "
                + (post.permalink == null ? "" : post.permalink) + " "
                + (post.sourceUrl == null ? "" : post.sourceUrl));
        String[] tokens = normalizedQuery.split(" ");
        for (String token : tokens) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

    private String normalizeLocalSearch(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US)
                .replaceAll("(^|\\s)[ru]/", "$1")
                .replaceAll("[^a-z0-9_]+", " ")
                .trim();
    }

    private int localSearchRelevance(RedditPost post) {
        String nq = normalizeLocalSearch(query);
        String title = normalizeLocalSearch(post.title);
        String community = normalizeLocalSearch(post.subreddit);
        String author = normalizeLocalSearch(post.author);
        int score = 0;
        if (!nq.isEmpty() && title.contains(nq)) score += 240;
        if (!nq.isEmpty() && community.equals(nq)) score += 220;
        if (!nq.isEmpty() && author.equals(nq)) score += 180;
        for (String token : nq.split(" ")) {
            if (token.isEmpty()) continue;
            if (title.contains(token)) score += 32;
            if (community.contains(token)) score += 20;
            if (author.contains(token)) score += 14;
        }
        score += Math.min(80, Math.max(0, post.score) / 100);
        return score;
    }

    private double localHotScore(RedditPost post) {
        long now = System.currentTimeMillis() / 1000L;
        long age = Math.max(1L, now - Math.max(0L, post.createdUtc));
        double hours = age / 3600.0;
        return (Math.max(0, post.score) + 1.0) / Math.pow(hours + 2.0, 0.82);
    }

    private boolean localWithinTopTime(RedditPost post) {
        if (!sort.equals("top") || topTime.equals("all")) return true;
        long seconds;
        if (topTime.equals("hour")) seconds = 3600L;
        else if (topTime.equals("day")) seconds = 86400L;
        else if (topTime.equals("week")) seconds = 604800L;
        else if (topTime.equals("month")) seconds = 2678400L;
        else if (topTime.equals("year")) seconds = 31536000L;
        else return true;
        long now = System.currentTimeMillis() / 1000L;
        return post.createdUtc > 0L && now - post.createdUtc <= seconds;
    }

    private void sortLocalSearch(ArrayList<RedditPost> items) {
        if (sort.equals("random")) {
            Collections.shuffle(items);
        } else if (sort.equals("new")) {
            items.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
        } else if (sort.equals("top")) {
            items.sort((a, b) -> Integer.compare(b.score, a.score));
        } else if (sort.equals("hot")) {
            items.sort((a, b) -> Double.compare(localHotScore(b), localHotScore(a)));
        } else {
            items.sort((a, b) -> {
                int relevance = Integer.compare(localSearchRelevance(b), localSearchRelevance(a));
                if (relevance != 0) return relevance;
                return Integer.compare(b.score, a.score);
            });
        }
    }

    private void finishLocalSearchCollection(int generation, ArrayList<RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        loading = false;
        lastFullscreenPostId = "";
        mediaReadyPostIds.clear();
        mediaFailedPostIds.clear();
        postAdapter.setHiddenMode(false);
        postAdapter.setPosts(collected);
        gridAdapter.setPosts(collected);
        finishSearchUi(collected.size());
    }

    private void finishSearchUi(int count) {
        if (count <= 0) {
            setStatus("No matching media found for “" + query + "” in "
                    + searchScopeDescription() + ".", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

'''
s = replace_between(
    s,
    '    private void loadSearchInternal() {',
    '    private void loadUserProfileInternal() {',
    search_methods,
    'source-aware Search implementation')

path.write_text(s)
print('Applied v3.7.0 source-aware Search: Global, Subreddit, Collector, Favorites, Subscriptions')
