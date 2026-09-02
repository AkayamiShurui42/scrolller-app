from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()
anchor = 'import java.util.LinkedHashMap;\n'
if anchor not in s:
    raise SystemExit('Missing LinkedHashMap import anchor for v3.7.0 imports')
if 'import java.util.LinkedHashSet;' not in s:
    s = s.replace(anchor, anchor + 'import java.util.LinkedHashSet;\n', 1)
if 'import java.util.Map;' not in s:
    map_anchor = 'import java.util.Locale;\n'
    if map_anchor in s:
        s = s.replace(map_anchor, map_anchor + 'import java.util.Map;\n', 1)
    else:
        s = s.replace(anchor, anchor + 'import java.util.Map;\n', 1)
path.write_text(s)
print('Restored LinkedHashSet + Map imports for v3.7.0 quality crawl/federated search')
