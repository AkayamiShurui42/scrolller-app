package com.scrolller.adblock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

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
        int size = parent.getResources().getDisplayMetrics().widthPixels / 2;
        root.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, size));
        root.setBackgroundColor(Color.BLACK);

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView badge = new TextView(context);
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

        return new Holder(root, image, badge);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        RedditPost post = posts.get(position);
        String thumb = !post.imageUrls.isEmpty() ? post.imageUrls.get(0) : post.posterUrl;
        Glide.with(holder.image).load(thumb).fitCenter().into(holder.image);

        if (post.mediaKind == RedditPost.MediaKind.VIDEO) holder.badge.setText("▶");
        else if (post.mediaKind == RedditPost.MediaKind.GALLERY) holder.badge.setText(post.imageUrls.size() + " ▣");
        else if (post.mediaKind == RedditPost.MediaKind.EXTERNAL) holder.badge.setText("↗");
        else holder.badge.setText("");

        holder.root.setOnClickListener(v -> {
            int p = holder.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION) listener.onPostClicked(p);
        });
    }

    @Override
    public int getItemCount() {
        return posts.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        final ImageView image;
        final TextView badge;
        Holder(FrameLayout root, ImageView image, TextView badge) {
            super(root);
            this.root = root;
            this.image = image;
            this.badge = badge;
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
