from pathlib import Path


def replace_method(text, start_sig, next_sig, replacement, label):
    start = text.find(start_sig)
    if start < 0:
        raise SystemExit(f'Missing v3.7.3 method start: {label}: {start_sig}')
    end = text.find(next_sig, start + len(start_sig))
    if end < 0:
        raise SystemExit(f'Missing v3.7.3 method end: {label}: {next_sig}')
    return text[:start] + replacement + text[end:]


main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

# ---------------------------------------------------------------------------
# Feed state has its own generation. A reset is allowed to supersede an in-flight
# request, and stale callbacks are ignored instead of contaminating the new sort,
# media filter or destination. Top/All follows Reddit's cursor to exhaustion and
# then supplements the visible ranking from the historical archive.
# ---------------------------------------------------------------------------
state_anchor = '''    private boolean historicalPrefetchRunning = false;
    private boolean historicalPrefetchDone = false;
'''
state_replacement = '''    private boolean historicalPrefetchRunning = false;
    private boolean historicalPrefetchDone = false;
    private boolean topAllArchiveRunning = false;
    private boolean topAllArchiveDone = false;
    private int feedGeneration = 0;
'''
if state_anchor not in s:
    raise SystemExit('Missing v3.7.3 state anchor')
s = s.replace(state_anchor, state_replacement, 1)

new_feed = r'''    private void loadFeed(boolean reset) {
        if (!engine.isReady()) return;
        if (loading && !reset) return;

        if (reset) {
            feedGeneration++;
            loading = false;
            after = "";
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            archivePrefetchGeneration++;
            archivePrefetchRunning = false;
            archivePrefetchDone = false;
            scrolllerPrefetchRunning = false;
            scrolllerPrefetchDone = false;
            historicalPrefetchRunning = false;
            historicalPrefetchDone = false;
            topAllArchiveRunning = false;
            topAllArchiveDone = false;
            deferredAppends.clear();
            deferredAppendScheduled = false;
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Loading media…", true);
        }

        final int generation = feedGeneration;
        loading = true;
        fetchFeedPages(generation, reset, new ArrayList<>(), 0);
    }

    private void fetchFeedPages(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected,
            int page) {
        if (generation != feedGeneration || screen != Screen.HOME) return;

        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !feedSeenCursors.add(cursor)) {
            after = "";
            finishFeedCollection(generation, reset, collected);
            return;
        }

        String path = listingPath(cursor);
        engine.get(path, result -> {
            if (generation != feedGeneration || screen != Screen.HOME) return;
            if (!result.ok) {
                loading = false;
                if (context.equals("subreddit") && subreddit != null && !subreddit.isEmpty()) {
                    setStatus("Live Reddit unavailable; loading historical r/" + subreddit + "…", true);
                    prefetchHistoricalSubredditIfNeeded(true);
                    return;
                }
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Reddit feed failed: " + friendlyError(result), false);
                }
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    String key = canonicalPostKey(post);
                    if (key.isEmpty() || !feedSeenPostIds.add(key)) continue;
                    if (hiddenPosts.containsKey(post.id)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            after = next;

            boolean topAll = sort.equals("top") && topTime.equals("all");
            boolean clientOrder = sort.equals("random") || sort.equals("oldest");
            int target = clientOrder ? 120 : 30;
            int pageLimit = topAll ? 100 : clientOrder ? 20 : 20;
            boolean canContinue = !next.isEmpty()
                    && !feedSeenCursors.contains(next)
                    && page + 1 < pageLimit;
            boolean needMore = topAll || clientOrder || collected.size() < target;

            if (canContinue && needMore) {
                fetchFeedPages(generation, reset, collected, page + 1);
                return;
            }

            finishFeedCollection(generation, reset, collected);
        });
    }

    private void finishFeedCollection(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected) {
        if (generation != feedGeneration || screen != Screen.HOME) return;
        loading = false;

        if (sort.equals("random")) {
            Collections.shuffle(collected);
        } else if (sort.equals("oldest")) {
            Collections.reverse(collected);
        }

        if (reset) replacePosts(collected);
        else appendUnique(collected);

        if (postAdapter.getItemCount() == 0) {
            setStatus("No unique media posts match this feed/filter.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
        prefetchSubredditReservoir();

        if (context.equals("subreddit") && sort.equals("top") && topTime.equals("all")) {
            prefetchHistoricalTopAllIfNeeded(generation);
        }
    }

'''
s = replace_method(
    s,
    '    private void loadFeed(boolean reset) {',
    '    private String listingPath(String cursor) {',
    new_feed,
    'generation-safe feed block')

