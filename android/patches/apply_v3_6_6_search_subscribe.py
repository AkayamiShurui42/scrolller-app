from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.6 patch target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)


path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# ---------------------------------------------------------------------------
# Subreddit subscription control.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '    private Button feedButton;\n    private Button sortButton;',
    '    private Button feedButton;\n    private Button subscribeButton;\n    private Button sortButton;',
    'subscribe button field')

s = replace_required(
    s,
    '''        topTitle.setOnClickListener(v -> {
            if (screen == Screen.HOME) showFeedSheet();
            else if (screen == Screen.USER && !profileUser.isEmpty()) {
                openBrowser(REDDIT + "/user/" + enc(profileUser) + "/", BrowserPurpose.SETTINGS);
            }
        });

        searchInput = new EditText(this);''',
    '''        topTitle.setOnClickListener(v -> {
            if (screen == Screen.HOME) showFeedSheet();
            else if (screen == Screen.USER && !profileUser.isEmpty()) {
                openBrowser(REDDIT + "/user/" + enc(profileUser) + "/", BrowserPurpose.SETTINGS);
            }
        });

        subscribeButton = topPill("Subscribe");
        subscribeButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams subscribeParams = new LinearLayout.LayoutParams(dp(104), dp(38));
        subscribeParams.leftMargin = dp(4);
        subscribeParams.rightMargin = dp(4);
        topHeader.addView(subscribeButton, subscribeParams);
        subscribeButton.setOnClickListener(v -> toggleSubredditSubscription());

        searchInput = new EditText(this);''',
    'subreddit subscribe header control')

# Refresh the header when the asynchronous subscription listing finishes so the
# current subreddit never remains stuck showing the pre-load state.
s = replace_required(
    s,
    '''    private void finishSubscriptions(@Nullable Runnable done) {
        subscriptions.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        if (done != null) done.run();
        if (screen == Screen.ACCOUNT) renderAccount();
    }''',
    '''    private void finishSubscriptions(@Nullable Runnable done) {
        subscriptions.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        if (done != null) done.run();
        if (screen == Screen.ACCOUNT) renderAccount();
        updateChrome();
    }''',
    'refresh subscription header after list load')

# ---------------------------------------------------------------------------
# Search is ephemeral. A fresh Search-tab visit always starts blank, submitting
# clears the text box, and history restoration never silently reruns an old query.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '        query = prefs.getString("lastSearch", "");',
    '        query = "";\n        prefs.edit().remove("lastSearch").apply();',
    'do not restore stale search from preferences')

old_restore_search = '''        } else if (screen == Screen.SEARCH) {
            applyLayoutVisibility();
            if (query.isEmpty()) {
                replacePosts(new ArrayList<>());
                setStatus("Search Reddit media by title, community, or creator.", false);
            } else {
                loadSearchInternal();
            }
        } else if (screen == Screen.FAVORITES) {'''
new_restore_search = '''        } else if (screen == Screen.SEARCH) {
            query = "";
            prefs.edit().remove("lastSearch").apply();
            applyLayoutVisibility();
            replacePosts(new ArrayList<>());
            searchInput.setText("");
            setStatus("Search Reddit media by title, community, or creator.", false);
        } else if (screen == Screen.FAVORITES) {'''
s = replace_required(s, old_restore_search, new_restore_search, 'history returns to clean Search')

old_open_search = '''    private void openSearchScreen() {
        if (screen != Screen.SEARCH) pushCurrentState();
        screen = Screen.SEARCH;
        context = "search";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        updateChrome();
        searchInput.setText(query);
        searchInput.setSelection(searchInput.length());
        if (query.isEmpty()) {
            replacePosts(new ArrayList<>());
            setStatus("Search Reddit media by title, community, or creator.", false);
            searchInput.requestFocus();
        } else {
            loadSearchInternal();
        }
    }'''
