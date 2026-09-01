from pathlib import Path

validator = Path('android/patches/validate_v3_7_0.py')
source = validator.read_text()
source = source.replace(
    '        ("old live pager Saved removal", \'postAdapter.removePostById(id)\'),\n',
    '')
source = source.replace(
    '        ("old live stream Saved removal", \'gridAdapter.removePostById(id)\'),\n',
    '')

# Run the full labeled behavior validator first. Its read-gate checks prove that
# the Search patch did not erase the v3.6.9 media-ready/Saved protections.
exec(compile(source, str(validator), 'exec'), {'__name__': '__main__', '__file__': str(validator)})

# Adapter removal is legitimate for the explicit Save transition. It is only a
# regression if the automatic swipe/read path itself removes live pager items.
main = Path('android/app/src/main/java/com/scrolller/adblock/MainActivity.java').read_text()
start = main.find('    private void trackFullscreenVisit(int position) {')
end = main.find('    private void restoreHiddenPost(', start)
if start < 0 or end < 0:
    raise SystemExit('Could not isolate trackFullscreenVisit for mutation guard')
read_path = main[start:end]
if 'postAdapter.removePostById' in read_path or 'gridAdapter.removePostById' in read_path:
    raise SystemExit('FORBIDDEN: automatic swipe/read path mutates active adapters')
print('OK      [MAIN] automatic swipe/read path keeps active adapters immutable')
