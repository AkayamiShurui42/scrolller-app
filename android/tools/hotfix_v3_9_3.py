from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Content taxonomy: category filters must not erase metadata-unknown posts.
# ---------------------------------------------------------------------------
taxonomy_path = Path("app/src/main/java/com/scrolller/adblock/ContentTaxonomy.java")
taxonomy = taxonomy_path.read_text()

taxonomy = replace_once(
    taxonomy,
    '''        // Most general NSFW communities do not explicitly write "straight" in
        // every title/flair. If metadata contains no explicit gay/lesbian/trans
        // signal, classify the content stream as the straight/general bucket.
        // Explicit LGBT metadata above always wins and prevents this fallback.
        if (mask == 0 && post.nsfw) mask = STRAIGHT;

        return mask;
    }

    static boolean matches(RedditPost post, int selectedMask) {
        if (selectedMask == 0) return true;
        return (classify(post) & selectedMask) != 0;
    }
''',
    '''        return mask;
    }

    static boolean matches(RedditPost post, int selectedMask) {
        if (selectedMask == 0) return true;
        int classified = classify(post);
        // Category metadata is often absent on otherwise-valid Reddit/Scrolller
        // posts. Unknown must stay visible rather than being treated as a proven
        // non-match; explicit conflicting metadata is still filtered normally.
        if (classified == 0) return true;
        return (classified & selectedMask) != 0;
    }
''',
    "taxonomy unknown-safe matching",
)
taxonomy_path.write_text(taxonomy)


# ---------------------------------------------------------------------------
# MainActivity: only explicit media-view evidence can move a post to Hidden.
# No dwell fallback. A media failure is never view evidence.
# ---------------------------------------------------------------------------
main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()

main = replace_once(
    main,
    '''private void setFullscreenReadBaseline(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) {
            lastFullscreenPostId = "";
            fullscreenVisitStartedAtMs = 0L;
            return;
        }
        lastFullscreenPostId = current.id;
        fullscreenVisitStartedAtMs = SystemClock.elapsedRealtime();
    }



private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;
        long now = SystemClock.elapsedRealtime();

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            fullscreenVisitStartedAtMs = now;
            return;
        }
        if (lastFullscreenPostId.equals(currentId)) return;

        RedditPost previous = null;
        for (RedditPost candidate : postAdapter.getPosts()) {
            if (candidate != null && lastFullscreenPostId.equals(candidate.id)) {
                previous = candidate;
                break;
            }
        }
        long dwellMs = fullscreenVisitStartedAtMs > 0L ? now - fullscreenVisitStartedAtMs : 0L;
        boolean actuallyViewed = previous != null && (
                mediaReadyPostIds.contains(previous.id)
                        || mediaFailedPostIds.contains(previous.id)
                        || dwellMs >= 350L);
        if (actuallyViewed
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            readHideStore.hideAsync(previous);
            trimHiddenPostCache();
        }
        lastFullscreenPostId = currentId;
        fullscreenVisitStartedAtMs = now;
    }
''',
    '''private void setFullscreenReadBaseline(int position) {
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
        for (RedditPost candidate : postAdapter.getPosts()) {
            if (candidate != null && lastFullscreenPostId.equals(candidate.id)) {
                previous = candidate;
                break;
            }
        }

        // PostPagerAdapter reports readiness only after the active media meets
        // its view gate: displayed image/GIF/gallery, or rendered video with
        // >= 1 second of actual playback. Time on the pager is not enough.
        boolean actuallyViewed = previous != null
                && mediaReadyPostIds.contains(previous.id);
        if (actuallyViewed
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            readHideStore.hideAsync(previous);
            trimHiddenPostCache();
        }
        lastFullscreenPostId = currentId;
    }
''',
    "read/hide transition",
)

main = replace_once(
    main,
    '''    @Override
    public void onMediaFailed(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        if (!mediaReadyPostIds.contains(post.id)) mediaFailedPostIds.add(post.id);
    }
''',
    '''    @Override
    public void onMediaFailed(RedditPost post) {
        // A failed image/video load is explicitly NOT evidence that the user
        // viewed the media. Keep the post unread so it can be encountered again.
    }
''',
    "media failure semantics",
)

