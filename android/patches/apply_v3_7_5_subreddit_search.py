from pathlib import Path


def replace_java_method(text, signature, replacement, label):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Missing v3.7.5 Java method: {label}: {signature}")
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"Missing v3.7.5 opening brace: {label}")
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
    raise SystemExit(f"Unbalanced v3.7.5 Java method: {label}")


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
s = main_path.read_text()

# A subreddit screen gets its own Search pill in the title row. Reuse the
# existing Search action button so the layout stays compact.
old_listener = '        searchGoButton.setOnClickListener(v -> beginSearch());'
new_listener = '''        searchGoButton.setOnClickListener(v -> {
            if (screen == Screen.SEARCH) beginSearch();
            else if (screen == Screen.HOME && context.equals("subreddit")) openSearchScreen();
        });'''
if old_listener not in s:
    raise SystemExit("Missing v3.7.5 target: Search button listener")
s = s.replace(old_listener, new_listener, 1)

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
    }

'''
s = replace_java_method(
    s,
    "    private void openSearchScreen() {",
    open_search,
    "openSearchScreen",
)

update_chrome = s.find("    private void updateChrome() {")
if update_chrome < 0:
    raise SystemExit("Missing v3.7.5 target: updateChrome")
segment_end = s.find("    private void openBrowser(", update_chrome)
if segment_end < 0:
    raise SystemExit("Missing v3.7.5 target: updateChrome end")
segment = s[update_chrome:segment_end]
old_visibility = '''        boolean searchMode = screen == Screen.SEARCH;
        topTitle.setVisibility(searchMode ? View.GONE : View.VISIBLE);
        searchInput.setVisibility(searchMode ? View.VISIBLE : View.GONE);
        searchGoButton.setVisibility(searchMode ? View.VISIBLE : View.GONE);'''
new_visibility = '''        boolean searchMode = screen == Screen.SEARCH;
        boolean subredditSearchEntry = screen == Screen.HOME && context.equals("subreddit");
        topTitle.setVisibility(searchMode ? View.GONE : View.VISIBLE);
        searchInput.setVisibility(searchMode ? View.VISIBLE : View.GONE);
        searchGoButton.setVisibility(
                searchMode || subredditSearchEntry ? View.VISIBLE : View.GONE);
        searchGoButton.setText("Search");'''
if old_visibility not in segment:
    raise SystemExit("Missing v3.7.5 target: Search visibility block")
segment = segment.replace(old_visibility, new_visibility, 1)
s = s[:update_chrome] + segment + s[segment_end:]

main_path.write_text(s)

# New test build version.
gradle = Path("app/build.gradle")
g = gradle.read_text()
if 'versionCode 32' not in g or 'versionName "3.7.4"' not in g:
    raise SystemExit("Missing v3.7.5 version target")
g = g.replace('versionCode 32', 'versionCode 33', 1)
g = g.replace('versionName "3.7.4"', 'versionName "3.7.5"', 1)
gradle.write_text(g)

print("Applied v3.7.5 in-subreddit search entry")
