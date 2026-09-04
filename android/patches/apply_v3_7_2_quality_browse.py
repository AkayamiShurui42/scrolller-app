from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.7.2 quality-browse target: {label}\n{old[:1200]}')
    return text.replace(old, new, 1)


path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# Session-local browsing state. This filters the existing cache-backed Quality
# catalog only; it never changes crawler inputs, Saved/Hidden state, or discovery.
s = replace_required(
    s,
    '''    private final LinkedHashMap<String, Integer> qualityAuthorHits = new LinkedHashMap<>();
    private boolean qualityCatalogLoaded = false;''',
    '''    private final LinkedHashMap<String, Integer> qualityAuthorHits = new LinkedHashMap<>();
    private String qualityQuery = "";
    private String qualityCategory = "all";
    private boolean qualityCatalogLoaded = false;''',
    'quality browse state')

# While inside Quality, the Feed control becomes the entry point for local
# search/categories rather than reopening the ordinary feed chooser.
s = replace_required(
    s,
    '''        feedButton.setOnClickListener(v -> {
            if (screen == Screen.SEARCH) showScopeSheet();
            else if (screen == Screen.FAVORITES) showFavoritesViewSheet();
            else showFeedSheet();
        });''',
    '''        feedButton.setOnClickListener(v -> {
            if (screen == Screen.SEARCH) showScopeSheet();
            else if (screen == Screen.FAVORITES) showFavoritesViewSheet();
            else if (screen == Screen.HOME && context.equals("quality")) showQualityBrowseSheet();
            else showFeedSheet();
        });''',
    'Quality browse control route')

# The Quality renderer applies only local view filters after the existing
# unread/content rejection rules have already passed.
s = replace_required(
    s,
    '''            if (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post)) continue;
            visible.add(post);''',
    '''            if (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post)) continue;
            if (!matchesQualityBrowse(post)) continue;
            visible.add(post);''',
    'Quality local search/category filtering')

