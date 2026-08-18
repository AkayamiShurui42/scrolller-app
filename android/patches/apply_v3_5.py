from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.5 patch target: {label}\n{old[:300]}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# MainActivity: combine Video/GIF filtering and add tap-to-toggle fullscreen UI.
# Runs after v3.3 + v3.4 + v3.4-stream patches.
# ---------------------------------------------------------------------------
main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

s = replace_required(
    s,
    '    private boolean initialized;\n    private boolean muted = true;\n    private int systemTopPx;',
    '    private boolean initialized;\n    private boolean muted = true;\n    private boolean fullscreenChromeVisible = false;\n    private int systemTopPx;',
    'fullscreen chrome state')

s = replace_required(
    s,
    '                postAdapter.setActivePosition(layoutMode.equals("fullscreen") ? position : -1);\n                if (screen == Screen.HOME && !loading && !after.isEmpty()',
    '                postAdapter.setActivePosition(layoutMode.equals("fullscreen") ? position : -1);\n                if (layoutMode.equals("fullscreen") && fullscreenChromeVisible) {\n                    setFullscreenChrome(false);\n                }\n                if (screen == Screen.HOME && !loading && !after.isEmpty()',
    'auto-hide chrome after fullscreen page change')

old_matches = '''    private boolean matchesMedia(RedditPost post) {
        if (media.equals("all")) return true;
        if (media.equals("image")) {
            return post.mediaKind == RedditPost.MediaKind.IMAGE
                    || post.mediaKind == RedditPost.MediaKind.GALLERY;
        }
        if (media.equals("video")) {
            return post.mediaKind == RedditPost.MediaKind.VIDEO;
        }
        if (media.equals("gif")) {
            return post.mediaKind == RedditPost.MediaKind.GIF;
        }
        return false;
    }
'''
new_matches = '''    private boolean matchesMedia(RedditPost post) {
        if (media.equals("all")) return true;
        if (media.equals("image")) {
            return post.mediaKind == RedditPost.MediaKind.IMAGE
                    || post.mediaKind == RedditPost.MediaKind.GALLERY;
        }
        if (media.equals("video")) {
            return post.mediaKind == RedditPost.MediaKind.VIDEO
                    || post.mediaKind == RedditPost.MediaKind.GIF;
        }
        return false;
    }
'''
s = replace_required(s, old_matches, new_matches, 'combined video GIF filter matcher')

s = replace_required(
    s,
    '                {"all", "All media"},\n                {"image", "Images"},\n                {"video", "Videos"},\n                {"gif", "GIFs"}',
    '                {"all", "All media"},\n                {"image", "Images"},\n                {"video", "Videos / GIFs"}',
    'combined media filter menu')

s = replace_required(
    s,
    '        filterButton.setText(media.equals("all") ? "All media"\n                : media.equals("image") ? "Images"\n                : media.equals("video") ? "Videos" : "GIFs");',
    '        filterButton.setText(media.equals("all") ? "All media"\n                : media.equals("image") ? "Images" : "Video/GIF");',
    'combined media filter chrome label')

s = replace_required(
    s,
    '    private void openFullscreenAt(int position) {\n        layoutMode = "fullscreen";\n        prefs.edit().putString("layout", layoutMode).apply();',
    '    private void openFullscreenAt(int position) {\n        layoutMode = "fullscreen";\n        fullscreenChromeVisible = false;\n        prefs.edit().putString("layout", layoutMode).apply();',
    'fullscreen entry hides chrome')

old_apply = '''    private void applyLayoutVisibility() {
        if (screen == Screen.ACCOUNT) {
            pager.setVisibility(View.GONE);
            gridView.setVisibility(View.GONE);
            postAdapter.setActivePosition(-1);
            return;
        }
        boolean grid = layoutMode.equals("grid");
        pager.setVisibility(grid ? View.GONE : View.VISIBLE);
        gridView.setVisibility(grid ? View.VISIBLE : View.GONE);
        postAdapter.setActivePosition(grid ? -1 : pager.getCurrentItem());
    }
'''
new_apply = '''    private void applyLayoutVisibility() {
        if (screen == Screen.ACCOUNT) {
            pager.setVisibility(View.GONE);
            gridView.setVisibility(View.GONE);
            postAdapter.setActivePosition(-1);
            setFullscreenChrome(true);
            return;
        }
        boolean grid = layoutMode.equals("grid");
        pager.setVisibility(grid ? View.GONE : View.VISIBLE);
        gridView.setVisibility(grid ? View.VISIBLE : View.GONE);
        postAdapter.setActivePosition(grid ? -1 : pager.getCurrentItem());
        setFullscreenChrome(grid || fullscreenChromeVisible);
    }
'''
s = replace_required(s, old_apply, new_apply, 'layout chrome synchronization')

s = replace_required(
    s,
    '                layoutMode = pair[0];\n                prefs.edit().putString("layout", layoutMode).apply();\n                dialog.dismiss();',
    '                layoutMode = pair[0];\n                fullscreenChromeVisible = !layoutMode.equals("fullscreen");\n                prefs.edit().putString("layout", layoutMode).apply();\n                dialog.dismiss();',
    'layout switch chrome default')

