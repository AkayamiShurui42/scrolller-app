from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.7.1 quality target: {label}\n{old[:1200]}')
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Cache-backed metadata catalog. This deliberately lives in cache, not durable
# app state: Android/user cache clearing is the eviction policy. No item cap.
# ---------------------------------------------------------------------------
store = Path('app/src/main/java/com/scrolller/adblock/QualityCatalogStore.java')
store.write_text(r'''package com.scrolller.adblock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class QualityCatalogStore {
    interface LoadCallback {
        void onLoaded(ArrayList<RedditPost> posts);
    }

    private static final String FILE_NAME = "quality_catalog.json";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private QualityCatalogStore() {}

    static void load(Context context, LoadCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            ArrayList<RedditPost> posts = new ArrayList<>();
            try {
                File file = new File(app.getCacheDir(), FILE_NAME);
                if (file.isFile()) {
                    StringBuilder text = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                        String line;
                        while ((line = reader.readLine()) != null) text.append(line);
                    }
                    JSONArray array = new JSONArray(text.toString());
                    for (int i = 0; i < array.length(); i++) {
                        RedditPost post = fromJson(array.optJSONObject(i));
                        if (post != null && post.id != null && !post.id.isEmpty()) posts.add(post);
                    }
                }
            } catch (Exception ignored) {}
            MAIN.post(() -> callback.onLoaded(posts));
        });
    }

    static void save(Context context, List<RedditPost> input) {
        Context app = context.getApplicationContext();
        ArrayList<RedditPost> snapshot = new ArrayList<>(input);
        EXECUTOR.execute(() -> {
            File dir = app.getCacheDir();
            File target = new File(dir, FILE_NAME);
            File temp = new File(dir, FILE_NAME + ".tmp");
            try {
                JSONArray array = new JSONArray();
                for (RedditPost post : snapshot) {
                    JSONObject encoded = toJson(post);
                    if (encoded != null) array.put(encoded);
                }
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(temp, false))) {
                    writer.write(array.toString());
                }
                if (target.exists() && !target.delete()) {
                    // Best effort; rename below may still replace on some filesystems.
                }
                if (!temp.renameTo(target)) {
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(target, false))) {
                        writer.write(array.toString());
                    }
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            } catch (Exception ignored) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        });
    }

    private static JSONObject toJson(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return null;
        try {
            JSONObject object = new JSONObject();
            object.put("id", post.id);
            object.put("title", post.title);
            object.put("author", post.author);
            object.put("subreddit", post.subreddit);
            object.put("permalink", post.permalink);
            object.put("sourceUrl", post.sourceUrl);
            object.put("score", post.score);
            object.put("comments", post.comments);
            object.put("createdUtc", post.createdUtc);
            object.put("saved", post.saved);
            object.put("nsfw", post.nsfw);
            object.put("mediaKind", post.mediaKind.name());
            JSONArray images = new JSONArray();
            if (post.imageUrls != null) for (String url : post.imageUrls) images.put(url);
            object.put("imageUrls", images);
            object.put("videoUrl", post.videoUrl);
            object.put("posterUrl", post.posterUrl);
            object.put("mediaWidth", post.mediaWidth);
            object.put("mediaHeight", post.mediaHeight);
            return object;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static RedditPost fromJson(JSONObject object) {
        if (object == null) return null;
        try {
            String id = object.optString("id", "");
            if (id.isEmpty()) return null;
            RedditPost.MediaKind kind = RedditPost.MediaKind.valueOf(
                    object.optString("mediaKind", RedditPost.MediaKind.IMAGE.name()));
            ArrayList<String> images = new ArrayList<>();
            JSONArray imageArray = object.optJSONArray("imageUrls");
            if (imageArray != null) {
                for (int i = 0; i < imageArray.length(); i++) {
                    String url = imageArray.optString(i, "");
                    if (!url.isEmpty()) images.add(url);
                }
            }
            return new RedditPost(
                    id,
                    object.optString("title", ""),
                    object.optString("author", ""),
                    object.optString("subreddit", ""),
                    object.optString("permalink", ""),
                    object.optString("sourceUrl", ""),
                    object.optInt("score", 0),
                    object.optInt("comments", 0),
                    object.optLong("createdUtc", 0L),
                    object.optBoolean("saved", false),
                    object.optBoolean("nsfw", false),
                    kind,
                    images,
                    object.optString("videoUrl", ""),
                    object.optString("posterUrl", ""),
                    object.optInt("mediaWidth", 0),
                    object.optInt("mediaHeight", 0));
        } catch (Exception ignored) {
            return null;
        }
    }
}
''')