# Make the top-row control self-describing while Quality is active.
s = replace_required(
    s,
    '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : "Feed");''',
    '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : screen == Screen.HOME && context.equals("quality")
                ? "Browse"
                : "Feed");''',
    'Quality Browse chrome label')

anchor = '    private void loadQualityCollection(boolean reset) {'
if anchor not in s:
    raise SystemExit('Missing v3.7.2 quality-browse target: loadQualityCollection anchor')

methods = r'''    private void showQualityBrowseSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Browse Quality collection");
        scroll.addView(body);

        EditText localSearch = new EditText(this);
        localSearch.setHint("Search title, r/subreddit, or u/author");
        localSearch.setText(qualityQuery);
        localSearch.setTextColor(Color.WHITE);
        localSearch.setHintTextColor(0xFF8E8E8E);
        localSearch.setTextSize(14);
        localSearch.setSingleLine(true);
        localSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        localSearch.setPadding(dp(12), 0, dp(12), 0);
        localSearch.setBackground(rounded(0xFF1B1B1B, 13));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        searchParams.topMargin = dp(6);
        body.addView(localSearch, searchParams);

        LinearLayout searchActions = new LinearLayout(this);
        searchActions.setOrientation(LinearLayout.HORIZONTAL);
        searchActions.setGravity(Gravity.CENTER_VERTICAL);
        Button applySearch = sheetButton("Apply search");
        Button clearSearch = sheetButton("Clear");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(46), 1f);
        half.topMargin = dp(7);
        half.rightMargin = dp(4);
        searchActions.addView(applySearch, half);
        LinearLayout.LayoutParams halfRight = new LinearLayout.LayoutParams(0, dp(46), 1f);
        halfRight.topMargin = dp(7);
        halfRight.leftMargin = dp(4);
        searchActions.addView(clearSearch, halfRight);
        body.addView(searchActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable apply = () -> {
            qualityQuery = localSearch.getText().toString().trim();
            dialog.dismiss();
            renderQualityCatalog();
        };
        applySearch.setOnClickListener(v -> apply.run());
        localSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                apply.run();
                return true;
            }
            return false;
        });
        clearSearch.setOnClickListener(v -> {
            qualityQuery = "";
            localSearch.setText("");
            dialog.dismiss();
            renderQualityCatalog();
        });

        TextView categoryTitle = sectionTitle("Categories");
        LinearLayout.LayoutParams categoryTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        categoryTitleParams.topMargin = dp(16);
        body.addView(categoryTitle, categoryTitleParams);

        addQualityCategoryButton(body, dialog, "all", "All quality");
        addQualityCategoryButton(body, dialog, "ultra", "Ultra HD · 8 MP / 3840px+");
        addQualityCategoryButton(body, dialog, "galleries", "Galleries");
        addQualityCategoryButton(body, dialog, "images", "Single images");

        LinkedHashMap<String, Integer> communityCounts = new LinkedHashMap<>();
        LinkedHashMap<String, String> communityLabels = new LinkedHashMap<>();
        for (RedditPost post : qualityCatalog.values()) {
            if (!eligibleQualityUnread(post)) continue;
            String community = post.subreddit == null ? "" : post.subreddit.trim();
            if (community.isEmpty()) continue;
            String key = community.toLowerCase(Locale.US);
            communityCounts.put(key, communityCounts.getOrDefault(key, 0) + 1);
            communityLabels.putIfAbsent(key, community);
        }

        ArrayList<String> communityKeys = new ArrayList<>(communityCounts.keySet());
        communityKeys.sort((a, b) -> {
            int countOrder = Integer.compare(
                    communityCounts.getOrDefault(b, 0),
                    communityCounts.getOrDefault(a, 0));
            if (countOrder != 0) return countOrder;
            return a.compareToIgnoreCase(b);
        });

        if (!communityKeys.isEmpty()) {
            TextView communitiesTitle = sectionTitle("Communities · " + communityKeys.size());
            LinearLayout.LayoutParams communitiesTitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            communitiesTitleParams.topMargin = dp(16);
            body.addView(communitiesTitle, communitiesTitleParams);

            for (String key : communityKeys) {
                String display = communityLabels.getOrDefault(key, key);
                int count = communityCounts.getOrDefault(key, 0);
                addQualityCategoryButton(
                        body,
                        dialog,
                        "subreddit:" + key,
                        "r/" + display + " · " + count);
            }
        }

        dialog.setContentView(scroll);
        dialog.show();
        localSearch.requestFocus();
    }

    private void addQualityCategoryButton(
            LinearLayout body,
            BottomSheetDialog dialog,
            String key,
            String label) {
        boolean selected = qualityCategory.equals(key);
        Button button = sheetButton(label + (selected ? "  ✓" : ""));
        body.addView(button, sectionButtonParams());
        button.setOnClickListener(v -> {
            qualityCategory = key;
            dialog.dismiss();
            renderQualityCatalog();
        });
    }

    private boolean eligibleQualityUnread(RedditPost post) {
        if (post == null || !matchesMedia(post)) return false;
        if (post.id == null || post.id.isEmpty()) return false;
        return !hiddenPosts.containsKey(post.id)
                && !isSavedForUnread(post)
                && !isContentBlocked(post);
    }

    private boolean matchesQualityBrowse(RedditPost post) {
        if (post == null) return false;

        String category = qualityCategory == null ? "all" : qualityCategory;
        if (category.equals("ultra")) {
            long area = (long) Math.max(0, post.mediaWidth) * Math.max(0, post.mediaHeight);
            int longEdge = Math.max(Math.max(0, post.mediaWidth), Math.max(0, post.mediaHeight));
            if (area < 8_000_000L && longEdge < 3840) return false;
        } else if (category.equals("galleries")) {
            if (post.mediaKind != RedditPost.MediaKind.GALLERY) return false;
        } else if (category.equals("images")) {
            if (post.mediaKind != RedditPost.MediaKind.IMAGE) return false;
        } else if (category.startsWith("subreddit:")) {
            String target = category.substring("subreddit:".length());
            String actual = post.subreddit == null ? "" : post.subreddit.toLowerCase(Locale.US);
            if (!actual.equals(target)) return false;
        }

        String queryText = qualityQuery == null ? "" : qualityQuery.trim().toLowerCase(Locale.US);
        if (queryText.isEmpty()) return true;
        String haystack = ((post.title == null ? "" : post.title)
                + " r/" + (post.subreddit == null ? "" : post.subreddit)
                + " u/" + (post.author == null ? "" : post.author))
                .toLowerCase(Locale.US);
        return haystack.contains(queryText);
    }

'''
s = s.replace(anchor, methods + anchor, 1)

# When the local Quality view is narrowed to zero, explain the view filter rather
# than implying that the cache itself is empty.
s = replace_required(
    s,
    '''        if (visible.isEmpty()) {
            setStatus(qualityCrawlRunning
                    ? "Finding high-resolution Reddit media…"
                    : "No unread high-resolution media is cached yet.", qualityCrawlRunning);
        } else {''',
    '''        if (visible.isEmpty()) {
            boolean narrowed = (qualityQuery != null && !qualityQuery.trim().isEmpty())
                    || (qualityCategory != null && !qualityCategory.equals("all"));
            if (narrowed) {
                setStatus("No unread Quality posts match this search/category.", false);
            } else {
                setStatus(qualityCrawlRunning
                        ? "Finding high-resolution Reddit media…"
                        : "No unread high-resolution media is cached yet.", qualityCrawlRunning);
            }
        } else {''',
    'Quality filtered-empty status')

path.write_text(s)
print('Applied v3.7.2 searchable categorized Quality browser')
