from pathlib import Path


def replace_java_method(text, signature, replacement, label):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Missing v3.7.5 search/random Java method: {label}: {signature}")
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"Missing v3.7.5 search/random opening brace: {label}")
    depth = 0
    in_string = False
    escaped = False
    for i in range(brace, len(text)):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                while end < len(text) and text[end] in "\r\n":
                    end += 1
                return text[:start] + replacement + text[end:]
    raise SystemExit(f"Unbalanced v3.7.5 search/random Java method: {label}")


# ---------------------------------------------------------------------------
# PullPush hidden search index. Comments are never rendered. Comment hits only
# contribute their parent submission IDs, which are then fetched through Reddit
# and rendered as ordinary media posts.
# ---------------------------------------------------------------------------
client = Path("app/src/main/java/com/scrolller/adblock/PullPushSearchClient.java")
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PullPushSearchClient {
    interface Callback {
        void onComplete(ArrayList<String> submissionIds);
        void onError(String error);
    }

    private static final String BASE = "https://api.pullpush.io";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PullPushSearchClient() {}

    static void searchLeadIns(
            String query,
            Set<String> allowedSubreddits,
            String exactSubreddit,
            Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                Set<String> allowed = new HashSet<>();
                if (allowedSubreddits != null) {
                    for (String value : allowedSubreddits) {
                        if (value != null && !value.trim().isEmpty()) {
                            allowed.add(value.trim().toLowerCase(Locale.US));
                        }
                    }
                }

                String subredditArg = exactSubreddit == null ? "" : exactSubreddit.trim();
                collectTopics(request("/topic", query, subredditArg), allowed, ids);
                collectComments(request("/comment", query, subredditArg), allowed, ids);

                ArrayList<String> result = new ArrayList<>(ids);
                MAIN.post(() -> callback.onComplete(result));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "PullPush search failed" : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    private static JSONObject request(String endpoint, String query, String subreddit) throws Exception {
        StringBuilder url = new StringBuilder(BASE)
                .append(endpoint)
                .append("?q=")
                .append(enc(query))
                .append("&size=100&sort=desc&lang_id=regex");
        if (subreddit != null && !subreddit.isEmpty()) {
            url.append("&subreddit=").append(enc(subreddit));
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "RedditMedia/3.7.5 hidden-search-index");

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
            throw new IllegalStateException("PullPush HTTP " + status);
        }
        return new JSONObject(text.toString());
    }

    private static void collectTopics(
            JSONObject root, Set<String> allowed, LinkedHashSet<String> ids) {
        JSONArray data = root != null ? root.optJSONArray("data") : null;
        if (data == null) return;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !allowed(item, allowed)) continue;
            String id = item.optString("id", "").trim();
            if (id.startsWith("t3_")) id = id.substring(3);
            id = id.replaceAll("[^A-Za-z0-9]", "");
            if (!id.isEmpty()) ids.add("t3_" + id);
        }
    }

    private static void collectComments(
            JSONObject root, Set<String> allowed, LinkedHashSet<String> ids) {
        JSONArray data = root != null ? root.optJSONArray("data") : null;
        if (data == null) return;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !allowed(item, allowed)) continue;
            String linkId = item.optString("link_id", "").trim();
            if (linkId.isEmpty()) continue;
            if (!linkId.startsWith("t3_")) linkId = "t3_" + linkId;
            String suffix = linkId.substring(3).replaceAll("[^A-Za-z0-9]", "");
            if (!suffix.isEmpty()) ids.add("t3_" + suffix);
        }
    }

    private static boolean allowed(JSONObject item, Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) return true;
        String subreddit = item.optString("subreddit", "").trim().toLowerCase(Locale.US);
        return !subreddit.isEmpty() && allowed.contains(subreddit);
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.toString());
    }
}
''')


# ---------------------------------------------------------------------------
# RedditPost local search metadata. This remains hidden and does not change the
# visible post card. Flair/selftext/domain can therefore participate in matching.
# ---------------------------------------------------------------------------
post_path = Path("app/src/main/java/com/scrolller/adblock/RedditPost.java")
p = post_path.read_text()
field_anchor = "    public final int mediaHeight;\n"
if field_anchor not in p:
    raise SystemExit("Missing v3.7.5 search metadata field anchor")
if "public String searchMetadata" not in p:
    p = p.replace(field_anchor, field_anchor + '    public String searchMetadata = "";\n', 1)

method_start = p.find("    public static RedditPost fromChild(JSONObject child) {")
method_end = p.find("    private static ParsedMedia parseMedia(JSONObject d) {", method_start)
if method_start < 0 or method_end < 0:
    raise SystemExit("Missing v3.7.5 RedditPost.fromChild region")
segment = p[method_start:method_end]
if "RedditPost post = new RedditPost(" not in segment:
    if "        return new RedditPost(\n" not in segment:
        raise SystemExit("Missing v3.7.5 RedditPost constructor return")
    segment = segment.replace("        return new RedditPost(\n", "        RedditPost post = new RedditPost(\n", 1)
    tail = '''                media.height
        );
    }

