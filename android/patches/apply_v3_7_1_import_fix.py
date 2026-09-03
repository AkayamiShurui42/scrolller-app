from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

if 'import java.util.Map;' not in s:
    anchor = 'import java.util.Locale;\n'
    if anchor not in s:
        raise SystemExit('Missing v3.7.1 import-fix anchor: java.util.Locale')
    s = s.replace(anchor, anchor + 'import java.util.Map;\n', 1)

path.write_text(s)
print('Applied v3.7.1 java.util.Map compile fix')
