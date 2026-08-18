from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.4 patch target: {label}\n{old[:260]}')
    return text.replace(old, new, 1)

# Main feed and Favorites pagination/order/filter behavior.
main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

# Random + Oldest use /new as the source listing, then client-side ordering after
# the complete accessible listing has been collected.
s = replace_required(
    s,
    'base = sort.equals("random") ? "/new.json"\n                    : sort.equals("best") ? "/.json" : "/" + sort + ".json";',
    'base = (sort.equals("random") || sort.equals("oldest")) ? "/new.json"\n                    : sort.equals("best") ? "/.json" : "/" + sort + ".json";',
    'home random/oldest listing source')
s = replace_required(
    s,
    'base = sort.equals("random") ? "/r/popular/new.json"\n                    : sort.equals("best") ? "/r/popular/hot.json" : "/r/popular/" + sort + ".json";',
    'base = (sort.equals("random") || sort.equals("oldest")) ? "/r/popular/new.json"\n                    : sort.equals("best") ? "/r/popular/hot.json" : "/r/popular/" + sort + ".json";',
    'popular random/oldest listing source')
s = replace_required(
    s,
    'base = sort.equals("random") ? "/r/" + enc(subreddit) + "/new.json"\n                    : sort.equals("best") ? "/r/" + enc(subreddit) + "/hot.json"',
    'base = (sort.equals("random") || sort.equals("oldest")) ? "/r/" + enc(subreddit) + "/new.json"\n                    : sort.equals("best") ? "/r/" + enc(subreddit) + "/hot.json"',
    'subreddit random/oldest listing source')

old_feed = '''    private void loadFeed(boolean reset) {
        if (loading || !engine.isReady()) return;
        loading = true;
        if (reset) {
            after = "";
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Loading media…", true);
        }
        fetchFeedPages(reset, new ArrayList<>(), 0);
    }

    private void fetchFeedPages(boolean reset, ArrayList<RedditPost> collected, int page) {
        String path = listingPath(after);
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
                    if (post != null && matchesMedia(post)) collected.add(post);
                }
            }
            after = data != null ? data.optString("after", "") : "";
            boolean collectMoreForRandom = sort.equals("random") && page < 2;
            if ((collected.size() < 12 || collectMoreForRandom) && !after.isEmpty() && page < 4) {
                fetchFeedPages(reset, collected, page + 1);
                return;
            }
            loading = false;
            if (sort.equals("random")) Collections.shuffle(collected);
            if (reset) replacePosts(collected); else appendUnique(collected);
            if (postAdapter.getItemCount() == 0) {
                setStatus("No media posts match this feed/filter.", false);
            } else {
                hideStatus();
            }
            updateChrome();
            restorePendingPosition();
        });
    }

'''
new_feed = '''    private void loadFeed(boolean reset) {
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
s = replace_required(s, old_feed, new_feed, 'exhaustive unique feed pagination')

# Favorites: follow every new cursor Reddit gives us, dedupe before ordering, and
# stop safely if Reddit repeats a cursor instead of looping forever.
s = replace_required(
    s,
    '        fetchFavoritesPage("", new ArrayList<>(), 0);',
    '        fetchFavoritesPage("", new ArrayList<>(), new HashSet<>(), new HashSet<>());',
    'favorites entry point')

old_fav_method = '''    private void fetchFavoritesPage(String cursor, ArrayList<RedditPost> collected, int page) {
        String path = "/user/" + enc(username) + "/saved.json?limit=100&raw_json=1";
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                setStatus("Favorites failed: " + friendlyError(result), false);
                return;
            }
            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            collected.addAll(parseListing(root, true));
            String next = data != null ? data.optString("after", "") : "";
            if (!next.isEmpty() && page < 9) {
                fetchFavoritesPage(next, collected, page + 1);
                return;
            }
            loading = false;
            applyFavoriteOrdering(collected);
            replacePosts(collected);
            if (collected.isEmpty()) setStatus("No saved media posts match this filter.", false);
            else hideStatus();
            updateChrome();
            restorePendingPosition();
        });
    }