s = replace_required(
    s,
    '        layoutButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);\n        applySystemInsets(systemTopPx, systemBottomPx);\n    }\n\n    private void openBrowser',
    '''        layoutButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
        applySystemInsets(systemTopPx, systemBottomPx);
        setFullscreenChrome(layoutMode.equals("grid") || fullscreenChromeVisible);
    }

    private void setFullscreenChrome(boolean visible) {
        if (topBar == null || bottomBar == null || postAdapter == null) return;
        boolean fullscreen = layoutMode.equals("fullscreen") && screen != Screen.ACCOUNT;
        fullscreenChromeVisible = fullscreen ? visible : true;
        boolean show = !fullscreen || fullscreenChromeVisible;
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        postAdapter.setChromeVisible(show);
    }

    private void toggleFullscreenChrome() {
        if (!layoutMode.equals("fullscreen") || screen == Screen.ACCOUNT) return;
        setFullscreenChrome(!fullscreenChromeVisible);
    }

    private void openBrowser''',
    'fullscreen chrome methods')

s = replace_required(
    s,
    '''    @Override
    public void onOpenUser(String username) {
        openUserProfile(username);
    }

    @Override
    public void onSave(RedditPost post) {''',
    '''    @Override
    public void onOpenUser(String username) {
        openUserProfile(username);
    }

    @Override
    public void onToggleChrome() {
        toggleFullscreenChrome();
    }

    @Override
    public void onSave(RedditPost post) {''',
    'adapter fullscreen chrome callback')

main_path.write_text(s)

# ---------------------------------------------------------------------------
# Fullscreen pager: explicit GIF autoplay, media controls, tap-to-toggle chrome.
# ---------------------------------------------------------------------------
pager_path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
pager_path.write_text(r'''package com.scrolller.adblock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
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
import java.util.List;
import java.util.Map;

public final class PostPagerAdapter extends RecyclerView.Adapter<PostPagerAdapter.PostHolder> {
    public interface Listener {
        void onOpenSubreddit(String subreddit);
        void onOpenUser(String username);
        void onToggleChrome();
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
    private int activePosition = 0;
    private boolean muted = true;
    private boolean chromeVisible = false;
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
            root.setOnClickListener(v -> listener.onToggleChrome());
            addMedia(post, position);
            addTopMeta(post);
            addBottomInfo(post);
            applyChromeVisibility();
        }

        private void addMedia(RedditPost post, int position) {
            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {
                playerView = new PlayerView(context);
                playerView.setUseController(true);
                playerView.setControllerAutoShow(false);
                playerView.setControllerShowTimeoutMs(4000);
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
                playerView.setOnClickListener(v -> listener.onToggleChrome());

                Button mute = pillButton(muted ? "Muted" : "Sound");
                mediaControl = mute;
                FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                        dp(74), dp(36), Gravity.TOP | Gravity.END);
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
                        .listener(loopingGifListener())
                        .into(image);
                image.setOnClickListener(v -> listener.onToggleChrome());

                Button playPause = pillButton("Pause");
                mediaControl = playPause;
                FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                        dp(74), dp(36), Gravity.TOP | Gravity.END);
                gp.topMargin = topInsetPx + dp(102);
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
                gallery.setAdapter(new GalleryAdapter(post.imageUrls));
                root.addView(gallery, fullParams());

                TextView badge = smallBadge(post.imageUrls.size() + " images");
                mediaControl = badge;
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
            image.setOnClickListener(v -> listener.onToggleChrome());
        }

        private void addTopMeta(RedditPost post) {
            LinearLayout meta = new LinearLayout(context);
            topMeta = meta;
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
            bottomInfo = bottom;
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
            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
            playerView = null;
            boundPosition = -1;
        }
    }

    private RequestListener<GifDrawable> loopingGifListener() {
        return new RequestListener<GifDrawable>() {
            @Override
            public boolean onLoadFailed(
                    @Nullable GlideException e,
                    Object model,
                    Target<GifDrawable> target,
                    boolean isFirstResource) {
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
                return false;
            }
        };
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
''')

