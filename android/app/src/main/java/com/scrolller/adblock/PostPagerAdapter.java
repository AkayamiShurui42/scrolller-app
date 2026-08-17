package com.scrolller.adblock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PostPagerAdapter extends RecyclerView.Adapter<PostPagerAdapter.PostHolder> {
    public interface Listener {
        void onOpenSubreddit(String subreddit);
        void onOpenUser(String username);
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
    private int activePosition = 0;
    private boolean muted = true;
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
    }

    public void appendPosts(List<RedditPost> items) {
        if (items.isEmpty()) return;
        int start = posts.size();
        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
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
        for (ExoPlayer player : players.values()) player.setVolume(muted ? 0f : 1f);
    }

    public boolean isMuted() { return muted; }

    public void setSystemInsets(int topPx, int bottomPx) {
        if (topInsetPx == topPx && bottomInsetPx == bottomPx) return;
        topInsetPx = Math.max(0, topPx);
        bottomInsetPx = Math.max(0, bottomPx);
        notifyDataSetChanged();
    }

    public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : players.entrySet()) {
            boolean active = entry.getKey() == position;
            entry.getValue().setPlayWhenReady(active);
            if (!active) entry.getValue().pause();
        }
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
        FrameLayout root = new FrameLayout(context);
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
    public void onViewRecycled(@NonNull PostHolder holder) {
        holder.releasePlayer();
        super.onViewRecycled(holder);
    }

    @Override public int getItemCount() { return posts.size(); }

    final class PostHolder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        ExoPlayer player;
        int boundPosition = -1;

        PostHolder(FrameLayout root) {
            super(root);
            this.root = root;
        }

        void bind(RedditPost post, int position) {
            releasePlayer();
            boundPosition = position;
            root.removeAllViews();
            root.setBackgroundColor(Color.BLACK);
            addMedia(post, position);
            addTopMeta(post);
            addBottomInfo(post);
        }

        private void addMedia(RedditPost post, int position) {
            if (post.mediaKind == RedditPost.MediaKind.VIDEO && post.videoUrl != null && !post.videoUrl.isEmpty()) {
                PlayerView playerView = new PlayerView(context);
                playerView.setUseController(false);
                playerView.setBackgroundColor(Color.BLACK);
                root.addView(playerView, fullParams());

                player = new ExoPlayer.Builder(context).build();
                player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
                player.setMediaItem(MediaItem.fromUri(post.videoUrl));
                player.setVolume(muted ? 0f : 1f);
                player.setPlayWhenReady(position == activePosition);
                player.prepare();
                playerView.setPlayer(player);
                players.put(position, player);

                Button mute = pillButton(muted ? "Muted" : "Sound");
                FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(74), dp(36), Gravity.TOP | Gravity.END);
                mp.topMargin = topInsetPx + dp(102);
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

            if (post.mediaKind == RedditPost.MediaKind.GALLERY && post.imageUrls.size() > 1) {
                ViewPager2 gallery = new ViewPager2(context);
                gallery.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
                gallery.setAdapter(new GalleryAdapter(post.imageUrls));
                root.addView(gallery, fullParams());

                TextView badge = smallBadge(post.imageUrls.size() + " images");
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(32), Gravity.TOP | Gravity.END);
                bp.topMargin = topInsetPx + dp(102);
                bp.rightMargin = dp(10);
                root.addView(badge, bp);
                return;
            }

            ImageView image = new ImageView(context);
            image.setBackgroundColor(Color.BLACK);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(image, fullParams());
            String url = !post.imageUrls.isEmpty() ? post.imageUrls.get(0) : post.posterUrl;
            Glide.with(image).load(url).fitCenter().into(image);

            if (post.mediaKind == RedditPost.MediaKind.EXTERNAL) {
                Button open = pillButton("Open media");
                open.setTextColor(Color.BLACK);
                open.setBackground(rounded(Color.WHITE, 999));
                FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.CENTER);
                root.addView(open, op);
                open.setOnClickListener(v -> listener.onOpenExternal(post));
            }
        }

        private void addTopMeta(RedditPost post) {
            LinearLayout meta = new LinearLayout(context);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            meta.setGravity(Gravity.CENTER_VERTICAL);
            meta.setPadding(dp(10), topInsetPx + dp(98), dp(10), dp(8));
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
            author.setOnClickListener(v -> listener.onOpenUser(post.author));

            if (post.nsfw) {
                TextView nsfw = smallBadge("NSFW");
                nsfw.setTextColor(0xFFFFD08A);
                meta.addView(nsfw, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
            }

            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, topInsetPx + dp(150), Gravity.TOP);
            root.addView(meta, p);
        }

        private void addBottomInfo(RedditPost post) {
            LinearLayout bottom = new LinearLayout(context);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setGravity(Gravity.BOTTOM);
            bottom.setPadding(dp(11), dp(70), dp(11), bottomInsetPx + dp(70));
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

            Button save = pillButton(post.saved ? "★ Saved" : "☆ Save");
            if (post.saved) {
                save.setTextColor(Color.BLACK);
                save.setBackground(rounded(0xFFF0F0F0, 999));
            }
            actions.addView(save, actionParams());
            save.setOnClickListener(v -> listener.onSave(post));

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

        void releasePlayer() {
            if (boundPosition >= 0) players.remove(boundPosition);
            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
            boundPosition = -1;
        }
    }

    private final class GalleryAdapter extends RecyclerView.Adapter<GalleryHolder> {
        private final List<String> urls;
        GalleryAdapter(List<String> urls) { this.urls = urls; }

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
            Glide.with(holder.image).load(urls.get(position)).fitCenter().into(holder.image);
        }

        @Override public int getItemCount() { return urls.size(); }
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
}
