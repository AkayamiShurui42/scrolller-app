from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()
if 'import java.util.Map;' not in s:
    anchor = 'import java.util.Locale;\n'
    if anchor not in s:
        anchor = 'import java.util.LinkedHashMap;\n'
    if anchor not in s:
        raise SystemExit('Missing java.util import anchor for v3.7.0 quality crawl')
    s = s.replace(anchor, anchor + 'import java.util.Map;\n', 1)
path.write_text(s)
print('Restored Map import for v3.7.0 quality crawl')