'''
    replacement_tail = '''                media.height
        );
        post.searchMetadata = decode(data.optString("link_flair_text", "")) + " "
                + decode(data.optString("author_flair_text", "")) + " "
                + decode(data.optString("selftext", "")) + " "
                + data.optString("domain", "") + " "
                + data.optString("post_hint", "") + " "
                + data.optString("subreddit_name_prefixed", "");
        return post;
    }

'''
    if tail not in segment:
        raise SystemExit("Missing v3.7.5 RedditPost.fromChild tail")
    segment = segment.replace(tail, replacement_tail, 1)
p = p[:method_start] + segment + p[method_end:]
post_path.write_text(p)


# ---------------------------------------------------------------------------
# MainActivity: richer search + fair Home Random subscription rounds.
# ---------------------------------------------------------------------------
main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
s = main_path.read_text()

state_anchor = '    private final ArrayList<String> searchCategoryCommunities = new ArrayList<>();\n'
if state_anchor not in s:
    raise SystemExit("Missing v3.7.5 search/random state anchor")
state_block = '''    private final ArrayList<String> homeRandomRound = new ArrayList<>();
    private final HashSet<String> homeRandomSeenPostIds = new HashSet<>();
    private int homeRandomRoundIndex = 0;
    private int homeRandomGeneration = 0;
'''
if "homeRandomRound" not in s:
    s = s.replace(state_anchor, state_anchor + state_block, 1)

# Fix/normalize category scope descriptions after the category patch and make all
# Search chrome labels explicit.
search_scope_description = r'''    private String searchScopeDescription() {
        if (searchScope.equals("subscribed")) return "subscriptions";
        if (searchScope.equals("favorites")) return "Favorites";
        if (searchScope.equals("collector")) return "Collector";
        if (searchScope.equals("category")) {
            return searchCategoryName.isEmpty() ? "a category" : searchCategoryName;
        }
        if (searchScope.equals("subreddit")) {
            return searchSubreddit.isEmpty() ? "a subreddit" : "r/" + searchSubreddit;
        }
        return "global Reddit";
    }

'''
s = replace_java_method(
    s,
    "    private String searchScopeDescription() {",
    search_scope_description,
    "searchScopeDescription",
)

search_scope_label = r'''    private String searchScopeLabel() {
        if (searchScope.equals("subscribed")) return "Subs";
        if (searchScope.equals("favorites")) return "Saved";
        if (searchScope.equals("collector")) return "Collector";
        if (searchScope.equals("category")) {
            return searchCategoryName.isEmpty() ? "Category" : searchCategoryName;
        }
        if (searchScope.equals("subreddit")) {
            return searchSubreddit.isEmpty() ? "Subreddit" : "r/" + searchSubreddit;
        }
        return "Global";
    }

