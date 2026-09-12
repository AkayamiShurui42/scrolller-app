package com.scrolller.adblock;

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
import java.util.concurrent.atomic.AtomicInteger;

@UnstableApi
final class HighQualityPlayerFactory {
    private static final long CACHE_BYTES = 384L * 1024L * 1024L;
    private static final long MANIFEST_PRELOAD_BYTES = 256L * 1024L;
    private static final Object CACHE_LOCK = new Object();
    private static final ExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Set<String> PRELOADING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger PRELOAD_GENERATION = new AtomicInteger();
    private static volatile CacheWriter activeWriter;
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

    static void cancelPendingPreloads() {
        PRELOAD_GENERATION.incrementAndGet();
        CacheWriter writer = activeWriter;
        if (writer != null) {
            try { writer.cancel(); } catch (RuntimeException ignored) {}
        }
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
        final int generation = PRELOAD_GENERATION.get();
        PRELOAD_EXECUTOR.execute(() -> {
            CacheWriter writer = null;
            try {
                if (generation != PRELOAD_GENERATION.get()) return;
                DataSpec spec = new DataSpec.Builder()
                        .setUri(mediaUrl)
                        .setPosition(0L)
                        .setLength(preloadBytes)
                        .build();
                CacheDataSource source = cachedDataSourceFactory(app, mediaUrl)
                        .createDataSourceForDownloading();
                writer = new CacheWriter(source, spec, null, null);
                activeWriter = writer;
                if (generation != PRELOAD_GENERATION.get()) return;
                writer.cache();
            } catch (Exception ignored) {
                // Preload is opportunistic. Playback still has the normal upstream path.
            } finally {
                if (activeWriter == writer) activeWriter = null;
                PRELOADING.remove(taskKey);
            }
        });
    }

    static void clearCacheAsync(Context context, Runnable done) {
        cancelPendingPreloads();
        Context app = context.getApplicationContext();
        PRELOAD_EXECUTOR.execute(() -> {
            try {
                SimpleCache current = cache(app);
                for (String key : new java.util.HashSet<>(current.getKeys())) {
                    try { current.removeResource(key); } catch (Exception ignored) {}
                }
            } finally {
                if (done != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
                }
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
                .setUserAgent("Mozilla/5.0 (Linux; Android 16) RedditMedia/3.8.8")
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
