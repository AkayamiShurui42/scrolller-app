from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/scrolller/adblock/MainActivity.java"
PAGER = ROOT / "app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java"
GRADLE = ROOT / "app/build.gradle"

main = MAIN.read_text()
pager = PAGER.read_text()
gradle = GRADLE.read_text()

old_merge = '''        RedditPost current = postAdapter.getPost(pager.getCurrentItem());
        String currentKey = canonicalPostKey(current);
        ArrayList<RedditPost> merged = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty() && ids.add(key)) merged.add(post);
        }
        for (RedditPost post : historical) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty() && ids.add(key)) merged.add(post);
        }

        merged.sort((a, b) -> {
            int scoreOrder = Integer.compare(b.score, a.score);
            if (scoreOrder != 0) return scoreOrder;
            return Long.compare(b.createdUtc, a.createdUtc);
        });
        replacePosts(merged);

        if (!currentKey.isEmpty()) {
            for (int i = 0; i < postAdapter.getItemCount(); i++) {
                if (currentKey.equals(canonicalPostKey(postAdapter.getPost(i)))) {
                    pager.setCurrentItem(i, false);
                    if (layoutMode.equals("grid")) gridView.scrollToPosition(i);
                    break;
                }
            }
        }
'''

new_merge = '''        // v3.9.5: once a feed prefix has been shown to the user, its order is
        // immutable for the lifetime of that active feed. Historical/background
        // discoveries may extend the stream, but they must never be re-sorted into
        // positions the user has already passed. Doing that made newly-arrived posts
        // appear behind or between already-viewed posts when scrolling backward.
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty()) ids.add(key);
        }

        ArrayList<RedditPost> historicalAdditions = new ArrayList<>();
        for (RedditPost post : historical) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty() && ids.add(key)) historicalAdditions.add(post);
        }

        historicalAdditions.sort((a, b) -> {
            int scoreOrder = Integer.compare(b.score, a.score);
            if (scoreOrder != 0) return scoreOrder;
            return Long.compare(b.createdUtc, a.createdUtc);
        });
        appendUnique(historicalAdditions);
'''

if old_merge not in main:
    raise SystemExit("Expected v3.9.4 historical merge block was not found")
main = main.replace(old_merge, new_merge, 1)

old_action_attach = '''            bottom.addView(actionScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
'''
new_action_attach = '''            // v3.9.5: the thumb-first command bar owns post actions now. Keep the
            // legacy action views unmounted so the old translucent strip cannot render
            // underneath the redesigned UI or intercept/duplicate visual state.
'''
if old_action_attach not in pager:
    raise SystemExit("Expected legacy action-strip attachment was not found")
pager = pager.replace(old_action_attach, new_action_attach, 1)

if 'versionCode 51' not in gradle or 'versionName "3.9.4"' not in gradle:
    raise SystemExit("Expected v3.9.4 version markers were not found")
gradle = gradle.replace('versionCode 51', 'versionCode 52', 1)
gradle = gradle.replace('versionName "3.9.4"', 'versionName "3.9.5"', 1)

MAIN.write_text(main)
PAGER.write_text(pager)
GRADLE.write_text(gradle)

print("Applied v3.9.5 stable-feed-order and legacy-overlay cleanup hotfix")
