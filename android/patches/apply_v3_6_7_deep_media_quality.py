from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.7 patch target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# MainActivity: continuously prefetch a large subreddit reservoir. Random feeds
# also sweep independent older Reddit listing windows after the live /new pool
# has been buffered, deduping everything by Reddit fullname.
# ---------------------------------------------------------------------------
main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

s = replace_required(
    s,
    '    private final Set<String> feedSeenCursors = new HashSet<>();\n    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();',
    '''    private final Set<String> feedSeenCursors = new HashSet<>();
    private boolean archivePrefetchRunning = false;
    private boolean archivePrefetchDone = false;
    private int archivePrefetchGeneration = 0;
    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();''',
    'subreddit archive prefetch state')

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
    'reset archive prefetch with fresh feed')

# Start loading the next live Reddit slice much earlier once the reservoir has
# been consumed beyond its warm buffer.
s = replace_required(
    s,
    'position >= postAdapter.getItemCount() - 5',
    'position >= postAdapter.getItemCount() - 60',
    'earlier near-end feed refill')

s = replace_required(
    s,
    '''        updateChrome();
        restorePendingPosition();
    }

    private void appendUnique(List<RedditPost> incoming) {''',
    '''        updateChrome();
        restorePendingPosition();
        prefetchSubredditReservoir();
    }

    private void prefetchSubredditReservoir() {
        if (screen != Screen.HOME || !context.equals("subreddit")
                || subreddit == null || subreddit.isEmpty()) return;

        // Keep a large live pool ahead of the pager without delaying first paint.
        // Each fetch still renders as soon as it completes; this just continues
        // filling the reservoir while the user is already browsing.
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

        // Random is a discovery mode, so once the live pool is warm, augment it
        // with independent Reddit listing windows that expose older/different posts.
        if (sort.equals("random") && postAdapter.getItemCount() >= 300) {
            prefetchRandomSubredditArchiveIfNeeded();
        } else if (sort.equals("random") && (after == null || after.isEmpty())) {
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

    private void appendUnique(List<RedditPost> incoming) {''',
    'subreddit background reservoir and archive sweeps')

main_path.write_text(s)

# ---------------------------------------------------------------------------
# RedditPost: retain RedGIFs posts that Reddit itself did not transcode. The
# normal reddit_video_preview path still wins whenever Reddit provides one.
# ---------------------------------------------------------------------------
post_path = Path('app/src/main/java/com/scrolller/adblock/RedditPost.java')
p = post_path.read_text()

p = replace_required(
    p,
    '    public final String videoUrl;\n',
    '    public String videoUrl;\n',
    'allow async external provider resolution')

p = replace_required(
    p,
    '''        if ("image".equals(hint)
                || direct.matches("(?i).*\\.(jpe?g|png|webp)(\\?.*)?$")
                || "i.redd.it".equalsIgnoreCase(domain)) {
            String image = !direct.isEmpty() ? direct : previewImage(d);
            if (!image.isEmpty()) {
                ArrayList<String> one = new ArrayList<>();
                one.add(image);
                return new ParsedMedia(MediaKind.IMAGE, one, "", image, previewWidth, previewHeight);
            }
        }

        return null;''',
    '''        if ("image".equals(hint)
                || direct.matches("(?i).*\\.(jpe?g|png|webp)(\\?.*)?$")
                || "i.redd.it".equalsIgnoreCase(domain)) {
            String image = !direct.isEmpty() ? direct : previewImage(d);
            if (!image.isEmpty()) {
                ArrayList<String> one = new ArrayList<>();
                one.add(image);
                return new ParsedMedia(MediaKind.IMAGE, one, "", image, previewWidth, previewHeight);
            }
        }

        // Reddit sometimes supplies a v.redd.it reddit_video_preview for RedGIFs;
        // that path was handled above. If it did not, retain the post and resolve
        // the provider's HD MP4 asynchronously instead of discarding the post.
        if (isRedgifs(direct, domain)) {
            String redgifsId = extractRedgifsId(direct);
            if (!redgifsId.isEmpty()) {
                return new ParsedMedia(
                        MediaKind.GIF,
                        new ArrayList<>(),
                        "redgifs:" + redgifsId,
                        previewImage(d),
                        previewWidth,
                        previewHeight);
            }
        }

        return null;''',
    'retain unresolved RedGIFs media')