'''
s = replace_java_method(
    s,
    "    private String searchScopeLabel() {",
    search_scope_label,
    "searchScopeLabel",
)

matches_local = r'''    private boolean matchesLocalSearch(RedditPost post, String value) {
        if (post == null) return false;
        String normalizedQuery = normalizeLocalSearch(value);
        if (normalizedQuery.isEmpty()) return true;
        String haystack = normalizeLocalSearch(
                (post.title == null ? "" : post.title) + " "
                + (post.subreddit == null ? "" : post.subreddit) + " "
                + (post.author == null ? "" : post.author) + " "
                + (post.permalink == null ? "" : post.permalink) + " "
                + (post.sourceUrl == null ? "" : post.sourceUrl) + " "
                + (post.searchMetadata == null ? "" : post.searchMetadata) + " "
                + categoryLabelsForSubreddit(post.subreddit));
        String[] tokens = normalizedQuery.split(" ");
        for (String token : tokens) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

'''
s = replace_java_method(
    s,
    "    private boolean matchesLocalSearch(RedditPost post, String value) {",
    matches_local,
    "matchesLocalSearch",
)

relevance = r'''    private int localSearchRelevance(RedditPost post) {
        String nq = normalizeLocalSearch(query);
        String title = normalizeLocalSearch(post.title);
        String community = normalizeLocalSearch(post.subreddit);
        String author = normalizeLocalSearch(post.author);
        String metadata = normalizeLocalSearch(post.searchMetadata);
        String categories = normalizeLocalSearch(categoryLabelsForSubreddit(post.subreddit));
        int score = 0;
        if (!nq.isEmpty() && title.contains(nq)) score += 240;
        if (!nq.isEmpty() && community.equals(nq)) score += 220;
        if (!nq.isEmpty() && author.equals(nq)) score += 180;
        if (!nq.isEmpty() && metadata.contains(nq)) score += 150;
        if (!nq.isEmpty() && categories.contains(nq)) score += 140;
        for (String token : nq.split(" ")) {
            if (token.isEmpty()) continue;
            if (title.contains(token)) score += 32;
            if (community.contains(token)) score += 20;
            if (author.contains(token)) score += 14;
            if (metadata.contains(token)) score += 12;
            if (categories.contains(token)) score += 10;
        }
        score += Math.min(80, Math.max(0, post.score) / 100);
        return score;
    }

'''
s = replace_java_method(
    s,
    "    private int localSearchRelevance(RedditPost post) {",
    relevance,
    "localSearchRelevance",
)

finish_search = r'''    private void finishSearchCollection(int generation, ArrayList<RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        loading = false;
        if (sort.equals("random")) {
            Collections.shuffle(collected);
        } else if (sort.equals("oldest")) {
            collected.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
        }
        replacePosts(collected);
        finishSearchUi(collected.size());
        startHiddenSearchLeadIns(generation);
        startCategoryMetadataLeadIns(generation);
    }

'''
s = replace_java_method(
    s,
    "    private void finishSearchCollection(int generation, ArrayList<RedditPost> collected) {",
    finish_search,
    "finishSearchCollection",
)

# Search helper insertion before user-profile loading keeps it inside the class and
# outside the source-aware Search replacement block.
search_helper_anchor = "    private void loadUserProfileInternal() {"
if search_helper_anchor not in s:
    raise SystemExit("Missing v3.7.5 search helper anchor")
search_helpers = r'''    private String categoryLabelsForSubreddit(String subredditName) {
        String clean = cleanSubredditName(subredditName);
        if (clean.isEmpty()) return "";
        StringBuilder labels = new StringBuilder();
        HashSet<String> seen = new HashSet<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            boolean contains = false;
            for (String community : row[3].split(",")) {
                if (clean.equalsIgnoreCase(cleanSubredditName(community))) {
                    contains = true;
                    break;
                }
            }
            if (!contains) continue;
            for (int i = 0; i < 3; i++) {
                String label = row[i];
                String key = label.toLowerCase(Locale.US);
                if (seen.add(key)) {
                    if (labels.length() > 0) labels.append(' ');
                    labels.append(label);
                }
            }
        }
        return labels.toString();
    }

    private Set<String> hiddenSearchAllowedSubreddits() {
        if (searchScope.equals("subreddit")) {
            HashSet<String> one = new HashSet<>();
            String clean = cleanSubredditName(searchSubreddit);
            if (!clean.isEmpty()) one.add(clean.toLowerCase(Locale.US));
            return one;
        }
        if (searchScope.equals("subscribed")) {
            HashSet<String> allowed = new HashSet<>();
            for (Subscription sub : subscriptions) {
                if (sub == null) continue;
                String clean = cleanSubredditName(sub.name);
                if (!clean.isEmpty()) allowed.add(clean.toLowerCase(Locale.US));
            }
            return allowed;
        }
        if (searchScope.equals("category")) {
            HashSet<String> allowed = new HashSet<>();
            for (String community : searchCategoryCommunities) {
                String clean = cleanSubredditName(community);
                if (!clean.isEmpty()) allowed.add(clean.toLowerCase(Locale.US));
            }
            return allowed;
        }
        return null;
    }

    private void startHiddenSearchLeadIns(int generation) {
        if (!searchStillValid(generation) || query == null || query.trim().isEmpty()) return;
        if (searchScope.equals("collector") || searchScope.equals("favorites")) return;

        Set<String> allowed = hiddenSearchAllowedSubreddits();
        String exactSubreddit = searchScope.equals("subreddit")
                ? cleanSubredditName(searchSubreddit) : "";
        PullPushSearchClient.searchLeadIns(query, allowed, exactSubreddit,
                new PullPushSearchClient.Callback() {
            @Override
            public void onComplete(ArrayList<String> submissionIds) {
                if (!searchStillValid(generation) || submissionIds == null || submissionIds.isEmpty()) return;
                fetchSearchLeadInPosts(generation, submissionIds, 0, new ArrayList<>());
            }

            @Override
            public void onError(String error) {
                // Hidden index failure must never break ordinary Reddit search.
            }
        });
    }

    private void fetchSearchLeadInPosts(
            int generation,
            ArrayList<String> ids,
            int offset,
            ArrayList<RedditPost> additions) {
        if (!searchStillValid(generation)) return;
        if (offset >= ids.size()) {
            if (!additions.isEmpty()) {
                if (sort.equals("random")) Collections.shuffle(additions);
                else if (sort.equals("oldest")) additions.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
                appendUnique(additions);
                if (postAdapter.getItemCount() > 0) hideStatus();
                updateChrome();
            }
            return;
        }

        int end = Math.min(ids.size(), offset + 50);
        StringBuilder joined = new StringBuilder();
        for (int i = offset; i < end; i++) {
            String id = ids.get(i);
            if (id == null || id.isEmpty()) continue;
            if (joined.length() > 0) joined.append(',');
            joined.append(id);
        }
        if (joined.length() == 0) {
            fetchSearchLeadInPosts(generation, ids, end, additions);
            return;
        }

        String path = "/by_id/" + joined + ".json?raw_json=1&show=all";
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !matchesMedia(post)) continue;
                        if (post.id == null || post.id.isEmpty()) continue;
                        if (hiddenPosts.containsKey(post.id)
                                || isSavedForUnread(post)
                                || isContentBlocked(post)) continue;
                        additions.add(post);
                    }
                }
            }
            fetchSearchLeadInPosts(generation, ids, end, additions);
        });
    }

    private ArrayList<String> categoryCommunitiesMatchingSearch(String value) {
        String nq = normalizeLocalSearch(value);
        ArrayList<String> matches = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if (nq.isEmpty()) return matches;
        for (String[] row : CURATED_CATEGORY_ROWS) {
            String labels = normalizeLocalSearch(row[0] + " " + row[1] + " " + row[2]);
            if (!labels.contains(nq) && !nq.contains(labels)) continue;
            for (String community : row[3].split(",")) {
                String clean = cleanSubredditName(community);
                String key = clean.toLowerCase(Locale.US);
                if (!clean.isEmpty() && seen.add(key)) matches.add(clean);
            }
        }
        return matches;
    }

    private void startCategoryMetadataLeadIns(int generation) {
        if (!searchStillValid(generation)) return;
        ArrayList<String> communities = categoryCommunitiesMatchingSearch(query);
        if (communities.isEmpty()) return;

        Set<String> allowed = hiddenSearchAllowedSubreddits();
        if (allowed != null && !allowed.isEmpty()) {
            communities.removeIf(name -> !allowed.contains(name.toLowerCase(Locale.US)));
        }
        if (communities.isEmpty()) return;

        ArrayList<String> groups = new ArrayList<>();
        for (int i = 0; i < communities.size(); i += 10) {
            StringBuilder group = new StringBuilder();
            int end = Math.min(communities.size(), i + 10);
            for (int j = i; j < end; j++) {
                if (group.length() > 0) group.append('+');
                group.append(communities.get(j));
            }
            if (group.length() > 0) groups.add(group.toString());
        }
        fetchCategoryMetadataGroup(generation, groups, 0);
    }

    private void fetchCategoryMetadataGroup(int generation, ArrayList<String> groups, int index) {
        if (!searchStillValid(generation) || index >= groups.size()) return;
        String path = "/r/" + groups.get(index) + "/new.json?limit=100&raw_json=1&show=all";
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (result.ok) {
                ArrayList<RedditPost> additions = parseListing(result.jsonObject(), true);
                appendUnique(additions);
                if (postAdapter.getItemCount() > 0) hideStatus();
            }
            fetchCategoryMetadataGroup(generation, groups, index + 1);
        });
    }