new_open_search = '''    private void openSearchScreen() {
        if (screen != Screen.SEARCH) pushCurrentState();
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
        setStatus("Search Reddit media by title, community, or creator.", false);
        searchInput.requestFocus();
    }'''
s = replace_required(s, old_open_search, new_open_search, 'fresh Search tab entry')

s = replace_required(
    s,
    '''            query = "";
            prefs.edit().putString("lastSearch", "").apply();
            replacePosts(new ArrayList<>());''',
    '''            query = "";
            prefs.edit().remove("lastSearch").apply();
            replacePosts(new ArrayList<>());''',
    'empty search does not persist state')

s = replace_required(
    s,
    '''        query = next;
        prefs.edit().putString("lastSearch", query).apply();
        screen = Screen.SEARCH;
        loadSearchInternal();''',
    '''        query = next;
        prefs.edit().remove("lastSearch").apply();
        searchInput.setText("");
        screen = Screen.SEARCH;
        loadSearchInternal();''',
    'clear input after Search submission')

# updateChrome previously repopulated the search EditText from query on every
# state update, undoing the clear immediately after submit.
s = replace_required(
    s,
    '''        if (searchMode && !searchInput.getText().toString().equals(query)) {
            searchInput.setText(query);
            searchInput.setSelection(searchInput.length());
        }

        String title;''',
    '''        String title;''',
    'do not repopulate submitted search text')

# ---------------------------------------------------------------------------
# Exhaustive, unique Search listing pagination.
# Follow Reddit's after cursor until exhaustion/repetition, dedupe every post ID
# across the whole query, and only order/shuffle after collection is complete.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '        fetchSearchPage("", new ArrayList<>(), 0);',
    '''        fetchSearchPage(
                "",
                new ArrayList<>(),
                new HashSet<>(),
                new HashSet<>(),
                0,
                0);''',
    'Search entry uses persistent per-query dedupe state')

old_fetch_search = '''    private void fetchSearchPage(String cursor, ArrayList<RedditPost> collected, int page) {
        String searchSort = sort.equals("random") ? "new"
                : sort.equals("best") ? "relevance"
                : (sort.equals("rising") ? "new" : sort);
        String path = "/search.json?q=" + enc(query)
                + "&type=link&limit=100&raw_json=1&sort=" + enc(searchSort);
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);

        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                setStatus("Search failed: " + friendlyError(result), false);
                return;
            }
            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty() || hiddenPosts.containsKey(post.id)) continue;
                    if (searchScope.equals("subscribed")
                            && !subscriptionNames.contains(post.subreddit.toLowerCase(Locale.US))) {
                        continue;
                    }
                    collected.add(post);
                }
            }
            String next = data != null ? data.optString("after", "") : "";
            int maxPages = 20;
            if (collected.size() < 45 && !next.isEmpty() && page + 1 < maxPages) {
                fetchSearchPage(next, collected, page + 1);
                return;
            }
            loading = false;
            if (sort.equals("random")) Collections.shuffle(collected);
            replacePosts(collected);
            if (collected.isEmpty()) {
                setStatus("No matching media found for “" + query + "”.", false);
            } else {
                hideStatus();
            }
            updateChrome();
            restorePendingPosition();
        });
    }'''