main = replace_once(
    main,
    '''        body.addView(bodyText("Multi-select. Gay / Lesbian and Trans require explicit subreddit/title/flair metadata. General NSFW content with no explicit LGBT tag falls into Straight so normal adult feeds do not disappear. The app never guesses identity from an image. No selection shows everything."));''',
    '''        body.addView(bodyText("Multi-select. Categories use explicit subreddit/title/flair/tag metadata only. Posts with no reliable category metadata stay visible instead of being falsely rejected. Explicit non-matching metadata is filtered. The app never guesses identity from an image. No selection shows everything."));''',
    "people filter help",
)
main_path.write_text(main)


# ---------------------------------------------------------------------------
# PostPagerAdapter: image == viewed only after the visual resource is available
# on the active settled page. Video == viewed only after first rendered frame
# plus 1000 ms of actual playback on the active settled page.
# ---------------------------------------------------------------------------
adapter_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
adapter = adapter_path.read_text()

adapter = replace_once(
    adapter,
    '''import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;''',
    '''import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;''',
    "adapter timing imports",
)

adapter = replace_once(
    adapter,
    '''    private final ArrayList<PostHolder> attachedHolders = new ArrayList<>();
    private final Set<String> warmingRedgifs = new HashSet<>();
    private int activePosition = 0;''',
    '''    private final ArrayList<PostHolder> attachedHolders = new ArrayList<>();
    private final Set<String> warmingRedgifs = new HashSet<>();
    private final Set<String> loadedVisualPostIds = new HashSet<>();
    private static final long VIDEO_VIEW_THRESHOLD_MS = 1000L;
    private static final long VIDEO_VIEW_SAMPLE_MS = 100L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String videoViewPostId = "";
    private boolean videoFirstFrameRendered = false;
    private boolean videoViewReported = false;
    private boolean videoWasPlaying = false;
    private long videoPlayedMs = 0L;
    private long videoLastSampleAtMs = 0L;
    private final Runnable videoViewTicker = new Runnable() {
        @Override
        public void run() {
            sampleVideoViewProgress(true);
        }
    };
    private int activePosition = 0;''',
    "adapter view tracking fields",
)

adapter = replace_once(
    adapter,
    '''    public void setPosts(List<RedditPost> items) {
        releaseAll();
        posts.clear();
        posts.addAll(items);
        activePosition = 0;
        notifyDataSetChanged();
        warmAdjacentMedia(activePosition);
    }
''',
    '''    public void setPosts(List<RedditPost> items) {
        releaseAll();
        posts.clear();
        posts.addAll(items);
        loadedVisualPostIds.clear();
        activePosition = 0;
        resetVideoViewTracking(currentActivePost());
        notifyDataSetChanged();
        warmAdjacentMedia(activePosition);
    }
''',
    "setPosts view reset",
)

adapter = replace_once(
    adapter,
    '''        if (index < 0) return;
        releaseAll();
        posts.remove(index);''',
    '''        if (index < 0) return;
        releaseAll();
        loadedVisualPostIds.remove(id);
        posts.remove(index);''',
    "removePost visual state",
)

