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

# Persistent fair-round state for multi-subreddit presets. One subreddit may
# contribute at most one post before every other selected subreddit is attempted.
main = replace_once(
    main,
    '''    private final LinkedHashMap<String, ArrayList<String>> subredditPresets = new LinkedHashMap<>();
    private long fullscreenVisitStartedAtMs = 0L;''',
    '''    private final LinkedHashMap<String, ArrayList<String>> subredditPresets = new LinkedHashMap<>();
    private final ArrayList<String> multiSubredditRound = new ArrayList<>();
    private final HashSet<String> multiSubredditSeenPostIds = new HashSet<>();
    private int multiSubredditRoundIndex = 0;
    private int multiSubredditGeneration = 0;
    private int multiSubredditRequestsThisLoad = 0;
    private long fullscreenVisitStartedAtMs = 0L;''',
    "multi fair-round fields",
)

# Route multi-preset loading through the fair scheduler before generic feed loading.
main = replace_once(
    main,
    '''    private void loadFeed(boolean reset) {
        if (!engine.isReady()) return;
        if (loading && !reset) return;

        if (reset) {''',
    '''    private void loadFeed(boolean reset) {
        if (!engine.isReady()) return;
        if (loading && !reset) return;

        if (screen == Screen.HOME && context.equals("multi")) {
            loadMultiSubredditFair(reset);
            return;
        }

        if (reset) {''',
    "multi loader dispatch",
)

# Do not replace an already-visible first page when the background reservoir finishes.
# replacePosts() releases every player; appendUnique() preserves the mounted page/player.
main = replace_once(
    main,
    '''        if (reset) replacePosts(collected);
        else appendUnique(collected);''',
    '''        if (reset && postAdapter.getItemCount() == 0) replacePosts(collected);
        else appendUnique(collected);''',
    "stable feed finalizer",
)

# Install the fair scheduler immediately before the old combined multi route helper.
anchor = '''    private String multiListingPath(String cursor) {'''
helpers = '''    private void loadMultiSubredditFair(boolean reset) {
        if (!engine.isReady()) return;
        if (loading && !reset) return;

        if (reset) {
            feedGeneration++;
            multiSubredditGeneration++;
            loading = false;
            after = "";
            multiSubredditRound.clear();
            multiSubredditRoundIndex = 0;
            multiSubredditSeenPostIds.clear();
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            deferredAppends.clear();
            deferredAppendScheduled = false;
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Preparing preset round…", true);
        }

        if (multiSubredditRoundIndex >= multiSubredditRound.size()) {
            prepareMultiSubredditRound();
        }
        if (multiSubredditRound.isEmpty()) {
            loading = false;
            setStatus("This preset has no usable subreddits.", false);
            return;
        }

        final int feedGen = feedGeneration;
        final int multiGen = multiSubredditGeneration;
        multiSubredditRequestsThisLoad = 0;
        loading = true;
        fetchMultiSubredditRoundNext(feedGen, multiGen);
    }

    private void prepareMultiSubredditRound() {
        multiSubredditRound.clear();
        multiSubredditRoundIndex = 0;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String community : multiCommunities()) {
            String clean = cleanSubredditName(community);
            if (!clean.isEmpty()) unique.add(clean);
        }
        multiSubredditRound.addAll(unique);
        Collections.shuffle(multiSubredditRound);
    }

    private boolean multiSubredditContextValid(int feedGen, int multiGen) {
        return feedGen == feedGeneration
                && multiGen == multiSubredditGeneration
                && screen == Screen.HOME
                && context.equals("multi");
    }

    private void fetchMultiSubredditRoundNext(int feedGen, int multiGen) {
        if (!multiSubredditContextValid(feedGen, multiGen)) return;

        if (multiSubredditRoundIndex >= multiSubredditRound.size()) {
            loading = false;
            after = "multi-round-complete";
            if (postAdapter.getItemCount() == 0) {
                setStatus("No accessible NSFW media matched this preset round.", false);
            } else {
                hideStatus();
            }
            updateChrome();
            restorePendingPosition();
            return;
        }

        // Keep enough posts buffered for smooth swiping without making 200 network
        // requests at once. The next chunk resumes at the next subreddit, so no
        // subreddit can repeat until the entire preset round has been attempted.
        if (multiSubredditRequestsThisLoad >= 12 && postAdapter.getItemCount() > 0) {
            loading = false;
            after = "multi-round-continue";
            hideStatus();
            updateChrome();
            restorePendingPosition();
            return;
        }

        final String target = multiSubredditRound.get(multiSubredditRoundIndex++);
        multiSubredditRequestsThisLoad++;
        engine.get(singlePresetListingPath(target), result -> {
            if (!multiSubredditContextValid(feedGen, multiGen)) return;

            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                ArrayList<RedditPost> candidates = new ArrayList<>();
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !post.nsfw || !matchesMedia(post)) continue;
                        if (post.id == null || post.id.isEmpty()) continue;
                        if (hiddenPosts.containsKey(post.id)
                                || isSavedForUnread(post)
                                || isContentBlocked(post)) continue;
                        String key = canonicalPostKey(post);
                        if (key.isEmpty() || multiSubredditSeenPostIds.contains(key)) continue;
                        candidates.add(post);
                    }
                }

                if (!candidates.isEmpty()) {
                    if (sort.equals("random")) Collections.shuffle(candidates);
                    RedditPost selected = candidates.get(0);
                    String key = canonicalPostKey(selected);
                    if (!key.isEmpty()) {
                        multiSubredditSeenPostIds.add(key);
                        feedSeenPostIds.add(key);
                    }
                    ArrayList<RedditPost> one = new ArrayList<>();
                    one.add(selected);
                    appendUnique(one);
                    hideStatus();
                }
            }

            root.postDelayed(
                    () -> fetchMultiSubredditRoundNext(feedGen, multiGen),
                    180L);
        });
    }

'''
if helpers not in main:
    if main.count(anchor) != 1:
        raise SystemExit(f"multi helper anchor: expected 1, found {main.count(anchor)}")
    main = main.replace(anchor, helpers + anchor, 1)

