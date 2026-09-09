package com.scrolller.adblock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PostPagerAdapter extends RecyclerView.Adapter<PostPagerAdapter.PostHolder> {
    public interface Listener {
        void onOpenSubreddit(String subreddit);
        void onOpenUser(RedditPost post);
        void onToggleChrome();
        void onMediaReady(RedditPost post);
        void onMediaFailed(RedditPost post);
        void onRestoreHidden(RedditPost post);
        void onSave(RedditPost post);
        void onComments(RedditPost post);
        void onShare(RedditPost post);
        void onOpenExternal(RedditPost post);
        void onMutedChanged(boolean muted);
    }

    private final Context context;
    private final Listener listener;
    private final ArrayList<RedditPost> posts = new ArrayList<>();
    private final Map<Integer, ExoPlayer> players = new HashMap<>();
    private final ArrayList<PostHolder> attachedHolders = new ArrayList<>();
    private final Set<String> warmingRedgifs = new HashSet<>();
    private int activePosition = 0;
    private boolean muted = true;
    private boolean chromeVisible = false;
    private boolean hiddenMode = false;
    private int topInsetPx = 0;
    private int bottomInsetPx = 0;

    public PostPagerAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setPosts(List<RedditPost> items) {
        releaseAll();
        posts.clear();
        posts.addAll(items);
        activePosition = 0;
        notifyDataSetChanged();
        warmAdjacentMedia(activePosition);
    }

    public void appendPosts(List<RedditPost> items) {
        if (items.isEmpty()) return;
        int start = posts.size();
        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
        warmAdjacentMedia(activePosition);
    }

    public List<RedditPost> getPosts() { return posts; }

    public RedditPost getPost(int position) {
        return position >= 0 && position < posts.size() ? posts.get(position) : null;
    }

    public void refreshPost(RedditPost post) {
        int i = posts.indexOf(post);
        if (i >= 0) notifyItemChanged(i);
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        for (ExoPlayer player : new ArrayList<>(players.values())) {
            try { player.setVolume(muted ? 0f : 1f); } catch (RuntimeException ignored) {}
        }
    }

    public boolean isMuted() { return muted; }

    public void setHiddenMode(boolean hiddenMode) {
        if (this.hiddenMode == hiddenMode) return;
        this.hiddenMode = hiddenMode;
        notifyDataSetChanged();
    }

    public void removePostById(String id) {
        if (id == null || id.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < posts.size(); i++) {
            if (id.equals(posts.get(i).id)) { index = i; break; }
        }
        if (index < 0) return;
        releaseAll();
        posts.remove(index);
        activePosition = Math.max(0, Math.min(activePosition, posts.size() - 1));
        notifyDataSetChanged();
    }

    public void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        for (PostHolder holder : new ArrayList<>(attachedHolders)) {
            holder.applyChromeVisibility();
        }
    }

    public void setSystemInsets(int topPx, int bottomPx) {
        if (topInsetPx == topPx && bottomInsetPx == bottomPx) return;
        topInsetPx = Math.max(0, topPx);
        bottomInsetPx = Math.max(0, bottomPx);
        notifyDataSetChanged();
    }