# ---------------------------------------------------------------------------
# Canonical collection identity. Reddit, Scrolller and Arctic Shift can expose
# the same Reddit item with slightly different ID formatting or media URLs. Every
# rendered collection dedupes on normalized post identity; Random also dedupes
# normalized primary media so cross-source rediscovery cannot visibly repeat.
# ---------------------------------------------------------------------------
replace_posts = r'''    private void replacePosts(List<RedditPost> items) {
        lastFullscreenPostId = "";
        mediaReadyPostIds.clear();
        mediaFailedPostIds.clear();
        boolean hiddenLibrary = showingHiddenLibrary();
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        boolean randomFeed = screen == Screen.HOME && sort.equals("random");
        postAdapter.setHiddenMode(hiddenLibrary);

        Set<String> ids = new HashSet<>();
        Set<String> mediaKeys = new HashSet<>();
        ArrayList<RedditPost> visible = new ArrayList<>();
        if (items != null) {
            for (RedditPost post : items) {
                if (post == null || post.id == null || post.id.isEmpty()) continue;
                if (!hiddenLibrary && !favoritesSaved
                        && (hiddenPosts.containsKey(post.id)
                        || isSavedForUnread(post)
                        || isContentBlocked(post))) continue;

                String key = canonicalPostKey(post);
                if (key.isEmpty() || !ids.add(key)) continue;
                if (randomFeed) {
                    String mediaKey = canonicalMediaKey(post);
                    if (!mediaKey.isEmpty() && !mediaKeys.add(mediaKey)) continue;
                }
                visible.add(post);
            }
        }
        postAdapter.setPosts(visible);
        gridAdapter.setPosts(visible);
    }

'''
s = replace_method(
    s,
    '    private void replacePosts(List<RedditPost> items) {',
    '    private void appendUnique(List<RedditPost> incoming) {',
    replace_posts,
    'canonical replacePosts')

append_now = r'''    private void appendUniqueNow(List<RedditPost> incoming) {
        if (showingHiddenLibrary() || incoming == null || incoming.isEmpty()) return;
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        boolean randomFeed = screen == Screen.HOME && sort.equals("random");

        Set<String> ids = new HashSet<>();
        Set<String> mediaKeys = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty()) ids.add(key);
            if (randomFeed) {
                String mediaKey = canonicalMediaKey(post);
                if (!mediaKey.isEmpty()) mediaKeys.add(mediaKey);
            }
        }

        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (!favoritesSaved
                    && (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post))) continue;

            String key = canonicalPostKey(post);
            if (key.isEmpty() || !ids.add(key)) continue;
            if (randomFeed) {
                String mediaKey = canonicalMediaKey(post);
                if (!mediaKey.isEmpty() && !mediaKeys.add(mediaKey)) continue;
            }
            unique.add(post);
        }
        postAdapter.appendPosts(unique);
        gridAdapter.appendPosts(unique);
    }

'''
s = replace_method(
    s,
    '    private void appendUniqueNow(List<RedditPost> incoming) {',
    '    private void openFullscreenAt(int position) {',
    append_now,
    'canonical appendUniqueNow')

