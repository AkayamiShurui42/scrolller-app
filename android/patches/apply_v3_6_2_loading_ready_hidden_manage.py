from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.2 patch target: {label}\n{old[:620]}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# MainActivity: media-ready gating, hidden-aware pagination, Saved/Hidden
# separation, and bulk Hidden cleanup controls.
# ---------------------------------------------------------------------------
main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

s = replace_required(
    s,
    '    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();\n    private String lastFullscreenPostId = "";',
    '    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();\n    private final Set<String> mediaReadyPostIds = new HashSet<>();\n    private final Set<String> mediaFailedPostIds = new HashSet<>();\n    private String lastFullscreenPostId = "";',
    'media readiness state')

# Only posts that will actually be visible may satisfy the feed batch target.
s = replace_required(
    s,
    '''                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (feedSeenPostIds.add(post.id)) collected.add(post);''',
    '''                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!feedSeenPostIds.add(post.id)) continue;
                    if (hiddenPosts.containsKey(post.id)) continue;
                    collected.add(post);''',
    'hidden-aware feed collection')

s = replace_required(
    s,
    '''            boolean needMoreThisBatch = random
                    ? (collected.size() < 45 && page < 2)
                    : collected.size() < 18;

            if (canContinue && (oldest || needMoreThisBatch)) {''',
    '''            boolean needMoreThisBatch = random
                    ? (collected.size() < 45 && page < 20)
                    : (collected.size() < 18 && page < 20);

            if (canContinue && (oldest || needMoreThisBatch)) {''',
    'continue through hidden-heavy feed pages')

# Search and user-profile page targets must also count visible posts, not items
# that replacePosts() will immediately discard.
s = replace_required(
    s,
    '''                    if (post == null || !matchesMedia(post)) continue;
                    if (searchScope.equals("subscribed")''',
    '''                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty() || hiddenPosts.containsKey(post.id)) continue;
                    if (searchScope.equals("subscribed")''',
    'hidden-aware search collection')
s = replace_required(
    s,
    '''            int maxPages = searchScope.equals("subscribed") ? 8 : 5;
            if (collected.size() < 30 && !next.isEmpty() && page + 1 < maxPages) {''',
    '''            int maxPages = 20;
            if (collected.size() < 45 && !next.isEmpty() && page + 1 < maxPages) {''',
    'deeper search pagination')

# This exact target occurs in the user profile collector after the search patch.
user_old = '''                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post != null && matchesMedia(post)) collected.add(post);
                }
            }
            String next = data != null ? data.optString("after", "") : "";
            if (collected.size() < 24 && !next.isEmpty() && page < 4) {'''
user_new = '''                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post != null && matchesMedia(post)
                            && post.id != null && !post.id.isEmpty()
                            && !hiddenPosts.containsKey(post.id)) collected.add(post);
                }
            }
            String next = data != null ? data.optString("after", "") : "";
            if (collected.size() < 45 && !next.isEmpty() && page < 20) {'''
s = replace_required(s, user_old, user_new, 'hidden-aware deeper user pagination')

# A replacement collection starts a fresh readiness set. Appends keep readiness
# for holders that remain alive in the current immutable collection.
s = replace_required(
    s,
    '''    private void replacePosts(List<RedditPost> items) {
        lastFullscreenPostId = "";''',
    '''    private void replacePosts(List<RedditPost> items) {
        lastFullscreenPostId = "";
        mediaReadyPostIds.clear();
        mediaFailedPostIds.clear();''',
    'reset media readiness on collection replacement')

# Mark only genuinely loaded, non-saved media. Broken/unready posts are not read.
s = replace_required(
    s,
    '''        if (previous != null && previous.id != null && !previous.id.isEmpty()
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            saveReadHideState();
        }''',
    '''        if (previous != null && previous.id != null && !previous.id.isEmpty()
                && mediaReadyPostIds.contains(previous.id)
                && !previous.saved
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            saveReadHideState();
        }''',
    'only ready unsaved posts become hidden')

