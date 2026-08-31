from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.9 stability target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)


path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# v3.6.9 unread_content_filters originally removed a Saved post directly from
# the active ViewPager collection. That reintroduces the index-shift bug fixed
# in v3.6.1. Saved state changes immediately, but the current pager collection
# remains immutable until the next reload/destination change.
old_save = '''            if (result.ok) {
                post.saved = !post.saved;
                if (post.saved) {
                    if (post.id != null && !post.id.isEmpty()) savedPostIds.add(post.id);
                    persistSavedPostIds();
                    if (post.id != null && hiddenPosts.remove(post.id) != null) {
                        saveReadHideState();
                        if (showingHiddenLibrary()) {
                            loadHiddenPostsView();
                            return;
                        }
                    }
                    if (screen != Screen.FAVORITES) {
                        int position = pager.getCurrentItem();
                        postAdapter.removePostById(post.id);
                        gridAdapter.removePostById(post.id);
                        if (postAdapter.getItemCount() > 0) {
                            int target = Math.min(position, postAdapter.getItemCount() - 1);
                            pager.setCurrentItem(target, false);
                            setFullscreenReadBaseline(target);
                            hideStatus();
                        } else {
                            lastFullscreenPostId = "";
                            setStatus("No unread media remains in this collection.", false);
                        }
                        return;
                    }
                } else {
                    savedPostIds.remove(post.id);
                    persistSavedPostIds();
                    if (screen == Screen.FAVORITES && favoritesView.equals("saved")) {
                        int position = pager.getCurrentItem();
                        postAdapter.removePostById(post.id);
                        gridAdapter.removePostById(post.id);
                        if (postAdapter.getItemCount() > 0) {
                            pager.setCurrentItem(Math.min(position, postAdapter.getItemCount() - 1), false);
                            hideStatus();
                        } else {
                            setStatus("No saved media posts yet.", false);
                        }
                        return;
                    }
                }
                postAdapter.refreshPost(post);
            } else {'''

new_save = '''            if (result.ok) {
                post.saved = !post.saved;
                if (post.saved) {
                    if (post.id != null && !post.id.isEmpty()) savedPostIds.add(post.id);
                    persistSavedPostIds();
                    if (post.id != null && hiddenPosts.remove(post.id) != null) {
                        saveReadHideState();
                        if (showingHiddenLibrary()) {
                            loadHiddenPostsView();
                            return;
                        }
                    }
                    // Do not mutate the active fullscreen/stream collection here.
                    // The post is already outside the logical Unread population and
                    // replacePosts()/appendUnique() will exclude it on every future
                    // collection. Keeping this collection immutable prevents the old
                    // ViewPager2 skip/reload bug from returning.
                    postAdapter.refreshPost(post);
                    return;
                }

                if (post.id != null && !post.id.isEmpty()) {
                    savedPostIds.remove(post.id);
                    feedSeenPostIds.remove(post.id);
                }
                persistSavedPostIds();
                if (screen == Screen.FAVORITES && favoritesView.equals("saved")) {
                    // Favorites is passive, so a clean library reload is safe and
                    // makes an Unsave disappear from the Saved folder immediately.
                    loadFavoritesInternal();
                    return;
                }
                postAdapter.refreshPost(post);
            } else {'''

s = replace_required(s, old_save, new_save, 'stable Saved transition')

# A Saved item that remains visually present in the immutable current collection
# must never also become Read/Hidden when the user swipes away from it.
s = replace_required(
    s,
    '''                && mediaReadyPostIds.contains(previous.id)
                && !previous.saved
                && !hiddenPosts.containsKey(previous.id)) {''',
    '''                && mediaReadyPostIds.contains(previous.id)
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {''',
    'Saved remains disjoint from Hidden')

path.write_text(s)
print('Applied v3.6.9 Saved-state pager stability correction')
