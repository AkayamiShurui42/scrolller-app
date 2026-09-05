from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()


def replace_region(text, start_sig, next_sig, replacement, label):
    a = text.find(start_sig)
    if a < 0:
        raise SystemExit(f'Missing v3.7.5 reconstruction start: {label}: {start_sig}')
    b = text.find(next_sig, a + len(start_sig))
    if b < 0:
        raise SystemExit(f'Missing v3.7.5 reconstruction end: {label}: {next_sig}')
    return text[:a] + replacement.rstrip() + '\n\n' + text[b:]


open_search = r'''    private void openSearchScreen() {
        boolean subredditEntry = screen == Screen.HOME
                && context.equals("subreddit")
                && subreddit != null
                && !subreddit.isEmpty();

        if (screen != Screen.SEARCH) {
            searchCollectorSnapshot.clear();
            searchCollectorSnapshot.addAll(postAdapter.getPosts());
            if (subredditEntry) {
                searchSubreddit = cleanSubredditName(subreddit);
            }
            pushCurrentState();
        }

        if (subredditEntry) {
            searchScope = "subreddit";
            prefs.edit()
                    .putString("searchScope", searchScope)
                    .putString("searchSubreddit", searchSubreddit)
                    .apply();
        }

        query = "";
        prefs.edit().remove("lastSearch").apply();
        screen = Screen.SEARCH;
        context = "search";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        searchInput.setText("");
        updateChrome();
        setStatus("Search " + searchScopeDescription() + " by title, community, or creator.", false);
        searchInput.requestFocus();
    }'''

scope_description = r'''    private String searchScopeDescription() {
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
    }'''

scope_label = r'''    private String searchScopeLabel() {
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
    }'''

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
    }'''

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
    }'''

local_relevance = r'''    private int localSearchRelevance(RedditPost post) {
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
    }'''

pairs = [
    ('    private void openSearchScreen() {',
     '    private void beginSearch() {',
     open_search,
     'openSearchScreen'),
    ('    private void finishSearchCollection(int generation, ArrayList<RedditPost> collected) {',
     '    private void fetchFavoritesSearchPage(',
     finish_search,
     'finishSearchCollection'),
    ('    private boolean matchesLocalSearch(RedditPost post, String value) {',
     '    private String normalizeLocalSearch(String value) {',
     matches_local,
     'matchesLocalSearch'),
    ('    private int localSearchRelevance(RedditPost post) {',
     '    private double localHotScore(RedditPost post) {',
     local_relevance,
     'localSearchRelevance'),
    ('    private String searchScopeDescription() {',
     '    private String searchScopeLabel() {',
     scope_description,
     'searchScopeDescription'),
    ('    private String searchScopeLabel() {',
     '    private void reloadCurrent() {',
     scope_label,
     'searchScopeLabel'),
]

for start_sig, next_sig, replacement, label in pairs:
    s = replace_region(s, start_sig, next_sig, replacement, label)

# Validate that each next method declaration now appears at class brace-depth 1.
def brace_depth_at(text, target_index):
    depth = 0
    in_string = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    escaped = False
    i = 0
    while i < target_index:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < target_index else ''
        if in_line_comment:
            if ch == '\n': in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            if ch == '*' and nxt == '/':
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if escaped: escaped = False
            elif ch == '\\': escaped = True
            elif ch == '"': in_string = False
            i += 1
            continue
        if in_char:
            if escaped: escaped = False
            elif ch == '\\': escaped = True
            elif ch == "'": in_char = False
            i += 1
            continue
        if ch == '/' and nxt == '/':
            in_line_comment = True
            i += 2
            continue
        if ch == '/' and nxt == '*':
            in_block_comment = True
            i += 2
            continue
        if ch == '"': in_string = True
        elif ch == "'": in_char = True
        elif ch == '{': depth += 1
        elif ch == '}': depth -= 1
        i += 1
    return depth

next_methods = [
    '    private void beginSearch() {',
    '    private void fetchFavoritesSearchPage(',
    '    private String normalizeLocalSearch(String value) {',
    '    private double localHotScore(RedditPost post) {',
    '    private String searchScopeLabel() {',
    '    private void reloadCurrent() {',
]
for signature in next_methods:
    idx = s.find(signature)
    if idx < 0:
        raise SystemExit(f'Missing v3.7.5 reconstructed next method: {signature}')
    depth = brace_depth_at(s, idx)
    if depth != 1:
        raise SystemExit(f'v3.7.5 reconstructed method not at class scope: {signature} depth={depth}')

path.write_text(s)
print('Reconstructed v3.7.5 generated search methods with explicit class-scope boundaries')
