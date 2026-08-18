from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.5.2 patch target: {label}\n{old[:360]}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# Use TextureView-backed PlayerView everywhere. SurfaceView owns a separate
# compositing surface and can outlive/hide independently of its RecyclerView
# parent during layout transitions. TextureView stays in the normal hierarchy.
# ---------------------------------------------------------------------------
layout = Path('app/src/main/res/layout/view_texture_player.xml')
layout.parent.mkdir(parents=True, exist_ok=True)
layout.write_text('''<?xml version="1.0" encoding="utf-8"?>
<androidx.media3.ui.PlayerView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black"
    app:surface_type="texture_view"
    app:resize_mode="fit"
    app:use_controller="true"
    app:auto_show="false"
    app:show_timeout="4000"
    app:keep_content_on_player_reset="false" />
''')

# ---------------------------------------------------------------------------
# Fullscreen pager: inflate the TextureView PlayerView and explicitly detach
# player/view before release so no stale frame survives holder recycling.
# ---------------------------------------------------------------------------
pager_path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
s = pager_path.read_text()

old_player = '''                playerView = new PlayerView(context);
                playerView.setUseController(true);
                playerView.setControllerAutoShow(false);
                playerView.setControllerShowTimeoutMs(4000);
                playerView.setBackgroundColor(Color.BLACK);
                root.addView(playerView, fullParams());
'''
new_player = '''                playerView = (PlayerView) android.view.LayoutInflater.from(context)
                        .inflate(R.layout.view_texture_player, root, false);
                root.addView(playerView, fullParams());
'''
s = replace_required(s, old_player, new_player, 'fullscreen TextureView player')

old_release = '''        void releasePlayer() {
            if (boundPosition >= 0) players.remove(boundPosition);
            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
            playerView = null;
            boundPosition = -1;
        }
'''
new_release = '''        void releasePlayer() {
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
'''
s = replace_required(s, old_release, new_release, 'fullscreen player detach/release')
pager_path.write_text(s)

# ---------------------------------------------------------------------------
# Stream renderer: TextureView player + attached-holder tracking so every
# stream player can be killed synchronously before Fullscreen becomes visible.
# ---------------------------------------------------------------------------
grid_path = Path('app/src/main/java/com/scrolller/adblock/GridPostAdapter.java')
g = grid_path.read_text()

g = replace_required(
    g,
    '    private final ArrayList<RedditPost> posts = new ArrayList<>();\n',
    '    private final ArrayList<RedditPost> posts = new ArrayList<>();\n    private final ArrayList<Holder> activeHolders = new ArrayList<>();\n',
    'stream active-holder tracking')

g = replace_required(
    g,
    '''    public void appendPosts(List<RedditPost> items) {
        if (items.isEmpty()) return;
        int start = posts.size();
        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }
''',
    '''    public void appendPosts(List<RedditPost> items) {
        if (items.isEmpty()) return;
        int start = posts.size();
        posts.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public void releaseAllPlayers() {
        for (Holder holder : new ArrayList<>(activeHolders)) {
            holder.releasePlayer();
        }
    }
''',
    'stream releaseAllPlayers')

g = replace_required(
    g,
    '''    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(posts.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.releasePlayer();
        super.onViewRecycled(holder);
    }
''',
    '''    @Override
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
''',
    'stream detach/recycle release')

g = replace_required(
    g,
    '        ExoPlayer player;\n',
    '        ExoPlayer player;\n        PlayerView playerView;\n',
    'stream playerView field')

g = replace_required(
    g,
    '''            PlayerView view = new PlayerView(context);
            view.setUseController(true);
            view.setControllerAutoShow(false);
            view.setControllerShowTimeoutMs(4000);
            view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            view.setBackgroundColor(Color.BLACK);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
''',
    '''            PlayerView view = (PlayerView) android.view.LayoutInflater.from(context)
                    .inflate(R.layout.view_texture_player, root, false);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            playerView = view;
''',
    'stream TextureView player')

g = replace_required(
    g,
    '''        void releasePlayer() {
            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
        }
''',
    '''        void releasePlayer() {
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
''',
    'stream player detach/release')

grid_path.write_text(g)

# ---------------------------------------------------------------------------
# MainActivity: synchronously shut down Stream players before hiding Stream;
# force a rebind when Stream is shown again so released holders get fresh media.
# ---------------------------------------------------------------------------
main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
m = main_path.read_text()

m = replace_required(
    m,
    '        gridView.setClipToPadding(false);\n',
    '        gridView.setClipToPadding(false);\n        gridView.setItemAnimator(null);\n',
    'disable stream item animator')

old_apply = '''    private void applyLayoutVisibility() {
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
new_apply = '''    private void applyLayoutVisibility() {
        if (screen == Screen.ACCOUNT) {
            gridAdapter.releaseAllPlayers();
            pager.setVisibility(View.GONE);
            gridView.setVisibility(View.GONE);
            postAdapter.setActivePosition(-1);
            setFullscreenChrome(true);
            return;
        }
        boolean grid = layoutMode.equals("grid");
        if (grid) {
            boolean wasHidden = gridView.getVisibility() != View.VISIBLE;
            pager.setVisibility(View.GONE);
            gridView.setVisibility(View.VISIBLE);
            if (wasHidden) gridAdapter.notifyDataSetChanged();
            postAdapter.setActivePosition(-1);
        } else {
            gridView.stopScroll();
            gridAdapter.releaseAllPlayers();
            gridView.setVisibility(View.GONE);
            pager.setVisibility(View.VISIBLE);
            postAdapter.setActivePosition(pager.getCurrentItem());
        }
        setFullscreenChrome(grid || fullscreenChromeVisible);
    }
'''
m = replace_required(m, old_apply, new_apply, 'single active media surface per layout')
main_path.write_text(m)

print('Applied v3.5.2 TextureView/stale-surface fix')
