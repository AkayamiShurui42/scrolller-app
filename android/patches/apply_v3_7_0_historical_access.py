from pathlib import Path
import re


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.7.0 historical target: {label}\n{old[:1000]}')
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Arctic Shift: posts only. No comments are queried anywhere in this client.
# It supports deep historical traversal by subreddit/author and exact post ID.
# ---------------------------------------------------------------------------
client = Path('app/src/main/java/com/scrolller/adblock/ArcticShiftClient.java')
client.write_text(r'''package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArcticShiftClient {
    interface CrawlCallback {
        void onBatch(JSONArray items);
        void onComplete();
        void onError(String error);
    }

    interface LookupCallback {
        void onComplete(JSONArray items);
        void onError(String error);
    }

    private static final String BASE = "https://arctic-shift.photon-reddit.com/api";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private ArcticShiftClient() {}

    static void crawlSubreddit(String subreddit, int maxItems, CrawlCallback callback) {
        crawl("subreddit", subreddit, maxItems, callback);
    }

    static void crawlAuthor(String author, int maxItems, CrawlCallback callback) {
        crawl("author", author, maxItems, callback);
    }

    static void lookupPost(String redditId, LookupCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                String clean = cleanPostId(redditId);
                if (clean.isEmpty()) throw new IllegalArgumentException("Invalid Reddit post ID");
                String url = BASE + "/posts/ids?ids=" + enc(clean);
                JSONObject root = getJson(url);
                JSONArray data = root.optJSONArray("data");
                JSONArray result = data != null ? data : new JSONArray();
                MAIN.post(() -> callback.onComplete(result));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Arctic Shift lookup failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static void crawl(String field, String value, int maxItems, CrawlCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                int delivered = 0;
                String before = "";
                Set<String> seenBefore = new HashSet<>();
                while (delivered < maxItems) {
                    int limit = Math.min(100, maxItems - delivered);
                    StringBuilder url = new StringBuilder(BASE)
                            .append("/posts/search?")
                            .append(field).append('=').append(enc(value))
                            .append("&limit=").append(limit)
                            .append("&sort=desc");
                    if (!before.isEmpty()) url.append("&before=").append(enc(before));

                    JSONObject root = getJson(url.toString());
                    JSONArray data = root.optJSONArray("data");
                    if (data == null || data.length() == 0) break;

                    delivered += data.length();
                    JSONArray batch = data;
                    MAIN.post(() -> callback.onBatch(batch));

                    long oldest = Long.MAX_VALUE;
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.optJSONObject(i);
                        if (item == null) continue;
                        long created = createdUtc(item.opt("created_utc"));
                        if (created > 0 && created < oldest) oldest = created;
                    }
                    if (oldest == Long.MAX_VALUE || data.length() < limit) break;
                    String nextBefore = isoUtc(Math.max(1L, oldest - 1L));
                    if (nextBefore.isEmpty() || !seenBefore.add(nextBefore)) break;
                    before = nextBefore;

                    try { Thread.sleep(220L); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                MAIN.post(callback::onComplete);
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Arctic Shift request failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "RedditMedia/3.7.0 Android historical-access");

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Arctic Shift HTTP " + status);
        }
        return new JSONObject(text.toString());
    }

    private static long createdUtc(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        String text = String.valueOf(value).trim();
        try { return (long) Double.parseDouble(text); } catch (Exception ignored) {}
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd HH:mm:ss"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = parser.parse(text);
                if (parsed != null) return parsed.getTime() / 1000L;
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private static String isoUtc(long epochSeconds) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date(epochSeconds * 1000L));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String cleanPostId(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.startsWith("t3_")) clean = clean.substring(3);
        int comments = clean.indexOf("/comments/");
        if (comments >= 0) {
            String tail = clean.substring(comments + "/comments/".length());
            int slash = tail.indexOf('/');
            clean = slash >= 0 ? tail.substring(0, slash) : tail;
        }
        return clean.matches("[A-Za-z0-9]+") ? clean : "";
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }
}
''')


