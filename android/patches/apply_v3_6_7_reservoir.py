from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.7 reservoir target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

s = replace_required(
    s,
    '    private final Set<String> feedSeenCursors = new HashSet<>();\n    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();',
    '''    private final Set<String> feedSeenCursors = new HashSet<>();
    private boolean archivePrefetchRunning = false;
    private boolean archivePrefetchDone = false;
    private int archivePrefetchGeneration = 0;
    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();''',
    'archive state fields')

s = replace_required(
    s,
    '''            after = "";
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            replacePosts(new ArrayList<>());''',
    '''            after = "";
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            archivePrefetchGeneration++;
            archivePrefetchRunning = false;
            archivePrefetchDone = false;
            replacePosts(new ArrayList<>());''',
    'fresh feed archive reset')

s = replace_required(
    s,
    'position >= postAdapter.getItemCount() - 5',
    'position >= postAdapter.getItemCount() - 60',
    'earlier near-end refill')

s = replace_required(
    s,
    '''        if (postAdapter.getItemCount() == 0) {
            setStatus("No unique media posts match this feed/filter.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }''',
    '''        if (postAdapter.getItemCount() == 0) {
            setStatus("No unique media posts match this feed/filter.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
        prefetchSubredditReservoir();
    }''',
    'start reservoir after each rendered feed batch')

anchor = '    private void appendUnique(List<RedditPost> incoming) {'
if anchor not in s:
    raise SystemExit('Missing v3.6.7 reservoir target: appendUnique anchor')

methods = '''    private void prefetchSubredditReservoir() {
        if (screen != Screen.HOME || !context.equals("subreddit")
                || subreddit == null || subreddit.isEmpty()) return;

        if (!loading && postAdapter.getItemCount() < 300 && after != null && !after.isEmpty()) {
            final int generation = archivePrefetchGeneration;
            root.postDelayed(() -> {
                if (generation != archivePrefetchGeneration) return;
                if (screen == Screen.HOME && context.equals("subreddit")
                        && !loading && postAdapter.getItemCount() < 300
                        && after != null && !after.isEmpty()) {
                    loadFeed(false);
                }
            }, 40);
            return;
        }

        if (sort.equals("random")
                && (postAdapter.getItemCount() >= 300 || after == null || after.isEmpty())) {
            prefetchRandomSubredditArchiveIfNeeded();
        }
    }

    private void prefetchRandomSubredditArchiveIfNeeded() {
        if (archivePrefetchRunning || archivePrefetchDone) return;
        if (screen != Screen.HOME || !context.equals("subreddit") || !sort.equals("random")) return;
        if (subreddit == null || subreddit.isEmpty()) return;

        archivePrefetchRunning = true;
        final int generation = archivePrefetchGeneration;
        final String targetSubreddit = subreddit;
        fetchSubredditArchiveSource(generation, targetSubreddit, 0, "", new HashSet<>(), 0);
    }

    private boolean archiveContextStillValid(int generation, String targetSubreddit) {
        return generation == archivePrefetchGeneration
                && screen == Screen.HOME
                && context.equals("subreddit")
                && sort.equals("random")
                && subreddit != null
                && subreddit.equalsIgnoreCase(targetSubreddit);
    }

    private void fetchSubredditArchiveSource(
            int generation,
            String targetSubreddit,
            int source,
            String cursor,
            Set<String> sourceSeenCursors,
            int page) {
        if (!archiveContextStillValid(generation, targetSubreddit)) return;
        if (postAdapter.getItemCount() >= 800 || source >= 5) {
            archivePrefetchRunning = false;
            archivePrefetchDone = true;
            return;
        }
        if (!cursor.isEmpty() && !sourceSeenCursors.add(cursor)) {
            fetchSubredditArchiveSource(
                    generation, targetSubreddit, source + 1, "", new HashSet<>(), 0);
            return;
        }

        String path = archiveListingPath(targetSubreddit, source, cursor);
        engine.get(path, result -> {
            if (!archiveContextStillValid(generation, targetSubreddit)) return;
            if (!result.ok) {
                fetchSubredditArchiveSource(
                        generation, targetSubreddit, source + 1, "", new HashSet<>(), 0);
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            ArrayList<RedditPost> additions = new ArrayList<>();
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!feedSeenPostIds.add(post.id)) continue;
                    if (hiddenPosts.containsKey(post.id)) continue;
                    additions.add(post);
                }
            }
            if (!additions.isEmpty()) {
                Collections.shuffle(additions);
                appendUnique(additions);
            }

            String next = data != null ? data.optString("after", "") : "";
            boolean canContinue = !next.isEmpty()
                    && !sourceSeenCursors.contains(next)
                    && page < 4
                    && postAdapter.getItemCount() < 800;
            if (canContinue) {
                fetchSubredditArchiveSource(
                        generation,
                        targetSubreddit,
                        source,
                        next,
                        sourceSeenCursors,
                        page + 1);
            } else {
                fetchSubredditArchiveSource(
                        generation, targetSubreddit, source + 1, "", new HashSet<>(), 0);
            }
        });
    }

    private String archiveListingPath(String targetSubreddit, int source, String cursor) {
        String base = "/r/" + enc(targetSubreddit);
        String path;
        switch (source) {
            case 0:
                path = base + "/top.json?limit=100&raw_json=1&show=all&t=all";
                break;
            case 1:
                path = base + "/top.json?limit=100&raw_json=1&show=all&t=year";
                break;
            case 2:
                path = base + "/top.json?limit=100&raw_json=1&show=all&t=month";
                break;
            case 3:
                path = base + "/hot.json?limit=100&raw_json=1&show=all";
                break;
            default:
                path = base + "/controversial.json?limit=100&raw_json=1&show=all&t=all";
                break;
        }
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        return path;
    }

'''

s = s.replace(anchor, methods + anchor, 1)
path.write_text(s)
print('Applied v3.6.7 subreddit warm reservoir + Random archive discovery')