adapter = replace_once(
    adapter,
    '''public void setActivePosition(int position) {
        activePosition = position;
        PostHolder target = null;
        for (PostHolder holder : new ArrayList<>(attachedHolders)) {
            if (holder.boundPosition == position) {
                target = holder;
                break;
            }
        }
        if (target != null) target.activateIfNeeded();
        else pausePlayers();
        if (!pagerScrolling) warmAdjacentMedia(position);
    }

    public void setPagerScrolling(boolean scrolling) {
        if (pagerScrolling == scrolling) return;
        pagerScrolling = scrolling;
        if (scrolling) {
            // CacheWriter is opportunistic and may be cancelled during touch input.
            // The selected pooled player is NOT paused; onPageSelected hands playback
            // directly to the new page while ViewPager2 is still settling.
            HighQualityPlayerFactory.cancelPendingPreloads();
            return;
        }
        warmAdjacentMedia(activePosition);
    }
''',
    '''public void setActivePosition(int position) {
        int previousPosition = activePosition;
        activePosition = position;
        if (previousPosition != position) resetVideoViewTracking(currentActivePost());
        PostHolder target = null;
        for (PostHolder holder : new ArrayList<>(attachedHolders)) {
            if (holder.boundPosition == position) {
                target = holder;
                break;
            }
        }
        if (target != null) target.activateIfNeeded();
        else pausePlayers();
        if (!pagerScrolling) warmAdjacentMedia(position);
    }

    public void setPagerScrolling(boolean scrolling) {
        if (pagerScrolling == scrolling) return;
        if (scrolling) sampleVideoViewProgress(false);
        pagerScrolling = scrolling;
        if (scrolling) {
            // A drag means the user is in the act of leaving this page. Do not
            // count image callbacks or video playback time while the pager moves.
            HighQualityPlayerFactory.cancelPendingPreloads();
            return;
        }
        PostHolder target = null;
        for (PostHolder holder : new ArrayList<>(attachedHolders)) {
            if (holder.boundPosition == activePosition) {
                target = holder;
                break;
            }
        }
        if (target != null) target.activateIfNeeded();
        sampleVideoViewProgress(true);
        warmAdjacentMedia(activePosition);
    }
''',
    "active pager view semantics",
)

adapter = replace_once(
    adapter,
    '''    private boolean isRedgifsUrl(String url) {''',
    '''    private RedditPost currentActivePost() {
        return activePosition >= 0 && activePosition < posts.size()
                ? posts.get(activePosition) : null;
    }

    private boolean isPostActive(RedditPost post) {
        return post != null && currentActivePost() == post;
    }

    private boolean isStreamedVideo(RedditPost post) {
        return post != null
                && (post.mediaKind == RedditPost.MediaKind.VIDEO
                || post.mediaKind == RedditPost.MediaKind.GIF)
                && post.videoUrl != null
                && !post.videoUrl.isEmpty();
    }

    private void markVisualLoaded(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        loadedVisualPostIds.add(post.id);
        if (isPostActive(post) && !pagerScrolling && !isStreamedVideo(post)) {
            listener.onMediaReady(post);
        }
    }

    private void resetVideoViewTracking(RedditPost post) {
        mainHandler.removeCallbacks(videoViewTicker);
        videoViewPostId = isStreamedVideo(post) && post.id != null ? post.id : "";
        videoFirstFrameRendered = false;
        videoViewReported = false;
        videoWasPlaying = false;
        videoPlayedMs = 0L;
        videoLastSampleAtMs = 0L;
    }

    private void onVideoFirstFrame(ExoPlayer player, boolean familyRedgifs) {
        RedditPost post = familyRedgifs ? redgifsPlayerPost : defaultPlayerPost;
        if (player == null || player != activePlayer || !isPostActive(post)) return;
        if (post.id == null || !post.id.equals(videoViewPostId)) return;
        videoFirstFrameRendered = true;
        videoLastSampleAtMs = SystemClock.elapsedRealtime();
        sampleVideoViewProgress(true);
    }

    private void sampleVideoViewProgress(boolean scheduleNext) {
        mainHandler.removeCallbacks(videoViewTicker);
        if (videoViewReported || videoViewPostId.isEmpty()) return;
        RedditPost post = currentActivePost();
        if (post == null || post.id == null || !videoViewPostId.equals(post.id)) return;

        long now = SystemClock.elapsedRealtime();
        boolean playingNow = !pagerScrolling
                && videoFirstFrameRendered
                && activePlayer != null
                && activePlayerHolder != null
                && activePlayerHolder.boundPost == post
                && activePlayer.isPlaying();

        if (videoWasPlaying && videoLastSampleAtMs > 0L) {
            videoPlayedMs += Math.max(0L, now - videoLastSampleAtMs);
        }
        videoWasPlaying = playingNow;
        videoLastSampleAtMs = now;

        if (videoFirstFrameRendered && videoPlayedMs >= VIDEO_VIEW_THRESHOLD_MS) {
            videoViewReported = true;
            listener.onMediaReady(post);
            return;
        }
        if (scheduleNext && (playingNow || videoFirstFrameRendered)) {
            mainHandler.postDelayed(videoViewTicker, VIDEO_VIEW_SAMPLE_MS);
        }
    }

    private boolean isRedgifsUrl(String url) {''',
    "view tracking helpers",
)

