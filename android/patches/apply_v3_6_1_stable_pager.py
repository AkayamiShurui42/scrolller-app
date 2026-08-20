from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.1 patch target: {label}\n{old[:520]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# A read/hide transition must come from a real ViewPager drag. Programmatic
# setCurrentItem(), restore, feed refresh, and adapter callbacks do not count.
s = replace_required(
    s,
    '    private String lastFullscreenPostId = "";\n    private final Deque<NavState> history = new ArrayDeque<>();',
    '    private String lastFullscreenPostId = "";\n    private boolean fullscreenUserGesture = false;\n    private int pendingUserFullscreenPosition = -1;\n    private final Deque<NavState> history = new ArrayDeque<>();',
    'fullscreen user-gesture state')

old_callback = '''        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                postAdapter.setActivePosition(layoutMode.equals("fullscreen") ? position : -1);
                if (layoutMode.equals("fullscreen")
                        && screen != Screen.ACCOUNT
                        && screen != Screen.FAVORITES) {
                    trackFullscreenVisit(position);
                } else {
                    // Stream and every Favorites view are passive: neither can mark
                    // a post read nor advance the five-unique-post hide delay.
                    lastFullscreenPostId = "";
                }
                if (layoutMode.equals("fullscreen") && fullscreenChromeVisible) {
                    setFullscreenChrome(false);
                }
                if (screen == Screen.HOME && !loading && !after.isEmpty()
                        && position >= postAdapter.getItemCount() - 5) {
                    loadFeed(false);
                }
            }
        });'''

new_callback = '''        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    fullscreenUserGesture = true;
                    pendingUserFullscreenPosition = -1;
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    if (fullscreenUserGesture && pendingUserFullscreenPosition >= 0
                            && layoutMode.equals("fullscreen")
                            && screen != Screen.ACCOUNT
                            && screen != Screen.FAVORITES) {
                        trackFullscreenVisit(pendingUserFullscreenPosition);
                    }
                    fullscreenUserGesture = false;
                    pendingUserFullscreenPosition = -1;
                }
            }

            @Override
            public void onPageSelected(int position) {
                postAdapter.setActivePosition(layoutMode.equals("fullscreen") ? position : -1);
                boolean readEligible = layoutMode.equals("fullscreen")
                        && screen != Screen.ACCOUNT
                        && screen != Screen.FAVORITES;
                if (readEligible) {
                    if (fullscreenUserGesture) {
                        // The real read transition is committed only once the swipe settles.
                        pendingUserFullscreenPosition = position;
                    } else {
                        // Programmatic selections establish a baseline only. They never mark
                        // a post read and never advance another post's five-item delay.
                        setFullscreenReadBaseline(position);
                    }
                } else {
                    lastFullscreenPostId = "";
                    pendingUserFullscreenPosition = -1;
                }
                if (layoutMode.equals("fullscreen") && fullscreenChromeVisible) {
                    setFullscreenChrome(false);
                }
                if (screen == Screen.HOME && !loading && !after.isEmpty()
                        && position >= postAdapter.getItemCount() - 5) {
                    loadFeed(false);
                }
            }
        });'''

s = replace_required(s, old_callback, new_callback, 'user-drag-only fullscreen read tracking')

# Programmatic page selection only updates the current baseline.
anchor = '    private void trackFullscreenVisit(int position) {'
baseline = '''    private void setFullscreenReadBaseline(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) {
            lastFullscreenPostId = "";
            return;
        }
        lastFullscreenPostId = current.id;
    }

'''
if anchor not in s:
    raise SystemExit('Missing v3.6.1 patch target: tracking method anchor')
s = s.replace(anchor, baseline + anchor, 1)

# Already-marked hidden posts remain physically present for the current collection,
# so revisiting one in that same session must not poison the baseline. It can count
# as a distinct viewed post for older pending entries, but it is not queued again.
s = replace_required(
    s,
    '''        String currentId = current.id;
        if (hiddenPosts.containsKey(currentId)) return;

        if (lastFullscreenPostId.isEmpty()) {''',
    '''        String currentId = current.id;

        if (lastFullscreenPostId.isEmpty()) {''',
    'allow stable-session traversal across newly hidden posts')

# Critical stability fix: reaching the threshold updates persistent hidden state only.
# Do NOT remove from either live adapter. The current collection remains immutable;
# hidden filtering is applied next time another collection/page is loaded/reloaded.
old_hide = '''    private void hideReadPost(String id) {
        RedditPost post = pendingReadPosts.remove(id);
        pendingReadAfter.remove(id);
        if (post == null) {
            for (RedditPost candidate : postAdapter.getPosts()) {
                if (id.equals(candidate.id)) { post = candidate; break; }
            }
        }
        if (post == null || hiddenPosts.containsKey(id)) return;
        hiddenPosts.put(id, post);
        postAdapter.removePostById(id);
        gridAdapter.removePostById(id);
    }'''

new_hide = '''    private void hideReadPost(String id) {
        RedditPost post = pendingReadPosts.remove(id);
        pendingReadAfter.remove(id);
        if (post == null) {
            for (RedditPost candidate : postAdapter.getPosts()) {
                if (id.equals(candidate.id)) { post = candidate; break; }
            }
        }
        if (post == null || hiddenPosts.containsKey(id)) return;
        hiddenPosts.put(id, post);
        // Deliberately do not mutate postAdapter/gridAdapter here. ViewPager2 must
        // keep exactly the same list and indices for the entire current collection.
        // replacePosts()/appendUnique() apply hiddenPosts only after the user leaves,
        // changes destination/filter/sort, or otherwise reloads a collection.
    }'''

s = replace_required(s, old_hide, new_hide, 'defer hide until next collection/page load')

path.write_text(s)
print('Applied v3.6.1 stable pager + deferred hide filtering')
