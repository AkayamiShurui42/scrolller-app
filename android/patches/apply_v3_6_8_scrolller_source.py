from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.8 Scrolller target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# ScrolllerClient: supplemental Reddit discovery through Scrolller GraphQL.
# Results remain Reddit posts because every accepted item must expose redditPath.
# ---------------------------------------------------------------------------
client_path = Path('app/src/main/java/com/scrolller/adblock/ScrolllerClient.java')
client_path.write_text(r'''package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ScrolllerClient {
    interface Callback {
        void onBatch(JSONArray items);
        void onComplete();
        void onError(String error);
    }

    private static final String ENDPOINT = "https://api.scrolller.com/admin";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static final String SUBREDDIT_QUERY =
            "query Q($url:String!,$limit:Int!){getSubreddit(data:{url:$url,limit:$limit,sortBy:RANDOM})" +
            "{id isNsfw children{iterator items{" +
            "id title subredditTitle redditPath isNsfw hasAudio commentsCount username " +
            "mediaSources{url width height isOptimized} " +
            "albumContent{mediaSources{url width height isOptimized}}" +
            "}}}}";

    private static final String CHILDREN_QUERY =
            "query Q($subredditId:Int!,$iterator:String,$limit:Int!,$isNsfw:Boolean)" +
            "{getSubredditChildren(data:{subredditId:$subredditId,iterator:$iterator,limit:$limit," +
            "sortBy:RANDOM,isNsfw:$isNsfw}){iterator items{" +
            "id title subredditTitle redditPath isNsfw hasAudio commentsCount username " +
            "mediaSources{url width height isOptimized} " +
            "albumContent{mediaSources{url width height isOptimized}}" +
            "}}}";

    private ScrolllerClient() {}

    static void crawlSubreddit(String subreddit, int maxItems, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                int delivered = 0;
                JSONObject vars = new JSONObject();
                vars.put("url", "/r/" + subreddit);
                vars.put("limit", 50);
                JSONObject first = request(SUBREDDIT_QUERY, vars);
                JSONObject data = first.optJSONObject("data");
                JSONObject sr = data != null ? data.optJSONObject("getSubreddit") : null;
                if (sr == null) throw new IllegalStateException("Scrolller subreddit unavailable");

                int subredditId = sr.optInt("id", 0);
                boolean nsfw = sr.optBoolean("isNsfw", false);
                JSONObject children = sr.optJSONObject("children");
                if (children == null) {
                    MAIN.post(callback::onComplete);
                    return;
                }

                JSONArray items = children.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    delivered += items.length();
                    JSONArray batch = items;
                    MAIN.post(() -> callback.onBatch(batch));
                }
                String iterator = children.optString("iterator", "");

                while (!iterator.isEmpty() && delivered < maxItems && subredditId > 0) {
                    try { Thread.sleep(650L); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    JSONObject nextVars = new JSONObject();
                    nextVars.put("subredditId", subredditId);
                    nextVars.put("iterator", iterator);
                    nextVars.put("limit", Math.min(50, maxItems - delivered));
                    nextVars.put("isNsfw", nsfw);
                    JSONObject next = request(CHILDREN_QUERY, nextVars);
                    JSONObject nextData = next.optJSONObject("data");
                    JSONObject listing = nextData != null
                            ? nextData.optJSONObject("getSubredditChildren") : null;
                    if (listing == null) break;
                    JSONArray nextItems = listing.optJSONArray("items");
                    if (nextItems != null && nextItems.length() > 0) {
                        delivered += nextItems.length();
                        JSONArray batch = nextItems;
                        MAIN.post(() -> callback.onBatch(batch));
                    }
                    String nextIterator = listing.optString("iterator", "");
                    if (nextIterator.isEmpty() || nextIterator.equals(iterator)) break;
                    iterator = nextIterator;
                }
                MAIN.post(callback::onComplete);
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Scrolller request failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static JSONObject request(String query, JSONObject variables) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("variables", variables);
        body.put("authorization", JSONObject.NULL);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Origin", "https://scrolller.com");
        connection.setRequestProperty("Referer", "https://scrolller.com/");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) RedditMedia/3.6.8");
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Scrolller HTTP " + status);
        }
        JSONObject result = new JSONObject(text.toString());
        JSONArray errors = result.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            throw new IllegalStateException("Scrolller GraphQL error");
        }
        return result;
    }
}
''')

