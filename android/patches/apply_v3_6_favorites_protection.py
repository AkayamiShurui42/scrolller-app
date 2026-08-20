from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6 Favorites patch target: {label}\n{old[:420]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# Favorites is never a source of read/hide progression, even in Fullscreen.
s = replace_required(
    s,
    '''                if (layoutMode.equals("fullscreen")
                        && screen != Screen.ACCOUNT
                        && !(screen == Screen.FAVORITES && favoritesView.equals("hidden"))) {
                    trackFullscreenVisit(position);
                } else {
                    lastFullscreenPostId = "";
                }''',
    '''                if (layoutMode.equals("fullscreen")
                        && screen != Screen.ACCOUNT
                        && screen != Screen.FAVORITES) {
                    trackFullscreenVisit(position);
                } else {
                    // Stream and every Favorites view are passive: neither can mark
                    // a post read nor advance the five-unique-post hide delay.
                    lastFullscreenPostId = "";
                }''',
    'Favorites must not mark read')

# Saved Favorites is a visibility exemption. A post can remain globally hidden
# from normal feeds while still appearing in the user's Saved library.
s = replace_required(
    s,
    '''        boolean hiddenLibrary = showingHiddenLibrary();
        postAdapter.setHiddenMode(hiddenLibrary);
        ArrayList<RedditPost> visible = new ArrayList<>();
        for (RedditPost post : items) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (hiddenLibrary || !hiddenPosts.containsKey(post.id)) visible.add(post);
        }''',
    '''        boolean hiddenLibrary = showingHiddenLibrary();
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        postAdapter.setHiddenMode(hiddenLibrary);
        ArrayList<RedditPost> visible = new ArrayList<>();
        for (RedditPost post : items) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (hiddenLibrary || favoritesSaved || !hiddenPosts.containsKey(post.id)) visible.add(post);
        }''',
    'Saved Favorites ignores hidden filtering')

s = replace_required(
    s,
    '''        if (showingHiddenLibrary()) return;
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) ids.add(post.id);
        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (hiddenPosts.containsKey(post.id)) continue;
            if (ids.add(post.id)) unique.add(post);
        }''',
    '''        if (showingHiddenLibrary()) return;
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) ids.add(post.id);
        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (!favoritesSaved && hiddenPosts.containsKey(post.id)) continue;
            if (ids.add(post.id)) unique.add(post);
        }''',
    'Saved Favorites append ignores hidden filtering')

path.write_text(s)
print('Applied v3.6 Favorites protection: no read tracking and Saved stays visible')
