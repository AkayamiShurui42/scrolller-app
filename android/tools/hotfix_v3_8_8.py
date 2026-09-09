from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, repl: str, label: str) -> str:
    out, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex match, found {count}")
    return out


adapter_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
adapter = adapter_path.read_text()

adapter = replace_once(
    adapter,
    '''    private final Map<Integer, ExoPlayer> players = new HashMap<>();
    private final ArrayList<PostHolder> attachedHolders = new ArrayList<>();''',
    '''    // A bounded two-player pool: one generic Reddit/Scrolller player and one
    // RedGIFs player (which needs different request headers). Pages never own players.
    private ExoPlayer defaultPlayer;
    private ExoPlayer redgifsPlayer;
    private ExoPlayer activePlayer;
    private PostHolder activePlayerHolder;
    private RedditPost defaultPlayerPost;
    private RedditPost redgifsPlayerPost;
    private String defaultPlayerUrl = "";
    private String redgifsPlayerUrl = "";
    private final ArrayList<PostHolder> attachedHolders = new ArrayList<>();''',
    "replace per-page player map")

adapter = replace_once(
    adapter,
    '''    public void setMuted(boolean muted) {
        this.muted = muted;
        for (ExoPlayer player : new ArrayList<>(players.values())) {
            try { player.setVolume(muted ? 0f : 1f); } catch (RuntimeException ignored) {}
        }
    }''',
    '''    public void setMuted(boolean muted) {
        this.muted = muted;
        if (defaultPlayer != null) {
            try { defaultPlayer.setVolume(muted ? 0f : 1f); } catch (RuntimeException ignored) {}
        }
        if (redgifsPlayer != null) {
            try { redgifsPlayer.setVolume(muted ? 0f : 1f); } catch (RuntimeException ignored) {}
        }
    }''',
    "shared-player mute")

