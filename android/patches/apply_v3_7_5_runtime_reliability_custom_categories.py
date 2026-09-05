from pathlib import Path


def replace_java_method(text, signature, replacement, label):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Missing v3.7.5 runtime method: {label}: {signature}")
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"Missing v3.7.5 runtime opening brace: {label}")
    depth = 0
    in_string = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    escaped = False
    i = brace
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == "'":
                in_char = False
            i += 1
            continue
        if ch == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue
        if ch == '"':
            in_string = True
        elif ch == "'":
            in_char = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                while end < len(text) and text[end] in "\r\n":
                    end += 1
                return text[:start] + replacement.rstrip() + "\n\n" + text[end:]
        i += 1
    raise SystemExit(f"Unbalanced v3.7.5 runtime method: {label}")


path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
s = path.read_text()

# ---------------------------------------------------------------------------
# 1) Reddit feed reliability.
#
# v3.7.4/v3.7.5 could crawl 20 pages for a client-side ordering mode before
# returning anything. That is both visually indistinguishable from a dead feed
# and unnecessarily aggressive against Reddit's session endpoints. Render the
# first useful page immediately, use bounded reservoirs, and pace follow-up pages.
# ---------------------------------------------------------------------------
feed_pages = r'''    private void fetchFeedPages(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected,
            int page) {
        if (generation != feedGeneration || screen != Screen.HOME) return;

        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !feedSeenCursors.add(cursor)) {
            after = "";
            finishFeedCollection(generation, reset, collected);
            return;
        }

        String path = listingPath(cursor);
        engine.get(path, result -> {
            if (generation != feedGeneration || screen != Screen.HOME) return;
            if (!result.ok) {
                // If at least one live page already arrived, keep it instead of
                // turning a later pagination/rate-limit failure into a dead feed.
                if (!collected.isEmpty()) {
                    after = "";
                    finishFeedCollection(generation, reset, collected);
                    return;
                }
                loading = false;
                if (context.equals("subreddit") && subreddit != null && !subreddit.isEmpty()) {
                    setStatus("Live Reddit unavailable; loading historical r/" + subreddit + "…", true);
                    prefetchHistoricalSubredditIfNeeded(true);
                    return;
                }
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Reddit feed failed: " + friendlyError(result), false);
                }
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    String key = canonicalPostKey(post);
                    if (key.isEmpty() || !feedSeenPostIds.add(key)) continue;
                    if (hiddenPosts.containsKey(post.id)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            after = next;

            // Make subreddit/feed entry feel immediate. Final ordering still runs
            // once the small reservoir is complete.
            if (reset && page == 0 && !collected.isEmpty()) {
                replacePosts(new ArrayList<>(collected));
                hideStatus();
                updateChrome();
            }

            boolean topAll = sort.equals("top") && topTime.equals("all");
            boolean random = sort.equals("random");
            boolean oldest = sort.equals("oldest");
            int target = oldest ? 350 : random ? 120 : 30;
            int pageLimit = topAll ? 12 : oldest ? 5 : random ? 3 : 4;
            boolean canContinue = !next.isEmpty()
                    && !feedSeenCursors.contains(next)
                    && page + 1 < pageLimit;
            boolean needMore = topAll || random || oldest || collected.size() < target;

            if (canContinue && needMore) {
                root.postDelayed(
                        () -> fetchFeedPages(generation, reset, collected, page + 1),
                        180L);
                return;
            }

            finishFeedCollection(generation, reset, collected);
        });
    }'''

s = replace_java_method(
    s,
    "    private void fetchFeedPages(",
    feed_pages,
    "bounded paced feed pagination",
)