# ---------------------------------------------------------------------------
# MainActivity: integrate archive discovery with subreddit feeds, user profiles,
# and exact archive lookups. Archive results use the exact same Unread rejection
# rules as live Reddit/Scrolller: Hidden, Saved and blocked content stay excluded.
# ---------------------------------------------------------------------------
path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# State alongside Scrolller/archive-prefetch state.
s = replace_required(
    s,
    '    private boolean scrolllerPrefetchDone = false;\n',
    '''    private boolean scrolllerPrefetchDone = false;
    private boolean historicalPrefetchRunning = false;
    private boolean historicalPrefetchDone = false;
''',
    'historical prefetch fields')

s = replace_required(
    s,
    '''            scrolllerPrefetchRunning = false;
            scrolllerPrefetchDone = false;
            replacePosts(new ArrayList<>());''',
    '''            scrolllerPrefetchRunning = false;
            scrolllerPrefetchDone = false;
            historicalPrefetchRunning = false;
            historicalPrefetchDone = false;
            replacePosts(new ArrayList<>());''',
    'historical reset with fresh feed')

# Begin Arctic Shift alongside the existing subreddit reservoir. Normal live
# feeds only supplement Random; failures can force archive fallback regardless.
s = replace_required(
    s,
    '        prefetchScrolllerSubredditIfNeeded();\n\n        if (!loading',
    '        prefetchScrolllerSubredditIfNeeded();\n        prefetchHistoricalSubredditIfNeeded(false);\n\n        if (!loading',
    'start historical subreddit reservoir')

# Live Reddit failure on a subreddit falls through to archive instead of ending.
feed_error_pattern = re.compile(
    r'''            if \(!result\.ok\) \{\n                loading = false;\n                if \(postAdapter\.getItemCount\(\) == 0\) \{\n                    setStatus\("Reddit feed failed: " \+ friendlyError\(result\), false\);\n                \}\n                return;\n            \}''')
feed_error_replacement = '''            if (!result.ok) {
                loading = false;
                if (screen == Screen.HOME && context.equals("subreddit")
                        && subreddit != null && !subreddit.isEmpty()) {
                    setStatus("Live Reddit unavailable; loading historical r/" + subreddit + "…", true);
                    prefetchHistoricalSubredditIfNeeded(true);
                    return;
                }
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Reddit feed failed: " + friendlyError(result), false);
                }
                return;
            }'''
s, count = feed_error_pattern.subn(feed_error_replacement, s, count=1)
if count != 1:
    raise SystemExit('Missing v3.7.0 historical target: live feed error fallback')

# Direct archive syntax is intentionally narrow so ordinary Search semantics are
# untouched: r/name, u/name and post:<id>.
search_anchor = '    private void loadSearchInternal() {'
if search_anchor not in s:
    raise SystemExit('Missing v3.7.0 historical target: loadSearchInternal')
s = s.replace(
    search_anchor,
    search_anchor + '\n        if (tryHistoricalDirectSearch()) return;',
    1)

# User profiles: if live Reddit fails (deleted/suspended account), load archived
# submissions. If live succeeds, append older archived submissions afterward.
user_method_match = re.search(
    r'    private void fetchUserPage\(String cursor, ArrayList<RedditPost> collected, int page\) \{.*?\n    \}\n\n    private void loadFavorites\(',
    s,
    flags=re.S)
if not user_method_match:
    raise SystemExit('Missing v3.7.0 historical target: fetchUserPage block')
user_block = user_method_match.group(0)

old_user_error = '''            if (!result.ok) {
                loading = false;
                setStatus("Could not load u/" + profileUser + ": " + friendlyError(result), false);
                return;
            }'''
new_user_error = '''            if (!result.ok) {
                loading = false;
                setStatus("Live profile unavailable; loading archived u/" + profileUser + "…", true);
                loadHistoricalUserProfile(true);
                return;
            }'''
if old_user_error not in user_block:
    raise SystemExit('Missing v3.7.0 historical target: user error block')
user_block = user_block.replace(old_user_error, new_user_error, 1)

old_user_finish = '''            updateChrome();
            restorePendingPosition();
        });
    }

    private void loadFavorites('''
new_user_finish = '''            updateChrome();
            restorePendingPosition();
            loadHistoricalUserProfile(false);
        });
    }

    private void loadFavorites('''
if old_user_finish not in user_block:
    raise SystemExit('Missing v3.7.0 historical target: user completion block')
user_block = user_block.replace(old_user_finish, new_user_finish, 1)
s = s[:user_method_match.start()] + user_block + s[user_method_match.end():]

