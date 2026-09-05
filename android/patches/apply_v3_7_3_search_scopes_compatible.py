from pathlib import Path

# v3.7.0's source-aware Search patch was developed but never included in the
# v3.7.2 build stack because its broad showScopeSheet replacement could erase
# helper methods inserted by the v3.6 read/hide stack. This wrapper normalizes
# the state-field order expected by that patch, narrows the scope-sheet edit to
# the original method only, then executes the existing implementation.
#
# It also adapts later patches in the build workspace so Search/Quality and the
# final stabilization patch can coexist without depending on brittle historical
# insertion points from older generated MainActivity snapshots.

main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

old_state = '''    private String searchScope = "global";
    private String favoriteSort = "random";
    private String favoritesView = "saved";
    private String query = "";'''
new_state = '''    private String favoriteSort = "random";
    private String favoritesView = "saved";
    private String searchScope = "global";
    private String query = "";'''
if old_state in s:
    s = s.replace(old_state, new_state, 1)
main_path.write_text(s)

patch_path = Path('patches/apply_v3_7_0_search_scopes.py')
source = patch_path.read_text()

bad = '''s = replace_between(
    s,
    '    private void showScopeSheet() {',
    '    private void reloadCurrent() {',
    scope_sheet,
    'showScopeSheet')'''

good = r'''old_scope_sheet = ''' + "'''" + r'''    private void showScopeSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Search scope");
        String[][] values = {
                {"global", "Global"},
                {"subscribed", "Subscribed only"}
        };
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (searchScope.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                searchScope = pair[0];
                prefs.edit().putString("searchScope", searchScope).apply();
                dialog.dismiss();
                if (!query.isEmpty()) loadSearchInternal();
                updateChrome();
            });
        }
        dialog.setContentView(body);
        dialog.show();
    }
''' + "'''" + r'''
s = replace_required(s, old_scope_sheet, scope_sheet, 'showScopeSheet only')'''

if bad not in source:
    raise SystemExit('Missing v3.7.3 Search compatibility target')
source = source.replace(bad, good, 1)
exec(compile(source, str(patch_path), 'exec'), {
    '__name__': '__main__',
    '__file__': str(patch_path),
})

# The next Quality-browse patch was authored against the pre-source-aware Search
# header. Rewrite only those two embedded Java snippets in the runner workspace.
quality_path = Path('patches/apply_v3_7_2_quality_browse.py')
quality = quality_path.read_text()
old_search_label = '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : "Feed");'''
new_search_label = '''        feedButton.setText(screen == Screen.SEARCH
                ? searchScopeLabel()
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : "Feed");'''
old_quality_label = '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : screen == Screen.HOME && context.equals("quality")
                ? "Browse"
                : "Feed");'''
new_quality_label = '''        feedButton.setText(screen == Screen.SEARCH
                ? searchScopeLabel()
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : screen == Screen.HOME && context.equals("quality")
                ? "Browse"
                : "Feed");'''
if old_search_label not in quality or old_quality_label not in quality:
    raise SystemExit('Missing v3.7.3 Quality/Search header compatibility target')
quality = quality.replace(old_search_label, new_search_label, 1)
quality = quality.replace(old_quality_label, new_quality_label, 1)
quality_path.write_text(quality)

# Adapt the final stabilization patch before it runs. Two of its original range
# replacements used the *next method signature* as an end marker. Older patches
# intentionally insert helper methods between replacePosts()/appendUnique(), so
# that strategy deleted Reservoir, Historical, and Quality helper definitions.
# Replace only the exact Java method body by matching braces instead.
stability_path = Path('patches/apply_v3_7_3_feed_search_stability.py')
stability = stability_path.read_text()

helper_anchor = "\n\nmain_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')"
brace_helper = r'''

def replace_java_method(text, signature, replacement, label):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f'Missing v3.7.3 Java method: {label}: {signature}')
    brace = text.find('{', start + len(signature))
    if brace < 0:
        raise SystemExit(f'Missing v3.7.3 Java opening brace: {label}')
    depth = 0
    for i in range(brace, len(text)):
        ch = text[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                while end < len(text) and text[end] in '\r\n':
                    end += 1
                return text[:start] + replacement + text[end:]
    raise SystemExit(f'Unbalanced v3.7.3 Java method: {label}')
'''
if helper_anchor not in stability:
    raise SystemExit('Missing v3.7.3 brace-helper insertion anchor')
stability = stability.replace(helper_anchor, brace_helper + helper_anchor, 1)

old_replace_posts = '''s = replace_method(
    s,
    '    private void replacePosts(List<RedditPost> items) {',
    '    private void appendUnique(List<RedditPost> incoming) {',
    replace_posts,
    'canonical replacePosts')'''
new_replace_posts = '''s = replace_java_method(
    s,
    '    private void replacePosts(List<RedditPost> items) {',
    replace_posts,
    'canonical replacePosts')'''
if old_replace_posts not in stability:
    raise SystemExit('Missing v3.7.3 replacePosts range rewrite target')
stability = stability.replace(old_replace_posts, new_replace_posts, 1)

old_append_now = '''s = replace_method(
    s,
    '    private void appendUniqueNow(List<RedditPost> incoming) {',
    '    private void openFullscreenAt(int position) {',
    append_now,
    'canonical appendUniqueNow')'''
new_append_now = '''s = replace_java_method(
    s,
    '    private void appendUniqueNow(List<RedditPost> incoming) {',
    append_now,
    'canonical appendUniqueNow')'''
if old_append_now not in stability:
    raise SystemExit('Missing v3.7.3 appendUniqueNow range rewrite target')
stability = stability.replace(old_append_now, new_append_now, 1)

# The final stabilization patch creates canonicalPostKey() itself before it needs
# to insert the Top/All archive helpers. Use that self-created method as the seam,
# so later historical/Scrolller patches cannot move or erase the anchor.
old_anchor = "historical_anchor = '    private void prefetchHistoricalSubredditIfNeeded(boolean forceFallback) {'"
new_anchor = "historical_anchor = '    private String canonicalPostKey(RedditPost post) {'"
if old_anchor not in stability:
    raise SystemExit('Missing v3.7.3 stability historical anchor compatibility target')
stability = stability.replace(old_anchor, new_anchor, 1)
stability_path.write_text(stability)

print('Applied v3.7.3 compatible source-aware Search wrapper')