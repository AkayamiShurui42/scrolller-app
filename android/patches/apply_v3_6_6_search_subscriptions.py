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
    'import android.widget.TextView;\n',
    'import android.widget.TextView;\nimport android.widget.Toast;\n',
    'Toast import')

s = replace_required(
    s,
    '    private Button searchGoButton;\n    private Button feedButton;',
    '    private Button searchGoButton;\n    private Button subredditSubscribeButton;\n    private Button feedButton;',
    'subreddit subscribe button field')

s = replace_required(
    s,
    '''        searchGoButton.setOnClickListener(v -> beginSearch());

        controlRow = new LinearLayout(this);''',
    '''        searchGoButton.setOnClickListener(v -> beginSearch());

        subredditSubscribeButton = topPill("Subscribe");
        subredditSubscribeButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams subscribeButtonParams = new LinearLayout.LayoutParams(dp(112), dp(38));
        subscribeButtonParams.leftMargin = dp(5);
        topHeader.addView(subredditSubscribeButton, subscribeButtonParams);
        subredditSubscribeButton.setOnClickListener(v -> toggleSubredditSubscription());

        controlRow = new LinearLayout(this);''',
    'subreddit subscribe button UI')

# Always refresh chrome after subscriptions finish so a currently-open subreddit
# immediately reflects the authoritative membership state.
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
    'subscription refresh updates subreddit button')

# Insert the native subscribe/unsubscribe action before navigation-state capture.
anchor = '    private NavState captureState() {'
subscription_methods = '''    private boolean isCurrentSubredditSubscribed() {
        return !subreddit.isEmpty()
                && subscriptionNames.contains(subreddit.toLowerCase(Locale.US));
    }

    private void toggleSubredditSubscription() {
        if (screen != Screen.HOME || !context.equals("subreddit") || subreddit.isEmpty()) return;
        if (username.isEmpty()) {
            openBrowser(REDDIT + "/login/?dest=" + enc(REDDIT + "/r/" + subreddit + "/"), BrowserPurpose.LOGIN);
            return;
        }

        boolean currentlySubscribed = isCurrentSubredditSubscribed();
        String action = currentlySubscribed ? "unsub" : "sub";
        String body = "action=" + enc(action)
                + "&sr_name=" + enc(subreddit)
                + (modhash.isEmpty() ? "" : "&uh=" + enc(modhash));

        subredditSubscribeButton.setEnabled(false);
        subredditSubscribeButton.setText(currentlySubscribed ? "Leaving…" : "Joining…");
        engine.postForm("/api/subscribe", body, result -> {
            subredditSubscribeButton.setEnabled(true);
            if (!result.ok) {
                updateChrome();
                Toast.makeText(this,
                        (currentlySubscribed ? "Unsubscribe" : "Subscribe")
                                + " failed: " + friendlyError(result),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            String key = subreddit.toLowerCase(Locale.US);
            if (currentlySubscribed) subscriptionNames.remove(key);
            else subscriptionNames.add(key);
            updateChrome();

            // Refresh the full subscription objects so Account and feed selectors
            // stay synchronized with Reddit after the write succeeds.
            loadSubscriptions(this::updateChrome);
        });
    }

'''
if anchor not in s:
    raise SystemExit('Missing v3.6.6 patch target: navigation-state anchor')
s = s.replace(anchor, subscription_methods + anchor, 1)

# Chrome only shows the control while actually browsing r/<subreddit>.
s = replace_required(
    s,
    '''        searchGoButton.setVisibility(searchMode ? View.VISIBLE : View.GONE);
        if (searchMode && !searchInput.getText().toString().equals(query)) {''',
    '''        searchGoButton.setVisibility(searchMode ? View.VISIBLE : View.GONE);
        boolean subredditMode = screen == Screen.HOME
                && context.equals("subreddit") && !subreddit.isEmpty();
        subredditSubscribeButton.setVisibility(subredditMode ? View.VISIBLE : View.GONE);
        if (subredditMode) {
            subredditSubscribeButton.setEnabled(true);
            subredditSubscribeButton.setText(isCurrentSubredditSubscribed() ? "Unsubscribe" : "Subscribe");
        }
        if (searchMode && !searchInput.getText().toString().equals(query)) {''',
    'subreddit subscribe chrome state')

