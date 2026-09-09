from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


# ----- MainActivity: stop retaining detached pager holders and gate preload during swipes.
main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()

main = replace_once(
    main,
    '            pagerRecycler.setItemViewCacheSize(4);',
    '            pagerRecycler.setItemViewCacheSize(0);',
    'pager detached-view cache',
)

old_scroll = '''            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    fullscreenUserGesture = true;
                    pendingUserFullscreenPosition = -1;
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    if (fullscreenUserGesture && pendingUserFullscreenPosition >= 0
                            && layoutMode.equals("fullscreen")
                            && screen != Screen.ACCOUNT
                            && screen != Screen.FAVORITES) {
                        trackFullscreenVisit(pendingUserFullscreenPosition);
                    }
                    fullscreenUserGesture = false;
                    pendingUserFullscreenPosition = -1;
                }
            }'''

new_scroll = '''            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    postAdapter.setPagerScrolling(true);
                    fullscreenUserGesture = true;
                    pendingUserFullscreenPosition = -1;
                } else if (state == ViewPager2.SCROLL_STATE_SETTLING) {
                    postAdapter.setPagerScrolling(true);
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    postAdapter.setPagerScrolling(false);
                    if (fullscreenUserGesture && pendingUserFullscreenPosition >= 0
                            && layoutMode.equals("fullscreen")
                            && screen != Screen.ACCOUNT
                            && screen != Screen.FAVORITES) {
                        trackFullscreenVisit(pendingUserFullscreenPosition);
                    }
                    fullscreenUserGesture = false;
                    pendingUserFullscreenPosition = -1;
                }
            }'''

main = replace_once(main, old_scroll, new_scroll, 'pager scroll lifecycle')
main_path.write_text(main)


# ----- PostPagerAdapter: release detached ExoPlayers immediately and debounce preloading.
pager_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
pager = pager_path.read_text()

pager = replace_once(
    pager,
    '''    private boolean hiddenMode = false;
    private int topInsetPx = 0;''',
    '''    private boolean hiddenMode = false;
    private boolean pagerScrolling = false;
    private int topInsetPx = 0;''',
    'pager scrolling field',
)

old_active = '''public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : new ArrayList<>(players.entrySet())) {
            try {
                boolean active = entry.getKey() == position;
                entry.getValue().setPlayWhenReady(active);
                if (!active) entry.getValue().pause();
            } catch (RuntimeException ignored) {}
        }
        warmAdjacentMedia(position);
    }'''

new_active = '''public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : new ArrayList<>(players.entrySet())) {
            try {
                boolean active = !pagerScrolling && entry.getKey() == position;
                entry.getValue().setPlayWhenReady(active);
                if (!active) entry.getValue().pause();
            } catch (RuntimeException ignored) {}
        }
        if (!pagerScrolling) warmAdjacentMedia(position);
    }

    public void setPagerScrolling(boolean scrolling) {
        if (pagerScrolling == scrolling) return;
        pagerScrolling = scrolling;
        if (scrolling) {
            HighQualityPlayerFactory.cancelPendingPreloads();
            for (ExoPlayer player : new ArrayList<>(players.values())) {
                try {
                    player.setPlayWhenReady(false);
                    player.pause();
                } catch (RuntimeException ignored) {}
            }
            return;
        }
        // Resume only after ViewPager2 is idle. This collapses a burst of
        // intermediate onPageSelected callbacks into one preload/playback update.
        setActivePosition(activePosition);
    }'''

pager = replace_once(pager, old_active, new_active, 'active position swipe gate')

old_attach = '''    @Override
    public void onViewAttachedToWindow(@NonNull PostHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (!attachedHolders.contains(holder)) attachedHolders.add(holder);
        holder.applyChromeVisibility();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull PostHolder holder) {
        attachedHolders.remove(holder);
        super.onViewDetachedFromWindow(holder);
    }'''

new_attach = '''    @Override
    public void onViewAttachedToWindow(@NonNull PostHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (!attachedHolders.contains(holder)) attachedHolders.add(holder);
        if (holder.needsRebindAfterDetach) {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION && position >= 0 && position < posts.size()) {
                holder.bind(posts.get(position), position);
            }
        }
        holder.applyChromeVisibility();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull PostHolder holder) {
        attachedHolders.remove(holder);
        holder.releaseForDetach();
        super.onViewDetachedFromWindow(holder);
    }'''

