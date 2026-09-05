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

main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()

# The original v3.6.9 helper block can leave a stale tail after replacing
# isContentBlocked(). The next declaration must be normalizeFilterText(), so trim
# only anything between the new method's real end and that helper declaration.
blocked_start = main.find("    private boolean isContentBlocked(RedditPost post) {")
normalize_start = main.find("    private static String normalizeFilterText(String value) {", blocked_start)
if blocked_start < 0 or normalize_start < 0:
    raise SystemExit("Missing final v3.7.4 content filter/helper declarations")
blocked_end_marker = "        return false;\n    }\n\n"
blocked_end = main.find(blocked_end_marker, blocked_start)
if blocked_end < 0 or blocked_end >= normalize_start:
    raise SystemExit("Could not find final v3.7.4 isContentBlocked end")
blocked_end += len(blocked_end_marker)
filter_gap = main[blocked_end:normalize_start]
if filter_gap.strip():
    if "blockGoreContent" not in filter_gap and "blockLgbtTopics" not in filter_gap:
        raise SystemExit("Unexpected content between isContentBlocked and normalizeFilterText")
    main = main[:blocked_end] + main[normalize_start:]

# The historical Quality patch can leave a stale tail from the old listingPath
# immediately after the replacement method. Remove only that gap. Do not replace
# the whole listingPath -> matchesMedia region because the Saved/content-filter
# helpers intentionally live there.
listing_start = main.find("    private String listingPath(String cursor) {")
if listing_start < 0:
    raise SystemExit("Missing final v3.7.4 listingPath")
listing_end_marker = "        return path;\n    }\n\n"
listing_end = main.find(listing_end_marker, listing_start)
if listing_end < 0:
    raise SystemExit("Could not find final v3.7.4 listingPath end")
listing_end += len(listing_end_marker)
helpers_start = main.find("    private boolean isSavedForUnread(RedditPost post) {", listing_end)
if helpers_start < 0:
    raise SystemExit("Missing Saved/content-filter helper block after listingPath")
listing_gap = main[listing_end:helpers_start]
if listing_gap.strip():
    if "context.equals(\"popular\")" not in listing_gap or "return path;" not in listing_gap:
        raise SystemExit("Unexpected non-listing content in v3.7.4 listing gap")
    main = main[:listing_end] + main[helpers_start:]

# showSortSheet() is also replaced on top of generated v3.7.x source. In this
# stack the old method body can survive after the new method closes, leaving an
# orphaned `if (screen == Screen.SEARCH)` at class scope. Keep the new method and
# trim only the stale body before the next known method declaration.
sort_start = main.find("    private void showSortSheet() {")
hidden_manage_start = main.find("    private void showHiddenManageSheet() {", sort_start)
if sort_start < 0 or hidden_manage_start < 0:
    raise SystemExit("Missing final v3.7.4 sort/hidden-manage declarations")
sort_end_marker = "        dialog.show();\n    }\n\n"
sort_end = main.find(sort_end_marker, sort_start)
if sort_end < 0 or sort_end >= hidden_manage_start:
    raise SystemExit("Could not find final v3.7.4 showSortSheet end")
sort_end += len(sort_end_marker)
sort_gap = main[sort_end:hidden_manage_start]
if sort_gap.strip():
    if "if (screen == Screen.SEARCH)" not in sort_gap or "dialog.show();" not in sort_gap:
        raise SystemExit("Unexpected content between showSortSheet and showHiddenManageSheet")
    main = main[:sort_end] + main[hidden_manage_start:]

main_path.write_text(main)

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

print("Applied v3.7.4 final filter/listing/sort tail cleanup + TextureView transition smoothing")
