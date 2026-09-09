from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


# Shared Media3 disk cache + progressive byte preloader. This is deliberately
# separate from the ViewPager's ExoPlayer count: we keep only nearby players alive
# while warming upcoming bytes without allocating extra video decoders.
factory_path = Path("app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java")
factory_path.write_text(r'''package com.scrolller.adblock;

import android.content.Context;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
final class HighQualityPlayerFactory {
    private static final long CACHE_BYTES = 384L * 1024L * 1024L;
    private static final long MANIFEST_PRELOAD_BYTES = 256L * 1024L;
    private static final Object CACHE_LOCK = new Object();
    private static final ExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Set<String> PRELOADING = ConcurrentHashMap.newKeySet();
    private static volatile SimpleCache sharedCache;

    private HighQualityPlayerFactory() {}

    static void warmup(Context context) {
        Context app = context.getApplicationContext();
        PRELOAD_EXECUTOR.execute(() -> cache(app));
    }

    static ExoPlayer create(Context context, String mediaUrl) {
        Context app = context.getApplicationContext();
        DefaultTrackSelector selector = new DefaultTrackSelector(app);
        selector.setParameters(
                selector.buildUponParameters()
                        .setForceHighestSupportedBitrate(false));

        CacheDataSource.Factory dataSourceFactory = cachedDataSourceFactory(app, mediaUrl);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(app)
                .setDataSourceFactory(dataSourceFactory);

        return new ExoPlayer.Builder(app)
                .setTrackSelector(selector)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
    }

    static void preload(Context context, String mediaUrl, long requestedBytes) {
        if (mediaUrl == null || mediaUrl.isEmpty() || requestedBytes <= 0L) return;
        String lower = mediaUrl.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return;

        long bytes = requestedBytes;
        if (isManifest(lower)) bytes = Math.min(bytes, MANIFEST_PRELOAD_BYTES);
        final long preloadBytes = Math.max(64L * 1024L, bytes);
        final String taskKey = mediaUrl + "#" + preloadBytes;
        if (!PRELOADING.add(taskKey)) return;

        Context app = context.getApplicationContext();
        PRELOAD_EXECUTOR.execute(() -> {
            try {
                DataSpec spec = new DataSpec.Builder()
                        .setUri(mediaUrl)
                        .setPosition(0L)
                        .setLength(preloadBytes)
                        .build();
                CacheDataSource source = cachedDataSourceFactory(app, mediaUrl)
                        .createDataSourceForDownloading();
                new CacheWriter(source, spec, null, null).cache();
            } catch (Exception ignored) {
                // Preload is opportunistic. Playback still has the normal upstream path.
            } finally {
                PRELOADING.remove(taskKey);
            }
        });
    }

    private static CacheDataSource.Factory cachedDataSourceFactory(Context context, String mediaUrl) {
        return new CacheDataSource.Factory()
                .setCache(cache(context))
                .setUpstreamDataSourceFactory(httpFactory(mediaUrl))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private static DefaultHttpDataSource.Factory httpFactory(String mediaUrl) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 16) RedditMedia/3.8.5")
                .setAllowCrossProtocolRedirects(true);

        if (isRedgifsMedia(mediaUrl)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.redgifs.com/");
            headers.put("Origin", "https://www.redgifs.com");
            headers.put("Accept", "*/*");
            http.setDefaultRequestProperties(headers);
        }
        return http;
    }

    @SuppressWarnings("deprecation")
    private static SimpleCache cache(Context context) {
        SimpleCache current = sharedCache;
        if (current != null) return current;
        synchronized (CACHE_LOCK) {
            current = sharedCache;
            if (current == null) {
                File directory = new File(context.getCacheDir(), "reddit-media-media3");
                current = new SimpleCache(
                        directory,
                        new LeastRecentlyUsedCacheEvictor(CACHE_BYTES));
                sharedCache = current;
            }
        }
        return current;
    }

    private static boolean isManifest(String lowerUrl) {
        int query = lowerUrl.indexOf('?');
        String path = query >= 0 ? lowerUrl.substring(0, query) : lowerUrl;
        return path.endsWith(".m3u8") || path.endsWith(".mpd");
    }

    private static boolean isRedgifsMedia(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("redgifs.com") || lower.contains("redgifsusercontent.com");
    }
}
''')


