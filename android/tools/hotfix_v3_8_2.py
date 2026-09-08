from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()
main = replace_once(
    main,
    "pager.setOffscreenPageLimit(3);",
    "pager.setOffscreenPageLimit(1);",
    "pager offscreen limit",
)
main = replace_once(
    main,
    "pagerRecycler.setItemViewCacheSize(8);",
    "pagerRecycler.setItemViewCacheSize(4);",
    "pager cache size",
)
main = replace_once(
    main,
    'String path = "/r/" + joined + "/" + remoteSort + ".json?limit=50&raw_json=1&show=all";',
    'String path = "/r/" + joined + "/" + remoteSort + ".json?limit=100&raw_json=1&show=all";',
    "multi subreddit page size",
)
main = replace_once(
    main,
    '''            boolean topAll = sort.equals("top") && topTime.equals("all");
            boolean random = sort.equals("random");
            boolean oldest = sort.equals("oldest");
            int target = oldest ? 350 : random ? 120 : 30;
            int pageLimit = topAll ? 12 : oldest ? 5 : random ? 3 : 4;''',
    '''            boolean topAll = sort.equals("top") && topTime.equals("all");
            boolean random = sort.equals("random");
            boolean oldest = sort.equals("oldest");
            boolean multi = context.equals("multi");
            int target = multi ? 100 : oldest ? 350 : random ? 120 : 30;
            int pageLimit = multi ? 1 : topAll ? 12 : oldest ? 5 : random ? 3 : 4;''',
    "multi feed bounded paging",
)
main_path.write_text(main)


pager_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
pager = pager_path.read_text()
pager = replace_once(
    pager,
    '''    public void setMuted(boolean muted) {
        this.muted = muted;
        for (ExoPlayer player : players.values()) player.setVolume(muted ? 0f : 1f);
    }''',
    '''    public void setMuted(boolean muted) {
        this.muted = muted;
        for (ExoPlayer player : new ArrayList<>(players.values())) {
            try { player.setVolume(muted ? 0f : 1f); } catch (RuntimeException ignored) {}
        }
    }''',
    "safe mute iteration",
)
pager = replace_once(
    pager,
    '''public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : players.entrySet()) {
            boolean active = entry.getKey() == position;
            entry.getValue().setPlayWhenReady(active);
            if (!active) entry.getValue().pause();
        }
        warmAdjacentMedia(position);
    }''',
    '''public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : new ArrayList<>(players.entrySet())) {
            try {
                boolean active = entry.getKey() == position;
                entry.getValue().setPlayWhenReady(active);
                if (!active) entry.getValue().pause();
            } catch (RuntimeException ignored) {}
        }
        warmAdjacentMedia(position);
    }''',
    "safe active-player iteration",
)
pager = replace_once(
    pager,
    'if (boundPosition == position) bind(post, position);',
    'if (boundPosition == position && position >= 0 && position < posts.size() && posts.get(position) == post) bind(post, position);',
    "stale RedGIF callback guard",
)
pager = replace_once(
    pager,
    '''                player = HighQualityPlayerFactory.create(context, post.videoUrl);
                player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
                player.setMediaItem(MediaItem.fromUri(post.videoUrl));
                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) listener.onMediaReady(post);
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        listener.onMediaFailed(post);
                    }
                });
                player.setVolume(muted ? 0f : 1f);
                player.setPlayWhenReady(position == activePosition);
                player.prepare();
                playerView.setPlayer(player);
                players.put(position, player);
                playerView.setOnClickListener(null);''',
    '''                try {
                    player = HighQualityPlayerFactory.create(context, post.videoUrl);
                    player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
                    player.setMediaItem(MediaItem.fromUri(post.videoUrl));
                    player.addListener(new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            if (state == Player.STATE_READY) listener.onMediaReady(post);
                        }

                        @Override
                        public void onPlayerError(PlaybackException error) {
                            listener.onMediaFailed(post);
                        }
                    });
                    player.setVolume(muted ? 0f : 1f);
                    player.setPlayWhenReady(position == activePosition);
                    player.prepare();
                    playerView.setPlayer(player);
                    players.put(position, player);
                    playerView.setOnClickListener(null);
                } catch (RuntimeException playerError) {
                    if (playerView != null) {
                        try { playerView.setPlayer(null); } catch (Exception ignored) {}
                        try { root.removeView(playerView); } catch (Exception ignored) {}
                    }
                    if (player != null) {
                        try { player.release(); } catch (Exception ignored) {}
                    }
                    players.remove(position);
                    player = null;
                    playerView = null;
                    listener.onMediaFailed(post);
                    return;
                }''',
    "safe player creation",
)
pager = replace_once(
    pager,
    '''private void warmAdjacentMedia(int center) {
        int start = Math.max(0, center - 3);
        int end = Math.min(posts.size() - 1, center + 3);''',
    '''private void warmAdjacentMedia(int center) {
        int start = Math.max(0, center - 2);
        int end = Math.min(posts.size() - 1, center + 2);''',
    "lighter adjacent preload",
)
pager = replace_once(
    pager,
    'Glide.with(context).load(preview).preload();',
    'Glide.with(context.getApplicationContext()).load(preview).preload();',
    "application-context preload",
)
pager_path.write_text(pager)


build_path = Path("app/build.gradle")
build = build_path.read_text()
if 'versionCode 36' not in build:
    build = replace_once(build, 'versionCode 35', 'versionCode 36', 'versionCode')
if 'versionName "3.8.2"' not in build:
    build = replace_once(build, 'versionName "3.8.1"', 'versionName "3.8.2"', 'versionName')
build_path.write_text(build)

print("Applied v3.8.2 429/multi-feed/swipe-stability hotfix")
