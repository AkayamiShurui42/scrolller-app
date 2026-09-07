package com.scrolller.adblock;

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
    private final ArrayList<Holder> activeHolders = new ArrayList<>();

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

    public void removePostById(String id) {
        if (id == null || id.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < posts.size(); i++) {
            if (id.equals(posts.get(i).id)) { index = i; break; }
        }
        if (index < 0) return;
        releaseAllPlayers();
        posts.remove(index);
        notifyDataSetChanged();
    }

    public void releaseAllPlayers() {
        for (Holder holder : new ArrayList<>(activeHolders)) {
            holder.releasePlayer();
        }
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
    public void onViewAttachedToWindow(@NonNull Holder holder) {
        super.onViewAttachedToWindow(holder);
        if (!activeHolders.contains(holder)) activeHolders.add(holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull Holder holder) {
        activeHolders.remove(holder);
        holder.releasePlayer();
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        activeHolders.remove(holder);
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
        PlayerView playerView;

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

            if (post.videoUrl != null && post.videoUrl.startsWith("redgifs:")) {
                final String unresolved = post.videoUrl;
                final String id = unresolved.substring("redgifs:".length());
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackgroundColor(Color.BLACK);
                root.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                if (post.posterUrl != null && !post.posterUrl.isEmpty()) {
                    Glide.with(image).load(post.posterUrl).fitCenter().into(image);
                }
                RedgifsResolver.resolve(id, new RedgifsResolver.Callback() {
                    @Override
                    public void onResolved(String url) {
                        if (post.videoUrl.equals(unresolved)) post.videoUrl = url;
                        int index = posts.indexOf(post);
                        if (index >= 0) notifyItemChanged(index);
                    }

                    @Override
                    public void onError(String error) {
                        // Keep the Reddit preview visible if provider resolution fails.
                    }
                });
                return;
            }

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
            PlayerView view = (PlayerView) android.view.LayoutInflater.from(context)
                    .inflate(R.layout.view_texture_player, root, false);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            playerView = view;

            player = HighQualityPlayerFactory.create(context, post.videoUrl);
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
