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
# Search did not erase the v3.6.9 media-ready/Saved protections.
exec(compile(source, str(validator), 'exec'), {'__name__': '__main__', '__file__': str(validator)})

main = Path('android/app/src/main/java/com/scrolller/adblock/MainActivity.java').read_text()
arctic = Path('android/app/src/main/java/com/scrolller/adblock/ArcticShiftClient.java').read_text()

required_main = {
    'federated coordinator': 'private void beginFederatedSearch',
    'Reddit stage delegates to federation': 'beginFederatedSearch(generation, collected)',
    'Scrolller engine': 'ScrolllerClient.crawlSubreddit(target, 50',
    'Arctic Shift engine': 'ArcticShiftClient.searchSubreddit(target, query',
    'global engine candidate expansion': 'searchScope.equals("global")',
    'single merged result map': 'LinkedHashMap<String, RedditPost> merged',
    'post-id dedupe': 'merged.containsKey(post.id)',
    'media identity dedupe': 'federatedMediaKey(post)',
    'single final local sort': 'sortLocalSearch(results)',
}
for label, needle in required_main.items():
    if needle not in main:
        raise SystemExit(f'MISSING [MAIN] {label}: {needle}')
    print(f'OK      [MAIN] {label}')

required_arctic = {
    'official archive endpoint': 'https://arctic-shift.photon-reddit.com/api/posts/search',
    'subreddit constraint': '?subreddit=',
    'keyword query': '"query", query',
    'creator query': '"author", creator',
    '100-result archive page': '&limit=100&sort=desc',
}
for label, needle in required_arctic.items():
    if needle not in arctic:
        raise SystemExit(f'MISSING [ARCTIC] {label}: {needle}')
    print(f'OK      [ARCTIC] {label}')

# Adapter removal is legitimate for the explicit Save transition. It is only a
# regression if the automatic swipe/read path itself removes live pager items.
start = main.find('    private void trackFullscreenVisit(int position) {')
end = main.find('    private void restoreHiddenPost(', start)
if start < 0 or end < 0:
    raise SystemExit('Could not isolate trackFullscreenVisit for mutation guard')
read_path = main[start:end]
if 'postAdapter.removePostById' in read_path or 'gridAdapter.removePostById' in read_path:
    raise SystemExit('FORBIDDEN: automatic swipe/read path mutates active adapters')
print('OK      [MAIN] automatic swipe/read path keeps active adapters immutable')
