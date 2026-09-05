from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

anchor = '    private void reloadCurrent() {'
if anchor not in s:
    raise SystemExit('Missing v3.7.5 read-hide restore anchor: reloadCurrent')

signatures = [
    '    private void loadHiddenPostsView() {',
    '    private void setFullscreenReadBaseline(int position) {',
    '    private void trackFullscreenVisit(int position) {',
    '    private void restoreHiddenPost(RedditPost post) {',
    '    private void loadReadHideState() {',
    '    private void saveReadHideState() {',
    '    private JSONObject postToJson(RedditPost post) throws Exception {',
    '    private RedditPost postFromJson(JSONObject o) {',
]
existing = [sig for sig in signatures if sig in s]
if existing:
    raise SystemExit('Refusing partial/duplicate v3.7.5 read-hide restore: ' + ', '.join(existing))

methods = r'''    private void loadHiddenPostsView() {
        loading = false;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        ArrayList<RedditPost> items = new ArrayList<>();
        for (RedditPost post : hiddenPosts.values()) {
            if (matchesMedia(post)) items.add(post);
        }
        applyFavoriteOrdering(items);
        replacePosts(items);
        if (items.isEmpty()) setStatus("No locally hidden posts.", false);
        else hideStatus();
        updateChrome();
        restorePendingPosition();
    }

    private void setFullscreenReadBaseline(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) {
            lastFullscreenPostId = "";
            return;
        }
        lastFullscreenPostId = current.id;
    }

    private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            return;
        }
        if (lastFullscreenPostId.equals(currentId)) return;

        RedditPost previous = null;
        for (RedditPost post : postAdapter.getPosts()) {
            if (lastFullscreenPostId.equals(post.id)) {
                previous = post;
                break;
            }
        }
        if (previous != null && previous.id != null && !previous.id.isEmpty()
                && mediaReadyPostIds.contains(previous.id)
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            saveReadHideState();
        }

        // Read state is persistent, but the live pager collection stays immutable.
        lastFullscreenPostId = currentId;
    }

    private void restoreHiddenPost(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        hiddenPosts.remove(post.id);
        feedSeenPostIds.remove(post.id);
        saveReadHideState();
        if (showingHiddenLibrary()) loadHiddenPostsView();
        else reloadCurrent();
    }

    private void loadReadHideState() {
        hiddenPosts.clear();
        try {
            JSONArray hidden = new JSONArray(prefs.getString("hiddenPosts", "[]"));
            for (int i = 0; i < hidden.length(); i++) {
                RedditPost post = postFromJson(hidden.optJSONObject(i));
                if (post != null && post.id != null && !post.id.isEmpty()) {
                    hiddenPosts.put(post.id, post);
                }
            }
            // v3.6.1 replaced the old five-post delayed queue with immediate marking.
            prefs.edit().remove("pendingReadPosts").apply();
        } catch (Exception ignored) {}
    }

    private void saveReadHideState() {
        try {
            JSONArray hidden = new JSONArray();
            for (RedditPost post : hiddenPosts.values()) hidden.put(postToJson(post));
            prefs.edit()
                    .putString("hiddenPosts", hidden.toString())
                    .remove("pendingReadPosts")
                    .apply();
        } catch (Exception ignored) {}
    }

    private JSONObject postToJson(RedditPost post) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", post.id);
        o.put("title", post.title);
        o.put("author", post.author);
        o.put("subreddit", post.subreddit);
        o.put("permalink", post.permalink);
        o.put("sourceUrl", post.sourceUrl);
        o.put("score", post.score);
        o.put("comments", post.comments);
        o.put("createdUtc", post.createdUtc);
        o.put("saved", post.saved);
        o.put("nsfw", post.nsfw);
        o.put("mediaKind", post.mediaKind.name());
        JSONArray images = new JSONArray();
        for (String url : post.imageUrls) images.put(url);
        o.put("imageUrls", images);
        o.put("videoUrl", post.videoUrl);
        o.put("posterUrl", post.posterUrl);
        o.put("mediaWidth", post.mediaWidth);
        o.put("mediaHeight", post.mediaHeight);
        o.put("searchMetadata", post.searchMetadata == null ? "" : post.searchMetadata);
        return o;
    }

    private RedditPost postFromJson(JSONObject o) {
        if (o == null) return null;
        try {
            ArrayList<String> images = new ArrayList<>();
            JSONArray imageArray = o.optJSONArray("imageUrls");
            if (imageArray != null) {
                for (int i = 0; i < imageArray.length(); i++) {
                    String url = imageArray.optString(i, "");
                    if (!url.isEmpty()) images.add(url);
                }
            }
            RedditPost.MediaKind kind = RedditPost.MediaKind.valueOf(
                    o.optString("mediaKind", RedditPost.MediaKind.IMAGE.name()));
            RedditPost post = new RedditPost(
                    o.optString("id", ""),
                    o.optString("title", ""),
                    o.optString("author", ""),
                    o.optString("subreddit", ""),
                    o.optString("permalink", ""),
                    o.optString("sourceUrl", ""),
                    o.optInt("score", 0),
                    o.optInt("comments", 0),
                    o.optLong("createdUtc", 0L),
                    o.optBoolean("saved", false),
                    o.optBoolean("nsfw", false),
                    kind,
                    images,
                    o.optString("videoUrl", ""),
                    o.optString("posterUrl", ""),
                    o.optInt("mediaWidth", 0),
                    o.optInt("mediaHeight", 0));
            post.searchMetadata = o.optString("searchMetadata", "");
            return post;
        } catch (Exception ignored) {
            return null;
        }
    }

'''

s = s.replace(anchor, methods + anchor, 1)

# The helpers must sit directly in MainActivity class scope, never inside a method.
def brace_depth_at(text, target_index):
    depth = 0
    in_string = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    escaped = False
    i = 0
    while i < target_index:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < target_index else ''
        if in_line_comment:
            if ch == '\n': in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            if ch == '*' and nxt == '/':
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if escaped: escaped = False
            elif ch == '\\': escaped = True
            elif ch == '"': in_string = False
            i += 1
            continue
        if in_char:
            if escaped: escaped = False
            elif ch == '\\': escaped = True
            elif ch == "'": in_char = False
            i += 1
            continue
        if ch == '/' and nxt == '/':
            in_line_comment = True
            i += 2
            continue
        if ch == '/' and nxt == '*':
            in_block_comment = True
            i += 2
            continue
        if ch == '"': in_string = True
        elif ch == "'": in_char = True
        elif ch == '{': depth += 1
        elif ch == '}': depth -= 1
        i += 1
    return depth

for signature in signatures + [anchor]:
    idx = s.find(signature)
    if idx < 0:
        raise SystemExit('Missing restored v3.7.5 helper: ' + signature)
    depth = brace_depth_at(s, idx)
    if depth != 1:
        raise SystemExit(f'Restored v3.7.5 helper not at class scope: {signature} depth={depth}')

path.write_text(s)
print('Restored v3.7.5 read/hide persistence helpers after search reconstruction')