'''
s = s.replace(search_helper_anchor, search_helpers + search_helper_anchor, 1)

# Home Random now means one post per subscribed subreddit per round.
load_feed_sig = "    private void loadFeed(boolean reset) {"
load_feed_start = s.find(load_feed_sig)
if load_feed_start < 0:
    raise SystemExit("Missing v3.7.5 Home Random loadFeed")
load_feed_brace = s.find("{", load_feed_start + len(load_feed_sig))
random_gate = '''
        if (screen == Screen.HOME
                && context.equals("home")
                && sort.equals("random")
                && !username.isEmpty()
                && !subscriptions.isEmpty()) {
            loadHomeSubscriptionRandom(reset);
            return;
        }
'''
if "loadHomeSubscriptionRandom(reset);" not in s[load_feed_start:load_feed_start + 900]:
    s = s[:load_feed_brace + 1] + random_gate + s[load_feed_brace + 1:]

random_helpers = r'''    private boolean homeSubscriptionRandomEnabled() {
        return screen == Screen.HOME
                && context.equals("home")
                && sort.equals("random")
                && !username.isEmpty()
                && !subscriptions.isEmpty();
    }

    private void loadHomeSubscriptionRandom(boolean reset) {
        if (!engine.isReady()) return;
        if (loading && !reset) return;

        if (reset) {
            feedGeneration++;
            homeRandomGeneration++;
            loading = false;
            after = "";
            homeRandomRound.clear();
            homeRandomRoundIndex = 0;
            homeRandomSeenPostIds.clear();
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            deferredAppends.clear();
            deferredAppendScheduled = false;
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Shuffling subscriptions…", true);
        }

        if (homeRandomRoundIndex >= homeRandomRound.size()) {
            prepareHomeRandomRound();
        }
        if (homeRandomRound.isEmpty()) {
            loading = false;
            setStatus("No subscriptions are available for Random.", false);
            return;
        }

        final int feedGen = feedGeneration;
        final int randomGen = homeRandomGeneration;
        loading = true;
        fetchHomeRandomRoundNext(feedGen, randomGen);
    }

    private void prepareHomeRandomRound() {
        homeRandomRound.clear();
        homeRandomRoundIndex = 0;
        HashSet<String> seen = new HashSet<>();
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            String key = clean.toLowerCase(Locale.US);
            if (!clean.isEmpty() && seen.add(key)) homeRandomRound.add(clean);
        }
        Collections.shuffle(homeRandomRound);
    }

    private boolean homeRandomContextValid(int feedGen, int randomGen) {
        return feedGen == feedGeneration
                && randomGen == homeRandomGeneration
                && homeSubscriptionRandomEnabled();
    }

    private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {
        if (!homeRandomContextValid(feedGen, randomGen)) return;
        if (homeRandomRoundIndex >= homeRandomRound.size()) {
            loading = false;
            after = "home-random-round";
            if (postAdapter.getItemCount() == 0) {
                setStatus("No matching media was found across your subscriptions.", false);
            } else {
                hideStatus();
            }
            updateChrome();
            restorePendingPosition();
            return;
        }

        final String targetSubreddit = homeRandomRound.get(homeRandomRoundIndex++);
        String path = "/r/" + enc(targetSubreddit)
                + "/new.json?limit=100&raw_json=1&show=all";
        engine.get(path, result -> {
            if (!homeRandomContextValid(feedGen, randomGen)) return;
            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                ArrayList<RedditPost> candidates = new ArrayList<>();
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !matchesMedia(post)) continue;
                        if (post.id == null || post.id.isEmpty()) continue;
                        if (hiddenPosts.containsKey(post.id)
                                || isSavedForUnread(post)
                                || isContentBlocked(post)) continue;
                        String key = canonicalPostKey(post);
                        if (key.isEmpty() || homeRandomSeenPostIds.contains(key)) continue;
                        candidates.add(post);
                    }
                }

                if (!candidates.isEmpty()) {
                    Collections.shuffle(candidates);
                    RedditPost selected = candidates.get(0);
                    String key = canonicalPostKey(selected);
                    if (!key.isEmpty()) {
                        homeRandomSeenPostIds.add(key);
                        feedSeenPostIds.add(key);
                    }
                    ArrayList<RedditPost> one = new ArrayList<>();
                    one.add(selected);
                    appendUnique(one);
                    hideStatus();
                }
            }

            root.postDelayed(
                    () -> fetchHomeRandomRoundNext(feedGen, randomGen),
                    55L);
        });
    }

