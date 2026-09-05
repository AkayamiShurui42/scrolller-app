from pathlib import Path

# Reuse the v3.7.4 cleanup/filter/sort logic, but strip its first pager experiment.
# The actual generated fullscreen player is TextureView-backed by v3.5.2, so the
# transition work below targets that final shape directly.
source_path = Path("patches/apply_v3_7_4_cleanup_filters_sort_pager.py")
source = source_path.read_text()
start = source.find("# ---------------------------------------------------------------------------\n# 4) Smooth post-to-post media transitions.")
version = source.find("# Version bump after the v3.7.3 patch stack.")
if start < 0 or version < 0 or version <= start:
    raise SystemExit("Could not isolate v3.7.4 cleanup body")
source = source[:start] + source[version:]
exec(compile(source, str(source_path), "exec"), {"__name__": "__main__", "__file__": str(source_path)})

pager_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
p = pager_path.read_text()

marker = '''                playerView = (PlayerView) android.view.LayoutInflater.from(context)
                        .inflate(R.layout.view_texture_player, root, false);
                root.addView(playerView, fullParams());
'''
if marker not in p:
    raise SystemExit("Missing v3.7.4 TextureView PlayerView target")

replacement = '''                if (post.posterUrl != null && !post.posterUrl.isEmpty()) {
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
'''
p = p.replace(marker, replacement, 1)
pager_path.write_text(p)

layout = Path("app/src/main/res/layout/view_texture_player.xml")
xml = layout.read_text()
if 'android:background="@android:color/black"' not in xml:
    raise SystemExit("Missing v3.7.4 TextureView background target")
xml = xml.replace(
    'android:background="@android:color/black"',
    'android:background="@android:color/transparent"',
    1,
)
if 'app:keep_content_on_player_reset="false"' not in xml:
    raise SystemExit("Missing v3.7.4 keep-content XML target")
xml = xml.replace(
    'app:keep_content_on_player_reset="false"',
    'app:keep_content_on_player_reset="true"',
    1,
)
layout.write_text(xml)

print("Applied v3.7.4 final TextureView transition smoothing")