# ---------------------------------------------------------------------------
# 2) Search reliability and responsiveness.
#
# The old implementation could fetch up to 50 pages per search group and showed
# nothing until the crawl was done. Bound it, pace it, and surface the first page
# immediately. Subscriptions/categories search one page per 10-community group;
# global and single-subreddit search may inspect up to four pages.
# ---------------------------------------------------------------------------
remote_search = r'''    private void fetchRemoteSearchGroup(
            int generation,
            ArrayList<String> groups,
            int groupIndex,
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors,
            int page,
            int rawFetched) {
        if (!searchStillValid(generation)) return;
        if (groupIndex >= groups.size()) {
            finishSearchCollection(generation, collected);
            return;
        }
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            fetchRemoteSearchGroup(
                    generation, groups, groupIndex + 1, "", collected,
                    seenPostIds, new HashSet<>(), 0, 0);
            return;
        }

        String group = groups.get(groupIndex);
        String path = remoteSearchPath(group, cursor, rawFetched);
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (!result.ok) {
                // A failed group should not poison successful groups. Global and
                // single-subreddit searches still expose the useful Reddit error
                // when nothing at all has been returned.
                if (groupIndex + 1 < groups.size()) {
                    final int nextGroup = groupIndex + 1;
                    root.postDelayed(() -> fetchRemoteSearchGroup(
                            generation, groups, nextGroup, "", collected,
                            seenPostIds, new HashSet<>(), 0, 0), 220L);
                    return;
                }
                if (!collected.isEmpty()) {
                    finishSearchCollection(generation, collected);
                } else {
                    loading = false;
                    setStatus("Search failed: " + friendlyError(result), false);
                }
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            int pageRawCount = children != null ? children.length() : 0;
            int before = collected.size();
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!seenPostIds.add(post.id)) continue;
                    if (hiddenPosts.containsKey(post.id)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    collected.add(post);
                }
            }

            // First useful page becomes visible now rather than after the entire
            // multi-page/multi-group search finishes.
            if (before == 0 && !collected.isEmpty() && postAdapter.getItemCount() == 0) {
                replacePosts(new ArrayList<>(collected));
                hideStatus();
                updateChrome();
            }

            String next = data != null ? data.optString("after", "") : "";
            int nextRawFetched = rawFetched + pageRawCount;
            boolean deepSingleSource = searchScope.equals("global")
                    || searchScope.equals("subreddit");
            int maxPages = deepSingleSource ? 4 : 1;
            boolean canContinue = !next.isEmpty()
                    && !seenCursors.contains(next)
                    && page + 1 < maxPages;

            if (canContinue) {
                root.postDelayed(() -> fetchRemoteSearchGroup(
                        generation, groups, groupIndex, next, collected,
                        seenPostIds, seenCursors, page + 1, nextRawFetched), 180L);
                return;
            }

            if (groupIndex + 1 < groups.size()) {
                final int nextGroup = groupIndex + 1;
                setStatus("Searching " + searchScopeDescription() + " · "
                        + (nextGroup + 1) + "/" + groups.size() + "…", true);
                root.postDelayed(() -> fetchRemoteSearchGroup(
                        generation, groups, nextGroup, "", collected,
                        seenPostIds, new HashSet<>(), 0, 0), 220L);
                return;
            }

            finishSearchCollection(generation, collected);
        });
    }'''

s = replace_java_method(
    s,
    "    private void fetchRemoteSearchGroup(",
    remote_search,
    "bounded streaming Search",
)

# ---------------------------------------------------------------------------
# 3) Home Random request pressure.
#
# Preserve the one-community-per-round behavior, but process it in small chunks.
# A single load may issue at most 12 subreddit requests once there is something
# visible, and the next chunk is triggered by normal near-end pagination.
# ---------------------------------------------------------------------------
state_anchor = "    private int homeRandomGeneration = 0;\n"
if state_anchor not in s:
    raise SystemExit("Missing v3.7.5 Home Random generation state")
if "homeRandomRequestsThisLoad" not in s:
    s = s.replace(
        state_anchor,
        state_anchor + "    private int homeRandomRequestsThisLoad = 0;\n",
        1,
    )

load_random_sig = "    private void loadHomeSubscriptionRandom(boolean reset) {"
load_random_start = s.find(load_random_sig)
if load_random_start < 0:
    raise SystemExit("Missing v3.7.5 Home Random loader")
load_random_end_sig = "    private void prepareHomeRandomRound() {"
load_random_end = s.find(load_random_end_sig, load_random_start)
if load_random_end < 0:
    raise SystemExit("Missing v3.7.5 Home Random loader boundary")
load_random_segment = s[load_random_start:load_random_end]
old_start = '''        final int feedGen = feedGeneration;
        final int randomGen = homeRandomGeneration;
        loading = true;
        fetchHomeRandomRoundNext(feedGen, randomGen);'''
new_start = '''        final int feedGen = feedGeneration;
        final int randomGen = homeRandomGeneration;
        homeRandomRequestsThisLoad = 0;
        loading = true;
        fetchHomeRandomRoundNext(feedGen, randomGen);'''
if old_start not in load_random_segment:
    raise SystemExit("Missing v3.7.5 Home Random chunk start")
load_random_segment = load_random_segment.replace(old_start, new_start, 1)
s = s[:load_random_start] + load_random_segment + s[load_random_end:]

random_next = r'''    private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {
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

        if (homeRandomRequestsThisLoad >= 12 && postAdapter.getItemCount() > 0) {
            loading = false;
            after = "home-random-round";
            hideStatus();
            updateChrome();
            restorePendingPosition();
            return;
        }

        final String targetSubreddit = homeRandomRound.get(homeRandomRoundIndex++);
        homeRandomRequestsThisLoad++;
        String path = "/r/" + enc(targetSubreddit)
                + "/new.json?limit=35&raw_json=1&show=all";
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

            // Keep Reddit request cadence sane. This is intentionally much slower
            // than the old 55 ms loop and is chunked by normal pagination.
            root.postDelayed(
                    () -> fetchHomeRandomRoundNext(feedGen, randomGen),
                    280L);
        });
    }'''