# ---------------------------------------------------------------------------
# MainActivity: isolated Quality feed. Crawl live Reddit sources, score actual
# media dimensions/gallery richness, learn authors that repeatedly qualify, and
# merge only quality candidates into the cache-backed collection.
# ---------------------------------------------------------------------------
path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

s = replace_required(
    s,
    '    private static final String REDDIT = "https://www.reddit.com";\n',
    '''    private static final String REDDIT = "https://www.reddit.com";
    private static final String[] QUALITY_SEED_SUBREDDITS = {
            "EarthPorn",
            "itookapicture",
            "astrophotography",
            "WildlifePhotography",
            "ExposurePorn",
            "CityPorn",
            "WidescreenWallpaper",
            "wallpapers",
            "wallpaper",
            "ImaginaryLandscapes",
            "ArchitecturePorn",
            "spaceporn"
    };
    private static final String[] QUALITY_TIME_WINDOWS = {"all", "year", "month"};
''',
    'quality seed constants')

s = replace_required(
    s,
    '    private boolean historicalPrefetchDone = false;\n',
    '''    private boolean historicalPrefetchDone = false;
    private final LinkedHashMap<String, RedditPost> qualityCatalog = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> qualityAuthorHits = new LinkedHashMap<>();
    private boolean qualityCatalogLoaded = false;
    private boolean qualityCrawlRunning = false;
    private boolean qualityCrawlDone = false;
    private int qualityCrawlGeneration = 0;
''',
    'quality crawl state')

# Quality is a first-class feed choice, but this does not touch other contexts.
s = replace_required(
    s,
    '''        Button home = sheetButton("Home" + (context.equals("home") ? "  ✓" : ""));
        Button popular = sheetButton("Popular" + (context.equals("popular") ? "  ✓" : ""));
        body.addView(home, sectionButtonParams());
        body.addView(popular, sectionButtonParams());''',
    '''        Button home = sheetButton("Home" + (context.equals("home") ? "  ✓" : ""));
        Button popular = sheetButton("Popular" + (context.equals("popular") ? "  ✓" : ""));
        Button quality = sheetButton("Quality collection" + (context.equals("quality") ? "  ✓" : ""));
        body.addView(home, sectionButtonParams());
        body.addView(popular, sectionButtonParams());
        body.addView(quality, sectionButtonParams());''',
    'Quality feed button')

s = replace_required(
    s,
    '''        popular.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("popular", true);
        });''',
    '''        popular.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("popular", true);
        });
        quality.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("quality", true);
        });''',
    'Quality feed navigation')

# Intercept Quality before Reddit's ordinary listingPath machinery.
s = replace_required(
    s,
    '''    private void loadFeed(boolean reset) {
        if (loading || !engine.isReady()) return;
        loading = true;''',
    '''    private void loadFeed(boolean reset) {
        if (!engine.isReady()) return;
        if (context.equals("quality")) {
            loadQualityCollection(reset);
            return;
        }
        if (loading) return;
        loading = true;''',
    'Quality feed load interception')

# Quality title is explicit instead of pretending to be Home.
s = replace_required(
    s,
    '''        else if (context.equals("subreddit")) title = "r/" + subreddit;
        else title = context.equals("popular") ? "Popular" : "Home";''',
    '''        else if (context.equals("subreddit")) title = "r/" + subreddit;
        else if (context.equals("quality")) title = "Quality";
        else title = context.equals("popular") ? "Popular" : "Home";''',
    'Quality chrome title')

helper_anchor = '    private void prefetchHistoricalSubredditIfNeeded(boolean forceFallback) {'
if helper_anchor not in s:
    raise SystemExit('Missing v3.7.1 quality target: historical helper anchor')

