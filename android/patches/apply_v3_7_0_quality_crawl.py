from pathlib import Path
import re


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.7.0 quality target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)


path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# ---------------------------------------------------------------------------
# Quality crawl state. This is intentionally separate from Hidden/Saved state
# and from the existing subreddit/Scrolller reservoirs.
# ---------------------------------------------------------------------------
s = replace_required(
    s,
    '    private final Set<String> savedPostIds = new HashSet<>();\n'
    '    private final ArrayList<RedditPost> deferredAppends = new ArrayList<>();',
    '    private final Set<String> savedPostIds = new HashSet<>();\n'
    '    private final Set<String> qualitySeenPostIds = new HashSet<>();\n'
    '    private final LinkedHashMap<String, Integer> qualitySourceHits = new LinkedHashMap<>();\n'
    '    private final LinkedHashMap<String, Integer> qualityScores = new LinkedHashMap<>();\n'
    '    private int qualityCrawlGeneration = 0;\n'
    '    private boolean qualityCrawlRunning = false;\n'
    '    private final ArrayList<RedditPost> deferredAppends = new ArrayList<>();',
    'quality crawl fields')

# ---------------------------------------------------------------------------
# Add a dedicated Quality feed. It is a supplemental discovery view and does
# not change the meaning of Home, Popular, or an individual subreddit.
# ---------------------------------------------------------------------------
feed_block = '''        Button home = sheetButton("Home" + (context.equals("home") ? "  ✓" : ""));
        Button popular = sheetButton("Popular" + (context.equals("popular") ? "  ✓" : ""));
        body.addView(home, sectionButtonParams());
        body.addView(popular, sectionButtonParams());
        home.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("home", true);
        });
        popular.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("popular", true);
        });'''
feed_replacement = '''        Button home = sheetButton("Home" + (context.equals("home") ? "  ✓" : ""));
        Button popular = sheetButton("Popular" + (context.equals("popular") ? "  ✓" : ""));
        Button quality = sheetButton("Quality crawl" + (context.equals("quality") ? "  ✓" : ""));
        body.addView(home, sectionButtonParams());
        body.addView(popular, sectionButtonParams());
        body.addView(quality, sectionButtonParams());
        home.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("home", true);
        });
        popular.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("popular", true);
        });
        quality.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("quality", true);
        });'''
s = replace_required(s, feed_block, feed_replacement, 'Quality feed selector')

# Quality owns its own ranking, so the normal Reddit sort control is hidden.
s = replace_required(
    s,
    '''        sortButton.setVisibility(
                screen == Screen.FAVORITES ? View.GONE : View.VISIBLE);''',
    '''        sortButton.setVisibility(
                screen == Screen.FAVORITES
                        || (screen == Screen.HOME && context.equals("quality"))
                        ? View.GONE : View.VISIBLE);''',
    'hide ordinary sort in Quality feed')

# Give Quality its own title instead of falling through to Home.
s = replace_required(
    s,
    '''        else if (context.equals("subreddit")) title = "r/" + subreddit;
        else title = context.equals("popular") ? "Popular" : "Home";''',
    '''        else if (context.equals("subreddit")) title = "r/" + subreddit;
        else if (context.equals("quality")) title = "Quality";
        else title = context.equals("popular") ? "Popular" : "Home";''',
    'Quality chrome title')

# ---------------------------------------------------------------------------
# Quality crawl implementation.
# Phase 1 probes subscription groups using Top all/year/month. Phase 2 learns
# which subreddits actually yielded the most high-resolution posts and deepens
# those sources individually. No hardcoded topic/subreddit list is required.
# ---------------------------------------------------------------------------
anchor = '    private void loadFeed(boolean reset) {'
if anchor not in s:
    raise SystemExit('Missing v3.7.0 quality target: loadFeed anchor')

