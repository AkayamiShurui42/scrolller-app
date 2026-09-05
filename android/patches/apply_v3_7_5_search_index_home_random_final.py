from pathlib import Path

patch_path = Path('patches/apply_v3_7_5_search_index_home_random.py')
source = patch_path.read_text()

start_marker = '''# Infinite-scroll trigger for round-based Home Random does not depend on Reddit's
# aggregate feed cursor. The next complete subscription round starts near the end.
'''
end_marker = '''# If subscriptions finish loading after startup, replace any temporary aggregate
# Random feed with the fair per-subscription round immediately.
'''
start = source.find(start_marker)
end = source.find(end_marker, start + len(start_marker))
if start < 0 or end < 0:
    raise SystemExit('Missing v3.7.5 Home Random trigger-cleanup markers')

# The existing pager/grid callbacks already call loadFeed(false) near the end when
# `after` is non-empty. The round engine sets `after = "home-random-round"` after
# completing a round, and loadFeed() intercepts Home+Random before listingPath(),
# so no callback rewrite is needed.
source = source[:start] + end_marker + source[end + len(end_marker):]

exec(compile(source, str(patch_path), 'exec'), {
    '__name__': '__main__',
    '__file__': str(patch_path),
})

print('Applied v3.7.5 final hidden search index + fair Home Random using existing pagination trigger')
