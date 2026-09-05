from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

orphan = '''    }

        postAdapter.appendPosts(unique);
        gridAdapter.appendPosts(unique);
    }

    private void prefetchHistoricalTopAllIfNeeded(int generation) {'''
fixed = '''    }

    private void prefetchHistoricalTopAllIfNeeded(int generation) {'''

if s.count(orphan) != 1:
    raise SystemExit(f'Expected exactly one v3.7.3 orphan append tail, found {s.count(orphan)}')

s = s.replace(orphan, fixed, 1)

if s.count('    private void appendUniqueNow(List<RedditPost> incoming) {') != 1:
    raise SystemExit('appendUniqueNow signature count is not exactly one after repair')
if '\n        postAdapter.appendPosts(unique);\n        gridAdapter.appendPosts(unique);\n    }\n\n        postAdapter.appendPosts(unique);' in s:
    raise SystemExit('orphan append tail still present after repair')

path.write_text(s)
print('Removed v3.7.3 orphan append tail; MainActivity class scope restored after appendUniqueNow')