adapter = regex_once(
    adapter,
    r'''public void setActivePosition\(int position\) \{.*?    public void releaseAll\(\) \{.*?\n    \}\n\n    @Override\n    public long getItemId''',
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

    private boolean isRedgifsUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("redgifs.com") || lower.contains("redgifsusercontent.com");
    }

    private ExoPlayer obtainPlayer(String url, boolean redgifs) {
        ExoPlayer player = redgifs ? redgifsPlayer : defaultPlayer;
        if (player != null) return player;
        player = HighQualityPlayerFactory.create(context, url);
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
        });
        if (redgifs) redgifsPlayer = player;
        else defaultPlayer = player;
        return player;
    }

    private void attachPooledPlayer(PostHolder holder, RedditPost post, int position, String url) {
        if (holder == null || holder.playerView == null || post == null || url == null || url.isEmpty()) return;
        if (position != activePosition || holder.boundPosition != position || holder.boundPost != post) return;

        boolean redgifs = isRedgifsUrl(url);
        try {
            ExoPlayer target = obtainPlayer(url, redgifs);
            ExoPlayer other = redgifs ? defaultPlayer : redgifsPlayer;
            if (other != null && other != target) {
                try { other.setPlayWhenReady(false); other.pause(); } catch (RuntimeException ignored) {}
            }

            if (activePlayerHolder != null && activePlayerHolder != holder
                    && activePlayerHolder.playerView != null) {
                try { activePlayerHolder.playerView.setPlayer(null); } catch (RuntimeException ignored) {}
                activePlayerHolder.player = null;
            }

            activePlayer = target;
            activePlayerHolder = holder;
            holder.player = target;
            holder.playerView.setPlayer(target);
            holder.playerView.setOnClickListener(null);

            String currentUrl = redgifs ? redgifsPlayerUrl : defaultPlayerUrl;
            if (redgifs) redgifsPlayerPost = post;
            else defaultPlayerPost = post;

            if (!url.equals(currentUrl) || target.getPlayerError() != null) {
                target.setMediaItem(MediaItem.fromUri(url));
                target.prepare();
                if (redgifs) redgifsPlayerUrl = url;
                else defaultPlayerUrl = url;
            }
            target.setVolume(muted ? 0f : 1f);
            target.setPlayWhenReady(true);
        } catch (RuntimeException playerError) {
            listener.onMediaFailed(post);
        }
    }

    private void detachPooledPlayer(PostHolder holder) {
        if (holder == null) return;
        if (holder.playerView != null) {
            try { holder.playerView.setPlayer(null); } catch (RuntimeException ignored) {}
        }
        holder.player = null;
        if (activePlayerHolder == holder) {
            if (activePlayer != null) {
                try { activePlayer.setPlayWhenReady(false); activePlayer.pause(); } catch (RuntimeException ignored) {}
            }
            activePlayerHolder = null;
            activePlayer = null;
        }
    }

    private void pausePlayers() {
        if (defaultPlayer != null) {
            try { defaultPlayer.setPlayWhenReady(false); defaultPlayer.pause(); } catch (RuntimeException ignored) {}
        }
        if (redgifsPlayer != null) {
            try { redgifsPlayer.setPlayWhenReady(false); redgifsPlayer.pause(); } catch (RuntimeException ignored) {}
        }
        activePlayer = null;
        activePlayerHolder = null;
    }

    public void releaseAll() {
        if (activePlayerHolder != null && activePlayerHolder.playerView != null) {
            try { activePlayerHolder.playerView.setPlayer(null); } catch (RuntimeException ignored) {}
            activePlayerHolder.player = null;
        }
        activePlayerHolder = null;
        activePlayer = null;
        if (defaultPlayer != null) {
            try { defaultPlayer.release(); } catch (Exception ignored) {}
            defaultPlayer = null;
        }
        if (redgifsPlayer != null) {
            try { redgifsPlayer.release(); } catch (Exception ignored) {}
            redgifsPlayer = null;
        }
        defaultPlayerPost = null;
        redgifsPlayerPost = null;
        defaultPlayerUrl = "";
        redgifsPlayerUrl = "";
    }

    @Override
    public long getItemId''',
    "replace player lifecycle with bounded pool")

adapter = replace_once(
    adapter,
    '''    @Override
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
    }''',
    '''    @Override
    public void onViewAttachedToWindow(@NonNull PostHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (!attachedHolders.contains(holder)) attachedHolders.add(holder);
        holder.applyChromeVisibility();
        if (holder.boundPosition == activePosition) holder.activateIfNeeded();
    }''',
    "remove detach rebind")

adapter = replace_once(
    adapter,
    '''        int boundPosition = -1;
        boolean needsRebindAfterDetach = false;''',
    '''        int boundPosition = -1;
        RedditPost boundPost;''',
    "holder bound post")

adapter = replace_once(
    adapter,
    '''        void bind(RedditPost post, int position) {
            releasePlayer();
            needsRebindAfterDetach = false;
            boundPosition = position;
            topMeta = null;
            bottomInfo = null;
            mediaControl = null;
            playerView = null;
            root.removeAllViews();
            root.setBackgroundColor(Color.BLACK);
            root.setOnClickListener(null);
            addMedia(post, position);
            addTopMeta(post);
            addBottomInfo(post);
            applyChromeVisibility();
        }''',
    '''        void bind(RedditPost post, int position) {
            releasePlayer();
            boundPosition = position;
            boundPost = post;
            topMeta = null;
            bottomInfo = null;
            mediaControl = null;
            playerView = null;
            root.removeAllViews();
            root.setBackgroundColor(Color.BLACK);
            root.setOnClickListener(null);
            addMedia(post, position);
            addTopMeta(post);
            addBottomInfo(post);
            applyChromeVisibility();
            if (position == activePosition) activateIfNeeded();
        }''',
    "bind without player allocation")

# Replace only the first per-page ExoPlayer creation block (normal Reddit/Scrolller video).
direct_pattern = r'''                try \{\n                    player = HighQualityPlayerFactory\.create\(context, post\.videoUrl\);.*?                    return;\n                \}\n'''
direct_repl = '''                if (position == activePosition) {
                    attachPooledPlayer(this, post, position, post.videoUrl);
                }
                playerView.setOnClickListener(null);
