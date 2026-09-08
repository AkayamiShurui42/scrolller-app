from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()

old_error = '''            if (!result.ok) {
                // If at least one live page already arrived, keep it instead of
                // turning a later pagination/rate-limit failure into a dead feed.
                if (!collected.isEmpty()) {
                    after = "";
                    finishFeedCollection(generation, reset, collected);
                    return;
                }
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
            }'''

new_error = '''            if (!result.ok) {
                // If at least one live page already arrived, keep it instead of
                // turning a later pagination/rate-limit failure into a dead feed.
                if (!collected.isEmpty()) {
                    after = "";
                    finishFeedCollection(generation, reset, collected);
                    return;
                }
                if (context.equals("multi") && result.status == 404) {
                    after = "";
                    setStatus("Preset route unavailable; retrying subreddits individually…", true);
                    fetchMulti404Fallback(
                            generation,
                            reset,
                            collected,
                            multiCommunities(),
                            0);
                    return;
                }
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
            }'''

main = replace_once(main, old_error, new_error, "multi-feed 404 dispatch")

anchor = '''    private void finishFeedCollection(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected) {'''

helpers = '''    private ArrayList<String> multiCommunities() {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        String[] parts = subreddit == null ? new String[0] : subreddit.split("\\\\+");
        for (String part : parts) {
            String clean = cleanSubredditName(part);
            if (!clean.isEmpty()) unique.add(clean);
        }
        return new ArrayList<>(unique);
    }

    private String singlePresetListingPath(String target) {
        String remoteSort = sort;
        if (remoteSort.equals("random") || remoteSort.equals("oldest")) remoteSort = "new";
        String base = remoteSort.equals("best")
                ? "/r/" + enc(target) + "/hot.json"
                : "/r/" + enc(target) + "/" + remoteSort + ".json";
        String path = base + "?limit=50&raw_json=1&show=all";
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        return path;
    }

    private void fetchMulti404Fallback(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected,
            ArrayList<String> communities,
            int index) {
        if (generation != feedGeneration || screen != Screen.HOME || !context.equals("multi")) return;

        if (index >= communities.size() || collected.size() >= 100) {
            after = "";
            boolean empty = collected.isEmpty();
            finishFeedCollection(generation, reset, collected);
            if (empty) setStatus("No accessible media matched this preset/filter.", false);
            return;
        }

        String target = communities.get(index);
        engine.get(singlePresetListingPath(target), result -> {
            if (generation != feedGeneration || screen != Screen.HOME || !context.equals("multi")) return;

            if (result.ok) {
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

                // Show the first successful subreddit immediately while the remaining
                // preset members continue through the serialized request queue.
                if (reset && postAdapter.getItemCount() == 0 && !collected.isEmpty()) {
                    replacePosts(new ArrayList<>(collected));
                    hideStatus();
                    updateChrome();
                }
            }

            root.postDelayed(
                    () -> fetchMulti404Fallback(
                            generation,
                            reset,
                            collected,
                            communities,
                            index + 1),
                    180L);
        });
    }

'''

if helpers not in main:
    if main.count(anchor) != 1:
        raise SystemExit(f"feed finalizer anchor: expected 1, found {main.count(anchor)}")
    main = main.replace(anchor, helpers + anchor, 1)

main_path.write_text(main)

build_path = Path("app/build.gradle")
build = build_path.read_text()
if 'versionCode 37' not in build:
    build = replace_once(build, 'versionCode 36', 'versionCode 37', 'versionCode')
if 'versionName "3.8.3"' not in build:
    build = replace_once(build, 'versionName "3.8.2"', 'versionName "3.8.3"', 'versionName')
build_path.write_text(build)

print("Applied v3.8.3 preset 404 fallback hotfix")