identity_helpers = r'''    private String canonicalPostKey(RedditPost post) {
        if (post == null) return "";
        String id = post.id == null ? "" : post.id.trim().toLowerCase(Locale.US);
        if (id.startsWith("t3_")) id = id.substring(3);
        if (!id.isEmpty()) return "id:" + id;
        String permalink = post.permalink == null ? "" : post.permalink.trim().toLowerCase(Locale.US);
        return permalink.isEmpty() ? "" : "path:" + permalink;
    }

    private String canonicalMediaKey(RedditPost post) {
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
anchor = '    private void openFullscreenAt(int position) {'
if anchor not in s:
    raise SystemExit('Missing v3.7.3 identity helper anchor')
s = s.replace(anchor, identity_helpers + anchor, 1)

# Upstream supplemental sources also use the same normalized post identity.
s = s.replace('feedSeenPostIds.add(post.id)', 'feedSeenPostIds.add(canonicalPostKey(post))')
s = s.replace('feedSeenPostIds.remove(post.id)', 'feedSeenPostIds.remove(canonicalPostKey(post))')

# ---------------------------------------------------------------------------
# Top/All historical supplement. Arctic Shift is time-cursor based rather than
# score-cursor based, so this is a bounded historical enrichment pass followed by
# a client-side score sort. It fixes premature live-list exhaustion without
# falsely claiming the remote archive can stream an infinite score-sorted cursor.
# ---------------------------------------------------------------------------
top_all_methods = r'''    private void prefetchHistoricalTopAllIfNeeded(int generation) {
        if (topAllArchiveRunning || topAllArchiveDone) return;
        if (generation != feedGeneration || screen != Screen.HOME) return;
        if (!context.equals("subreddit") || subreddit == null || subreddit.isEmpty()) return;
        if (!sort.equals("top") || !topTime.equals("all")) return;

        topAllArchiveRunning = true;
        final String targetSubreddit = subreddit;
        final int targetArchiveGeneration = archivePrefetchGeneration;
        final ArrayList<RedditPost> historical = new ArrayList<>();
        final Set<String> historicalIds = new HashSet<>();

        ArcticShiftClient.crawlSubreddit(targetSubreddit, 4000, new ArcticShiftClient.CrawlCallback() {
            @Override
            public void onBatch(JSONArray items) {
                if (!topAllContextStillValid(generation, targetArchiveGeneration, targetSubreddit)) return;
                for (int i = 0; i < items.length(); i++) {
                    RedditPost post = redditPostFromArcticArchive(items.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (hiddenPosts.containsKey(post.id)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    String key = canonicalPostKey(post);
                    if (!key.isEmpty() && historicalIds.add(key)) historical.add(post);
                }
            }

            @Override
            public void onComplete() {
                if (!topAllContextStillValid(generation, targetArchiveGeneration, targetSubreddit)) return;
                topAllArchiveRunning = false;
                topAllArchiveDone = true;
                mergeHistoricalTopAll(generation, targetArchiveGeneration, targetSubreddit, historical);
            }

            @Override
            public void onError(String error) {
                if (!topAllContextStillValid(generation, targetArchiveGeneration, targetSubreddit)) return;
                topAllArchiveRunning = false;
                topAllArchiveDone = true;
            }
        });
    }

    private boolean topAllContextStillValid(
            int generation, int targetArchiveGeneration, String targetSubreddit) {
        return generation == feedGeneration
                && targetArchiveGeneration == archivePrefetchGeneration
                && screen == Screen.HOME
                && context.equals("subreddit")
                && sort.equals("top")
                && topTime.equals("all")
                && subreddit != null
                && subreddit.equalsIgnoreCase(targetSubreddit);
    }

    private void mergeHistoricalTopAll(
            int generation,
            int targetArchiveGeneration,
            String targetSubreddit,
            ArrayList<RedditPost> historical) {
        if (!topAllContextStillValid(generation, targetArchiveGeneration, targetSubreddit)) return;
        if (layoutMode.equals("fullscreen") && pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            root.postDelayed(() -> mergeHistoricalTopAll(
                    generation, targetArchiveGeneration, targetSubreddit, historical), 140L);
            return;
        }

        RedditPost current = postAdapter.getPost(pager.getCurrentItem());
        String currentKey = canonicalPostKey(current);
        ArrayList<RedditPost> merged = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty() && ids.add(key)) merged.add(post);
        }
        for (RedditPost post : historical) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty() && ids.add(key)) merged.add(post);
        }

        merged.sort((a, b) -> {
            int scoreOrder = Integer.compare(b.score, a.score);
            if (scoreOrder != 0) return scoreOrder;
            return Long.compare(b.createdUtc, a.createdUtc);
        });
        replacePosts(merged);

        if (!currentKey.isEmpty()) {
            for (int i = 0; i < postAdapter.getItemCount(); i++) {
                if (currentKey.equals(canonicalPostKey(postAdapter.getPost(i)))) {
                    pager.setCurrentItem(i, false);
                    if (layoutMode.equals("grid")) gridView.scrollToPosition(i);
                    break;
                }
            }
        }
        hideStatus();
        updateChrome();
    }

