from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


adapter_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
adapter = adapter_path.read_text()

adapter = replace_once(
    adapter,
    '''public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : new ArrayList<>(players.entrySet())) {
            try {
                boolean active = !pagerScrolling && entry.getKey() == position;
                entry.getValue().setPlayWhenReady(active);
                if (!active) entry.getValue().pause();
            } catch (RuntimeException ignored) {}
        }
        if (!pagerScrolling) warmAdjacentMedia(position);
    }

    public void setPagerScrolling(boolean scrolling) {
        if (pagerScrolling == scrolling) return;
        pagerScrolling = scrolling;
        if (scrolling) {
            HighQualityPlayerFactory.cancelPendingPreloads();
            for (ExoPlayer player : new ArrayList<>(players.values())) {
                try {
                    player.setPlayWhenReady(false);
                    player.pause();
                } catch (RuntimeException ignored) {}
            }
            return;
        }
        // Resume only after ViewPager2 is idle. This collapses a burst of
        // intermediate onPageSelected callbacks into one preload/playback update.
        setActivePosition(activePosition);
    }''',
    '''public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : new ArrayList<>(players.entrySet())) {
            try {
                // Playback follows the selected page immediately, even while the
                // ViewPager is settling. Only one player is ever allowed to play.
                boolean active = entry.getKey() == position;
                entry.getValue().setPlayWhenReady(active);
                if (!active) entry.getValue().pause();
            } catch (RuntimeException ignored) {}
        }
        // Expensive background cache warming still waits until the gesture settles.
        if (!pagerScrolling) warmAdjacentMedia(position);
    }

    public void setPagerScrolling(boolean scrolling) {
        if (pagerScrolling == scrolling) return;
        pagerScrolling = scrolling;
        if (scrolling) {
            // Stop opportunistic CacheWriter work so it cannot fight the pager, but
            // keep the currently selected player hot. onPageSelected() will hand
            // playback directly to the new selected page before IDLE.
            HighQualityPlayerFactory.cancelPendingPreloads();
            setActivePosition(activePosition);
            return;
        }
        // The selected player is already running; IDLE only restarts look-ahead
        // cache warming for the next posts.
        setActivePosition(activePosition);
    }''',
    "selected playback during swipe")

adapter_path.write_text(adapter)

factory_path = Path("app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java")
factory = factory_path.read_text()
factory = factory.replace("RedditMedia/3.8.5", "RedditMedia/3.8.7")
factory_path.write_text(factory)

build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, "versionCode 40", "versionCode 41", "versionCode")
build = replace_once(build, 'versionName "3.8.6"', 'versionName "3.8.7"', "versionName")
build_path.write_text(build)

print("Applied v3.8.7 immediate selected-page playback hotfix")