# ---------------------------------------------------------------------------
# Search becomes transient. Returning to Search always opens a blank input and
# never reloads the prior query/results from SharedPreferences or history.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '        query = prefs.getString("lastSearch", "");\n',
    '        query = "";\n        prefs.edit().remove("lastSearch").apply();\n',
    'do not restore prior search at app start')

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
        screen = Screen.SEARCH;
        context = "search";
        profileUser = "";
        query = "";
        prefs.edit().remove("lastSearch").apply();
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        searchInput.setText("");
        updateChrome();
        setStatus("Search Reddit media by title, community, or creator.", false);
        searchInput.requestFocus();
    }'''

s = replace_required(s, old_open_search, new_open_search, 'fresh Search tab every entry')

s = replace_required(
    s,
    '''        query = next;
        prefs.edit().putString("lastSearch", query).apply();
        screen = Screen.SEARCH;
        loadSearchInternal();''',
    '''        query = next;
        prefs.edit().remove("lastSearch").apply();
        screen = Screen.SEARCH;
        loadSearchInternal();''',
    'search query is session-only')

# Back/history restoration of a Search destination also becomes a clean search
# screen instead of silently re-running the previous query.
s = replace_required(
    s,
    '''        } else if (screen == Screen.SEARCH) {
            applyLayoutVisibility();
            if (query.isEmpty()) {
                replacePosts(new ArrayList<>());
                setStatus("Search Reddit media by title, community, or creator.", false);
            } else {
                loadSearchInternal();
            }
        } else if (screen == Screen.FAVORITES) {''',
    '''        } else if (screen == Screen.SEARCH) {
            query = "";
            prefs.edit().remove("lastSearch").apply();
            applyLayoutVisibility();
            replacePosts(new ArrayList<>());
            searchInput.setText("");
            setStatus("Search Reddit media by title, community, or creator.", false);
            searchInput.requestFocus();
        } else if (screen == Screen.FAVORITES) {''',
    'history restore opens blank Search')

# ---------------------------------------------------------------------------
# Search pagination: exhaust Reddit's cursor chain, dedupe every post ID and
# cursor, and never let hidden/non-media/duplicate items satisfy completion.
# Random shuffles only after exhaustive collection.
# ---------------------------------------------------------------------------
old_search = '''    private void loadSearchInternal() {
        if (loading || !engine.isReady() || query.isEmpty()) return;
        screen = Screen.SEARCH;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        setStatus("Searching “" + query + "”…", true);
        loading = true;
        fetchSearchPage("", new ArrayList<>(), 0);
        updateChrome();
    }

    private void fetchSearchPage(String cursor, ArrayList<RedditPost> collected, int page) {
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

new_search = '''    private void loadSearchInternal() {
        if (loading || !engine.isReady() || query.isEmpty()) return;
        screen = Screen.SEARCH;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        setStatus("Searching “" + query + "”…", true);
        loading = true;
        fetchSearchPage("", new ArrayList<>(), new HashSet<>(), new HashSet<>(), 0);
        updateChrome();
    }

    private void fetchSearchPage(
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors,
            int page) {
        String searchSort = sort.equals("random") ? "new"
                : sort.equals("best") ? "relevance"
                : (sort.equals("rising") ? "new" : sort);
        String path = "/search.json?q=" + enc(query)
                + "&type=link&limit=100&raw_json=1&sort=" + enc(searchSort)
                + "&count=" + Math.max(0, page * 100);
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
            boolean repeatedCursor = !next.isEmpty() && !seenCursors.add(next);
            boolean canContinue = !next.isEmpty() && !repeatedCursor && page + 1 < 100;
            if (canContinue) {
                fetchSearchPage(next, collected, seenPostIds, seenCursors, page + 1);
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

s = replace_required(s, old_search, new_search, 'exhaustive unique Search pagination')

path.write_text(s)
print('Applied v3.6.6 subreddit subscribe/unsubscribe + transient exhaustive Search')