# Hidden Saved overlap can exist from older builds; reconcile whenever Favorites
# Saved has been fetched from Reddit.
s = replace_required(
    s,
    '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        applyFavoriteOrdering(collected);''',
    '''    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        boolean hiddenChanged = false;
        for (RedditPost savedPost : collected) {
            if (savedPost != null && savedPost.id != null && hiddenPosts.remove(savedPost.id) != null) {
                hiddenChanged = true;
            }
        }
        if (hiddenChanged) saveReadHideState();
        applyFavoriteOrdering(collected);''',
    'Saved and Hidden separation reconciliation')

# Hidden view uses the Favorites sort button as a management entry point.
s = replace_required(
    s,
    '''    private void showSortSheet() {
        if (screen == Screen.FAVORITES) {
            showFavoriteSortSheet();
            return;
        }''',
    '''    private void showSortSheet() {
        if (screen == Screen.FAVORITES) {
            if (favoritesView.equals("hidden")) showHiddenManageSheet();
            else showFavoriteSortSheet();
            return;
        }''',
    'Hidden management route')

# Add Hidden cleanup sheet before the Favorites sort sheet.
anchor = '    private void showFavoriteSortSheet() {'
hidden_manage = '''    private void showHiddenManageSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Manage hidden posts");
        scroll.addView(body);

        Button all = sheetButton("Clear hidden list · restore all (" + hiddenPosts.size() + ")");
        Button images = sheetButton("Restore images / galleries");
        Button videos = sheetButton("Restore videos / GIFs");
        body.addView(all, sectionButtonParams());
        body.addView(images, sectionButtonParams());
        body.addView(videos, sectionButtonParams());

        all.setOnClickListener(v -> {
            dialog.dismiss();
            restoreHiddenGroup("all", "");
        });
        images.setOnClickListener(v -> {
            dialog.dismiss();
            restoreHiddenGroup("images", "");
        });
        videos.setOnClickListener(v -> {
            dialog.dismiss();
            restoreHiddenGroup("videos", "");
        });

        ArrayList<String> communities = new ArrayList<>();
        for (RedditPost post : hiddenPosts.values()) {
            if (post == null || post.subreddit == null || post.subreddit.isEmpty()) continue;
            boolean exists = false;
            for (String existing : communities) {
                if (existing.equalsIgnoreCase(post.subreddit)) { exists = true; break; }
            }
            if (!exists) communities.add(post.subreddit);
        }
        communities.sort(String.CASE_INSENSITIVE_ORDER);
        if (!communities.isEmpty()) {
            TextView title = sectionTitle("Restore by subreddit");
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(14);
            body.addView(title, tp);
            for (String community : communities) {
                Button b = sheetButton("r/" + community);
                body.addView(b, sectionButtonParams());
                b.setOnClickListener(v -> {
                    dialog.dismiss();
                    restoreHiddenGroup("subreddit", community);
                });
            }
        }
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void restoreHiddenGroup(String kind, String community) {
        ArrayList<String> removeIds = new ArrayList<>();
        for (RedditPost post : hiddenPosts.values()) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            boolean match;
            if (kind.equals("all")) {
                match = true;
            } else if (kind.equals("images")) {
                match = post.mediaKind == RedditPost.MediaKind.IMAGE
                        || post.mediaKind == RedditPost.MediaKind.GALLERY;
            } else if (kind.equals("videos")) {
                match = post.mediaKind == RedditPost.MediaKind.VIDEO
                        || post.mediaKind == RedditPost.MediaKind.GIF;
            } else {
                match = post.subreddit != null && post.subreddit.equalsIgnoreCase(community);
            }
            if (match) removeIds.add(post.id);
        }
        for (String id : removeIds) {
            hiddenPosts.remove(id);
            feedSeenPostIds.remove(id);
        }
        saveReadHideState();
        loadHiddenPostsView();
    }

'''
if anchor not in s:
    raise SystemExit('Missing v3.6.2 patch target: hidden manage insertion anchor')
s = s.replace(anchor, hidden_manage + anchor, 1)

# Chrome makes the management action obvious while browsing Hidden.
s = replace_required(
    s,
    '        sortButton.setText(screen == Screen.FAVORITES ? favoriteSortLabel() : label(sort));',
    '        sortButton.setText(screen == Screen.FAVORITES\n                ? (favoritesView.equals("hidden") ? "Manage" : favoriteSortLabel())\n                : label(sort));',
    'Hidden Manage button label')