pager_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
pager = pager_path.read_text()
pager = replace_once(
    pager,
    '''import java.util.HashMap;
import java.util.List;
import java.util.Map;''',
    '''import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;''',
    "pager collection imports",
)
pager = replace_once(
    pager,
    '''    private final Map<Integer, ExoPlayer> players = new HashMap<>();
    private final ArrayList<PostHolder> attachedHolders = new ArrayList<>();''',
    '''    private final Map<Integer, ExoPlayer> players = new HashMap<>();
    private final ArrayList<PostHolder> attachedHolders = new ArrayList<>();
    private final Set<String> warmingRedgifs = new HashSet<>();''',
    "preload state",
)
pager = replace_once(
    pager,
    '''        activePosition = 0;
        notifyDataSetChanged();
    }''',
    '''        activePosition = 0;
        notifyDataSetChanged();
        warmAdjacentMedia(activePosition);
    }''',
    "initial media warmup",
)
pager = replace_once(
    pager,
    '''        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }''',
    '''        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
        warmAdjacentMedia(activePosition);
    }''',
    "append media warmup",
)
old_warm = '''private void warmAdjacentMedia(int center) {
        int start = Math.max(0, center - 2);
        int end = Math.min(posts.size() - 1, center + 2);
        for (int i = start; i <= end; i++) {
            RedditPost post = posts.get(i);
            if (post == null) continue;
            String preview = post.posterUrl;
            if ((preview == null || preview.isEmpty()) && post.imageUrls != null && !post.imageUrls.isEmpty()) {
                preview = post.imageUrls.get(0);
            }
            if (preview != null && !preview.isEmpty()) {
                Glide.with(context.getApplicationContext()).load(preview).preload();
            }
        }
    }'''
new_warm = '''private void warmAdjacentMedia(int center) {
        if (center < 0 || posts.isEmpty()) return;
        int start = Math.max(0, center - 1);
        int end = Math.min(posts.size() - 1, center + 4);
        Context app = context.getApplicationContext();
        for (int i = start; i <= end; i++) {
            RedditPost post = posts.get(i);
            if (post == null) continue;
            String preview = post.posterUrl;
            if ((preview == null || preview.isEmpty())
                    && post.imageUrls != null && !post.imageUrls.isEmpty()) {
                preview = post.imageUrls.get(0);
            }
            if (preview != null && !preview.isEmpty()) {
                Glide.with(app).load(preview).preload();
            }

            int distance = i - center;
            if (distance > 0) preloadUpcomingVideo(post, distance);
        }
    }

    private void preloadUpcomingVideo(RedditPost post, int distance) {
        if (post == null || distance < 1 || distance > 4) return;
        String video = post.videoUrl == null ? "" : post.videoUrl;
        final long bytes = distance == 1
                ? 4L * 1024L * 1024L
                : distance == 2
                ? 2L * 1024L * 1024L
                : distance == 3
                ? 768L * 1024L
                : 256L * 1024L;

        if (video.startsWith("redgifs:")) {
            final String unresolved = video;
            final String id = unresolved.substring("redgifs:".length());
            final String key = id.toLowerCase();
            if (key.isEmpty() || !warmingRedgifs.add(key)) return;
            RedgifsResolver.resolve(id, new RedgifsResolver.Callback() {
                @Override
                public void onResolved(String url) {
                    warmingRedgifs.remove(key);
                    if (post.videoUrl.equals(unresolved)) post.videoUrl = url;
                    HighQualityPlayerFactory.preload(context.getApplicationContext(), url, bytes);
                }

                @Override
                public void onError(String error) {
                    warmingRedgifs.remove(key);
                }
            });
            return;
        }

        if (!video.isEmpty()) {
            HighQualityPlayerFactory.preload(context.getApplicationContext(), video, bytes);
        }
    }'''
pager = replace_once(pager, old_warm, new_warm, "video byte preloading")
pager_path.write_text(pager)


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()
main = replace_once(
    main,
    '''        postAdapter = new PostPagerAdapter(this, this);
        postAdapter.setMuted(muted);
        pager.setAdapter(postAdapter);''',
    '''        postAdapter = new PostPagerAdapter(this, this);
        postAdapter.setMuted(muted);
        HighQualityPlayerFactory.warmup(getApplicationContext());
        pager.setAdapter(postAdapter);''',
    "early cache warmup",
)
main_path.write_text(main)


build_path = Path("app/build.gradle")
build = build_path.read_text()
build = build.replace("androidx.media3:media3-exoplayer:1.5.1", "androidx.media3:media3-exoplayer:1.11.0")
build = build.replace("androidx.media3:media3-exoplayer-dash:1.5.1", "androidx.media3:media3-exoplayer-dash:1.11.0")
build = build.replace("androidx.media3:media3-exoplayer-hls:1.5.1", "androidx.media3:media3-exoplayer-hls:1.11.0")
build = build.replace("androidx.media3:media3-ui:1.5.1", "androidx.media3:media3-ui:1.11.0")
if "androidx.media3:media3-datasource:1.11.0" not in build:
    build = build.replace(
        "    implementation 'androidx.media3:media3-ui:1.11.0'\n",
        "    implementation 'androidx.media3:media3-ui:1.11.0'\n    implementation 'androidx.media3:media3-datasource:1.11.0'\n",
    )
if 'versionCode 39' not in build:
    build = replace_once(build, 'versionCode 38', 'versionCode 39', 'versionCode')
if 'versionName "3.8.5"' not in build:
    build = replace_once(build, 'versionName "3.8.4"', 'versionName "3.8.5"', 'versionName')
build_path.write_text(build)

print("Applied v3.8.5 shared media cache + upcoming video preload hotfix")
