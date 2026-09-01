from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()
if 'import java.util.LinkedHashSet;' not in s:
    old = 'import java.util.LinkedHashMap;\n'
    if old not in s:
        raise SystemExit('Missing LinkedHashMap import anchor for federated search')
    s = s.replace(old, old + 'import java.util.LinkedHashSet;\n', 1)
path.write_text(s)
print('Restored LinkedHashSet import for v3.7.0 federated search')