# Adapter callbacks track whether media genuinely became usable.
s = replace_required(
    s,
    '''    @Override
    public void onToggleChrome() {
        toggleFullscreenChrome();
    }

    @Override
    public void onRestoreHidden(RedditPost post) {''',
    '''    @Override
    public void onToggleChrome() {
        toggleFullscreenChrome();
    }

    @Override
    public void onMediaReady(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        mediaFailedPostIds.remove(post.id);
        mediaReadyPostIds.add(post.id);
    }

    @Override
    public void onMediaFailed(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        if (!mediaReadyPostIds.contains(post.id)) mediaFailedPostIds.add(post.id);
    }

    @Override
    public void onRestoreHidden(RedditPost post) {''',
    'media ready/failed callbacks')

# Saving a post removes it from Hidden immediately. Unsave does not create a
# hidden/read record. Hidden library refreshes so categories stay disjoint.
s = replace_required(
    s,
    '''            if (result.ok) {
                post.saved = !post.saved;
                postAdapter.refreshPost(post);
            } else {''',
    '''            if (result.ok) {
                post.saved = !post.saved;
                if (post.saved && post.id != null && hiddenPosts.remove(post.id) != null) {
                    feedSeenPostIds.remove(post.id);
                    saveReadHideState();
                    if (showingHiddenLibrary()) {
                        loadHiddenPostsView();
                        return;
                    }
                }
                postAdapter.refreshPost(post);
            } else {''',
    'save removes Hidden overlap')

main_path.write_text(s)

# ---------------------------------------------------------------------------
# PostPagerAdapter: reliable chrome reveal from every media surface and report
# real media readiness/failure to MainActivity.
# ---------------------------------------------------------------------------
pager_path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
p = pager_path.read_text()

p = replace_required(
    p,
    'import android.view.Gravity;\nimport android.view.View;\nimport android.view.ViewGroup;',
    'import android.view.Gravity;\nimport android.view.MotionEvent;\nimport android.view.View;\nimport android.view.ViewGroup;',
    'MotionEvent import')
p = replace_required(
    p,
    'import androidx.media3.common.MediaItem;\nimport androidx.media3.exoplayer.ExoPlayer;',
    'import androidx.media3.common.MediaItem;\nimport androidx.media3.common.PlaybackException;\nimport androidx.media3.common.Player;\nimport androidx.media3.exoplayer.ExoPlayer;',
    'Media3 readiness imports')

p = replace_required(
    p,
    '        void onToggleChrome();\n        void onRestoreHidden(RedditPost post);',
    '        void onToggleChrome();\n        void onMediaReady(RedditPost post);\n        void onMediaFailed(RedditPost post);\n        void onRestoreHidden(RedditPost post);',
    'media readiness listener methods')

# Parent sees all touch events, including those consumed by PlayerView/ViewPager2.
# It reveals hidden chrome after child dispatch only when the gesture was a tap,
# preventing a child click listener from double-toggling it back off.
p = replace_required(
    p,
    '''    @NonNull
    @Override
    public PostHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout root = new FrameLayout(context);''',
    '''    @NonNull
    @Override
    public PostHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout root = new TapFrameLayout(context);''',
    'reliable fullscreen tap root')

# Video/GIF streams become markable only after ExoPlayer reaches READY.
p = replace_required(
    p,
    '''                player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
                player.setMediaItem(MediaItem.fromUri(post.videoUrl));
                player.setVolume(muted ? 0f : 1f);''',
    '''                player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
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
                player.setVolume(muted ? 0f : 1f);''',
    'video readiness callbacks')

p = replace_required(
    p,
    '.listener(loopingGifListener())\n                        .into(image);',
    '.listener(loopingGifListener(post))\n                        .into(image);',
    'direct GIF readiness listener')

p = replace_required(
    p,
    '                gallery.setAdapter(new GalleryAdapter(post.imageUrls));',
    '                gallery.setAdapter(new GalleryAdapter(post, post.imageUrls));',
    'gallery readiness post')