# ---------------------------------------------------------------------------
# RedditPost: normalize Scrolller media back into a canonical RedditPost.
# ---------------------------------------------------------------------------
post_path = Path('app/src/main/java/com/scrolller/adblock/RedditPost.java')
p = post_path.read_text()
anchor = '    private static ParsedMedia parseMedia(JSONObject d) {'
if anchor not in p:
    raise SystemExit('Missing v3.6.8 Scrolller target: RedditPost parseMedia anchor')

methods = r'''    public static RedditPost fromScrolller(JSONObject item) {
        if (item == null) return null;
        String redditPath = decode(item.optString("redditPath", ""));
        String redditId = redditIdFromPath(redditPath);
        if (redditId.isEmpty()) return null;

        ArrayList<String> album = new ArrayList<>();
        int width = 0;
        int height = 0;
        JSONArray albumContent = item.optJSONArray("albumContent");
        if (albumContent != null) {
            for (int i = 0; i < albumContent.length(); i++) {
                JSONObject media = albumContent.optJSONObject(i);
                JSONObject best = bestScrolllerSource(
                        media != null ? media.optJSONArray("mediaSources") : null);
                if (best == null) continue;
                String url = decode(best.optString("url", ""));
                if (url.isEmpty()) continue;
                album.add(url);
                if (width <= 0 || height <= 0) {
                    width = best.optInt("width", 0);
                    height = best.optInt("height", 0);
                }
            }
        }

        MediaKind kind;
        String video = "";
        String poster = "";
        String sourceUrl = "";
        if (album.size() > 1) {
            kind = MediaKind.GALLERY;
            sourceUrl = album.get(0);
            poster = album.get(0);
        } else {
            JSONObject best = bestScrolllerSource(item.optJSONArray("mediaSources"));
            if (best == null && album.size() == 1) {
                sourceUrl = album.get(0);
                width = Math.max(width, 0);
                height = Math.max(height, 0);
            } else if (best != null) {
                sourceUrl = decode(best.optString("url", ""));
                width = best.optInt("width", width);
                height = best.optInt("height", height);
            }
            if (sourceUrl.isEmpty()) return null;
            String lower = sourceUrl.toLowerCase();
            boolean stream = lower.matches(".*\\.(mp4|webm|m3u8)(\\?.*)?$");
            if (stream) {
                kind = item.optBoolean("hasAudio", false) ? MediaKind.VIDEO : MediaKind.GIF;
                video = sourceUrl;
                poster = "";
                album.clear();
            } else if (lower.matches(".*\\.gif(\\?.*)?$")) {
                kind = MediaKind.GIF;
                album.clear();
                album.add(sourceUrl);
                poster = sourceUrl;
            } else if (lower.matches(".*\\.(jpe?g|png|webp)(\\?.*)?$")) {
                kind = MediaKind.IMAGE;
                album.clear();
                album.add(sourceUrl);
                poster = sourceUrl;
            } else {
                return null;
            }
        }

        return new RedditPost(
                "t3_" + redditId,
                decode(item.optString("title", "")),
                item.optString("username", ""),
                item.optString("subredditTitle", ""),
                redditPath,
                sourceUrl,
                0,
                item.optInt("commentsCount", 0),
                0L,
                false,
                item.optBoolean("isNsfw", false),
                kind,
                album,
                video,
                poster,
                width,
                height);
    }

    private static JSONObject bestScrolllerSource(JSONArray sources) {
        if (sources == null) return null;
        JSONObject best = null;
        long bestArea = -1L;
        boolean bestOptimized = true;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source == null || source.optString("url", "").isEmpty()) continue;
            long area = (long) Math.max(0, source.optInt("width", 0))
                    * Math.max(0, source.optInt("height", 0));
            boolean optimized = source.optBoolean("isOptimized", false);
            if (best == null || area > bestArea || (area == bestArea && bestOptimized && !optimized)) {
                best = source;
                bestArea = area;
                bestOptimized = optimized;
            }
        }
        return best;
    }

    private static String redditIdFromPath(String path) {
        if (path == null || path.isEmpty()) return "";
        String marker = "/comments/";
        int at = path.indexOf(marker);
        if (at < 0) return "";
        String tail = path.substring(at + marker.length());
        int slash = tail.indexOf('/');
        if (slash >= 0) tail = tail.substring(0, slash);
        return tail.replaceAll("[^A-Za-z0-9].*$", "");
    }

'''
p = p.replace(anchor, methods + anchor, 1)
post_path.write_text(p)