s = replace_java_method(
    s,
    "    private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {",
    random_next,
    "paced chunked Home Random",
)

# ---------------------------------------------------------------------------
# 4) User-created Categories.
#
# Categories were curated/read-only. Add a persistent creator. The explicit
# first + second subreddit inputs make the minimum-two rule obvious; additional
# communities can be supplied comma-separated.
# ---------------------------------------------------------------------------
category_root = r'''    private void showCategoryRoot() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Categories");
        scroll.addView(body);

        Button addCategory = sheetButton("＋ Add category · pick at least two subreddits");
        body.addView(addCategory, sectionButtonParams());
        addCategory.setOnClickListener(v -> {
            dialog.dismiss();
            showAddCustomCategorySheet();
        });

        ArrayList<String> customNames = customCategoryNames();
        if (!customNames.isEmpty()) {
            TextView mineTitle = sectionTitle("My categories");
            LinearLayout.LayoutParams mineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mineParams.topMargin = dp(16);
            body.addView(mineTitle, mineParams);
            for (String name : customNames) {
                String[] communities = customCategoryCommunities(name);
                Button mine = sheetButton(name + " · " + communities.length + " communities");
                body.addView(mine, sectionButtonParams());
                mine.setOnClickListener(v -> {
                    dialog.dismiss();
                    showCustomCategory(name);
                });
            }
        }

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search categories, subcategories, or communities");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFF8E8E8E);
        search.setTextSize(14);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(rounded(0xE51A1A1A, 14));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        searchParams.topMargin = dp(12);
        body.addView(search, searchParams);

        Button find = sheetButton("Search category folders");
        body.addView(find, sectionButtonParams());
        find.setOnClickListener(v -> {
            String term = search.getText().toString().trim();
            if (term.isEmpty()) {
                search.requestFocus();
                return;
            }
            dialog.dismiss();
            showCategoryMatches(term);
        });

        TextView modeTitle = sectionTitle("Browse built-in categories");
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mtp.topMargin = dp(16);
        body.addView(modeTitle, mtp);

        Button sfw = sheetButton("SFW folders");
        Button nsfw = sheetButton("NSFW folders");
        body.addView(sfw, sectionButtonParams());
        body.addView(nsfw, sectionButtonParams());
        sfw.setOnClickListener(v -> {
            dialog.dismiss();
            showCategorySafety("SFW");
        });
        nsfw.setOnClickListener(v -> {
            dialog.dismiss();
            showCategorySafety("NSFW");
        });

        dialog.setContentView(scroll);
        dialog.show();
    }'''

s = replace_java_method(
    s,
    "    private void showCategoryRoot() {",
    category_root,
    "custom Categories root",
)

custom_methods_anchor = "    private void showCategorySafety(String safety) {"
if custom_methods_anchor not in s:
    raise SystemExit("Missing v3.7.5 custom category insertion anchor")
