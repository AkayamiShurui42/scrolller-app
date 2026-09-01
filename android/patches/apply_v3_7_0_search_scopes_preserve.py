from pathlib import Path

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
    raise SystemExit('Missing v3.7.0 Search preservation target')
source = source.replace(bad, good, 1)
exec(compile(source, str(patch_path), 'exec'), {'__name__': '__main__', '__file__': str(patch_path)})