# ---------------------------------------------------------------------------
# MainActivity: start Scrolller discovery alongside the Reddit warm reservoir.
# Only Random subreddit feeds use the supplemental source so explicit Reddit
# sorting semantics remain exact for New/Top/Hot/etc.
# ---------------------------------------------------------------------------
main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

s = replace_required(
    s,
    '''    private boolean archivePrefetchRunning = false;
    private boolean archivePrefetchDone = false;
    private int archivePrefetchGeneration = 0;
    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();''',
    '''    private boolean archivePrefetchRunning = false;
    private boolean archivePrefetchDone = false;
    private boolean scrolllerPrefetchRunning = false;
    private boolean scrolllerPrefetchDone = false;
    private int archivePrefetchGeneration = 0;
    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();''',
    'Scrolller prefetch fields')

s = replace_required(
    s,
    '''            archivePrefetchGeneration++;
            archivePrefetchRunning = false;
            archivePrefetchDone = false;
            replacePosts(new ArrayList<>());''',
    '''            archivePrefetchGeneration++;
            archivePrefetchRunning = false;
            archivePrefetchDone = false;
            scrolllerPrefetchRunning = false;
            scrolllerPrefetchDone = false;
            replacePosts(new ArrayList<>());''',
    'reset Scrolller state with collection')

s = replace_required(
    s,
    '''        if (screen != Screen.HOME || !context.equals("subreddit")
                || subreddit == null || subreddit.isEmpty()) return;

        if (!loading && postAdapter.getItemCount() < 300 && after != null && !after.isEmpty()) {''',
    '''        if (screen != Screen.HOME || !context.equals("subreddit")
                || subreddit == null || subreddit.isEmpty()) return;

        prefetchScrolllerSubredditIfNeeded();

        if (!loading && postAdapter.getItemCount() < 300 && after != null && !after.isEmpty()) {''',
    'start Scrolller beside Reddit reservoir')

anchor = '    private void prefetchRandomSubredditArchiveIfNeeded() {'
if anchor not in s:
    raise SystemExit('Missing v3.6.8 Scrolller target: archive prefetch anchor')

scrolller_methods = '''    private void prefetchScrolllerSubredditIfNeeded() {
        if (scrolllerPrefetchRunning || scrolllerPrefetchDone) return;
        if (screen != Screen.HOME || !context.equals("subreddit") || !sort.equals("random")) return;
        if (subreddit == null || subreddit.isEmpty()) return;

        scrolllerPrefetchRunning = true;
        final int generation = archivePrefetchGeneration;
        final String targetSubreddit = subreddit;
        ScrolllerClient.crawlSubreddit(targetSubreddit, 600, new ScrolllerClient.Callback() {
            @Override
            public void onBatch(JSONArray items) {
                if (!scrolllerContextStillValid(generation, targetSubreddit)) return;
                ArrayList<RedditPost> additions = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    RedditPost post = RedditPost.fromScrolller(items.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!feedSeenPostIds.add(post.id)) continue;
                    if (hiddenPosts.containsKey(post.id)) continue;
                    additions.add(post);
                    if (postAdapter.getItemCount() + additions.size() >= 1400) break;
                }
                if (!additions.isEmpty()) {
                    Collections.shuffle(additions);
                    appendUnique(additions);
                    hideStatus();
                }
            }

            @Override
            public void onComplete() {
                if (generation != archivePrefetchGeneration) return;
                scrolllerPrefetchRunning = false;
                scrolllerPrefetchDone = true;
            }

            @Override
            public void onError(String error) {
                if (generation != archivePrefetchGeneration) return;
                // Supplemental source failure must never break the Reddit feed.
                scrolllerPrefetchRunning = false;
                scrolllerPrefetchDone = true;
            }
        });
    }

    private boolean scrolllerContextStillValid(int generation, String targetSubreddit) {
        return generation == archivePrefetchGeneration
                && screen == Screen.HOME
                && context.equals("subreddit")
                && sort.equals("random")
                && subreddit != null
                && subreddit.equalsIgnoreCase(targetSubreddit)
                && postAdapter.getItemCount() < 1400;
    }

'''
s = s.replace(anchor, scrolller_methods + anchor, 1)
main_path.write_text(s)

print('Applied v3.6.8 Scrolller supplemental Reddit discovery source')