pager = replace_once(pager, old_attach, new_attach, 'detach lifecycle')

pager = replace_once(
    pager,
    '''        View mediaControl;
        int boundPosition = -1;''',
    '''        View mediaControl;
        int boundPosition = -1;
        boolean needsRebindAfterDetach = false;''',
    'holder detach field',
)

pager = replace_once(
    pager,
    '''        void bind(RedditPost post, int position) {
            releasePlayer();
            boundPosition = position;''',
    '''        void bind(RedditPost post, int position) {
            releasePlayer();
            needsRebindAfterDetach = false;
            boundPosition = position;''',
    'bind clears detach flag',
)

release_anchor = '''        void releasePlayer() {
            if (boundPosition >= 0) players.remove(boundPosition);'''
release_helper = '''        void releaseForDetach() {
            // RecyclerView may keep a detached holder without recycling it. Holding
            // its ExoPlayer in that state leaks decoder/buffer pressure across rapid
            // swipes, so release immediately and rebuild only if the holder reattaches.
            needsRebindAfterDetach = true;
            releasePlayer();
        }

'''
if release_helper not in pager:
    if pager.count(release_anchor) != 1:
        raise SystemExit(f"release helper anchor: expected 1, found {pager.count(release_anchor)}")
    pager = pager.replace(release_anchor, release_helper + release_anchor, 1)

# Keep poster warmup slightly ahead, but only prebuffer video for the next two posts.
pager = replace_once(
    pager,
    '''        int start = Math.max(0, center - 1);
        int end = Math.min(posts.size() - 1, center + 4);''',
    '''        int start = Math.max(0, center);
        int end = Math.min(posts.size() - 1, center + 3);''',
    'preload window',
)

pager = replace_once(
    pager,
    '''        if (post == null || distance < 1 || distance > 4) return;
        String video = post.videoUrl == null ? "" : post.videoUrl;
        final long bytes = distance == 1
                ? 4L * 1024L * 1024L
                : distance == 2
                ? 2L * 1024L * 1024L
                : distance == 3
                ? 768L * 1024L
                : 256L * 1024L;''',
    '''        if (post == null || distance < 1 || distance > 2) return;
        String video = post.videoUrl == null ? "" : post.videoUrl;
        final long bytes = distance == 1
                ? 4L * 1024L * 1024L
                : 2L * 1024L * 1024L;''',
    'video preload window',
)

pager_path.write_text(pager)


# ----- HighQualityPlayerFactory: cancel stale cache warmups when a swipe starts.
factory_path = Path("app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java")
factory = factory_path.read_text()

factory = replace_once(
    factory,
    '''import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;''',
    '''import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;''',
    'atomic import',
)

factory = replace_once(
    factory,
    '''    private static final Set<String> PRELOADING = ConcurrentHashMap.newKeySet();
    private static volatile SimpleCache sharedCache;''',
    '''    private static final Set<String> PRELOADING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger PRELOAD_GENERATION = new AtomicInteger();
    private static volatile CacheWriter activeWriter;
    private static volatile SimpleCache sharedCache;''',
    'preload cancellation fields',
)

preload_anchor = '''    static void preload(Context context, String mediaUrl, long requestedBytes) {'''
cancel_method = '''    static void cancelPendingPreloads() {
        PRELOAD_GENERATION.incrementAndGet();
        CacheWriter writer = activeWriter;
        if (writer != null) {
            try { writer.cancel(); } catch (RuntimeException ignored) {}
        }
    }

'''
if cancel_method not in factory:
    if factory.count(preload_anchor) != 1:
        raise SystemExit(f"preload anchor: expected 1, found {factory.count(preload_anchor)}")
    factory = factory.replace(preload_anchor, cancel_method + preload_anchor, 1)

old_preload_exec = '''        Context app = context.getApplicationContext();
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
        });'''

new_preload_exec = '''        Context app = context.getApplicationContext();
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
        });'''

factory = replace_once(factory, old_preload_exec, new_preload_exec, 'cancelable preload execution')
factory = factory.replace('RedditMedia/3.8.5', 'RedditMedia/3.8.6')
factory_path.write_text(factory)


# ----- version
build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, 'versionCode 39', 'versionCode 40', 'versionCode')
build = replace_once(build, 'versionName "3.8.5"', 'versionName "3.8.6"', 'versionName')
build_path.write_text(build)

print("Applied v3.8.6 swipe lifecycle/preload hotfix")
