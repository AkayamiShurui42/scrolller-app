from pathlib import Path

# v3.7.2 Quality browse was authored before source-aware Search. Search now owns
# the Search-screen Feed label through searchScopeLabel(), so adapt only that
# exact chrome target while leaving all Quality behavior unchanged.

patch_path = Path('patches/apply_v3_7_2_quality_browse.py')
source = patch_path.read_text()

old_target = '''    ''' + "'''" + '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : "Feed");''' + "'''"

new_target = '''    ''' + "'''" + '''        feedButton.setText(screen == Screen.SEARCH
                ? searchScopeLabel()
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : "Feed");''' + "'''"

old_replacement = '''    ''' + "'''" + '''        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : screen == Screen.HOME && context.equals("quality")
                ? "Browse"
                : "Feed");''' + "'''"

new_replacement = '''    ''' + "'''" + '''        feedButton.setText(screen == Screen.SEARCH
                ? searchScopeLabel()
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : screen == Screen.HOME && context.equals("quality")
                ? "Browse"
                : "Feed");''' + "'''"

if old_target not in source or old_replacement not in source:
    raise SystemExit('Missing v3.7.3 Quality/Search compatibility targets')
source = source.replace(old_target, new_target, 1)
source = source.replace(old_replacement, new_replacement, 1)

exec(compile(source, str(patch_path), 'exec'), {
    '__name__': '__main__',
    '__file__': str(patch_path),
})

print('Applied v3.7.3 Quality browse compatible with source-aware Search')