custom_methods = r'''    private JSONObject customCategoryStore() {
        try {
            return new JSONObject(prefs.getString("customCategories", "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private ArrayList<String> customCategoryNames() {
        ArrayList<String> names = new ArrayList<>();
        JSONObject store = customCategoryStore();
        JSONArray keys = store.names();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String name = keys.optString(i, "").trim();
                if (!name.isEmpty()) names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private String[] customCategoryCommunities(String name) {
        ArrayList<String> communities = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        JSONObject store = customCategoryStore();
        JSONArray values = store.optJSONArray(name);
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                String clean = cleanSubredditName(values.optString(i, ""));
                String key = clean.toLowerCase(Locale.US);
                if (!clean.isEmpty() && seen.add(key)) communities.add(clean);
            }
        }
        return communities.toArray(new String[0]);
    }

    private void showAddCustomCategorySheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Add category");
        scroll.addView(body);

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Category name");
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(0xFF8E8E8E);
        name.setBackground(rounded(0xE51A1A1A, 14));
        name.setPadding(dp(12), 0, dp(12), 0);
        body.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        EditText first = new EditText(this);
        first.setSingleLine(true);
        first.setHint("First subreddit");
        first.setTextColor(Color.WHITE);
        first.setHintTextColor(0xFF8E8E8E);
        first.setBackground(rounded(0xE51A1A1A, 14));
        first.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        fieldParams.topMargin = dp(10);
        body.addView(first, fieldParams);

        EditText second = new EditText(this);
        second.setSingleLine(true);
        second.setHint("Second subreddit");
        second.setTextColor(Color.WHITE);
        second.setHintTextColor(0xFF8E8E8E);
        second.setBackground(rounded(0xE51A1A1A, 14));
        second.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        secondParams.topMargin = dp(10);
        body.addView(second, secondParams);

        EditText more = new EditText(this);
        more.setSingleLine(false);
        more.setMinLines(2);
        more.setHint("More subreddits (optional, comma separated)");
        more.setTextColor(Color.WHITE);
        more.setHintTextColor(0xFF8E8E8E);
        more.setBackground(rounded(0xE51A1A1A, 14));
        more.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        moreParams.topMargin = dp(10);
        body.addView(more, moreParams);

        Button save = sheetButton("Save category");
        body.addView(save, sectionButtonParams());
        save.setOnClickListener(v -> {
            String categoryName = name.getText().toString().trim();
            String firstName = cleanSubredditName(first.getText().toString());
            String secondName = cleanSubredditName(second.getText().toString());
            if (categoryName.isEmpty()) {
                name.setError("Name this category");
                name.requestFocus();
                return;
            }
            if (firstName.isEmpty()) {
                first.setError("Pick a subreddit");
                first.requestFocus();
                return;
            }
            if (secondName.isEmpty()) {
                second.setError("Pick a second subreddit");
                second.requestFocus();
                return;
            }

            ArrayList<String> communities = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            String[] raw = (firstName + "," + secondName + "," + more.getText().toString())
                    .split("[,\\n\\r\\t ]+");
            for (String value : raw) {
                String clean = cleanSubredditName(value);
                String key = clean.toLowerCase(Locale.US);
                if (!clean.isEmpty() && seen.add(key)) communities.add(clean);
            }
            if (communities.size() < 2) {
                second.setError("Choose two different subreddits");
                second.requestFocus();
                return;
            }

            try {
                JSONObject store = customCategoryStore();
                JSONArray values = new JSONArray();
                for (String community : communities) values.put(community);
                store.put(categoryName, values);
                prefs.edit().putString("customCategories", store.toString()).apply();
            } catch (Exception ignored) {
                return;
            }
            dialog.dismiss();
            showCategoryRoot();
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCustomCategory(String name) {
        String[] communities = customCategoryCommunities(name);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody(name);
        scroll.addView(body);

        Button search = sheetButton("Search this category");
        body.addView(search, sectionButtonParams());
        search.setOnClickListener(v -> {
            dialog.dismiss();
            openCategorySearch(name, communities);
        });

        for (String community : communities) {
            Button button = sheetButton("r/" + community);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                openSubredditFeed(community);
            });
        }

        Button delete = sheetButton("Delete category");
        body.addView(delete, sectionButtonParams());
        delete.setOnClickListener(v -> {
            try {
                JSONObject store = customCategoryStore();
                store.remove(name);
                prefs.edit().putString("customCategories", store.toString()).apply();
            } catch (Exception ignored) {}
            dialog.dismiss();
            showCategoryRoot();
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

'''
s = s.replace(custom_methods_anchor, custom_methods + custom_methods_anchor, 1)

# Category-search lookup also includes custom category names and their community
# names so a saved category is discoverable from the Categories search box.
category_matches = r'''    private void showCategoryMatches(String term) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Category search · " + term);
        scroll.addView(body);

        String needle = term.toLowerCase(Locale.US);
        int matches = 0;

        for (String name : customCategoryNames()) {
            StringBuilder customHaystack = new StringBuilder(name);
            for (String community : customCategoryCommunities(name)) {
                customHaystack.append(' ').append(community);
            }
            if (!customHaystack.toString().toLowerCase(Locale.US).contains(needle)) continue;
            matches++;
            Button result = sheetButton("My category · " + name);
            body.addView(result, sectionButtonParams());
            result.setOnClickListener(v -> {
                dialog.dismiss();
                showCustomCategory(name);
            });
        }

        for (String[] row : CURATED_CATEGORY_ROWS) {
            String haystack = (row[0] + " " + row[1] + " " + row[2] + " " + row[3])
                    .toLowerCase(Locale.US);
            if (!haystack.contains(needle)) continue;
            matches++;
            String safety = row[0];
            String category = row[1];
            String subcategory = row[2];
            Button result = sheetButton(safety + " · " + category + " · " + subcategory);
            body.addView(result, sectionButtonParams());
            result.setOnClickListener(v -> {
                dialog.dismiss();
                showCategorySubfolder(safety, category, subcategory);
            });
        }

        if (matches == 0) {
            body.addView(bodyText("No categories matched that search."));
        }

        dialog.setContentView(scroll);
        dialog.show();
    }'''

s = replace_java_method(
    s,
    "    private void showCategoryMatches(String term) {",
    category_matches,
    "custom category lookup",
)

path.write_text(s)
print("Applied v3.7.5 runtime reliability + streaming Search + custom Categories")
