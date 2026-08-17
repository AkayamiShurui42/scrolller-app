from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

def rep(old, new, count=1):
    global s
    if old not in s:
        raise SystemExit('Missing patch target:\n' + old[:240])
    s = s.replace(old, new, count)

# Imports and state.
rep('import java.util.ArrayList;\nimport java.util.Deque;',
    'import java.util.ArrayList;\nimport java.util.Collections;\nimport java.util.Deque;')
rep('        final String searchScope;\n        final int pagerPosition;',
    '        final String searchScope;\n        final String favoriteSort;\n        final int pagerPosition;')
rep('                String layoutMode,\n                String searchScope,\n                int pagerPosition',
    '                String layoutMode,\n                String searchScope,\n                String favoriteSort,\n                int pagerPosition')
rep('            this.searchScope = searchScope;\n            this.pagerPosition = pagerPosition;',
    '            this.searchScope = searchScope;\n            this.favoriteSort = favoriteSort;\n            this.pagerPosition = pagerPosition;')
rep('    private String sort = "best";\n    private String topTime = "day";',
    '    private String sort = "random";\n    private String topTime = "day";')
rep('    private String searchScope = "global";\n    private String query = "";',
    '    private String searchScope = "global";\n    private String favoriteSort = "random";\n    private String query = "";')
rep('        sort = prefs.getString("sort", "best");',
    '        sort = "random";')

# History preserves the active Favorites ordering as well.
rep('                layoutMode,\n                searchScope,\n                position);',
    '                layoutMode,\n                searchScope,\n                favoriteSort,\n                position);')
rep('                && a.layoutMode.equals(b.layoutMode)\n                && a.searchScope.equals(b.searchScope);',
    '                && a.layoutMode.equals(b.layoutMode)\n                && a.searchScope.equals(b.searchScope)\n                && a.favoriteSort.equals(b.favoriteSort);')
rep('        searchScope = state.searchScope;\n        pendingRestorePosition',
    '        searchScope = state.searchScope;\n        favoriteSort = state.favoriteSort;\n        pendingRestorePosition')

# Fresh feed destinations default to a new random order. History restore bypasses these methods.
rep('        screen = Screen.HOME;\n        context = which;',
    '        sort = "random";\n        screen = Screen.HOME;\n        context = which;', 1)
rep('        pushCurrentState();\n        screen = Screen.HOME;\n        context = "subreddit";',
    '        pushCurrentState();\n        sort = "random";\n        screen = Screen.HOME;\n        context = "subreddit";')
rep('        screen = Screen.SEARCH;\n        context = "search";',
    '        if (sort.equals("random")) sort = "best";\n        screen = Screen.SEARCH;\n        context = "search";')
rep('        pushCurrentState();\n        screen = Screen.USER;\n        profileUser = name;',
    '        pushCurrentState();\n        if (sort.equals("random")) sort = "new";\n        screen = Screen.USER;\n        profileUser = name;')

# Random is a client-side shuffle over a larger valid Reddit listing sample.
rep('        if (context.equals("home")) {\n            base = sort.equals("best") ? "/.json" : "/" + sort + ".json";\n        } else if (context.equals("popular")) {\n            base = sort.equals("best") ? "/r/popular/hot.json" : "/r/popular/" + sort + ".json";\n        } else {\n            base = sort.equals("best")\n                    ? "/r/" + enc(subreddit) + "/hot.json"\n                    : "/r/" + enc(subreddit) + "/" + sort + ".json";\n        }',
'''        if (context.equals("home")) {
            base = sort.equals("random") ? "/new.json"
                    : sort.equals("best") ? "/.json" : "/" + sort + ".json";
        } else if (context.equals("popular")) {
            base = sort.equals("random") ? "/r/popular/new.json"
                    : sort.equals("best") ? "/r/popular/hot.json" : "/r/popular/" + sort + ".json";
        } else {
            base = sort.equals("random") ? "/r/" + enc(subreddit) + "/new.json"
                    : sort.equals("best") ? "/r/" + enc(subreddit) + "/hot.json"
                    : "/r/" + enc(subreddit) + "/" + sort + ".json";
        }''')
rep('            if (collected.size() < 12 && !after.isEmpty() && page < 4) {\n                fetchFeedPages(reset, collected, page + 1);\n                return;\n            }\n            loading = false;\n            if (reset) replacePosts(collected); else appendUnique(collected);',
'''            boolean collectMoreForRandom = sort.equals("random") && page < 2;
            if ((collected.size() < 12 || collectMoreForRandom) && !after.isEmpty() && page < 4) {
                fetchFeedPages(reset, collected, page + 1);
                return;
            }
            loading = false;
            if (sort.equals("random")) Collections.shuffle(collected);
            if (reset) replacePosts(collected); else appendUnique(collected);''')