adapter = replace_once(
    adapter,
    '''        player = HighQualityPlayerFactory.create(context, url);
        player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
        final boolean familyRedgifs = redgifs;
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state != Player.STATE_READY) return;
                RedditPost post = familyRedgifs ? redgifsPlayerPost : defaultPlayerPost;
                if (post != null) listener.onMediaReady(post);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                RedditPost post = familyRedgifs ? redgifsPlayerPost : defaultPlayerPost;
                if (post != null) listener.onMediaFailed(post);
            }
        });''',
    '''        player = HighQualityPlayerFactory.create(context, url);
        player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
        final boolean familyRedgifs = redgifs;
        final ExoPlayer observedPlayer = player;
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                onVideoFirstFrame(observedPlayer, familyRedgifs);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                sampleVideoViewProgress(true);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                RedditPost post = familyRedgifs ? redgifsPlayerPost : defaultPlayerPost;
                if (post != null) listener.onMediaFailed(post);
            }
        });''',
    "video playback gate",
)

adapter = replace_once(
    adapter,
    '''    public void releaseAll() {
        if (activePlayerHolder != null && activePlayerHolder.playerView != null) {''',
    '''    public void releaseAll() {
        resetVideoViewTracking(null);
        if (activePlayerHolder != null && activePlayerHolder.playerView != null) {''',
    "release video tracker",
)

adapter = replace_once(
    adapter,
    '''        void activateIfNeeded() {
            if (boundPost == null || boundPosition != activePosition) return;
            if (playerView == null) return;
            String url = boundPost.videoUrl == null ? "" : boundPost.videoUrl;
            if (url.isEmpty() || url.startsWith("redgifs:")) return;
            if (boundPost.mediaKind == RedditPost.MediaKind.VIDEO
                    || boundPost.mediaKind == RedditPost.MediaKind.GIF) {
                attachPooledPlayer(this, boundPost, boundPosition, url);
            }
        }
''',
    '''        void activateIfNeeded() {
            if (boundPost == null || boundPosition != activePosition) return;
            if (!isStreamedVideo(boundPost)) {
                if (!pagerScrolling && boundPost.id != null
                        && loadedVisualPostIds.contains(boundPost.id)) {
                    listener.onMediaReady(boundPost);
                }
                return;
            }
            if (playerView == null) return;
            String url = boundPost.videoUrl == null ? "" : boundPost.videoUrl;
            if (url.isEmpty() || url.startsWith("redgifs:")) return;
            attachPooledPlayer(this, boundPost, boundPosition, url);
            sampleVideoViewProgress(true);
        }
''',
    "active visual/video gate",
)

# After STATE_READY was removed, the only direct success callbacks are Glide image/GIF.
adapter = adapter.replace("                listener.onMediaReady(post);\n                return false;",
                          "                markVisualLoaded(post);\n                return false;")
if adapter.count("markVisualLoaded(post);") != 2:
    raise SystemExit("image/GIF readiness: expected exactly two success callbacks")

adapter_path.write_text(adapter)


# ---------------------------------------------------------------------------
# Version bump.
# ---------------------------------------------------------------------------
build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, "versionCode 49", "versionCode 50", "versionCode")
build = replace_once(build, 'versionName "3.9.2"', 'versionName "3.9.3"', "versionName")
build_path.write_text(build)

print("Applied Reddit Media v3.9.3 read/view + category filter hotfix")
