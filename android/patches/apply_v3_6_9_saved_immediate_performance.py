from pathlib import Path
import re


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.9 performance target: {label}\n{old[:900]}')
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# MainActivity: Saved leaves the visible Unread collection immediately, while
# preserving the real-swipe read gate. Also defer async adapter appends until
# ViewPager2 is idle so discovery cannot jank the swipe animation.
# ---------------------------------------------------------------------------
path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

s = replace_required(
    s,
    '    private final Set<String> savedPostIds = new HashSet<>();',
    '    private final Set<String> savedPostIds = new HashSet<>();\n'
    '    private final ArrayList<RedditPost> deferredAppends = new ArrayList<>();\n'
    '    private boolean deferredAppendScheduled = false;',
    'deferred append state')

# Favorites is an additive synchronization source. Do not clear IDs first,
# because a media-type filter could otherwise forget Saved videos while viewing
# only images (or vice versa). In-app Unsave removes the canonical ID explicitly.
s = s.replace('        savedPostIds.clear();\n', '', 1)

old_saved_branch = '''                    // Do not mutate the active fullscreen/stream collection here.
                    // The post is already outside the logical Unread population and
                    // replacePosts()/appendUnique() will exclude it on every future
                    // collection. Keeping this collection immutable prevents the old
                    // ViewPager2 skip/reload bug from returning.
                    postAdapter.refreshPost(post);
                    return;'''
new_saved_branch = '''                    // Save is an explicit state transition, not swipe/read progression.
                    // Remove only this manually-saved item from the active Unread
                    // collection and establish a fresh baseline for the item that
                    // shifts into its place. The automatic read path still never
                    // mutates the live pager collection.
                    if (screen != Screen.FAVORITES) {
                        removeSavedFromUnread(post.id);
                        return;
                    }
                    postAdapter.refreshPost(post);
                    return;'''
s = replace_required(s, old_saved_branch, new_saved_branch, 'Saved immediately leaves Unread')

anchor = '    @Override\n    public void onComments(RedditPost post) {'
if anchor not in s:
    raise SystemExit('Missing v3.6.9 performance target: onComments anchor')
remove_method = '''    private void removeSavedFromUnread(String id) {
        if (id == null || id.isEmpty()) return;
        int previousPosition = pager != null ? pager.getCurrentItem() : 0;

        // A button-triggered removal is never allowed to masquerade as a swipe.
        fullscreenUserGesture = false;
        pendingUserFullscreenPosition = -1;
        lastFullscreenPostId = "";

        postAdapter.removePostById(id);
        gridAdapter.removePostById(id);

        int count = postAdapter.getItemCount();
        if (count <= 0) {
            setStatus("No unread media remains in this collection.", false);
            return;
        }

        int target = Math.max(0, Math.min(previousPosition, count - 1));
        pager.setCurrentItem(target, false);
        if (layoutMode.equals("grid")) {
            gridView.scrollToPosition(target);
        } else {
            setFullscreenReadBaseline(target);
            postAdapter.setActivePosition(target);
        }
        hideStatus();
    }

'''
s = s.replace(anchor, remove_method + anchor, 1)

# Replace appendUnique with an idle-aware wrapper. The actual filtering semantics
# are unchanged: Saved/Hidden/content-blocked posts never enter Unread.
append_match = re.search(
    r'    private void appendUnique\(List<RedditPost> incoming\) \{.*?\n    \}\n',
    s,
    flags=re.S)
if not append_match:
    raise SystemExit('Missing v3.6.9 performance target: appendUnique')
old_append = append_match.group(0)
body_match = re.search(r'    private void appendUnique\(List<RedditPost> incoming\) \{(.*?)\n    \}\n', old_append, flags=re.S)
if not body_match:
    raise SystemExit('Could not isolate appendUnique body')
original_body = body_match.group(1)
new_append = '''    private void appendUnique(List<RedditPost> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        if (layoutMode.equals("fullscreen") && pager != null
                && pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            deferredAppends.addAll(incoming);
            scheduleDeferredAppend();
            return;
        }
        appendUniqueNow(incoming);
    }

    private void scheduleDeferredAppend() {
        if (deferredAppendScheduled || root == null) return;
        deferredAppendScheduled = true;
        root.postDelayed(this::flushDeferredAppends, 120L);
    }

    private void flushDeferredAppends() {
        deferredAppendScheduled = false;
        if (deferredAppends.isEmpty()) return;
        if (layoutMode.equals("fullscreen") && pager != null
                && pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            scheduleDeferredAppend();
            return;
        }
        ArrayList<RedditPost> batch = new ArrayList<>(deferredAppends);
        deferredAppends.clear();
        appendUniqueNow(batch);
    }

    private void appendUniqueNow(List<RedditPost> incoming) {''' + original_body + '''
    }
'''
s = s[:append_match.start()] + new_append + s[append_match.end():]

path.write_text(s)


# ---------------------------------------------------------------------------
# Media3: v3.6.7 forced the maximum representation for every prepared player,
# including adjacent offscreen pages. Restore adaptive selection so HD remains
# available but playback can step down instead of buffering/stalling.
# ---------------------------------------------------------------------------
hq = Path('app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java')
hq.write_text(r'''package com.scrolller.adblock;

import android.content.Context;

import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.util.HashMap;
import java.util.Map;

final class HighQualityPlayerFactory {
    private HighQualityPlayerFactory() {}

    static ExoPlayer create(Context context, String mediaUrl) {
        DefaultTrackSelector selector = new DefaultTrackSelector(context);
        selector.setParameters(
                selector.buildUponParameters()
                        .setForceHighestSupportedBitrate(false));

        ExoPlayer.Builder builder = new ExoPlayer.Builder(context)
                .setTrackSelector(selector);

        if (isRedgifsMedia(mediaUrl)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.redgifs.com/");
            headers.put("Origin", "https://www.redgifs.com");
            headers.put("Accept", "*/*");

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 16) RedditMedia/3.6.9")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http);
            builder.setMediaSourceFactory(mediaSourceFactory);
        }

        return builder.build();
    }

    private static boolean isRedgifsMedia(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("redgifs.com") || lower.contains("redgifsusercontent.com");
    }
}
''')

print('Applied v3.6.9 immediate Saved transition + adaptive playback + idle-only discovery appends')
