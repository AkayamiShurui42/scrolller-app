from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()

# ---------------------------------------------------------------------------
# Bottom command bar: Home / View / Collections / Save / Settings.
# Icon glyphs are intentionally deferred; labels make interaction testable now.
# ---------------------------------------------------------------------------
main = replace_once(
    main,
    '''        addNavButton("⌂\\nHome", () -> navigateHome("home", true));
        addNavButton("⌕\\nSearch", this::openSearchScreen);
        addNavButton("★\\nFavorites", this::loadFavorites);
        addNavButton("●\\nAccount", this::showAccount);
''',
    '''        addNavButton("Home", this::showHomeCommandSheet);
        addNavButton("View", this::showViewCommandSheet);
        addNavButton("Collections", this::showCollectionsCommandSheet);
        saveCommandButton = addNavButton("Save", this::saveCurrentPostFromCommandBar);
        addNavButton("Settings", this::showSettingsCommandSheet);
''',
    "bottom command buttons",
)

main = replace_once(
    main,
    '''    private Button compactMenuButton;
''',
    '''    private Button compactMenuButton;
    private Button saveCommandButton;
''',
    "save command field",
)

main = replace_once(
    main,
    '''        installCompactNavigation();
}''',
    '''        // Fullscreen opens clean. One media tap reveals the thumb-first command bar.
        setFullscreenChrome(!layoutMode.equals("fullscreen"));
}''',
    "disable compact menu install",
)

# Hide chrome as soon as a fullscreen swipe begins.
main = replace_once(
    main,
    '''                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    postAdapter.setPagerScrolling(true);
                    fullscreenUserGesture = true;
                    pendingUserFullscreenPosition = -1;
''',
    '''                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    postAdapter.setPagerScrolling(true);
                    if (layoutMode.equals("fullscreen")) setFullscreenChrome(false);
                    fullscreenUserGesture = true;
                    pendingUserFullscreenPosition = -1;
''',
    "hide command bar on swipe",
)

main = replace_once(
    main,
    '''                // Preserve fullscreen chrome state across page changes.
                // Entering Fullscreen still starts hidden, but once the user taps
                // to reveal the overlay it stays visible until they tap to hide it.
                if (screen == Screen.HOME && !loading && !after.isEmpty()
''',
    '''                updateSaveCommandState();
                if (screen == Screen.HOME && !loading && !after.isEmpty()
''',
    "save state on page selection",
)

# ---------------------------------------------------------------------------
# Chrome behavior: top UI is gone; bottom bar is the single app command surface.
# ---------------------------------------------------------------------------
old_chrome = '''        applySystemInsets(systemTopPx, systemBottomPx);
        setFullscreenChrome(layoutMode.equals("grid") || fullscreenChromeVisible);
    
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (compactMenuButton != null) compactMenuButton.setVisibility(View.VISIBLE);
}

private void setFullscreenChrome(boolean visible) {
        fullscreenChromeVisible = visible;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (compactMenuButton != null) compactMenuButton.setVisibility(View.VISIBLE);
        if (postAdapter != null) postAdapter.setChromeVisible(visible);
    }
'''
new_chrome = '''        applySystemInsets(systemTopPx, systemBottomPx);
        setFullscreenChrome(layoutMode.equals("grid") || fullscreenChromeVisible);
        updateSaveCommandState();
}

private void setFullscreenChrome(boolean visible) {
        fullscreenChromeVisible = visible;
        if (topBar != null) topBar.setVisibility(View.GONE);
        boolean showBottom = !layoutMode.equals("fullscreen")
                || screen == Screen.ACCOUNT
                || visible;
        if (bottomBar != null) bottomBar.setVisibility(showBottom ? View.VISIBLE : View.GONE);
        if (compactMenuButton != null) compactMenuButton.setVisibility(View.GONE);
        if (postAdapter != null) postAdapter.setChromeVisible(visible);
        updateSaveCommandState();
    }
'''
main = replace_once(main, old_chrome, new_chrome, "single bottom chrome")

