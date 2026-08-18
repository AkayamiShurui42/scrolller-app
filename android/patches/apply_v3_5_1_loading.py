from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.5.1 patch target: {label}\n{old[:320]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# Keep one dedupe/cursor history for the active feed for its entire lifetime.
# This allows Random to render quickly in batches without ever re-adding a post
# that appeared in an earlier batch.
s = replace_required(
    s,
    '    private final Set<String> subscriptionNames = new HashSet<>();\n    private final Deque<NavState> history = new ArrayDeque<>();',
    '    private final Set<String> subscriptionNames = new HashSet<>();\n    private final Set<String> feedSeenPostIds = new HashSet<>();\n    private final Set<String> feedSeenCursors = new HashSet<>();\n    private final Deque<NavState> history = new ArrayDeque<>();',
    'persistent feed dedupe state')

old = '''    private void loadFeed(boolean reset) {
        if (loading || !engine.isReady()) return;
        loading = true;
        if (reset) {
            after = "";
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Loading unique media…", true);
        }
        fetchFeedPages(
                reset,
                new ArrayList<>(),
                new HashSet<>(),
                new HashSet<>(),
                0);
    }

    private void fetchFeedPages(
            boolean reset,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors,
            int page) {
        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            finishFeedCollection(reset, collected);
            return;
        }

        String path = listingPath(cursor);
        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Reddit feed failed: " + friendlyError(result), false);
                }
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
                    if (seenPostIds.add(post.id)) collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            after = next;
            boolean exhaustive = sort.equals("random") || sort.equals("oldest");
            boolean needMoreNormal = collected.size() < 18;
            boolean canContinue = !next.isEmpty()
                    && !seenCursors.contains(next)
                    && page < 100;

            if (canContinue && (exhaustive || needMoreNormal)) {
                fetchFeedPages(reset, collected, seenPostIds, seenCursors, page + 1);
                return;
            }

            finishFeedCollection(reset, collected);
        });
    }

    private void finishFeedCollection(boolean reset, ArrayList<RedditPost> collected) {
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
    }

'''

new = '''    private void loadFeed(boolean reset) {
        if (loading || !engine.isReady()) return;
        loading = true;
        if (reset) {
            after = "";
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus(sort.equals("oldest") ? "Finding oldest unique media…" : "Loading media…", true);
        }
        fetchFeedPages(reset, new ArrayList<>(), 0);
    }

    private void fetchFeedPages(
            boolean reset,
            ArrayList<RedditPost> collected,
            int page) {
        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !feedSeenCursors.add(cursor)) {
            after = "";
            finishFeedCollection(reset, collected);
            return;
        }

        String path = listingPath(cursor);
        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Reddit feed failed: " + friendlyError(result), false);
                }
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
                    if (feedSeenPostIds.add(post.id)) collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            after = next;
            boolean oldest = sort.equals("oldest");
            boolean random = sort.equals("random");
            boolean canContinue = !next.isEmpty() && !feedSeenCursors.contains(next);

            // Oldest truly requires reaching the end before ordering. Random does not.
            // Random gets a useful unique buffer quickly, then subsequent near-end
            // scrolls continue from `after` using the same seen-ID/cursor sets.
            boolean needMoreThisBatch = random
                    ? (collected.size() < 45 && page < 2)
                    : collected.size() < 18;

            if (canContinue && (oldest || needMoreThisBatch)) {
                fetchFeedPages(reset, collected, page + 1);
                return;
            }

            finishFeedCollection(reset, collected);
        });
    }

    private void finishFeedCollection(boolean reset, ArrayList<RedditPost> collected) {
        loading = false;
        if (sort.equals("random")) {
            Collections.shuffle(collected);
        } else if (sort.equals("oldest")) {
            Collections.reverse(collected);
            // Oldest exhausted the source listing, so there is nothing else to append.
            after = "";
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
    }

'''

s = replace_required(s, old, new, 'progressive unique Random feed loading')

path.write_text(s)
print('Applied v3.5.1 progressive unique loading fix')