'''
new_fav_method = '''    private void fetchFavoritesPage(
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors) {
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            finishFavoritesCollection(collected);
            return;
        }

        String path = "/user/" + enc(username) + "/saved.json?limit=100&raw_json=1";
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                setStatus("Favorites failed: " + friendlyError(result), false);
                return;
            }

            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            ArrayList<RedditPost> pageItems = parseListing(root, true);
            for (RedditPost post : pageItems) {
                if (post.id == null || post.id.isEmpty()) continue;
                if (seenPostIds.add(post.id)) collected.add(post);
            }

            String next = data != null ? data.optString("after", "") : "";
            if (!next.isEmpty() && !seenCursors.contains(next)) {
                fetchFavoritesPage(next, collected, seenPostIds, seenCursors);
                return;
            }

            finishFavoritesCollection(collected);
        });
    }

    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        applyFavoriteOrdering(collected);
        replacePosts(collected);
        if (collected.isEmpty()) {
            setStatus("No unique saved media posts match this filter.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

'''
s = replace_required(s, old_fav_method, new_fav_method, 'exhaustive unique favorites pagination')

# Normal feed sort menu gains Oldest. Random and Oldest are both client-side
# orderings over the full accessible /new listing.
s = replace_required(
    s,
    'values = new String[]{"random", "best", "hot", "new", "top", "rising"};',
    'values = new String[]{"random", "best", "hot", "new", "oldest", "top", "rising"};',
    'oldest feed sort option')

# Separate GIF filtering from images and videos.
old_matches = '''    private boolean matchesMedia(RedditPost post) {
        if (media.equals("all")) return true;
        if (media.equals("image")) {
            return post.mediaKind == RedditPost.MediaKind.IMAGE
                    || post.mediaKind == RedditPost.MediaKind.GALLERY;
        }
        return post.mediaKind == RedditPost.MediaKind.VIDEO
                || post.mediaKind == RedditPost.MediaKind.EXTERNAL;
    }
'''
new_matches = '''    private boolean matchesMedia(RedditPost post) {
        if (media.equals("all")) return true;
        if (media.equals("image")) {
            return post.mediaKind == RedditPost.MediaKind.IMAGE
                    || post.mediaKind == RedditPost.MediaKind.GALLERY;
        }
        if (media.equals("video")) {
            return post.mediaKind == RedditPost.MediaKind.VIDEO;
        }
        if (media.equals("gif")) {
            return post.mediaKind == RedditPost.MediaKind.GIF;
        }
        return false;
    }
'''
s = replace_required(s, old_matches, new_matches, 'image/video/gif media matcher')

s = replace_required(
    s,
    '                {"all", "All media"},\n                {"image", "Images"},\n                {"video", "Video"}',
    '                {"all", "All media"},\n                {"image", "Images"},\n                {"video", "Videos"},\n                {"gif", "GIFs"}',
    'GIF media filter menu')

s = replace_required(
    s,
    '        filterButton.setText(media.equals("all") ? "All media"\n                : media.equals("image") ? "Images" : "Video");',
    '        filterButton.setText(media.equals("all") ? "All media"\n                : media.equals("image") ? "Images"\n                : media.equals("video") ? "Videos" : "GIFs");',
    'GIF filter chrome label')

main_path.write_text(s)

# GIFs backed by Reddit video-preview streams should play in the same native
# looping player as videos. Direct .gif files continue through Glide.
pager_path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
p = pager_path.read_text()
p = replace_required(
    p,
    'if (post.mediaKind == RedditPost.MediaKind.VIDEO && post.videoUrl != null && !post.videoUrl.isEmpty()) {',
    'if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)\n                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {',
    'GIF native playback')
pager_path.write_text(p)

# Grid badges distinguish GIFs from ordinary images/videos.
grid_path = Path('app/src/main/java/com/scrolller/adblock/GridPostAdapter.java')
g = grid_path.read_text()
g = replace_required(
    g,
    '        if (post.mediaKind == RedditPost.MediaKind.VIDEO) holder.badge.setText("▶");\n        else if (post.mediaKind == RedditPost.MediaKind.GALLERY)',
    '        if (post.mediaKind == RedditPost.MediaKind.VIDEO) holder.badge.setText("▶");\n        else if (post.mediaKind == RedditPost.MediaKind.GIF) holder.badge.setText("GIF");\n        else if (post.mediaKind == RedditPost.MediaKind.GALLERY)',
    'GIF grid badge')
grid_path.write_text(g)

print('Applied v3.4 exhaustive unique listings + GIF filters')