methods = r'''    private void loadQualityFeed(boolean reset) {
        if (!engine.isReady()) return;

        qualityCrawlGeneration++;
        final int generation = qualityCrawlGeneration;
        qualityCrawlRunning = true;
        qualitySeenPostIds.clear();
        qualitySourceHits.clear();
        qualityScores.clear();
        feedSeenPostIds.clear();
        feedSeenCursors.clear();
        after = "";

        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        setStatus("Scanning subscriptions for high-resolution media…", true);
        updateChrome();

        ArrayList<String> groups = qualityProbeGroups();
        crawlQualityProbe(generation, groups, 0, 0);
    }

    private boolean qualityContextStillValid(int generation) {
        return generation == qualityCrawlGeneration
                && screen == Screen.HOME
                && context.equals("quality");
    }

    private ArrayList<String> qualityProbeGroups() {
        ArrayList<String> names = new ArrayList<>();
        for (Subscription sub : subscriptions) {
            if (sub == null || sub.name == null) continue;
            String clean = sub.name.replaceAll("[^A-Za-z0-9_]", "");
            if (!clean.isEmpty()) names.add(clean);
        }
        Collections.shuffle(names);

        ArrayList<String> groups = new ArrayList<>();
        if (names.isEmpty()) {
            groups.add("__popular__");
            return groups;
        }
        for (int i = 0; i < names.size(); i += 10) {
            StringBuilder multi = new StringBuilder();
            int end = Math.min(names.size(), i + 10);
            for (int j = i; j < end; j++) {
                if (multi.length() > 0) multi.append('+');
                multi.append(names.get(j));
            }
            if (multi.length() > 0) groups.add(multi.toString());
        }
        return groups;
    }

    private void crawlQualityProbe(
            int generation,
            ArrayList<String> groups,
            int groupIndex,
            int windowIndex) {
        if (!qualityContextStillValid(generation)) return;
        if (groupIndex >= groups.size()) {
            ArrayList<String> bestSources = bestQualitySources(12);
            if (bestSources.isEmpty()) {
                finishQualityCrawl(generation);
            } else {
                crawlQualityDeep(generation, bestSources, 0, 0);
            }
            return;
        }

        String[] windows = {"all", "year", "month"};
        if (windowIndex >= windows.length) {
            crawlQualityProbe(generation, groups, groupIndex + 1, 0);
            return;
        }

        String group = groups.get(groupIndex);
        String path = qualityProbePath(group, windows[windowIndex]);
        engine.get(path, result -> {
            if (!qualityContextStillValid(generation)) return;
            if (result.ok) acceptQualityListing(result.jsonObject());

            int nextGroup = groupIndex;
            int nextWindow = windowIndex + 1;
            if (nextWindow >= windows.length) {
                nextWindow = 0;
                nextGroup++;
            }
            final int g = nextGroup;
            final int w = nextWindow;
            root.postDelayed(() -> crawlQualityProbe(generation, groups, g, w), 220L);
        });
    }

    private String qualityProbePath(String group, String timeframe) {
        String base = group.equals("__popular__")
                ? "/r/popular"
                : "/r/" + group;
        return base + "/top.json?limit=100&raw_json=1&show=all&t=" + timeframe;
    }

    private ArrayList<String> bestQualitySources(int maxSources) {
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(qualitySourceHits.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        ArrayList<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            String clean = entry.getKey() == null ? ""
                    : entry.getKey().replaceAll("[^A-Za-z0-9_]", "");
            if (clean.isEmpty()) continue;
            result.add(clean);
            if (result.size() >= maxSources) break;
        }
        return result;
    }

    private void crawlQualityDeep(
            int generation,
            ArrayList<String> sources,
            int sourceIndex,
            int mode) {
        if (!qualityContextStillValid(generation)) return;
        if (sourceIndex >= sources.size()) {
            finishQualityCrawl(generation);
            return;
        }
        if (mode >= 4) {
            crawlQualityDeep(generation, sources, sourceIndex + 1, 0);
            return;
        }

        String subredditName = sources.get(sourceIndex);
        String path = qualityDeepPath(subredditName, mode);
        engine.get(path, result -> {
            if (!qualityContextStillValid(generation)) return;
            if (result.ok) acceptQualityListing(result.jsonObject());

            int nextSource = sourceIndex;
            int nextMode = mode + 1;
            if (nextMode >= 4) {
                nextMode = 0;
                nextSource++;
            }
            final int sIndex = nextSource;
            final int m = nextMode;
            root.postDelayed(() -> crawlQualityDeep(generation, sources, sIndex, m), 220L);
        });
    }

    private String qualityDeepPath(String subredditName, int mode) {
        String base = "/r/" + subredditName;
        if (mode == 0) return base + "/top.json?limit=100&raw_json=1&show=all&t=all";
        if (mode == 1) return base + "/top.json?limit=100&raw_json=1&show=all&t=year";
        if (mode == 2) return base + "/top.json?limit=100&raw_json=1&show=all&t=month";
        return base + "/new.json?limit=100&raw_json=1&show=all";
    }

    private void acceptQualityListing(JSONObject rootJson) {
        JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
        JSONArray children = data != null ? data.optJSONArray("children") : null;
        if (children == null) return;

        ArrayList<RedditPost> additions = new ArrayList<>();
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            int qualityScore = qualityScoreForChild(child);
            if (qualityScore < 0) continue;

            RedditPost post = RedditPost.fromChild(child);
            if (post == null || !matchesMedia(post)) continue;
            if (post.id == null || post.id.isEmpty()) continue;
            if (!qualitySeenPostIds.add(post.id)) continue;
            if (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post)) continue;

            qualityScores.put(post.id, qualityScore);
            if (post.subreddit != null && !post.subreddit.isEmpty()) {
                Integer current = qualitySourceHits.get(post.subreddit);
                qualitySourceHits.put(post.subreddit, current == null ? 1 : current + 1);
            }
            additions.add(post);
        }

        if (additions.isEmpty()) return;
        additions.sort((a, b) -> Integer.compare(
                qualityScores.getOrDefault(b.id, 0),
                qualityScores.getOrDefault(a.id, 0)));
        appendUnique(additions);
        hideStatus();
    }

    private int qualityScoreForChild(JSONObject child) {
        JSONObject d = child != null ? child.optJSONObject("data") : null;
        if (d == null) return -1;

        JSONObject metadata = d.optJSONObject("media_metadata");
        if (metadata != null && metadata.length() > 0) {
            JSONArray names = metadata.names();
            long totalArea = 0L;
            long maxArea = 0L;
            int maxEdge = 0;
            int count = 0;
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    JSONObject item = metadata.optJSONObject(names.optString(i, ""));
                    JSONObject source = item != null ? item.optJSONObject("s") : null;
                    int width = source != null ? source.optInt("x", 0) : 0;
                    int height = source != null ? source.optInt("y", 0) : 0;
                    if (width <= 0 || height <= 0) continue;
                    long area = (long) width * (long) height;
                    totalArea += area;
                    maxArea = Math.max(maxArea, area);
                    maxEdge = Math.max(maxEdge, Math.max(width, height));
                    count++;
                }
            }
            if (count >= 2) {
                long averageArea = totalArea / count;
                boolean high = maxEdge >= 2400
                        && (averageArea >= 3500000L || maxArea >= 8000000L);
                if (!high) return -1;
                long score = averageArea / 10000L + (long) count * 45L;
                if (maxArea >= 8000000L) score += 700L;
                if (maxArea >= 16000000L) score += 700L;
                return (int) Math.min(10000L, score);
            }
        }

        int videoWidth = 0;
        int videoHeight = 0;
        JSONObject secureMedia = d.optJSONObject("secure_media");
        JSONObject redditVideo = secureMedia != null ? secureMedia.optJSONObject("reddit_video") : null;
        if (redditVideo == null) {
            JSONObject preview = d.optJSONObject("preview");
            redditVideo = preview != null ? preview.optJSONObject("reddit_video_preview") : null;
        }
        if (redditVideo != null) {
            videoWidth = redditVideo.optInt("width", 0);
            videoHeight = redditVideo.optInt("height", 0);
        }
        if (videoWidth > 0 && videoHeight > 0) {
            long area = (long) videoWidth * (long) videoHeight;
            int longEdge = Math.max(videoWidth, videoHeight);
            int shortEdge = Math.min(videoWidth, videoHeight);
            if (area < 1600000L || longEdge < 1600 || shortEdge < 900) return -1;
            long score = area / 5000L;
            if (shortEdge >= 1080) score += 650L;
            if (longEdge >= 2160) score += 650L;
            return (int) Math.min(10000L, score);
        }

        JSONObject preview = d.optJSONObject("preview");
        JSONArray images = preview != null ? preview.optJSONArray("images") : null;
        JSONObject first = images != null ? images.optJSONObject(0) : null;
        JSONObject source = first != null ? first.optJSONObject("source") : null;
        int width = source != null ? source.optInt("width", 0) : 0;
        int height = source != null ? source.optInt("height", 0) : 0;
        if (width <= 0 || height <= 0) return -1;

        long area = (long) width * (long) height;
        int longEdge = Math.max(width, height);
        if (area < 3500000L || longEdge < 2400) return -1;

        long score = area / 10000L;
        if (area >= 8000000L) score += 700L;
        if (area >= 16000000L) score += 700L;
        return (int) Math.min(10000L, score);
    }

    private void finishQualityCrawl(int generation) {
        if (!qualityContextStillValid(generation)) return;
        qualityCrawlRunning = false;
        if (postAdapter.getItemCount() == 0) {
            setStatus("No high-resolution unread media matched the current filters.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

'''
s = s.replace(anchor, methods + anchor, 1)

# Route only the Quality context to the new crawler; all existing Reddit and
# Scrolller feed semantics remain untouched.
s = replace_required(
    s,
    '''    private void loadFeed(boolean reset) {
        if (loading || !engine.isReady()) return;''',
    '''    private void loadFeed(boolean reset) {
        if (context.equals("quality")) {
            loadQualityFeed(reset);
            return;
        }
        if (loading || !engine.isReady()) return;''',
    'route Quality feed')

path.write_text(s)
print('Applied v3.7.0 self-tuning high-resolution Quality crawl')