p = replace_required(
    p,
    '''            String url = !post.imageUrls.isEmpty() ? post.imageUrls.get(0) : post.posterUrl;
            Glide.with(image).load(url).fitCenter().into(image);''',
    '''            String url = !post.imageUrls.isEmpty() ? post.imageUrls.get(0) : post.posterUrl;
            Glide.with(image).load(url).fitCenter().listener(imageLoadListener(post)).into(image);''',
    'static image readiness listener')

# Replace GIF listener with post-aware success/failure callbacks.
p = replace_required(
    p,
    '''    private RequestListener<GifDrawable> loopingGifListener() {
        return new RequestListener<GifDrawable>() {''',
    '''    private RequestListener<GifDrawable> loopingGifListener(RedditPost post) {
        return new RequestListener<GifDrawable>() {''',
    'post-aware GIF listener signature')
p = replace_required(
    p,
    '''                boolean isFirstResource) {
                return false;
            }

            @Override
            public boolean onResourceReady(
                    GifDrawable resource,''',
    '''                boolean isFirstResource) {
                listener.onMediaFailed(post);
                return false;
            }

            @Override
            public boolean onResourceReady(
                    GifDrawable resource,''',
    'GIF load failure callback')
p = replace_required(
    p,
    '''                resource.setLoopCount(GifDrawable.LOOP_FOREVER);
                resource.start();
                return false;
            }
        };
    }

    private final class GalleryAdapter''',
    '''                resource.setLoopCount(GifDrawable.LOOP_FOREVER);
                resource.start();
                listener.onMediaReady(post);
                return false;
            }
        };
    }

    private RequestListener<Drawable> imageLoadListener(RedditPost post) {
        return new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(
                    @Nullable GlideException e,
                    Object model,
                    Target<Drawable> target,
                    boolean isFirstResource) {
                listener.onMediaFailed(post);
                return false;
            }

            @Override
            public boolean onResourceReady(
                    Drawable resource,
                    Object model,
                    Target<Drawable> target,
                    DataSource dataSource,
                    boolean isFirstResource) {
                listener.onMediaReady(post);
                return false;
            }
        };
    }

    private final class GalleryAdapter''',
    'image readiness listener')

# Gallery marks ready when its first page actually loads.
p = replace_required(
    p,
    '''    private final class GalleryAdapter extends RecyclerView.Adapter<GalleryHolder> {
        private final List<String> urls;
        GalleryAdapter(List<String> urls) { this.urls = urls; }''',
    '''    private final class GalleryAdapter extends RecyclerView.Adapter<GalleryHolder> {
        private final RedditPost post;
        private final List<String> urls;
        GalleryAdapter(RedditPost post, List<String> urls) {
            this.post = post;
            this.urls = urls;
        }''',
    'gallery post field')
p = replace_required(
    p,
    '''        public void onBindViewHolder(@NonNull GalleryHolder holder, int position) {
            Glide.with(holder.image).load(urls.get(position)).fitCenter().into(holder.image);
        }''',
    '''        public void onBindViewHolder(@NonNull GalleryHolder holder, int position) {
            if (position == 0) {
                Glide.with(holder.image).load(urls.get(position)).fitCenter()
                        .listener(imageLoadListener(post)).into(holder.image);
            } else {
                Glide.with(holder.image).load(urls.get(position)).fitCenter().into(holder.image);
            }
        }''',
    'gallery first-image readiness')

# Insert a FrameLayout that observes taps without stealing child gestures.
anchor = '    static final class GalleryHolder extends RecyclerView.ViewHolder {'
tap_class = '''    private final class TapFrameLayout extends FrameLayout {
        private float downX;
        private float downY;
        private long downAt;

        TapFrameLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                downAt = event.getEventTime();
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !chromeVisible) {
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                long elapsed = event.getEventTime() - downAt;
                if (dx <= dp(18) && dy <= dp(18) && elapsed <= 450) {
                    listener.onToggleChrome();
                }
            }
            return handled;
        }
    }

'''
if anchor not in p:
    raise SystemExit('Missing v3.6.2 patch target: tap class insertion anchor')
p = p.replace(anchor, tap_class + anchor, 1)

pager_path.write_text(p)
print('Applied v3.6.2 hidden-aware loading + media-ready marking + Favorites separation/manage + reliable overlay tap')
