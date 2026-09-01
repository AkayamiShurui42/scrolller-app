from pathlib import Path
import sys

files = {
    "MAIN": Path("android/app/src/main/java/com/scrolller/adblock/MainActivity.java"),
    "POST": Path("android/app/src/main/java/com/scrolller/adblock/RedditPost.java"),
    "SCROLLLER": Path("android/app/src/main/java/com/scrolller/adblock/ScrolllerClient.java"),
    "PAGER": Path("android/app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java"),
    "GRID": Path("android/app/src/main/java/com/scrolller/adblock/GridPostAdapter.java"),
    "HQ": Path("android/app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java"),
    "GRADLE": Path("android/app/build.gradle"),
}
text = {name: path.read_text() for name, path in files.items()}

required = {
    "MAIN": [
        ("quality feed selector", 'Quality crawl'),
        ("quality navigation", 'navigateHome("quality", true)'),
        ("quality context", 'context.equals("quality")'),
        ("quality title", 'title = "Quality"'),
        ("quality loader", 'private void loadQualityFeed(boolean reset)'),
        ("quality generation guard", 'private boolean qualityContextStillValid'),
        ("quality grouped probes", 'for (int i = 0; i < names.size(); i += 10)'),
        ("quality time windows", 'String[] windows = {"all", "year", "month"}'),
        ("quality learned sources", 'bestQualitySources(12)'),
        ("quality deep crawl", 'private void crawlQualityDeep'),
        ("quality New source", '/new.json?limit=100&raw_json=1&show=all'),
        ("quality request pacing", '220L'),
        ("quality scoring", 'private int qualityScoreForChild'),
        ("gallery dimensions", 'media_metadata'),
        ("gallery area threshold", 'averageArea >= 3500000L'),
        ("ultra-res threshold", 'maxArea >= 8000000L'),
        ("image edge threshold", 'longEdge < 2400'),
        ("video edge threshold", 'shortEdge < 900'),
        ("quality ranking", 'qualityScores.getOrDefault'),
        ("quality source learning", 'qualitySourceHits.put'),

        ("global search source", 'Global Reddit'),
        ("subscription search source", 'Subscriptions'),
        ("favorites search source", 'Favorites / Saved'),
        ("collector search source", 'Collector · current app collection'),
        ("subreddit search source", 'Subreddit search'),
        ("collector snapshot", 'private final ArrayList<RedditPost> searchCollectorSnapshot'),
        ("collector capture", 'searchCollectorSnapshot.addAll(postAdapter.getPosts())'),
        ("subscription group search", 'private ArrayList<String> subscriptionSearchGroups()'),
        ("subscription multireddit construction", 'group.append'),
        ("subreddit restriction", 'restrict_sr='),
        ("remote Reddit search", '/search.json'),
        ("favorites Saved listing search", '/saved.json?limit=100&raw_json=1&show=all'),
        ("local query matching", 'private boolean matchesLocalSearch'),
        ("local source sorting", 'private void sortLocalSearch'),
        ("generation-safe finish", 'private void finishSearchCollection(int generation'),
        ("deep search pagination", 'page < 49'),

        ("Hidden rejection", 'hiddenPosts.containsKey(post.id)'),
        ("Saved rejection", 'isSavedForUnread(post)'),
        ("content rejection", 'isContentBlocked(post)'),
        ("Saved identity set", 'private final Set<String> savedPostIds = new HashSet<>()'),
        ("immediate Saved transition", 'private void removeSavedFromUnread(String id)'),
        ("media-ready read gate", 'mediaReadyPostIds.contains(previous.id)'),
        ("Saved/Hidden disjointness", '!savedPostIds.contains(previous.id)'),
        ("deferred appends", 'private final ArrayList<RedditPost> deferredAppends'),
        ("pager idle gate", 'pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE'),
        ("deferred append flush", 'flushDeferredAppends'),
        ("Scrolller deep source", 'ScrolllerClient.crawlSubreddit(targetSubreddit, 600'),
        ("subscribe endpoint", 'engine.postForm("/api/subscribe"'),
        ("LGBTQ preference", 'blockLgbtTopics = prefs.getBoolean'),
        ("gore preference", 'blockGoreContent = prefs.getBoolean'),
    ],
    "HQ": [
        ("adaptive Media3 bitrate", 'setForceHighestSupportedBitrate(false)'),
    ],
    "SCROLLLER": [
        ("Scrolller GraphQL source", 'https://api.scrolller.com/admin'),
    ],
    "POST": [
        ("Scrolller normalization", 'public static RedditPost fromScrolller'),
    ],
    "PAGER": [
        ("fullscreen RedGIFs resolution", 'RedgifsResolver.resolve'),
    ],
    "GRID": [
        ("stream RedGIFs resolution", 'RedgifsResolver.resolve'),
    ],
    "GRADLE": [
        ("stable package", 'applicationId "com.crimson.redditmedia"'),
        ("versionCode", 'versionCode 28'),
        ("versionName", 'versionName "3.7.0"'),
    ],
}

prohibited = {
    "HQ": [
        ("forced maximum bitrate regression", 'setForceHighestSupportedBitrate(true)'),
    ],
    "MAIN": [
        ("old global-then-filter subscription search", 'subscriptionNames.contains(post.subreddit.toLowerCase(Locale.US))'),
        ("old live pager Saved removal", 'postAdapter.removePostById(id)'),
        ("old live stream Saved removal", 'gridAdapter.removePostById(id)'),
    ],
}

failed = False
for filename, checks in required.items():
    source = text[filename]
    for label, needle in checks:
        if needle not in source:
            failed = True
            print(f"MISSING [{filename}] {label}: {needle}")
        else:
            print(f"OK      [{filename}] {label}")

for filename, checks in prohibited.items():
    source = text[filename]
    for label, needle in checks:
        if needle in source:
            failed = True
            print(f"FORBIDDEN [{filename}] {label}: {needle}")
        else:
            print(f"OK      [{filename}] no {label}")

if failed:
    sys.exit(1)
print("v3.7.0 behavior validation passed")
