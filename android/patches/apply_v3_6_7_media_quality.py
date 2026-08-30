from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.7 media target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# RedditPost: retain RedGIFs posts that Reddit did not transcode into a native
# reddit_video_preview. The async resolver replaces this pseudo URL with HD MP4.
# ---------------------------------------------------------------------------
post_path = Path('app/src/main/java/com/scrolller/adblock/RedditPost.java')
p = post_path.read_text()

p = replace_required(
    p,
    '    public final String videoUrl;\n',
    '    public String videoUrl;\n',
    'mutable video URL for async provider resolution')

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

        // If Reddit exposed its own reddit_video_preview, that path already won
        // above. Otherwise keep RedGIFs posts instead of dropping them entirely.
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
    'retain unresolved RedGIFs posts')

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
# RedGIFs resolver. Resolve only when Reddit didn't provide its own v.redd.it
# preview. HD wins, SD is the fallback. Token and resolved media URLs are cached.
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
# Highest-quality Media3 player factory. Keep DASH/HLS so Reddit audio remains,
# but disable bitrate thriftiness by forcing the highest supported selection.
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
# Fullscreen pager: resolve RedGIFs lazily and rebuild only the still-bound item.
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
    'fullscreen RedGIFs lazy resolution')

pager = replace_required(
    pager,
    '                player = new ExoPlayer.Builder(context).build();',
    '                player = HighQualityPlayerFactory.create(context, post.videoUrl);',
    'fullscreen high-quality player')

pager_path.write_text(pager)

# ---------------------------------------------------------------------------
# Stream: keep poster visible during resolution, then rebind that media item.
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
                        // Keep the Reddit preview visible if provider resolution fails.
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
    'Stream RedGIFs lazy resolution')

grid = replace_required(
    grid,
    '            player = new ExoPlayer.Builder(context).build();',
    '            player = HighQualityPlayerFactory.create(context, post.videoUrl);',
    'Stream high-quality player')

grid_path.write_text(grid)
print('Applied v3.6.7 RedGIFs HD resolution + highest-quality Media3 playback')