# Favorites starts randomized on every fresh visit; internal reloads preserve the user's chosen ordering.
rep('        if (screen != Screen.FAVORITES) pushCurrentState();\n        screen = Screen.FAVORITES;',
    '        if (screen != Screen.FAVORITES) pushCurrentState();\n        favoriteSort = "random";\n        screen = Screen.FAVORITES;')

old_favorites = '''        String path = "/user/" + enc(username) + "/saved.json?limit=100&raw_json=1";
        engine.get(path, result -> {
            loading = false;
            if (!result.ok) {
                setStatus("Favorites failed: " + friendlyError(result), false);
                return;
            }
            ArrayList<RedditPost> items = parseListing(result.jsonObject(), true);
            replacePosts(items);
            if (items.isEmpty()) setStatus("No saved media posts yet.", false);
            else hideStatus();
            updateChrome();
            restorePendingPosition();
        });
    }

    private ArrayList<RedditPost> parseListing'''
new_favorites = '''        fetchFavoritesPage("", new ArrayList<>(), 0);
    }

    private void fetchFavoritesPage(String cursor, ArrayList<RedditPost> collected, int page) {
        String path = "/user/" + enc(username) + "/saved.json?limit=100&raw_json=1";
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                setStatus("Favorites failed: " + friendlyError(result), false);
                return;
            }
            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            collected.addAll(parseListing(root, true));
            String next = data != null ? data.optString("after", "") : "";
            if (!next.isEmpty() && page < 9) {
                fetchFavoritesPage(next, collected, page + 1);
                return;
            }
            loading = false;
            applyFavoriteOrdering(collected);
            replacePosts(collected);
            if (collected.isEmpty()) setStatus("No saved media posts match this filter.", false);
            else hideStatus();
            updateChrome();
            restorePendingPosition();
        });
    }

    private void applyFavoriteOrdering(ArrayList<RedditPost> items) {
        switch (favoriteSort) {
            case "saved_oldest":
                Collections.reverse(items);
                break;
            case "post_newest":
                items.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
                break;
            case "post_oldest":
                items.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
                break;
            case "random":
                Collections.shuffle(items);
                break;
            default:
                break;
        }
    }

    private ArrayList<RedditPost> parseListing'''
rep(old_favorites, new_favorites)

# Favorites owns its own sort menu; regular feeds include Random.
rep('    private void showSortSheet() {\n        BottomSheetDialog dialog = new BottomSheetDialog(this);',
'''    private void showSortSheet() {
        if (screen == Screen.FAVORITES) {
            showFavoriteSortSheet();
            return;
        }
        BottomSheetDialog dialog = new BottomSheetDialog(this);''')
rep('        } else {\n            values = new String[]{"best", "hot", "new", "top", "rising"};\n        }',
    '        } else {\n            values = new String[]{"random", "best", "hot", "new", "top", "rising"};\n        }')
anchor = '    private void showTopTimeSheet() {'
favorite_menu = '''    private void showFavoriteSortSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Favorites order");
        String[][] values = {
                {"random", "Randomize"},
                {"saved_newest", "Recently saved"},
                {"saved_oldest", "Oldest saved"},
                {"post_newest", "Newest post"},
                {"post_oldest", "Oldest post"}
        };
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (favoriteSort.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                favoriteSort = pair[0];
                dialog.dismiss();
                loadFavoritesInternal();
            });
        }
        dialog.setContentView(body);
        dialog.show();
    }

    private String favoriteSortLabel() {
        switch (favoriteSort) {
            case "saved_newest": return "Recent saved";
            case "saved_oldest": return "Oldest saved";
            case "post_newest": return "Newest post";
            case "post_oldest": return "Oldest post";
            default: return "Random";
        }
    }

'''
rep(anchor, favorite_menu + anchor)

# Chrome exposes Favorites sorting instead of hiding it.
rep('        sortButton.setText(label(sort));',
    '        sortButton.setText(screen == Screen.FAVORITES ? favoriteSortLabel() : label(sort));')
rep('        sortButton.setVisibility(\n                screen == Screen.FAVORITES ? View.GONE : View.VISIBLE);',
    '        sortButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);')

path.write_text(s)
print('Applied v3.3 MainActivity patch')