helpers = r'''    private void loadQualityCollection(boolean reset) {
        if (!engine.isReady()) return;
        if (reset) {
            qualityCrawlGeneration++;
            qualityCrawlRunning = false;
            qualityCrawlDone = false;
            qualityAuthorHits.clear();
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Loading high-resolution catalog…", true);
        }
        final int generation = qualityCrawlGeneration;

        if (!qualityCatalogLoaded) {
            qualityCatalogLoaded = true;
            QualityCatalogStore.load(this, cached -> {
                if (!qualityContextValid(generation)) return;
                for (RedditPost post : cached) {
                    if (post != null && post.id != null && !post.id.isEmpty()) {
                        qualityCatalog.put(post.id, post);
                    }
                }
                renderQualityCatalog();
                startQualityCrawlIfNeeded(generation);
            });
            return;
        }

        renderQualityCatalog();
        startQualityCrawlIfNeeded(generation);
    }

    private boolean qualityContextValid(int generation) {
        return generation == qualityCrawlGeneration
                && screen == Screen.HOME
                && context.equals("quality");
    }

    private void renderQualityCatalog() {
        ArrayList<RedditPost> visible = new ArrayList<>();
        for (RedditPost post : qualityCatalog.values()) {
            if (post == null || !matchesMedia(post)) continue;
            if (post.id == null || post.id.isEmpty()) continue;
            if (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post)) continue;
            visible.add(post);
        }
        orderQualityPosts(visible);
        replacePosts(visible);
        if (visible.isEmpty()) {
            setStatus(qualityCrawlRunning
                    ? "Finding high-resolution Reddit media…"
                    : "No unread high-resolution media is cached yet.", qualityCrawlRunning);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

    private void orderQualityPosts(ArrayList<RedditPost> posts) {
        if (sort.equals("random")) {
            Collections.shuffle(posts);
            return;
        }
        if (sort.equals("new") || sort.equals("rising")) {
            posts.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
            return;
        }
        posts.sort((a, b) -> {
            int quality = Long.compare(qualityRank(b), qualityRank(a));
            if (quality != 0) return quality;
            return Integer.compare(b.score, a.score);
        });
    }

    private void startQualityCrawlIfNeeded(int generation) {
        if (!qualityContextValid(generation) || qualityCrawlRunning || qualityCrawlDone) return;
        qualityCrawlRunning = true;
        qualityAuthorHits.clear();
        crawlQualitySeed(generation, 0, 0);
    }

    private void crawlQualitySeed(int generation, int seedIndex, int windowIndex) {
        if (!qualityContextValid(generation)) return;
        if (seedIndex >= QUALITY_SEED_SUBREDDITS.length) {
            crawlLearnedQualityAuthors(generation);
            return;
        }

        String community = QUALITY_SEED_SUBREDDITS[seedIndex];
        String timeframe = QUALITY_TIME_WINDOWS[windowIndex];
        String path = "/r/" + enc(community)
                + "/top.json?limit=100&raw_json=1&show=all&t=" + enc(timeframe);
        engine.get(path, result -> {
            if (!qualityContextValid(generation)) return;
            if (result.ok) {
                acceptQualityListing(result.jsonObject());
            }

            int nextWindow = windowIndex + 1;
            int nextSeed = seedIndex;
            if (nextWindow >= QUALITY_TIME_WINDOWS.length) {
                nextWindow = 0;
                nextSeed++;
            }
            final int scheduledSeed = nextSeed;
            final int scheduledWindow = nextWindow;
            root.postDelayed(
                    () -> crawlQualitySeed(generation, scheduledSeed, scheduledWindow),
                    180L);
        });
    }

    private void acceptQualityListing(JSONObject rootJson) {
        JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
        JSONArray children = data != null ? data.optJSONArray("children") : null;
        if (children == null) return;

        ArrayList<RedditPost> additions = new ArrayList<>();
        for (int i = 0; i < children.length(); i++) {
            RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
            if (post == null || !qualifiesForQualityCatalog(post)) continue;
            if (post.id == null || post.id.isEmpty()) continue;

            boolean fresh = qualityCatalog.putIfAbsent(post.id, post) == null;
            if (!fresh) continue;

            if (post.author != null && !post.author.isEmpty() && !"[deleted]".equals(post.author)) {
                int weight = qualityRank(post) >= 10_000_000L ? 2 : 1;
                qualityAuthorHits.put(
                        post.author,
                        qualityAuthorHits.getOrDefault(post.author, 0) + weight);
            }

            if (matchesMedia(post)
                    && !hiddenPosts.containsKey(post.id)
                    && !isSavedForUnread(post)
                    && !isContentBlocked(post)) {
                additions.add(post);
            }
        }

        if (!additions.isEmpty()) {
            orderQualityPosts(additions);
            appendUnique(additions);
            hideStatus();
        }
    }

    private boolean qualifiesForQualityCatalog(RedditPost post) {
        if (post.mediaKind != RedditPost.MediaKind.IMAGE
                && post.mediaKind != RedditPost.MediaKind.GALLERY) return false;
        int width = Math.max(0, post.mediaWidth);
        int height = Math.max(0, post.mediaHeight);
        if (width <= 0 || height <= 0) return false;

        long area = (long) width * height;
        int longEdge = Math.max(width, height);
        int shortEdge = Math.min(width, height);
        int gallerySize = post.imageUrls != null ? post.imageUrls.size() : 0;

        boolean highResolution = area >= 6_000_000L
                && longEdge >= 3000
                && shortEdge >= 1400;
        boolean richGallery = post.mediaKind == RedditPost.MediaKind.GALLERY
                && gallerySize >= 3
                && area >= 4_000_000L
                && longEdge >= 2400
                && shortEdge >= 1200;
        return highResolution || richGallery;
    }

    private long qualityRank(RedditPost post) {
        if (post == null) return 0L;
        long area = (long) Math.max(0, post.mediaWidth) * Math.max(0, post.mediaHeight);
        int gallerySize = post.imageUrls != null ? post.imageUrls.size() : 0;
        long galleryBonus = post.mediaKind == RedditPost.MediaKind.GALLERY
                ? Math.min(24, gallerySize) * 600_000L : 0L;
        long redditSignal = Math.min(50_000, Math.max(0, post.score)) * 250L;
        return area + galleryBonus + redditSignal;
    }

    private void crawlLearnedQualityAuthors(int generation) {
        if (!qualityContextValid(generation)) return;
        ArrayList<Map.Entry<String, Integer>> ranked = new ArrayList<>(qualityAuthorHits.entrySet());
        ranked.removeIf(entry -> entry.getValue() < 2);
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        ArrayList<String> authors = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ranked) {
            authors.add(entry.getKey());
            if (authors.size() >= 16) break;
        }
        crawlQualityAuthor(generation, authors, 0);
    }

    private void crawlQualityAuthor(int generation, ArrayList<String> authors, int index) {
        if (!qualityContextValid(generation)) return;
        if (index >= authors.size()) {
            finishQualityCrawl(generation);
            return;
        }

        String author = authors.get(index);
        String path = "/user/" + enc(author)
                + "/submitted.json?limit=100&raw_json=1&show=all&sort=top&t=all";
        engine.get(path, result -> {
            if (!qualityContextValid(generation)) return;
            if (result.ok) acceptQualityListing(result.jsonObject());
            root.postDelayed(() -> crawlQualityAuthor(generation, authors, index + 1), 180L);
        });
    }

    private void finishQualityCrawl(int generation) {
        if (!qualityContextValid(generation)) return;
        qualityCrawlRunning = false;
        qualityCrawlDone = true;
        QualityCatalogStore.save(this, new ArrayList<>(qualityCatalog.values()));
        if (postAdapter.getItemCount() == 0) {
            setStatus("No unread high-resolution media remains in the quality catalog.", false);
        } else {
            hideStatus();
        }
        updateChrome();
    }

'''
s = s.replace(helper_anchor, helpers + helper_anchor, 1)

path.write_text(s)
print('Applied v3.7.1 isolated high-resolution Quality crawl + cache-backed catalog')
