from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

orphan = '''    }

        postAdapter.setPosts(visible);
        gridAdapter.setPosts(visible);
    }

    private void prefetchSubredditReservoir() {'''
fixed = '''    }

    private void prefetchSubredditReservoir() {'''

if s.count(orphan) != 1:
    raise SystemExit(f'Expected exactly one v3.7.3 orphan collection tail, found {s.count(orphan)}')

s = s.replace(orphan, fixed, 1)

# Structural guards around the repaired area.
if s.count('    private void replacePosts(List<RedditPost> items) {') != 1:
    raise SystemExit('replacePosts signature count is not exactly one after repair')
if '\n        postAdapter.setPosts(visible);\n        gridAdapter.setPosts(visible);\n    }\n\n        postAdapter.setPosts(visible);' in s:
    raise SystemExit('orphan replacePosts tail still present after repair')

path.write_text(s)
print('Removed v3.7.3 orphan replacePosts tail; MainActivity class scope restored')