p = replace_required(
    p,
    '''    private static int positive(int first, int fallback) {
        return first > 0 ? first : Math.max(0, fallback);
    }''',
    '''    private static boolean isRedgifs(String url, String domain) {
        String u = url == null ? "" : url.toLowerCase();
        String d = domain == null ? "" : domain.toLowerCase();
        return d.contains("redgifs.com") || u.contains("redgifs.com/");
    }

    private static String extractRedgifsId(String url) {
        if (url == null || url.isEmpty()) return "";
        String clean = url;
        int cut = clean.indexOf('?');
        if (cut >= 0) clean = clean.substring(0, cut);
        cut = clean.indexOf('#');
        if (cut >= 0) clean = clean.substring(0, cut);

        String lower = clean.toLowerCase();
        String[] markers = {"/watch/", "/ifr/", "/i/"};
        for (String marker : markers) {
            int at = lower.indexOf(marker);
            if (at < 0) continue;
            String tail = clean.substring(at + marker.length());
            int slash = tail.indexOf('/');
            if (slash >= 0) tail = tail.substring(0, slash);
            int dot = tail.indexOf('.');
            if (dot >= 0) tail = tail.substring(0, dot);
            tail = tail.replaceAll("[^A-Za-z0-9].*$", "");
            if (!tail.isEmpty()) return tail;
        }
        return "";
    }

    private static int positive(int first, int fallback) {
        return first > 0 ? first : Math.max(0, fallback);
    }''',
    'RedGIFs URL helpers')

post_path.write_text(p)

# ---------------------------------------------------------------------------
# RedGIFs provider resolver: official-ish temporary-token API used by mature
# clients such as yt-dlp/gallery-dl. Cache both token and resolved HD URLs.
# ---------------------------------------------------------------------------
resolver_path = Path('app/src/main/java/com/scrolller/adblock/RedgifsResolver.java')
resolver_path.write_text(r'''package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RedgifsResolver {
    interface Callback {
        void onResolved(String url);
        void onError(String error);
    }

    private static final String API = "https://api.redgifs.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 16) RedditMedia/3.6.7";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile String token = "";
    private static volatile long tokenRefreshAt = 0L;

    private RedgifsResolver() {}

    static void resolve(String id, Callback callback) {
        if (id == null || id.isEmpty()) {
            callback.onError("missing RedGIFs id");
            return;
        }
        String key = id.toLowerCase();
        String cached = CACHE.get(key);
        if (cached != null && !cached.isEmpty()) {
            callback.onResolved(cached);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                String auth = temporaryToken();
                JSONObject root = requestJson(
                        API + "/v2/gifs/" + key,
                        auth,
                        "https://www.redgifs.com/watch/" + key);
                JSONObject gif = root.optJSONObject("gif");
                JSONObject urls = gif != null ? gif.optJSONObject("urls") : null;
                String hd = urls != null ? urls.optString("hd", "") : "";
                String sd = urls != null ? urls.optString("sd", "") : "";
                String resolved = !hd.isEmpty() ? hd : sd;
                if (resolved.isEmpty()) throw new IllegalStateException("no playable RedGIFs URL");
                CACHE.put(key, resolved);
                MAIN.post(() -> callback.onResolved(resolved));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "RedGIFs resolve failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static synchronized String temporaryToken() throws Exception {
        long now = System.currentTimeMillis();
        if (!token.isEmpty() && now < tokenRefreshAt) return token;
        JSONObject auth = requestJson(API + "/v2/auth/temporary", "", "https://www.redgifs.com/");
        String next = auth.optString("token", "");
        if (next.isEmpty()) throw new IllegalStateException("RedGIFs token unavailable");
        token = next;
        tokenRefreshAt = now + 8L * 60L * 1000L;
        return token;
    }

    private static JSONObject requestJson(String address, String bearer, String customHeader)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://www.redgifs.com/");
        connection.setRequestProperty("Origin", "https://www.redgifs.com");
        if (customHeader != null && !customHeader.isEmpty()) {
            connection.setRequestProperty("x-customheader", customHeader);
        }
        if (bearer != null && !bearer.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            if (status == 401) {
                token = "";
                tokenRefreshAt = 0L;
            }
            throw new IllegalStateException("RedGIFs HTTP " + status);
        }
        return new JSONObject(body.toString());
    }
}
''')