public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : new ArrayList<>(players.entrySet())) {
            try {
                boolean active = entry.getKey() == position;
                entry.getValue().setPlayWhenReady(active);
                if (!active) entry.getValue().pause();
            } catch (RuntimeException ignored) {}
        }
        warmAdjacentMedia(position);
    }



    public void releaseAll() {
        for (ExoPlayer player : players.values()) {
            try { player.release(); } catch (Exception ignored) {}
        }
        players.clear();
    }

    @Override
    public long getItemId(int position) {
        String id = posts.get(position).id;
        return id != null ? id.hashCode() : position;
    }

    @NonNull
    @Override
    public PostHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout root = new TapFrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return new PostHolder(root);
    }

    @Override
    public void onBindViewHolder(@NonNull PostHolder holder, int position) {
        holder.bind(posts.get(position), position);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull PostHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (!attachedHolders.contains(holder)) attachedHolders.add(holder);
        holder.applyChromeVisibility();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull PostHolder holder) {
        attachedHolders.remove(holder);
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull PostHolder holder) {
        attachedHolders.remove(holder);
        holder.releasePlayer();
        super.onViewRecycled(holder);
    }

    @Override public int getItemCount() { return posts.size(); }

    final class PostHolder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        ExoPlayer player;
        PlayerView playerView;
        View topMeta;
        View bottomInfo;
        View mediaControl;
        int boundPosition = -1;

        PostHolder(FrameLayout root) {
            super(root);
            this.root = root;
        }

        void bind(RedditPost post, int position) {
            releasePlayer();
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
        }

        private void addMedia(RedditPost post, int position) {
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
                        if (boundPosition == position
                                && position >= 0
                                && position < posts.size()
                                && posts.get(position) == post) {
                            attachResolvedRedgifsVideo(post, position, url);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        listener.onMediaFailed(post);
                    }
                });
                return;
            }

            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {
                if (post.posterUrl != null && !post.posterUrl.isEmpty()) {
                    ImageView videoPoster = new ImageView(context);
                    videoPoster.setBackgroundColor(Color.BLACK);
                    videoPoster.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    root.addView(videoPoster, fullParams());
                    Glide.with(videoPoster).load(post.posterUrl).fitCenter().into(videoPoster);
                }

                playerView = (PlayerView) android.view.LayoutInflater.from(context)
                        .inflate(R.layout.view_texture_player, root, false);
                playerView.setKeepContentOnPlayerReset(true);
                playerView.setShutterBackgroundColor(Color.TRANSPARENT);
                playerView.setBackgroundColor(Color.TRANSPARENT);
                root.addView(playerView, fullParams());

                try {
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
                return;
            }

            if (post.mediaKind == RedditPost.MediaKind.GIF && !post.imageUrls.isEmpty()) {
                ImageView image = new ImageView(context);
                image.setBackgroundColor(Color.BLACK);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                root.addView(image, fullParams());
                String url = post.imageUrls.get(0);
                Glide.with(image)
                        .asGif()
                        .load(url)
                        .fitCenter()
                        .listener(loopingGifListener(post))
                        .into(image);
                image.setOnClickListener(null);

                Button playPause = pillButton("Pause");
                mediaControl = playPause;
                FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                        dp(74), dp(36), Gravity.TOP | Gravity.END);
                gp.topMargin = topInsetPx + dp(12);
                gp.rightMargin = dp(10);
                root.addView(playPause, gp);
                playPause.setOnClickListener(v -> {
                    Drawable drawable = image.getDrawable();
                    if (drawable instanceof Animatable) {
                        Animatable anim = (Animatable) drawable;
                        if (anim.isRunning()) {
                            anim.stop();
                            playPause.setText("Play");
                        } else {
                            anim.start();
                            playPause.setText("Pause");
                        }
                    }
                });
                return;
            }

            if (post.mediaKind == RedditPost.MediaKind.GALLERY && post.imageUrls.size() > 1) {
                ViewPager2 gallery = new ViewPager2(context);
                gallery.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
                gallery.setAdapter(new GalleryAdapter(post, post.imageUrls));
                root.addView(gallery, fullParams());

                TextView badge = smallBadge(post.imageUrls.size() + " images");
                mediaControl = badge;
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(32), Gravity.TOP | Gravity.END);
                bp.topMargin = topInsetPx + dp(12);
                bp.rightMargin = dp(10);
                root.addView(badge, bp);
                return;
            }

            ImageView image = new ImageView(context);
            image.setBackgroundColor(Color.BLACK);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(image, fullParams());
            String url = !post.imageUrls.isEmpty() ? post.imageUrls.get(0) : post.posterUrl;
            Glide.with(image).load(url).fitCenter().listener(imageLoadListener(post)).into(image);
            image.setOnClickListener(null);
        }

        private void attachResolvedRedgifsVideo(RedditPost post, int position, String url) {
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

        private void addTopMeta(RedditPost post) {
            LinearLayout meta = new LinearLayout(context);
            topMeta = meta;
            meta.setOrientation(LinearLayout.HORIZONTAL);
            meta.setGravity(Gravity.CENTER_VERTICAL);
            meta.setPadding(dp(10), topInsetPx + dp(8), dp(10), dp(8));
            meta.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xD9000000, 0x78000000, 0x00000000}));

            Button sub = pillButton("r/" + post.subreddit);
            sub.setTextSize(12);
            meta.addView(sub, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            sub.setOnClickListener(v -> listener.onOpenSubreddit(post.subreddit));

            Button author = pillButton("u/" + post.author);
            author.setTextSize(11);
            author.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            author.setBackgroundColor(Color.TRANSPARENT);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(36), 1f);
            ap.leftMargin = dp(3);
            meta.addView(author, ap);
            author.setOnClickListener(v -> listener.onOpenUser(post));

            if (post.nsfw) {
                TextView nsfw = smallBadge("NSFW");
                nsfw.setTextColor(0xFFFFD08A);
                meta.addView(nsfw, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
            }

            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, topInsetPx + dp(64), Gravity.TOP);
            root.addView(meta, p);
        }

        private void addBottomInfo(RedditPost post) {
            LinearLayout bottom = new LinearLayout(context);
            bottomInfo = bottom;
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setGravity(Gravity.BOTTOM);
            bottom.setPadding(dp(11), dp(70), dp(11), bottomInsetPx + dp(18));
            bottom.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x66000000, 0xD9000000, 0xFF000000}));

            TextView title = new TextView(context);
            title.setText(post.title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(15);
            title.setMaxLines(3);
            title.setGravity(Gravity.START);
            title.setShadowLayer(6f, 0, 1, Color.BLACK);
            bottom.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            HorizontalScrollView actionScroll = new HorizontalScrollView(context);
            actionScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setPadding(0, dp(8), 0, 0);
            actionScroll.addView(actions, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (hiddenMode) {
                Button restore = pillButton("↶ Restore");
                restore.setTextColor(Color.BLACK);
                restore.setBackground(rounded(0xFFF0F0F0, 999));
                actions.addView(restore, actionParams());
                restore.setOnClickListener(v -> listener.onRestoreHidden(post));
            } else {
                Button save = pillButton(post.saved ? "★ Saved" : "☆ Save");
                if (post.saved) {
                    save.setTextColor(Color.BLACK);
                    save.setBackground(rounded(0xFFF0F0F0, 999));
                }
                actions.addView(save, actionParams());
                save.setOnClickListener(v -> listener.onSave(post));
            }

            Button comments = pillButton("◌ " + compact(post.comments));
            actions.addView(comments, actionParams());
            comments.setOnClickListener(v -> listener.onComments(post));

            Button share = pillButton("↗ Share");
            actions.addView(share, actionParams());
            share.setOnClickListener(v -> listener.onShare(post));

            TextView score = smallBadge("▲ " + compact(post.score));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            sp.leftMargin = dp(7);
            actions.addView(score, sp);

            bottom.addView(actionScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            root.addView(bottom, p);
        }

        void applyChromeVisibility() {
            int visibility = chromeVisible ? View.VISIBLE : View.GONE;
            if (topMeta != null) topMeta.setVisibility(visibility);
            if (bottomInfo != null) bottomInfo.setVisibility(visibility);
            if (mediaControl != null) mediaControl.setVisibility(visibility);
            if (playerView != null) {
                if (chromeVisible) playerView.showController();
                else playerView.hideController();
            }
        }

        void releasePlayer() {
            if (boundPosition >= 0) players.remove(boundPosition);
            if (playerView != null) {
                try { playerView.setPlayer(null); } catch (Exception ignored) {}
            }
            if (player != null) {
                try { player.setVideoTextureView(null); } catch (Exception ignored) {}
                try { player.stop(); } catch (Exception ignored) {}
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
            playerView = null;
            boundPosition = -1;
        }
    }

    private RequestListener<GifDrawable> loopingGifListener(RedditPost post) {
        return new RequestListener<GifDrawable>() {
            @Override
            public boolean onLoadFailed(
                    @Nullable GlideException e,
                    Object model,
                    Target<GifDrawable> target,
                    boolean isFirstResource) {
                listener.onMediaFailed(post);
                return false;
            }

            @Override
            public boolean onResourceReady(
                    GifDrawable resource,
                    Object model,
                    Target<GifDrawable> target,
                    DataSource dataSource,
                    boolean isFirstResource) {
                resource.setLoopCount(GifDrawable.LOOP_FOREVER);
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

    private final class GalleryAdapter extends RecyclerView.Adapter<GalleryHolder> {
        private final RedditPost post;
        private final List<String> urls;
        GalleryAdapter(RedditPost post, List<String> urls) {
            this.post = post;
            this.urls = urls;
        }

        @NonNull
        @Override
        public GalleryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView image = new ImageView(context);
            image.setBackgroundColor(Color.BLACK);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new GalleryHolder(image);
        }

        @Override
        public void onBindViewHolder(@NonNull GalleryHolder holder, int position) {
            if (position == 0) {
                Glide.with(holder.image).load(urls.get(position)).fitCenter()
                        .listener(imageLoadListener(post)).into(holder.image);
            } else {
                Glide.with(holder.image).load(urls.get(position)).fitCenter().into(holder.image);
            }
        }

        @Override public int getItemCount() { return urls.size(); }
    }

    private final class TapFrameLayout extends FrameLayout {
        private float downX;
        private float downY;
        private long downAt;
        private boolean downOnActionControl;

        TapFrameLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                downAt = event.getEventTime();
                downOnActionControl = isActionControlAt(event.getRawX(), event.getRawY());
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !downOnActionControl) {
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                long elapsed = event.getEventTime() - downAt;
                if (dx <= dp(24) && dy <= dp(24) && elapsed <= 600) {
                    // Overlay views remain attached/preloaded; this only toggles
                    // their visibility. The same tap path hides and reveals them.
                    listener.onToggleChrome();
                }
            }
            return handled;
        }

        private boolean isActionControlAt(float rawX, float rawY) {
            return hitActionControl(this, Math.round(rawX), Math.round(rawY));
        }

        private boolean hitActionControl(View view, int rawX, int rawY) {
            if (view == null || view.getVisibility() != View.VISIBLE) return false;

            // Protect app action buttons and Media3 controller buttons from the
            // background overlay toggle. Their normal click behavior wins.
            if (view instanceof Button || view instanceof android.widget.ImageButton) {
                Rect bounds = new Rect();
                return view.getGlobalVisibleRect(bounds) && bounds.contains(rawX, rawY);
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    if (hitActionControl(group.getChildAt(i), rawX, rawY)) return true;
                }
            }
            return false;
        }
    }

    static final class GalleryHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        GalleryHolder(ImageView image) { super(image); this.image = image; }
    }

    private FrameLayout.LayoutParams fullParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        p.rightMargin = dp(7);
        return p;
    }

    private Button pillButton(String text) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(11), 0, dp(11), 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setBackground(rounded(0xB3181818, 999));
        return button;
    }

    private TextView smallBadge(String text) {
        TextView badge = new TextView(context);
        badge.setText(text);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), 0, dp(9), 0);
        badge.setBackground(rounded(0xB3000000, 999));
        return badge;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String compact(int n) {
        if (Math.abs(n) >= 1_000_000) return String.format("%.1fm", n / 1_000_000f);
        if (Math.abs(n) >= 1_000) return String.format("%.1fk", n / 1_000f);
        return String.valueOf(n);
    }


private void warmAdjacentMedia(int center) {
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
    }
}