'''
random_anchor = "    private String listingPath(String cursor) {"
if random_anchor not in s:
    raise SystemExit("Missing v3.7.5 Home Random helper anchor")
s = s.replace(random_anchor, random_helpers + random_anchor, 1)

# Infinite-scroll trigger for round-based Home Random does not depend on Reddit's
# aggregate feed cursor. The next complete subscription round starts near the end.
old_pager = '''                if (screen == Screen.HOME && !loading && !after.isEmpty()
                        && position >= postAdapter.getItemCount() - 5) {
                    loadFeed(false);
                }'''
new_pager = '''                if (screen == Screen.HOME && !loading
                        && (homeSubscriptionRandomEnabled() || !after.isEmpty())
                        && position >= postAdapter.getItemCount() - 5) {
                    loadFeed(false);
                }'''
if old_pager not in s:
    raise SystemExit("Missing v3.7.5 Home Random pager trigger")
s = s.replace(old_pager, new_pager, 1)

old_grid = '''                if (screen == Screen.HOME && !loading && !after.isEmpty()
                        && last >= gridAdapter.getItemCount() - 8) {
                    loadFeed(false);
                }'''
new_grid = '''                if (screen == Screen.HOME && !loading
                        && (homeSubscriptionRandomEnabled() || !after.isEmpty())
                        && last >= gridAdapter.getItemCount() - 8) {
                    loadFeed(false);
                }'''
if old_grid not in s:
    raise SystemExit("Missing v3.7.5 Home Random grid trigger")
s = s.replace(old_grid, new_grid, 1)

# If subscriptions finish loading after startup, replace any temporary aggregate
# Random feed with the fair per-subscription round immediately.
finish_sub_sig = "    private void finishSubscriptions(@Nullable Runnable done) {"
finish_sub_start = s.find(finish_sub_sig)
if finish_sub_start < 0:
    raise SystemExit("Missing v3.7.5 finishSubscriptions")
finish_sub_end = s.find("    private NavState captureState() {", finish_sub_start)
if finish_sub_end < 0:
    raise SystemExit("Missing v3.7.5 finishSubscriptions end")
finish_segment = s[finish_sub_start:finish_sub_end]
reload_line = '''        if (screen == Screen.HOME && context.equals("home") && sort.equals("random")
                && !subscriptions.isEmpty()) loadFeed(true);
'''
if reload_line not in finish_segment:
    marker = "        if (screen == Screen.ACCOUNT) renderAccount();\n"
    if marker not in finish_segment:
        raise SystemExit("Missing v3.7.5 finishSubscriptions marker")
    finish_segment = finish_segment.replace(marker, marker + reload_line, 1)
    s = s[:finish_sub_start] + finish_segment + s[finish_sub_end:]

main_path.write_text(s)
print("Applied v3.7.5 hidden search index and fair Home Random subscription rounds")