# Insert all historical helpers immediately before the existing Scrolller method.
helper_anchor = '    private void prefetchScrolllerSubredditIfNeeded() {'
if helper_anchor not in s:
    raise SystemExit('Missing v3.7.0 historical target: Scrolller helper anchor')

helpers = r'''    private void prefetchHistoricalSubredditIfNeeded(boolean forceFallback) {
        if (historicalPrefetchRunning || historicalPrefetchDone) return;
        if (screen != Screen.HOME || !context.equals("subreddit")) return;
        if (subreddit == null || subreddit.isEmpty()) return;
        if (!forceFallback && !sort.equals("random")) return;

        historicalPrefetchRunning = true;
        final int generation = archivePrefetchGeneration;
        final String targetSubreddit = subreddit;
        ArcticShiftClient.crawlSubreddit(targetSubreddit, 2400, new ArcticShiftClient.CrawlCallback() {
            @Override
            public void onBatch(JSONArray items) {
                if (!historicalSubredditContextValid(generation, targetSubreddit)) return;
                ArrayList<RedditPost> additions = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    RedditPost post = redditPostFromArcticArchive(items.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!feedSeenPostIds.add(post.id)) continue;
                    if (hiddenPosts.containsKey(post.id)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    additions.add(post);
                }
                if (!additions.isEmpty()) {
                    if (sort.equals("random")) Collections.shuffle(additions);
                    appendUnique(additions);
                    hideStatus();
                }
            }

            @Override
            public void onComplete() {
                if (generation != archivePrefetchGeneration) return;
                historicalPrefetchRunning = false;
                historicalPrefetchDone = true;
                if (historicalSubredditContextValid(generation, targetSubreddit)
                        && postAdapter.getItemCount() == 0) {
                    setStatus("No unread live or archived media remains in r/" + targetSubreddit + ".", false);
                }
            }

            @Override
            public void onError(String error) {
                if (generation != archivePrefetchGeneration) return;
                historicalPrefetchRunning = false;
                historicalPrefetchDone = true;
                if (historicalSubredditContextValid(generation, targetSubreddit)
                        && postAdapter.getItemCount() == 0) {
                    setStatus("Historical r/" + targetSubreddit + " unavailable: " + error, false);
                }
            }
        });
    }

    private boolean historicalSubredditContextValid(int generation, String targetSubreddit) {
        return generation == archivePrefetchGeneration
                && screen == Screen.HOME
                && context.equals("subreddit")
                && subreddit != null
                && subreddit.equalsIgnoreCase(targetSubreddit);
    }

    private void loadHistoricalUserProfile(boolean replaceEmpty) {
        if (profileUser == null || profileUser.isEmpty()) return;
        final String targetUser = profileUser;
        ArcticShiftClient.crawlAuthor(targetUser, 2400, new ArcticShiftClient.CrawlCallback() {
            private boolean replaced = false;

            @Override
            public void onBatch(JSONArray items) {
                if (screen != Screen.USER || !targetUser.equalsIgnoreCase(profileUser)) return;
                ArrayList<RedditPost> additions = archivePostsFromArray(items);
                if (additions.isEmpty()) return;
                if (replaceEmpty && !replaced && postAdapter.getItemCount() == 0) {
                    replaced = true;
                    replacePosts(additions);
                } else {
                    appendUnique(additions);
                }
                hideStatus();
                updateChrome();
            }

            @Override
            public void onComplete() {
                if (screen != Screen.USER || !targetUser.equalsIgnoreCase(profileUser)) return;
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("No unread archived media found for u/" + targetUser + ".", false);
                }
            }

            @Override
            public void onError(String error) {
                if (screen != Screen.USER || !targetUser.equalsIgnoreCase(profileUser)) return;
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Archived u/" + targetUser + " unavailable: " + error, false);
                }
            }
        });
    }

    private boolean tryHistoricalDirectSearch() {
        if (query == null) return false;
        String clean = query.trim();
        String lower = clean.toLowerCase(Locale.US);

        if (lower.startsWith("r/") || lower.startsWith("/r/")) {
            String target = clean.substring(lower.startsWith("/r/") ? 3 : 2).trim();
            target = target.replaceFirst("/.*$", "");
            if (!target.matches("[A-Za-z0-9_]{1,64}")) return false;
            loadHistoricalSearchCollection("subreddit", target);
            return true;
        }

        if (lower.startsWith("u/") || lower.startsWith("/u/")) {
            String target = clean.substring(lower.startsWith("/u/") ? 3 : 2).trim();
            target = target.replaceFirst("/.*$", "");
            if (!target.matches("[A-Za-z0-9_-]{1,32}")) return false;
            loadHistoricalSearchCollection("author", target);
            return true;
        }

        if (lower.startsWith("post:")) {
            String id = clean.substring(5).trim();
            loadHistoricalPostLookup(id);
            return true;
        }

        return false;
    }

    private void loadHistoricalSearchCollection(String kind, String target) {
        loading = true;
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        setStatus("Searching historical " + (kind.equals("author") ? "u/" : "r/") + target + "…", true);

        ArcticShiftClient.CrawlCallback callback = new ArcticShiftClient.CrawlCallback() {
            @Override
            public void onBatch(JSONArray items) {
                if (screen != Screen.SEARCH) return;
                ArrayList<RedditPost> additions = archivePostsFromArray(items);
                if (!additions.isEmpty()) {
                    appendUnique(additions);
                    hideStatus();
                }
            }

            @Override
            public void onComplete() {
                if (screen != Screen.SEARCH) return;
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("No unread archived media found for "
                            + (kind.equals("author") ? "u/" : "r/") + target + ".", false);
                } else {
                    hideStatus();
                }
                updateChrome();
            }

            @Override
            public void onError(String error) {
                if (screen != Screen.SEARCH) return;
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Historical lookup failed: " + error, false);
                }
            }
        };

        if (kind.equals("author")) ArcticShiftClient.crawlAuthor(target, 2400, callback);
        else ArcticShiftClient.crawlSubreddit(target, 2400, callback);
    }

    private void loadHistoricalPostLookup(String redditId) {
        loading = true;
        replacePosts(new ArrayList<>());
        setStatus("Looking up archived post…", true);
        ArcticShiftClient.lookupPost(redditId, new ArcticShiftClient.LookupCallback() {
            @Override
            public void onComplete(JSONArray items) {
                if (screen != Screen.SEARCH) return;
                loading = false;
                ArrayList<RedditPost> posts = archivePostsFromArray(items);
                replacePosts(posts);
                if (postAdapter.getItemCount() == 0) {
                    setStatus("That archived post has no recoverable unread media.", false);
                } else {
                    hideStatus();
                }
                updateChrome();
            }

            @Override
            public void onError(String error) {
                if (screen != Screen.SEARCH) return;
                loading = false;
                setStatus("Archived post lookup failed: " + error, false);
            }
        });
    }

    private ArrayList<RedditPost> archivePostsFromArray(JSONArray items) {
        ArrayList<RedditPost> posts = new ArrayList<>();
        if (items == null) return posts;
        for (int i = 0; i < items.length(); i++) {
            RedditPost post = redditPostFromArcticArchive(items.optJSONObject(i));
            if (post == null || !matchesMedia(post)) continue;
            if (post.id == null || post.id.isEmpty()) continue;
            if (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post)) continue;
            posts.add(post);
        }
        return posts;
    }

    private RedditPost redditPostFromArcticArchive(JSONObject item) {
        if (item == null) return null;
        try {
            JSONObject data = new JSONObject(item.toString());
            String id = data.optString("id", "");
            if (id.isEmpty()) return null;
            if (data.optString("name", "").isEmpty()) data.put("name", "t3_" + id);
            if (data.optString("permalink", "").isEmpty()) {
                String sr = data.optString("subreddit", "");
                data.put("permalink", "/r/" + sr + "/comments/" + id + "/");
            }
            if (data.optString("url_overridden_by_dest", "").isEmpty()
                    && !data.optString("url", "").isEmpty()) {
                data.put("url_overridden_by_dest", data.optString("url", ""));
            }
            JSONObject child = new JSONObject();
            child.put("data", data);
            return RedditPost.fromChild(child);
        } catch (Exception ignored) {
            return null;
        }
    }

'''
s = s.replace(helper_anchor, helpers + helper_anchor, 1)

path.write_text(s)
print('Applied v3.7.0 Arctic Shift historical subreddit/account/post access')