main_path.write_text(main)


pager_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
pager = pager_path.read_text()

# RedGIF resolution used to call bind() on the active holder. bind() releases the
# current player and clears/rebuilds the entire page, producing the visible freeze
# and video restart. Attach the resolved player in place instead.
pager = replace_once(
    pager,
    '''                        if (post.videoUrl.equals(unresolved)) post.videoUrl = url;
                        if (boundPosition == position && position >= 0 && position < posts.size() && posts.get(position) == post) bind(post, position);''',
    '''                        if (post.videoUrl.equals(unresolved)) post.videoUrl = url;
                        if (boundPosition == position
                                && position >= 0
                                && position < posts.size()
                                && posts.get(position) == post) {
                            attachResolvedRedgifsVideo(post, position, url);
                        }''',
    "in-place RedGIF resolution",
)

pager_anchor = '''        private void addTopMeta(RedditPost post) {'''
pager_helper = '''        private void attachResolvedRedgifsVideo(RedditPost post, int position, String url) {
            if (player != null || playerView != null || url == null || url.isEmpty()) return;
            if (boundPosition != position || position < 0 || position >= posts.size()
                    || posts.get(position) != post) return;

            PlayerView nextView = (PlayerView) android.view.LayoutInflater.from(context)
                    .inflate(R.layout.view_texture_player, root, false);
            nextView.setKeepContentOnPlayerReset(true);
            nextView.setShutterBackgroundColor(Color.TRANSPARENT);
            nextView.setBackgroundColor(Color.TRANSPARENT);

            // The poster stays mounted as the background. Put the player above it
            // but below metadata/action overlays so the holder itself never rebinds.
            int mediaIndex = Math.min(1, root.getChildCount());
            root.addView(nextView, mediaIndex, fullParams());
            playerView = nextView;

            try {
                player = HighQualityPlayerFactory.create(context, url);
                player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
                player.setMediaItem(MediaItem.fromUri(url));
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
                nextView.setPlayer(player);
                players.put(position, player);
                nextView.setOnClickListener(null);
            } catch (RuntimeException playerError) {
                try { nextView.setPlayer(null); } catch (Exception ignored) {}
                try { root.removeView(nextView); } catch (Exception ignored) {}
                if (player != null) {
                    try { player.release(); } catch (Exception ignored) {}
                }
                players.remove(position);
                player = null;
                playerView = null;
                listener.onMediaFailed(post);
                return;
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

'''
if pager_helper not in pager:
    if pager.count(pager_anchor) != 1:
        raise SystemExit(f"pager helper anchor: expected 1, found {pager.count(pager_anchor)}")
    pager = pager.replace(pager_anchor, pager_helper + pager_anchor, 1)

pager_path.write_text(pager)


build_path = Path("app/build.gradle")
build = build_path.read_text()
if 'versionCode 38' not in build:
    build = replace_once(build, 'versionCode 37', 'versionCode 38', 'versionCode')
if 'versionName "3.8.4"' not in build:
    build = replace_once(build, 'versionName "3.8.3"', 'versionName "3.8.4"', 'versionName')
build_path.write_text(build)

print("Applied v3.8.4 fair-preset/media-continuity hotfix")