# ---------------------------------------------------------------------------
# Highest-quality Media3 player factory. Adaptive Reddit streams retain their
# audio tracks, while selection is forced to the highest supported bitrate.
# RedGIFs direct CDN playback gets the provider's required origin/referrer.
# ---------------------------------------------------------------------------
player_factory_path = Path('app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java')
player_factory_path.write_text(r'''package com.scrolller.adblock;

import android.content.Context;

import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.util.HashMap;
import java.util.Map;

final class HighQualityPlayerFactory {
    private HighQualityPlayerFactory() {}

    static ExoPlayer create(Context context, String mediaUrl) {
        DefaultTrackSelector selector = new DefaultTrackSelector(context);
        selector.setParameters(
                selector.buildUponParameters()
                        .setForceHighestSupportedBitrate(true));

        ExoPlayer.Builder builder = new ExoPlayer.Builder(context)
                .setTrackSelector(selector);

        if (isRedgifsMedia(mediaUrl)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.redgifs.com/");
            headers.put("Origin", "https://www.redgifs.com");
            headers.put("Accept", "*/*");

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 16) RedditMedia/3.6.7")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http);
            builder.setMediaSourceFactory(mediaSourceFactory);
        }

        return builder.build();
    }

    private static boolean isRedgifsMedia(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("redgifs.com") || lower.contains("redgifsusercontent.com");
    }
}
''')

# ---------------------------------------------------------------------------
# Fullscreen pager: resolve RedGIFs lazily on the visible holder and use the
# highest-quality player factory for all adaptive/direct video playback.
# ---------------------------------------------------------------------------
pager_path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
pager = pager_path.read_text()

pager = replace_required(
    pager,
    '''        private void addMedia(RedditPost post, int position) {
            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {''',
    '''        private void addMedia(RedditPost post, int position) {
            if (post.videoUrl != null && post.videoUrl.startsWith("redgifs:")) {
                final String unresolved = post.videoUrl;
                final String id = unresolved.substring("redgifs:".length());
                ImageView poster = new ImageView(context);
                poster.setBackgroundColor(Color.BLACK);
                poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
                root.addView(poster, fullParams());
                if (post.posterUrl != null && !post.posterUrl.isEmpty()) {
                    Glide.with(poster).load(post.posterUrl).fitCenter().into(poster);
                }
                RedgifsResolver.resolve(id, new RedgifsResolver.Callback() {
                    @Override
                    public void onResolved(String url) {
                        if (post.videoUrl.equals(unresolved)) post.videoUrl = url;
                        if (boundPosition == position) bind(post, position);
                    }

                    @Override
                    public void onError(String error) {
                        listener.onMediaFailed(post);
                    }
                });
                return;
            }

            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {''',
    'fullscreen RedGIFs lazy HD resolve')

pager = replace_required(
    pager,
    '                player = new ExoPlayer.Builder(context).build();',
    '                player = HighQualityPlayerFactory.create(context, post.videoUrl);',
    'fullscreen highest quality player')

pager_path.write_text(pager)

# ---------------------------------------------------------------------------
# Stream: show the poster while a RedGIFs HD URL resolves, then rebind just that
# item. Native videos use the same high-quality player selection.
# ---------------------------------------------------------------------------
grid_path = Path('app/src/main/java/com/scrolller/adblock/GridPostAdapter.java')
grid = grid_path.read_text()

grid = replace_required(
    grid,
    '''            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {
                addStreamPlayer(post);
                return;
            }

            ImageView image = new ImageView(context);''',
    '''            if (post.videoUrl != null && post.videoUrl.startsWith("redgifs:")) {
                final String unresolved = post.videoUrl;
                final String id = unresolved.substring("redgifs:".length());
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackgroundColor(Color.BLACK);
                root.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                if (post.posterUrl != null && !post.posterUrl.isEmpty()) {
                    Glide.with(image).load(post.posterUrl).fitCenter().into(image);
                }
                RedgifsResolver.resolve(id, new RedgifsResolver.Callback() {
                    @Override
                    public void onResolved(String url) {
                        if (post.videoUrl.equals(unresolved)) post.videoUrl = url;
                        int index = posts.indexOf(post);
                        if (index >= 0) notifyItemChanged(index);
                    }

                    @Override
                    public void onError(String error) {
                        // Keep the preview visible; fullscreen can still expose the source URL.
                    }
                });
                return;
            }

            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {
                addStreamPlayer(post);
                return;
            }

            ImageView image = new ImageView(context);''',
    'Stream RedGIFs lazy HD resolve')

grid = replace_required(
    grid,
    '            player = new ExoPlayer.Builder(context).build();',
    '            player = HighQualityPlayerFactory.create(context, post.videoUrl);',
    'Stream highest quality player')

grid_path.write_text(grid)

print('Applied v3.6.7 deep subreddit reservoir + archive discovery + HD media resolution')