new_fetch_search = '''    private void fetchSearchPage(
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors,
            int page,
            int rawFetched) {
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            finishSearchCollection(collected);
            return;
        }

        String searchSort = sort.equals("random") ? "new"
                : sort.equals("best") ? "relevance"
                : (sort.equals("rising") ? "new" : sort);
        String path = "/search.json?q=" + enc(query)
                + "&type=link&limit=100&raw_json=1&show=all&sort=" + enc(searchSort);
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        if (rawFetched > 0) path += "&count=" + rawFetched;

        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                setStatus("Search failed: " + friendlyError(result), false);
                return;
            }

            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            int pageRawCount = children != null ? children.length() : 0;

            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!seenPostIds.add(post.id)) continue;
                    if (hiddenPosts.containsKey(post.id)) continue;
                    if (searchScope.equals("subscribed")
                            && !subscriptionNames.contains(post.subreddit.toLowerCase(Locale.US))) {
                        continue;
                    }
                    collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            int nextRawFetched = rawFetched + pageRawCount;
            boolean canContinue = !next.isEmpty()
                    && !seenCursors.contains(next)
                    && page < 49;

            if (canContinue) {
                fetchSearchPage(
                        next,
                        collected,
                        seenPostIds,
                        seenCursors,
                        page + 1,
                        nextRawFetched);
                return;
            }

            finishSearchCollection(collected);
        });
    }

    private void finishSearchCollection(ArrayList<RedditPost> collected) {
        loading = false;
        if (sort.equals("random")) Collections.shuffle(collected);
        replacePosts(collected);
        if (collected.isEmpty()) {
            setStatus("No matching media found for “" + query + "”.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }'''

s = replace_required(s, old_fetch_search, new_fetch_search, 'exhaustive unique Search pagination')

# ---------------------------------------------------------------------------
# Header state and native subscribe/unsubscribe API action.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '''        searchGoButton.setVisibility(searchMode ? View.VISIBLE : View.GONE);
        String title;''',
    '''        searchGoButton.setVisibility(searchMode ? View.VISIBLE : View.GONE);

        boolean subredditScreen = screen == Screen.HOME
                && context.equals("subreddit")
                && subreddit != null
                && !subreddit.isEmpty();
        subscribeButton.setVisibility(subredditScreen ? View.VISIBLE : View.GONE);
        if (subredditScreen) {
            boolean subscribed = subscriptionNames.contains(subreddit.toLowerCase(Locale.US));
            subscribeButton.setText(subscribed ? "Unsubscribe" : "Subscribe");
        }

        String title;''',
    'subscription state in header chrome')

anchor = '    private void openBrowser(String url, BrowserPurpose purpose) {'
subscribe_method = '''    private void toggleSubredditSubscription() {
        if (screen != Screen.HOME || !context.equals("subreddit")
                || subreddit == null || subreddit.isEmpty()) return;

        if (username.isEmpty()) {
            openBrowser(
                    REDDIT + "/login/?dest=" + enc(REDDIT + "/r/" + subreddit + "/"),
                    BrowserPurpose.LOGIN);
            return;
        }

        final String target = subreddit;
        final String key = target.toLowerCase(Locale.US);
        final boolean currentlySubscribed = subscriptionNames.contains(key);

        subscribeButton.setEnabled(false);
        subscribeButton.setText(currentlySubscribed ? "Leaving…" : "Joining…");

        String body = "action=" + enc(currentlySubscribed ? "unsub" : "sub")
                + "&sr_name=" + enc(target)
                + "&uh=" + enc(modhash);
        engine.postForm("/api/subscribe", body, result -> {
            subscribeButton.setEnabled(true);
            if (!result.ok) {
                setStatus((currentlySubscribed ? "Unsubscribe" : "Subscribe")
                        + " failed: " + friendlyError(result), false);
                updateChrome();
                return;
            }

            if (currentlySubscribed) {
                subscriptionNames.remove(key);
                for (int i = subscriptions.size() - 1; i >= 0; i--) {
                    if (subscriptions.get(i).name.equalsIgnoreCase(target)) {
                        subscriptions.remove(i);
                    }
                }
            } else if (subscriptionNames.add(key)) {
                subscriptions.add(new Subscription(target, ""));
                subscriptions.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            }

            updateChrome();
        });
    }

'''
if anchor not in s:
    raise SystemExit('Missing v3.6.6 patch target: subscribe method insertion anchor')
s = s.replace(anchor, subscribe_method + anchor, 1)

path.write_text(s)
print('Applied v3.6.6 exhaustive Search + clean Search state + subreddit subscribe controls')
