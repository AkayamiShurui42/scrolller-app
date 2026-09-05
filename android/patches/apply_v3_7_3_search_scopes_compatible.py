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

# Historical access inserts its helper block immediately before the Scrolller
# prefetch helper. That Scrolller signature is stable across the full stack,
# whereas the historical helper signature itself may be rewritten/moved by later
# generated patches. Point the final stabilization insertion at the stable seam.
stability_path = Path('patches/apply_v3_7_3_feed_search_stability.py')
stability = stability_path.read_text()
old_anchor = "historical_anchor = '    private void prefetchHistoricalSubredditIfNeeded(boolean forceFallback) {'"
new_anchor = "historical_anchor = '    private void prefetchScrolllerSubredditIfNeeded() {'"
if old_anchor not in stability:
    raise SystemExit('Missing v3.7.3 stability historical anchor compatibility target')
stability = stability.replace(old_anchor, new_anchor, 1)
stability_path.write_text(stability)

print('Applied v3.7.3 compatible source-aware Search wrapper')
