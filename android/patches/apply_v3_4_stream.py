from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

old = 'gridView.setLayoutManager(new GridLayoutManager(this, 2));'
new = 'gridView.setLayoutManager(new GridLayoutManager(this, 1));'
if old not in s:
    raise SystemExit('Missing two-column layout target')
s = s.replace(old, new, 1)

# Keep the existing internal "grid" state key for compatibility with saved prefs,
# but present it to the user as the continuous Stream layout.
s = s.replace('{"grid", "Grid"}', '{"grid", "Stream"}', 1)
s = s.replace('layoutMode.equals("grid") ? "Grid" : "Fullscreen"',
              'layoutMode.equals("grid") ? "Stream" : "Fullscreen"', 1)

path.write_text(s)
print('Applied v3.4 continuous one-column stream layout')
