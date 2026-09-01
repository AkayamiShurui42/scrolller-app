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