# ---------------------------------------------------------------------------
# Continuous Stream: real video/GIF playback, controls, no crop/no gap retained.
# ---------------------------------------------------------------------------
grid_path = Path('app/src/main/java/com/scrolller/adblock/GridPostAdapter.java')
grid_path.write_text(r'''package com.scrolller.adblock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.ArrayList;
import java.util.List;

public final class GridPostAdapter extends RecyclerView.Adapter<GridPostAdapter.Holder> {
    public interface Listener {
        void onPostClicked(int position);
    }

    private final Context context;
    private final Listener listener;
    private final ArrayList<RedditPost> posts = new ArrayList<>();

    public GridPostAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setPosts(List<RedditPost> items) {
        posts.clear();
        posts.addAll(items);
        notifyDataSetChanged();
    }

    public void appendPosts(List<RedditPost> items) {
        if (items.isEmpty()) return;
        int start = posts.size();
        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    @Override
    public long getItemId(int position) {
        String id = posts.get(position).id;
        return id != null ? id.hashCode() : position;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setBackgroundColor(Color.BLACK);
        return new Holder(root);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(posts.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.releasePlayer();
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return posts.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        ExoPlayer player;

        Holder(FrameLayout root) {
            super(root);
            this.root = root;
        }

        void bind(RedditPost post) {
            releasePlayer();
            root.removeAllViews();
            root.setBackgroundColor(Color.BLACK);

            int widthPx = context.getResources().getDisplayMetrics().widthPixels;
            int heightPx = widthPx;
            if (post.mediaWidth > 0 && post.mediaHeight > 0) {
                heightPx = Math.max(1, Math.round(
                        widthPx * (post.mediaHeight / (float) post.mediaWidth)));
            }
            RecyclerView.LayoutParams rp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
            rp.setMargins(0, 0, 0, 0);
            root.setLayoutParams(rp);

            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {
                addStreamPlayer(post);
                return;
            }

            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(Color.BLACK);
            root.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            String source = !post.imageUrls.isEmpty() ? post.imageUrls.get(0) : post.posterUrl;
            if (post.mediaKind == RedditPost.MediaKind.GIF && !post.imageUrls.isEmpty()) {
                Glide.with(image)
                        .asGif()
                        .load(source)
                        .fitCenter()
                        .listener(loopingGifListener())
                        .into(image);
                addGifPauseButton(image);
            } else {
                Glide.with(image).load(source).fitCenter().into(image);
            }

            addBadge(post);
            addFullscreenButton();
            if (post.mediaKind != RedditPost.MediaKind.GIF) {
                root.setOnClickListener(v -> openCurrent());
            }
        }

        private void addStreamPlayer(RedditPost post) {
            PlayerView view = new PlayerView(context);
            view.setUseController(true);
            view.setControllerAutoShow(false);
            view.setControllerShowTimeoutMs(4000);
            view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            view.setBackgroundColor(Color.BLACK);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            player = new ExoPlayer.Builder(context).build();
            player.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
            player.setMediaItem(MediaItem.fromUri(post.videoUrl));
            player.setVolume(0f);
            player.setPlayWhenReady(post.mediaKind == RedditPost.MediaKind.GIF);
            player.prepare();
            view.setPlayer(player);

            Button sound = overlayButton("Muted");
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                    dp(72), dp(36), Gravity.TOP | Gravity.END);
            sp.topMargin = dp(8);
            sp.rightMargin = dp(8);
            root.addView(sound, sp);
            sound.setOnClickListener(v -> {
                boolean muted = player == null || player.getVolume() == 0f;
                if (player != null) player.setVolume(muted ? 1f : 0f);
                sound.setText(muted ? "Sound" : "Muted");
            });

            addBadge(post);
            addFullscreenButton();
        }

        private void addGifPauseButton(ImageView image) {
            Button playPause = overlayButton("Pause");
            FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                    dp(72), dp(36), Gravity.TOP | Gravity.END);
            gp.topMargin = dp(8);
            gp.rightMargin = dp(8);
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
        }

        private void addBadge(RedditPost post) {
            String text = "";
            if (post.mediaKind == RedditPost.MediaKind.VIDEO) text = "▶";
            else if (post.mediaKind == RedditPost.MediaKind.GIF) text = "GIF";
            else if (post.mediaKind == RedditPost.MediaKind.GALLERY) text = post.imageUrls.size() + " ▣";
            if (text.isEmpty()) return;

            TextView badge = new TextView(context);
            badge.setText(text);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(11);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(7), 0, dp(7), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xB3000000);
            bg.setCornerRadius(dp(999));
            badge.setBackground(bg);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), Gravity.BOTTOM | Gravity.END);
            bp.rightMargin = dp(6);
            bp.bottomMargin = dp(6);
            root.addView(badge, bp);
        }

        private void addFullscreenButton() {
            Button fullscreen = overlayButton("⛶");
            fullscreen.setTextSize(18);
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                    dp(44), dp(40), Gravity.BOTTOM | Gravity.START);
            fp.leftMargin = dp(6);
            fp.bottomMargin = dp(6);
            root.addView(fullscreen, fp);
            fullscreen.setOnClickListener(v -> openCurrent());
        }

        private void openCurrent() {
            int p = getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION) listener.onPostClicked(p);
        }

        void releasePlayer() {
            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
        }
    }

    private RequestListener<GifDrawable> loopingGifListener() {
        return new RequestListener<GifDrawable>() {
            @Override
            public boolean onLoadFailed(
                    @Nullable GlideException e,
                    Object model,
                    Target<GifDrawable> target,
                    boolean isFirstResource) {
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
                return false;
            }
        };
    }

    private Button overlayButton(String text) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3181818);
        bg.setCornerRadius(dp(999));
        button.setBackground(bg);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
''')

print('Applied v3.5 unified Video/GIF controls + fullscreen tap chrome')
