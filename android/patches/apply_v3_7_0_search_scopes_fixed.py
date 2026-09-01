from pathlib import Path
import runpy

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()
old = '''    private String searchScope = "global";
    private String favoriteSort = "random";
    private String favoritesView = "saved";
    private String query = "";'''
new = '''    private String favoriteSort = "random";
    private String favoritesView = "saved";
    private String searchScope = "global";
    private String query = "";'''
if old not in s:
    raise SystemExit('Missing v3.7.0 Search normalization target: search/favorites state block')
s = s.replace(old, new, 1)
path.write_text(s)
runpy.run_path('patches/apply_v3_7_0_search_scopes.py', run_name='__main__')