# System insets: reserve bottom dock space, stop forcing it hidden, move browser Back down.
main = replace_once(
    main,
    '''        FrameLayout.LayoutParams backParams = (FrameLayout.LayoutParams) browserBack.getLayoutParams();
        backParams.topMargin = systemTopPx + dp(8);
        browserBack.setLayoutParams(backParams);

        postAdapter.setSystemInsets(systemTopPx, systemBottomPx);
    
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (gridView != null) gridView.setPadding(0, systemTopPx + dp(8), 0, systemBottomPx + dp(8));
        if (statusPanel != null && statusPanel.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams statusParams = (FrameLayout.LayoutParams) statusPanel.getLayoutParams();
            statusParams.bottomMargin = systemBottomPx + dp(18);
            statusPanel.setLayoutParams(statusParams);
        }

        if (compactMenuButton != null && compactMenuButton.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams menuParams = (FrameLayout.LayoutParams) compactMenuButton.getLayoutParams();
            menuParams.bottomMargin = systemBottomPx + dp(14);
            compactMenuButton.setLayoutParams(menuParams);
        }
}''',
    '''        FrameLayout.LayoutParams backParams = (FrameLayout.LayoutParams) browserBack.getLayoutParams();
        backParams.topMargin = 0;
        backParams.bottomMargin = systemBottomPx + dp(14);
        browserBack.setLayoutParams(backParams);

        postAdapter.setSystemInsets(systemTopPx, systemBottomPx);

        if (topBar != null) topBar.setVisibility(View.GONE);
        if (gridView != null) gridView.setPadding(0, systemTopPx + dp(8), 0, systemBottomPx + dp(72));
        if (statusPanel != null && statusPanel.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams statusParams = (FrameLayout.LayoutParams) statusPanel.getLayoutParams();
            statusParams.bottomMargin = systemBottomPx + dp(78);
            statusPanel.setLayoutParams(statusParams);
        }

        if (compactMenuButton != null) compactMenuButton.setVisibility(View.GONE);
}''',
    "bottom inset ownership",
)

main = replace_once(
    main,
    '''        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(88), dp(42), Gravity.TOP | Gravity.START);
        bp.topMargin = dp(8);
        bp.leftMargin = dp(8);
''',
    '''        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(88), dp(42), Gravity.BOTTOM | Gravity.START);
        bp.bottomMargin = dp(14);
        bp.leftMargin = dp(8);
''',
    "browser back placement",
)

# ---------------------------------------------------------------------------
# Back semantics: content-source hopping is replacement, not a breadcrumb stack.
# ---------------------------------------------------------------------------
main = replace_once(
    main,
    '''        if (!history.isEmpty()) {
            restoreState(history.pop());
            return;
        }

        boolean rootHome = screen == Screen.HOME
                && context.equals("home")
                && subreddit.isEmpty();
        if (!rootHome) {
            navigateHome("home", false);
            return;
        }
''',
    '''        boolean rootHome = screen == Screen.HOME
                && context.equals("home")
                && subreddit.isEmpty();
        if (!rootHome) {
            history.clear();
            navigateHome("home", false);
            return;
        }
        history.clear();
''',
    "flat back navigation",
)

main = replace_once(
    main,
    '''    private void openSubredditFeed(String name) {
        if (name == null || name.isEmpty()) return;
        pushCurrentState();
        sort = "random";
''',
    '''    private void openSubredditFeed(String name) {
        if (name == null || name.isEmpty()) return;
        history.clear();
        sort = "random";
''',
    "subreddit replaces home content",
)

main = replace_once(
    main,
    '''        if (screen != Screen.SEARCH) {
            searchCollectorSnapshot.clear();
            searchCollectorSnapshot.addAll(postAdapter.getPosts());
            if (subredditEntry) {
                searchSubreddit = cleanSubredditName(subreddit);
            }
            pushCurrentState();
        }
''',
    '''        if (screen != Screen.SEARCH) {
            searchCollectorSnapshot.clear();
            searchCollectorSnapshot.addAll(postAdapter.getPosts());
            if (subredditEntry) {
                searchSubreddit = cleanSubredditName(subreddit);
            }
            history.clear();
        }
''',
    "search does not breadcrumb",
)

main = replace_once(
    main,
    '''        if (clean.size() < 2) return;
        pushCurrentState();
        sort = "random";
''',
    '''        if (clean.size() < 2) return;
        history.clear();
        sort = "random";
''',
    "preset replaces home content",
)

