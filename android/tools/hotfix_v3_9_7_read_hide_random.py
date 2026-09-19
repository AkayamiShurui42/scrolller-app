from pathlib import Path

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one target, found {count}")
    return text.replace(old, new, 1)

main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
gradle_path = Path("app/build.gradle")
main = main_path.read_text()
gradle = gradle_path.read_text()

gradle = replace_once(gradle, 'versionCode 53', 'versionCode 54', 'versionCode')
gradle = replace_once(gradle, 'versionName "3.9.6"', 'versionName "3.9.7"', 'versionName')

old_track = '''private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            return;
        }
        if (lastFullscreenPostId.equals(currentId)) return;

        RedditPost previous = null;
        for (RedditPost candidate : postAdapter.getPosts()) {
            if (candidate != null && lastFullscreenPostId.equals(candidate.id)) {
                previous = candidate;
                break;
            }
        }

        // PostPagerAdapter reports readiness only after the active media meets
        // its view gate: displayed image/GIF/gallery, or rendered video with
        // >= 1 second of actual playback. Time on the pager is not enough.
        boolean actuallyViewed = previous != null
                && mediaReadyPostIds.contains(previous.id);
        if (actuallyViewed
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenContainsPostId(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            readHideStore.hideAsync(previous);
            trimHiddenPostCache();
        }
        lastFullscreenPostId = currentId;
    }
'''

new_track = '''private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            return;
        }
        if (barePostId(lastFullscreenPostId).equals(barePostId(currentId))) return;

        RedditPost previous = null;
        for (RedditPost candidate : postAdapter.getPosts()) {
            if (candidate != null
                    && barePostId(lastFullscreenPostId).equals(barePostId(candidate.id))) {
                previous = candidate;
                break;
            }
        }

        // A completed ViewPager user gesture is itself the read event. The older
        // mediaReady gate made fast image swipes and videos under one second look
        // unread forever even though the user deliberately advanced past them.
        // Keep the active collection immutable; future loads filter this ID.
        if (previous != null
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedContainsPostId(previous.id)
                && !hiddenContainsPostId(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            readHideStore.hideAsync(previous);
            trimHiddenPostCache();
        }
        lastFullscreenPostId = currentId;
    }
'''
main = replace_once(main, old_track, new_track, 'completed swipe read tracking')

old_first = '''            // Make subreddit/feed entry feel immediate. Final ordering still runs
            // once the small reservoir is complete.
            if (reset && page == 0 && !collected.isEmpty()) {
                replacePosts(new ArrayList<>(collected));
                hideStatus();
                updateChrome();
            }
'''
new_first = '''            // Make subreddit/feed entry feel immediate, but honor Random before
            // anything reaches the screen. Previously page 0 was shown in Reddit's
            // remote "new" order and only later pages were shuffled.
            if (reset && page == 0 && !collected.isEmpty()) {
                ArrayList<RedditPost> firstVisible = new ArrayList<>(collected);
                if (sort.equals("random")) Collections.shuffle(firstVisible);
                replacePosts(firstVisible);
                hideStatus();
                updateChrome();
            }
'''
main = replace_once(main, old_first, new_first, 'randomize first visible batch')

old_multi = '''                // Show the first successful subreddit immediately while the remaining
                // preset members continue through the serialized request queue.
                if (reset && postAdapter.getItemCount() == 0 && !collected.isEmpty()) {
                    replacePosts(new ArrayList<>(collected));
                    hideStatus();
                    updateChrome();
                }
'''
new_multi = '''                // Show the first successful subreddit immediately while the remaining
                // preset members continue through the serialized request queue.
                if (reset && postAdapter.getItemCount() == 0 && !collected.isEmpty()) {
                    ArrayList<RedditPost> firstVisible = new ArrayList<>(collected);
                    if (sort.equals("random")) Collections.shuffle(firstVisible);
                    replacePosts(firstVisible);
                    hideStatus();
                    updateChrome();
                }
'''
main = replace_once(main, old_multi, new_multi, 'randomize multi fallback first batch')

main_path.write_text(main)
gradle_path.write_text(gradle)
print("Applied Reddit Media v3.9.7 read-hide + immediate Random hotfix")
