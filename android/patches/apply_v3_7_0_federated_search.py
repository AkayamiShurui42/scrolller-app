from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.7.0 federated-search target: {label}\n{old[:1200]}')
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Arctic Shift client. The archive API supports keyword search when a subreddit
# or author is supplied. Global search therefore uses candidate subreddits found
# by the live Reddit engine and deepens those candidates through this client.
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArcticShiftClient {
    interface Callback {
        void onComplete(JSONArray items);
        void onError(String error);
    }

    private static final String ENDPOINT =
            "https://arctic-shift.photon-reddit.com/api/posts/search";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private ArcticShiftClient() {}

    static void searchSubreddit(String subreddit, String query, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
                JSONArray keyword = request(subreddit, "query", query);
                merge(merged, keyword);

                String creator = creatorCandidate(query);
                if (!creator.isEmpty()) {
                    JSONArray author = request(subreddit, "author", creator);
                    merge(merged, author);
                }

                JSONArray result = new JSONArray();
                for (JSONObject item : merged.values()) result.put(item);
                MAIN.post(() -> callback.onComplete(result));
            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Arctic Shift request failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static JSONArray request(String subreddit, String field, String value) throws Exception {
        String url = ENDPOINT
                + "?subreddit=" + enc(subreddit)
                + "&" + field + "=" + enc(value)
                + "&limit=100&sort=desc";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "RedditMedia/3.7.0 Android");

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
        JSONObject root = new JSONObject(text.toString());
        JSONArray data = root.optJSONArray("data");
        return data != null ? data : new JSONArray();
    }

    private static void merge(LinkedHashMap<String, JSONObject> merged, JSONArray items) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if (id.isEmpty()) id = "row-" + i + "-" + item.optString("url", "");
            merged.putIfAbsent(id, item);
        }
    }

    private static String creatorCandidate(String query) {
        if (query == null) return "";
        String clean = query.trim();
        String lower = clean.toLowerCase(Locale.US);
        if (lower.startsWith("u/")) clean = clean.substring(2);
        else if (lower.startsWith("/u/")) clean = clean.substring(3);
        if (!clean.matches("[A-Za-z0-9_-]{1,32}")) return "";
        return clean;
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
# MainActivity: Reddit remains the first-stage live search because it can search
# globally. Its result set seeds candidate communities for engines that only
# support subreddit-scoped searching. All engines then merge into one canonical
# result set and are sorted exactly once.
# ---------------------------------------------------------------------------
path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

old_finish = '''    private void finishSearchCollection(int generation, ArrayList<RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        loading = false;
        if (sort.equals("random")) Collections.shuffle(collected);
        replacePosts(collected);
        finishSearchUi(collected.size());
    }
'''

new_finish = '''    private void finishSearchCollection(int generation, ArrayList<RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        beginFederatedSearch(generation, collected);
    }

    private void beginFederatedSearch(int generation, ArrayList<RedditPost> redditResults) {
        if (!searchStillValid(generation)) return;

        LinkedHashMap<String, RedditPost> merged = new LinkedHashMap<>();
        HashSet<String> mediaKeys = new HashSet<>();
        for (RedditPost post : redditResults) {
            addFederatedSearchPost(merged, mediaKeys, post);
        }

        ArrayList<String> targets = federatedSearchTargets(redditResults);
        if (targets.isEmpty()) {
            finishFederatedSearch(generation, merged);
            return;
        }

        final int[] remainingEngines = {2};
        ArrayList<String> scrolllerTargets = scrolllerSearchTargets(targets, redditResults);
        searchScrolllerTargets(
                generation, scrolllerTargets, 0, merged, mediaKeys,
                () -> federatedEngineFinished(generation, merged, remainingEngines));
        searchArcticTargets(
                generation, targets, 0, merged, mediaKeys,
                () -> federatedEngineFinished(generation, merged, remainingEngines));
    }

    private ArrayList<String> federatedSearchTargets(ArrayList<RedditPost> redditResults) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (searchScope.equals("subreddit")) {
            String target = cleanSubredditName(searchSubreddit);
            if (!target.isEmpty()) unique.add(target);
        } else if (searchScope.equals("subscribed")) {
            for (Subscription sub : subscriptions) {
                if (sub == null) continue;
                String target = cleanSubredditName(sub.name);
                if (!target.isEmpty()) unique.add(target);
            }
        } else if (searchScope.equals("global")) {
            for (RedditPost post : redditResults) {
                if (post == null) continue;
                String target = cleanSubredditName(post.subreddit);
                if (!target.isEmpty()) unique.add(target);
                if (unique.size() >= 36) break;
            }
            // If Reddit returned only a handful of communities, use the user's
            // subscriptions as additional historical candidates rather than
            // pretending Arctic Shift can do unrestricted global keyword search.
            if (unique.size() < 12) {
                for (Subscription sub : subscriptions) {
                    if (sub == null) continue;
                    String target = cleanSubredditName(sub.name);
                    if (!target.isEmpty()) unique.add(target);
                    if (unique.size() >= 18) break;
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private ArrayList<String> scrolllerSearchTargets(
            ArrayList<String> allTargets, ArrayList<RedditPost> redditResults) {
        if (searchScope.equals("subreddit")) return new ArrayList<>(allTargets);

        // Scrolller exposes subreddit-oriented random traversal rather than a
        // dependable global full-text endpoint. Deepen the communities already
        // implicated by Reddit first, and cap the expansion so one query cannot
        // turn into hundreds of GraphQL crawls.
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (RedditPost post : redditResults) {
            if (post == null) continue;
            String target = cleanSubredditName(post.subreddit);
            if (!target.isEmpty() && allTargets.contains(target)) ordered.add(target);
            if (ordered.size() >= 16) break;
        }
        for (String target : allTargets) {
            if (ordered.size() >= 16) break;
            ordered.add(target);
        }
        return new ArrayList<>(ordered);
    }

    private void searchScrolllerTargets(
            int generation,
            ArrayList<String> targets,
            int index,
            LinkedHashMap<String, RedditPost> merged,
            Set<String> mediaKeys,
            Runnable complete) {
        if (!searchStillValid(generation)) return;
        if (index >= targets.size()) {
            complete.run();
            return;
        }

        String target = targets.get(index);
        setStatus("Searching Scrolller · r/" + target + "…", true);
        ScrolllerClient.crawlSubreddit(target, 50, new ScrolllerClient.Callback() {
            @Override
            public void onBatch(JSONArray items) {
                if (!searchStillValid(generation)) return;
                for (int i = 0; i < items.length(); i++) {
                    RedditPost post = RedditPost.fromScrolller(items.optJSONObject(i));
                    if (post == null || !matchesLocalSearch(post, query)) continue;
                    addFederatedSearchPost(merged, mediaKeys, post);
                }
            }

            @Override
            public void onComplete() {
                if (!searchStillValid(generation)) return;
                searchScrolllerTargets(
                        generation, targets, index + 1, merged, mediaKeys, complete);
            }

            @Override
            public void onError(String error) {
                if (!searchStillValid(generation)) return;
                searchScrolllerTargets(
                        generation, targets, index + 1, merged, mediaKeys, complete);
            }
        });
    }

    private void searchArcticTargets(
            int generation,
            ArrayList<String> targets,
            int index,
            LinkedHashMap<String, RedditPost> merged,
            Set<String> mediaKeys,
            Runnable complete) {
        if (!searchStillValid(generation)) return;
        if (index >= targets.size()) {
            complete.run();
            return;
        }

        String target = targets.get(index);
        setStatus("Searching Arctic Shift · r/" + target + "…", true);
        ArcticShiftClient.searchSubreddit(target, query, new ArcticShiftClient.Callback() {
            @Override
            public void onComplete(JSONArray items) {
                if (!searchStillValid(generation)) return;
                for (int i = 0; i < items.length(); i++) {
                    RedditPost post = redditPostFromArctic(items.optJSONObject(i));
                    if (post == null || !matchesLocalSearch(post, query)) continue;
                    addFederatedSearchPost(merged, mediaKeys, post);
                }
                searchArcticTargets(
                        generation, targets, index + 1, merged, mediaKeys, complete);
            }

            @Override
            public void onError(String error) {
                if (!searchStillValid(generation)) return;
                searchArcticTargets(
                        generation, targets, index + 1, merged, mediaKeys, complete);
            }
        });
    }

    private RedditPost redditPostFromArctic(JSONObject item) {
        if (item == null) return null;
        try {
            JSONObject data = new JSONObject(item.toString());
            String id = data.optString("id", "");
            if (!id.isEmpty() && data.optString("name", "").isEmpty()) {
                data.put("name", "t3_" + id);
            }
            if (data.optString("permalink", "").isEmpty() && !id.isEmpty()) {
                String sr = cleanSubredditName(data.optString("subreddit", ""));
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

    private void addFederatedSearchPost(
            LinkedHashMap<String, RedditPost> merged,
            Set<String> mediaKeys,
            RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        if (!matchesMedia(post)) return;
        if (hiddenPosts.containsKey(post.id) || isSavedForUnread(post) || isContentBlocked(post)) return;
        if (merged.containsKey(post.id)) return;

        String mediaKey = federatedMediaKey(post);
        if (!mediaKey.isEmpty() && !mediaKeys.add(mediaKey)) return;
        merged.put(post.id, post);
    }

    private String federatedMediaKey(RedditPost post) {
        if (post == null) return "";
        String value = post.videoUrl != null && !post.videoUrl.isEmpty()
                ? post.videoUrl : post.sourceUrl;
        if ((value == null || value.isEmpty()) && post.imageUrls != null && !post.imageUrls.isEmpty()) {
            value = post.imageUrls.get(0);
        }
        if (value == null) return "";
        String clean = value.trim().toLowerCase(Locale.US);
        int question = clean.indexOf('?');
        if (question > 0) clean = clean.substring(0, question);
        return clean;
    }

    private void federatedEngineFinished(
            int generation,
            LinkedHashMap<String, RedditPost> merged,
            int[] remainingEngines) {
        if (!searchStillValid(generation)) return;
        remainingEngines[0]--;
        if (remainingEngines[0] <= 0) finishFederatedSearch(generation, merged);
    }

    private void finishFederatedSearch(
            int generation, LinkedHashMap<String, RedditPost> merged) {
        if (!searchStillValid(generation)) return;
        ArrayList<RedditPost> results = new ArrayList<>(merged.values());
        sortLocalSearch(results);
        loading = false;
        replacePosts(results);
        finishSearchUi(results.size());
    }
'''

s = replace_required(s, old_finish, new_finish, 'federated search completion')
path.write_text(s)
print('Applied v3.7.0 federated Reddit + Scrolller + Arctic Shift search')