'''
historical_anchor = '    private void prefetchHistoricalSubredditIfNeeded(boolean forceFallback) {'
if historical_anchor not in s:
    raise SystemExit('Missing v3.7.3 historical helper anchor')
s = s.replace(historical_anchor, top_all_methods + historical_anchor, 1)

# ---------------------------------------------------------------------------
# Navigation must not inherit the global loading flag from the screen being left.
# In particular, opening a user while a feed reservoir is active previously made
# loadUserProfileInternal() return immediately. The feed generation guard above
# makes it safe to clear the foreground loading gate on profile navigation.
# ---------------------------------------------------------------------------
open_user_old = '''    private void openUserProfile(String name) {
        if (name == null || name.isEmpty()) return;
        pushCurrentState();'''
open_user_new = '''    private void openUserProfile(String name) {
        if (name == null || name.isEmpty()) return;
        loading = false;
        feedGeneration++;
        pushCurrentState();'''
if open_user_old not in s:
    raise SystemExit('Missing v3.7.3 openUserProfile anchor')
s = s.replace(open_user_old, open_user_new, 1)

# Deleted-author taps resolve the original author from the archived post record
# when possible, then use the existing live->historical user-profile fallback.
on_user_old = '''    @Override
    public void onOpenUser(String username) {
        openUserProfile(username);
    }
'''
on_user_new = r'''    @Override
    public void onOpenUser(RedditPost post) {
        if (post == null) return;
        String author = post.author == null ? "" : post.author.trim();
        if (!isDeletedAuthorName(author)) {
            openUserProfile(author);
            return;
        }

        setStatus("Resolving deleted account from archive…", true);
        ArcticShiftClient.lookupPost(post.id, new ArcticShiftClient.LookupCallback() {
            @Override
            public void onComplete(JSONArray items) {
                String archivedAuthor = "";
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        String candidate = item.optString("author", "").trim();
                        if (!isDeletedAuthorName(candidate)) {
                            archivedAuthor = candidate;
                            break;
                        }
                    }
                }
                if (archivedAuthor.isEmpty()) {
                    setStatus("The archived post exists, but its original account identity is unavailable.", false);
                    return;
                }
                openUserProfile(archivedAuthor);
            }

            @Override
            public void onError(String error) {
                setStatus("Could not resolve the deleted account from archive: " + error, false);
            }
        });
    }

    private boolean isDeletedAuthorName(String value) {
        if (value == null) return true;
        String clean = value.trim().toLowerCase(Locale.US);
        return clean.isEmpty()
                || clean.equals("[deleted]")
                || clean.equals("deleted")
                || clean.equals("[removed]")
                || clean.equals("removed");
    }
'''
if on_user_old not in s:
    raise SystemExit('Missing v3.7.3 onOpenUser callback')
s = s.replace(on_user_old, on_user_new, 1)

main_path.write_text(s)

# PostPagerAdapter now passes the whole post when an author is tapped so a
# deleted live author can be resolved by exact post ID through Arctic Shift.
pager_path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
p = pager_path.read_text()
if '        void onOpenUser(String username);' not in p:
    raise SystemExit('Missing v3.7.3 PostPagerAdapter listener target')
p = p.replace('        void onOpenUser(String username);', '        void onOpenUser(RedditPost post);', 1)
if 'listener.onOpenUser(post.author)' not in p:
    raise SystemExit('Missing v3.7.3 PostPagerAdapter author click target')
p = p.replace('listener.onOpenUser(post.author)', 'listener.onOpenUser(post)', 1)
pager_path.write_text(p)

# Stabilization release version.
gradle = Path('app/build.gradle')
g = gradle.read_text()
if 'versionCode 30' not in g or 'versionName "3.7.2"' not in g:
    raise SystemExit('Missing v3.7.3 version targets')
g = g.replace('versionCode 30', 'versionCode 31', 1)
g = g.replace('versionName "3.7.2"', 'versionName "3.7.3"', 1)
gradle.write_text(g)

print('Applied v3.7.3 feed/filter/search stability + canonical dedupe + deleted-user resolution')