'''
adapter = regex_once(adapter, direct_pattern, direct_repl, "normal video pooled player")

adapter = regex_once(
    adapter,
    r'''        private void attachResolvedRedgifsVideo\(RedditPost post, int position, String url\) \{.*?\n        \}\n\n        private void addTopMeta''',
    '''        private void attachResolvedRedgifsVideo(RedditPost post, int position, String url) {
            if (playerView != null || url == null || url.isEmpty()) return;
            if (boundPosition != position || position < 0 || position >= posts.size()
                    || posts.get(position) != post) return;

            PlayerView nextView = (PlayerView) android.view.LayoutInflater.from(context)
                    .inflate(R.layout.view_texture_player, root, false);
            nextView.setKeepContentOnPlayerReset(true);
            nextView.setShutterBackgroundColor(Color.TRANSPARENT);
            nextView.setBackgroundColor(Color.TRANSPARENT);

            int mediaIndex = Math.min(1, root.getChildCount());
            root.addView(nextView, mediaIndex, fullParams());
            playerView = nextView;
            nextView.setOnClickListener(null);

            if (position == activePosition) {
                attachPooledPlayer(this, post, position, url);
            }

            Button mute = pillButton(muted ? "Muted" : "Sound");
            mediaControl = mute;
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                    dp(74), dp(36), Gravity.TOP | Gravity.END);
            mp.topMargin = topInsetPx + dp(12);
            mp.rightMargin = dp(10);
            root.addView(mute, mp);
            mute.setOnClickListener(v -> {
                muted = !muted;
                setMuted(muted);
                mute.setText(muted ? "Muted" : "Sound");
                listener.onMutedChanged(muted);
            });
            applyChromeVisibility();
        }

        void activateIfNeeded() {
            if (boundPost == null || boundPosition != activePosition) return;
            if (playerView == null) return;
            String url = boundPost.videoUrl == null ? "" : boundPost.videoUrl;
            if (url.isEmpty() || url.startsWith("redgifs:")) return;
            if (boundPost.mediaKind == RedditPost.MediaKind.VIDEO
                    || boundPost.mediaKind == RedditPost.MediaKind.GIF) {
                attachPooledPlayer(this, boundPost, boundPosition, url);
            }
        }

        private void addTopMeta''',
    "RedGIF pooled player")

adapter = regex_once(
    adapter,
    r'''        void releaseForDetach\(\) \{.*?        void releasePlayer\(\) \{.*?\n        \}\n    \}\n\n    private RequestListener''',
    '''        void releaseForDetach() {
            // Keep the page hierarchy intact for fast reverse/forward swipes. Only
            // detach it from the bounded player pool; no holder rebind is required.
            detachPooledPlayer(this);
        }

        void releasePlayer() {
            detachPooledPlayer(this);
            if (playerView != null) {
                try { playerView.setPlayer(null); } catch (Exception ignored) {}
            }
            player = null;
            playerView = null;
            boundPosition = -1;
            boundPost = null;
        }
    }

    private RequestListener''',
    "holder release without decoder destruction")

adapter_path.write_text(adapter)

main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()
main = replace_once(main, "        super.onCreate(savedInstanceState);", "        super.onCreate(savedInstanceState);\n        CrashReporter.install(this);", "install crash reporter")
main = replace_once(main, "pagerRecycler.setItemViewCacheSize(0);", "pagerRecycler.setItemViewCacheSize(2);", "restore bounded holder cache")
main_path.write_text(main)

factory_path = Path("app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java")
factory = factory_path.read_text().replace("RedditMedia/3.8.6", "RedditMedia/3.8.8")
factory_path.write_text(factory)

build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, "versionCode 40", "versionCode 42", "versionCode")
build = replace_once(build, 'versionName "3.8.6"', 'versionName "3.8.8"', "versionName")
build_path.write_text(build)

print("Applied v3.8.8 bounded reusable-player architecture hotfix")
