from pathlib import Path
import re
import runpy

# v3.6.x evolved the sort-button visibility expression several times. Normalize
# just that one chrome block to the shape expected by the original v3.7.0 patch,
# then execute the actual quality-crawl patch unchanged.
path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()
pattern = re.compile(
    r'        sortButton\.setVisibility\(.*?\);\n        filterButton\.setVisibility',
    re.S)
replacement = '''        sortButton.setVisibility(
                screen == Screen.FAVORITES ? View.GONE : View.VISIBLE);
        filterButton.setVisibility'''
s, count = pattern.subn(replacement, s, count=1)
if count != 1:
    raise SystemExit('Missing v3.7.0 quality normalization target: sortButton visibility block')
path.write_text(s)
runpy.run_path('patches/apply_v3_7_0_quality_crawl.py', run_name='__main__')

# v3.6.3/3.6.9 inserted Favorites state between searchScope and query. Reorder
# only these declarations so the source-aware Search patch has a stable anchor.
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
    raise SystemExit('Missing v3.7.0 post-quality Search normalization target')
s = s.replace(old, new, 1)
path.write_text(s)