# ---------------------------------------------------------------------------
# Navigation button helper now returns the button so Save can reflect state.
# ---------------------------------------------------------------------------
main = replace_once(
    main,
    '''    private void addNavButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setBackgroundColor(Color.TRANSPARENT);
        bottomBar.addView(button, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        button.setOnClickListener(v -> action.run());
    }
''',
    '''    private Button addNavButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(10);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setBackgroundColor(Color.TRANSPARENT);
        bottomBar.addView(button, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        button.setOnClickListener(v -> action.run());
        return button;
    }
''',
    "return nav button",
)

# ---------------------------------------------------------------------------
# New command surfaces. They reuse existing domain-specific sheets so behavior
# stays stable while the information architecture changes.
# ---------------------------------------------------------------------------
insert_before = '''    private void showCompactMainMenu() {'''
new_methods = r'''    private void saveCurrentPostFromCommandBar() {
        RedditPost post = currentPagerPost();
        if (post == null) {
            setStatus("No current post to save.", false);
            return;
        }
        onSave(post);
    }

    private void updateSaveCommandState() {
        if (saveCommandButton == null) return;
        RedditPost post = currentPagerPost();
        boolean available = post != null && post.id != null && !post.id.isEmpty();
        saveCommandButton.setEnabled(available);
        if (!available) {
            saveCommandButton.setText("Save");
            saveCommandButton.setAlpha(0.45f);
            return;
        }
        boolean saved = post.saved || savedPostIds.contains(post.id);
        saveCommandButton.setText(saved ? "Saved" : "Save");
        saveCommandButton.setAlpha(1f);
    }

    private void showHomeCommandSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Home");
        scroll.addView(body);

        Button home = sheetButton("Go to Home");
        body.addView(home, sectionButtonParams());
        home.setOnClickListener(v -> {
            dialog.dismiss();
            history.clear();
            navigateHome("home", false);
        });

        AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setSingleLine(true);
        input.setHint("Search posts or open a subreddit");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        input.setThreshold(0);
        body.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        installCommunityAutocomplete(input);

        Button search = sheetButton("Search media");
        Button openSubreddit = sheetButton("Open as subreddit");
        body.addView(search, sectionButtonParams());
        body.addView(openSubreddit, sectionButtonParams());
        search.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            dialog.dismiss();
            runMenuSearch(value, "global", "");
        });
        openSubreddit.setOnClickListener(v -> {
            String value = cleanSubredditName(input.getText().toString());
            if (value.isEmpty()) return;
            dialog.dismiss();
            openSubredditFeed(value);
        });

        Button categories = sheetButton("Browse categories");
        body.addView(categories, sectionButtonParams());
        categories.setOnClickListener(v -> { dialog.dismiss(); showCategoryRoot(); });

        addCommunitySection(body, "Favorites", favoriteSubreddits, true);
        LinkedHashSet<String> subscribedNames = new LinkedHashSet<>();
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) subscribedNames.add(clean);
        }
        addCommunitySection(body, "Subscribed", subscribedNames, false);

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showViewCommandSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("View");
        Button sortMenu = sheetButton("Sort · " + label(sort));
        Button mediaMenu = sheetButton("Media · " + (media.equals("all") ? "All" : media.equals("image") ? "Images" : "Video / GIF"));
        Button filterMenu = sheetButton("Filter");
        Button contentMenu = sheetButton("Content · " + ContentTaxonomy.label(peopleFilterMask));
        Button viewedMenu = sheetButton("Viewed · " + (showViewedPosts ? "Include viewed" : "Unread only"));
        body.addView(sortMenu, sectionButtonParams());
        body.addView(mediaMenu, sectionButtonParams());
        body.addView(filterMenu, sectionButtonParams());
        body.addView(contentMenu, sectionButtonParams());
        body.addView(viewedMenu, sectionButtonParams());
        sortMenu.setOnClickListener(v -> { dialog.dismiss(); showSortSheet(); });
        mediaMenu.setOnClickListener(v -> { dialog.dismiss(); showMediaSheet(); });
        filterMenu.setOnClickListener(v -> { dialog.dismiss(); showViewFilterSheet(); });
        contentMenu.setOnClickListener(v -> { dialog.dismiss(); showPeopleFilterSheet(); });
        viewedMenu.setOnClickListener(v -> { dialog.dismiss(); showViewedModeSheet(); });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showViewedModeSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Viewed posts");
        Button unread = sheetButton((!showViewedPosts ? "✓ " : "") + "Unread only");
        Button all = sheetButton((showViewedPosts ? "✓ " : "") + "Include viewed");
        body.addView(unread, sectionButtonParams());
        body.addView(all, sectionButtonParams());
        unread.setOnClickListener(v -> {
            showViewedPosts = false;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        all.setOnClickListener(v -> {
            showViewedPosts = true;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showViewFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Filter");
        Button sources = sheetButton("Sources · " + sourceFilterLabel());
        Button joined = sheetButton("Subreddits · " + (joinedOnlyFilter ? "Joined only" : "Any"));
        Button video = sheetButton("Video length / quality");
        Button advanced = sheetButton("Communities / blocked creators");
        body.addView(sources, sectionButtonParams());
        body.addView(joined, sectionButtonParams());
        body.addView(video, sectionButtonParams());
        body.addView(advanced, sectionButtonParams());
        sources.setOnClickListener(v -> { dialog.dismiss(); showSourceFilterSheet(); });
        joined.setOnClickListener(v -> {
            joinedOnlyFilter = !joinedOnlyFilter;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        video.setOnClickListener(v -> { dialog.dismiss(); showVideoFilterSheet(); });
        advanced.setOnClickListener(v -> { dialog.dismiss(); showAdvancedDiscoveryFilterSheet(); });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showCollectionsCommandSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Collections");
        Button saved = sheetButton("Saved posts");
        Button viewed = sheetButton("Viewed posts");
        Button subscribed = sheetButton("Subscribed subreddits");
        Button presets = sheetButton("Multi-subreddit presets");
        body.addView(saved, sectionButtonParams());
        body.addView(viewed, sectionButtonParams());
        body.addView(subscribed, sectionButtonParams());
        body.addView(presets, sectionButtonParams());
        saved.setOnClickListener(v -> {
            dialog.dismiss();
            favoritesView = "saved";
            loadFavorites();
        });
        viewed.setOnClickListener(v -> {
            dialog.dismiss();
            loadFavorites();
            favoritesView = "hidden";
            loadHiddenPostsView();
        });
        subscribed.setOnClickListener(v -> { dialog.dismiss(); showSubscribedCollectionsSheet(); });
        presets.setOnClickListener(v -> { dialog.dismiss(); showPresetSubmenu(); });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showSubscribedCollectionsSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Subscribed subreddits");
        scroll.addView(body);
        LinkedHashSet<String> subscribedNames = new LinkedHashSet<>();
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) subscribedNames.add(clean);
        }
        addCommunitySection(body, "Subscribed", subscribedNames, false);
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showSettingsCommandSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Settings");
        Button account = sheetButton("Account");
        Button layout = sheetButton("Layout");
        Button cache = sheetButton("Media cache");
        Button diagnostics = sheetButton("Feed diagnostics");
        body.addView(account, sectionButtonParams());
        body.addView(layout, sectionButtonParams());
        body.addView(cache, sectionButtonParams());
        body.addView(diagnostics, sectionButtonParams());
        account.setOnClickListener(v -> { dialog.dismiss(); showAccount(); });
        layout.setOnClickListener(v -> { dialog.dismiss(); showLayoutSheet(); });
        cache.setOnClickListener(v -> { dialog.dismiss(); showCacheControlSheet(); });
        diagnostics.setOnClickListener(v -> { dialog.dismiss(); showFeedDiagnosticsSheet(); });
        dialog.setContentView(body);
        dialog.show();
    }

'''
main = replace_once(main, insert_before, new_methods + insert_before, "insert bottom command surfaces")

# Save button state after API transitions.
main = main.replace('''                post.saved = !post.saved;
''', '''                post.saved = !post.saved;
                updateSaveCommandState();
''', 1)

main_path.write_text(main)

# Version bump.
build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, "versionCode 50", "versionCode 51", "versionCode")
build = replace_once(build, 'versionName "3.9.3"', 'versionName "3.9.4"', "versionName")
build_path.write_text(build)

print("Applied Reddit Media v3.9.4 bottom command UI")
