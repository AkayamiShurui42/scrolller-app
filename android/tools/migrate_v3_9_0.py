from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    if replacement in text:
        return text
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"{label}: start marker not found")
    b = text.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f"{label}: end marker not found")
    return text[:a] + replacement + "\n\n" + text[b:]


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()

# UI imports for fuzzy subreddit autocomplete.
main = replace_once(
    main,
    'import android.os.SystemClock;\n',
    'import android.os.SystemClock;\nimport android.text.Editable;\nimport android.text.TextWatcher;\n',
    'text watcher imports')
main = replace_once(
    main,
    'import android.widget.Button;\n',
    'import android.widget.ArrayAdapter;\nimport android.widget.AutoCompleteTextView;\nimport android.widget.Button;\n',
    'autocomplete imports')

# Persistent discovery/filter state.
old_fields = '''    private boolean fullscreenChromeVisible = true;
    private boolean blockLgbtTopics = false;
    private boolean blockGoreContent = false;
    private int systemTopPx;'''
new_fields = '''    private boolean fullscreenChromeVisible = true;
    private boolean blockLgbtTopics = false;
    private boolean blockGoreContent = false;

    // v3.9 discovery controls. A zero people/source mask means "all".
    private int peopleFilterMask = 0;
    private int sourceFilterMask = 0;
    private boolean showViewedPosts = false;
    private boolean joinedOnlyFilter = false;
    private int minimumVideoHeight = 0;
    private int videoLengthMode = 0; // 0 all, 1 <=30s, 2 31-120s, 3 >120s
    private final LinkedHashSet<String> favoriteSubreddits = new LinkedHashSet<>();
    private final LinkedHashSet<String> includedSubreddits = new LinkedHashSet<>();
    private final LinkedHashSet<String> excludedSubreddits = new LinkedHashSet<>();
    private final LinkedHashSet<String> blockedCreators = new LinkedHashSet<>();
    private final LinkedHashMap<String, String> presetFilterSnapshots = new LinkedHashMap<>();

    private int systemTopPx;'''
main = replace_once(main, old_fields, new_fields, 'filter fields')

old_prefs = '''        muted = prefs.getBoolean("muted", true);
        blockLgbtTopics = prefs.getBoolean("blockLgbtTopics", false);
        blockGoreContent = prefs.getBoolean("blockGoreContent", false);
        Set<String> persistedSavedIds = prefs.getStringSet("savedPostIds", null);'''
new_prefs = '''        muted = prefs.getBoolean("muted", true);
        // The old one-switch LGBTQ blocker is superseded by the explicit
        // multi-select people/content taxonomy below.
        blockLgbtTopics = false;
        prefs.edit().remove("blockLgbtTopics").apply();
        blockGoreContent = prefs.getBoolean("blockGoreContent", false);
        peopleFilterMask = prefs.getInt("peopleFilterMaskV1", 0);
        sourceFilterMask = prefs.getInt("sourceFilterMaskV1", 0);
        showViewedPosts = prefs.getBoolean("showViewedPostsV1", false);
        joinedOnlyFilter = prefs.getBoolean("joinedOnlyFilterV1", false);
        minimumVideoHeight = prefs.getInt("minimumVideoHeightV1", 0);
        videoLengthMode = prefs.getInt("videoLengthModeV1", 0);
        loadDiscoverySets();
        loadPresetFilterSnapshots();
        Set<String> persistedSavedIds = prefs.getStringSet("savedPostIds", null);'''
main = replace_once(main, old_prefs, new_prefs, 'filter prefs load')

# Make every discovery source go through the same metadata-only filters.
old_block_start = '''    private boolean isContentBlocked(RedditPost post) {
        if (post != null && !post.nsfw) return true;

        if (post == null) return false;
'''
new_block_start = '''    private boolean isContentBlocked(RedditPost post) {
        if (post != null && !post.nsfw) return true;

        if (post == null) return false;
        if (!ContentTaxonomy.matches(post, peopleFilterMask)) return true;
        if (!passesDiscoveryFilters(post)) return true;
'''
main = replace_once(main, old_block_start, new_block_start, 'central discovery filters')

# Tag Arctic Shift posts so source filtering/diagnostics can distinguish archives.
old_archive_return = '''            JSONObject child = new JSONObject();
            child.put("data", data);
            return RedditPost.fromChild(child);'''
new_archive_return = '''            JSONObject child = new JSONObject();
            child.put("data", data);
            RedditPost archived = RedditPost.fromChild(child);
            if (archived != null) archived.sourceOrigin = "archive";
            return archived;'''
main = replace_once(main, old_archive_return, new_archive_return, 'archive provenance')

# Read/viewed filtering remains recorded exactly as before, but can now be
# intentionally shown when Viewed = All.
main = main.replace('''if (hiddenPosts.containsKey(post.id)
                            || isSavedForUnread(post)''', '''if (isReadHiddenForDiscovery(post)
                            || isSavedForUnread(post)''')
main = main.replace('''if (hiddenPosts.containsKey(post.id)
                                || isSavedForUnread(post)''', '''if (isReadHiddenForDiscovery(post)
                                || isSavedForUnread(post)''')
main = main.replace('''if (hiddenPosts.containsKey(post.id) || isSavedForUnread(post) || isContentBlocked(post)) continue;''', '''if (isReadHiddenForDiscovery(post) || isSavedForUnread(post) || isContentBlocked(post)) continue;''')
main = main.replace('''&& (hiddenPosts.containsKey(post.id)
                        || isSavedForUnread(post)''', '''&& (isReadHiddenForDiscovery(post)
                        || isSavedForUnread(post)''')
main = main.replace('''return !hiddenPosts.containsKey(post.id)
                && !isSavedForUnread(post)''', '''return !isReadHiddenForDiscovery(post)
                && !isSavedForUnread(post)''')
main = main.replace('''&& !hiddenPosts.containsKey(post.id)
                    && !isSavedForUnread(post)''', '''&& !isReadHiddenForDiscovery(post)
                    && !isSavedForUnread(post)''')
main = main.replace('''&& !hiddenPosts.containsKey(post.id)) collected.add(post);''', '''&& !isReadHiddenForDiscovery(post)) collected.add(post);''')

# Replace the old account-level LGBTQ blocker with the actual multi-select filter.
old_account_filter = '''        Button lgbtFilter = sheetButton("LGBTQ topics · " + (blockLgbtTopics ? "Blocked" : "Allowed"));
        body.addView(lgbtFilter, sectionButtonParams());
        lgbtFilter.setOnClickListener(v -> {
            blockLgbtTopics = !blockLgbtTopics;
            prefs.edit().putBoolean("blockLgbtTopics", blockLgbtTopics).apply();
            renderAccount();
        });'''
new_account_filter = '''        Button peopleFilter = sheetButton("People / content · " + ContentTaxonomy.label(peopleFilterMask));
        body.addView(peopleFilter, sectionButtonParams());
        peopleFilter.setOnClickListener(v -> showPeopleFilterSheet());'''
main = replace_once(main, old_account_filter, new_account_filter, 'account people filter')

# Browse now includes current membership/favorite actions plus the user's actual
# favorite/subscribed community lists.
new_browse = '''    private void showBrowseSubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Browse");
        scroll.addView(body);

        Button home = sheetButton("Browse all / Home");
        Button subredditButton = sheetButton("Find / open subreddit…");
        Button categories = sheetButton("NSFW categories…");
        body.addView(home, sectionButtonParams());
        body.addView(subredditButton, sectionButtonParams());
        body.addView(categories, sectionButtonParams());
        home.setOnClickListener(v -> { dialog.dismiss(); navigateHome("home", true); });
        subredditButton.setOnClickListener(v -> { dialog.dismiss(); showOpenSubredditSheet(); });
        categories.setOnClickListener(v -> { dialog.dismiss(); showCategoryRoot(); });

        if (screen == Screen.HOME && context.equals("subreddit")
                && subreddit != null && !subreddit.isEmpty()) {
            String target = subreddit;
            boolean subscribed = subscriptionNames.contains(target.toLowerCase(Locale.US));
            Button membership = sheetButton((subscribed ? "Leave / unsubscribe " : "Join / subscribe ")
                    + "r/" + target);
            if (subscribed) membership.setTextColor(0xFFFFB0B0);
            body.addView(membership, sectionButtonParams());
            membership.setOnClickListener(v -> { dialog.dismiss(); toggleSubredditSubscription(); });

            boolean favorite = favoriteSubreddits.contains(target.toLowerCase(Locale.US));
            Button favoriteButton = sheetButton((favorite ? "★ Remove favorite " : "☆ Favorite ")
                    + "r/" + target);
            body.addView(favoriteButton, sectionButtonParams());
            favoriteButton.setOnClickListener(v -> {
                dialog.dismiss();
                toggleFavoriteSubreddit(target);
            });
        }

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
    }'''
main = replace_between(main,
        '    private void showBrowseSubmenu() {',
        '    private void showOpenSubredditSheet() {',
        new_browse,
        'browse menu')

new_open = '''    private void showOpenSubredditSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Find / open subreddit · NSFW posts only");
        scroll.addView(body);

        AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setSingleLine(true);
        input.setHint("Start typing a subreddit name");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        input.setThreshold(0);
        body.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        installCommunityAutocomplete(input);

        Button open = sheetButton("Open subreddit");
        body.addView(open, sectionButtonParams());
        open.setOnClickListener(v -> {
            String value = cleanSubredditName(input.getText().toString());
            if (value.isEmpty()) return;
            dialog.dismiss();
            openSubredditFeed(value);
        });

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
    }'''
main = replace_between(main,
        '    private void showOpenSubredditSheet() {',
        '    private void showSearchSubmenu() {',
        new_open,
        'open subreddit autocomplete')

# Remember filter state with presets so a preset can behave as a real saved feed.
main = main.replace(
    '''                open.setOnClickListener(v -> {
                    dialog.dismiss();
                    openMultiSubredditFeed(name, communities);
                });''',
    '''                open.setOnClickListener(v -> {
                    dialog.dismiss();
                    applyPresetFilterSnapshot(name);
                    openMultiSubredditFeed(name, communities);
                });''')
main = main.replace(
    '''                    subredditPresets.remove(name);
                    saveSubredditPresets();''',
    '''                    subredditPresets.remove(name);
                    removePresetFilterSnapshot(name);
                    saveSubredditPresets();''')
main = main.replace(
    '''            subredditPresets.put(presetName, new ArrayList<>(selected));
            saveSubredditPresets();
            dialog.dismiss();''',
    '''            subredditPresets.put(presetName, new ArrayList<>(selected));
            saveSubredditPresets();
            savePresetFilterSnapshot(presetName);
            dialog.dismiss();''')

# Expanded filter/control menu.
old_display = '''    private void showDisplaySubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Display & filters");
        Button sortMenu = sheetButton("Sort…");
        Button mediaMenu = sheetButton("Media filter…");
        Button layoutMenu = sheetButton("Layout…");
        body.addView(sortMenu, sectionButtonParams());
        body.addView(mediaMenu, sectionButtonParams());
        body.addView(layoutMenu, sectionButtonParams());
        sortMenu.setOnClickListener(v -> { dialog.dismiss(); showSortSheet(); });
        mediaMenu.setOnClickListener(v -> { dialog.dismiss(); showMediaSheet(); });
        layoutMenu.setOnClickListener(v -> { dialog.dismiss(); showLayoutSheet(); });
        dialog.setContentView(body);
        dialog.show();
    }'''
new_display = '''    private void showDisplaySubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Display & filters");
        scroll.addView(body);

        Button sortMenu = sheetButton("Sort…");
        Button mediaMenu = sheetButton("Media filter…");
        Button peopleMenu = sheetButton("People · " + ContentTaxonomy.label(peopleFilterMask));
        Button sourceMenu = sheetButton("Sources · " + sourceFilterLabel());
        Button viewedMenu = sheetButton("Viewed posts · " + (showViewedPosts ? "All" : "Unread only"));
        Button joinedMenu = sheetButton("Subreddits · " + (joinedOnlyFilter ? "Joined only" : "Any"));
        Button videoMenu = sheetButton("Video length / quality…");
        Button advancedMenu = sheetButton("Include / exclude / blocked creators…");
        Button layoutMenu = sheetButton("Layout…");
        Button cacheMenu = sheetButton("Media cache…");
        Button diagnosticsMenu = sheetButton("Feed diagnostics…");

        body.addView(sortMenu, sectionButtonParams());
        body.addView(mediaMenu, sectionButtonParams());
        body.addView(peopleMenu, sectionButtonParams());
        body.addView(sourceMenu, sectionButtonParams());
        body.addView(viewedMenu, sectionButtonParams());
        body.addView(joinedMenu, sectionButtonParams());
        body.addView(videoMenu, sectionButtonParams());
        body.addView(advancedMenu, sectionButtonParams());
        body.addView(layoutMenu, sectionButtonParams());
        body.addView(cacheMenu, sectionButtonParams());
        body.addView(diagnosticsMenu, sectionButtonParams());

        sortMenu.setOnClickListener(v -> { dialog.dismiss(); showSortSheet(); });
        mediaMenu.setOnClickListener(v -> { dialog.dismiss(); showMediaSheet(); });
        peopleMenu.setOnClickListener(v -> { dialog.dismiss(); showPeopleFilterSheet(); });
        sourceMenu.setOnClickListener(v -> { dialog.dismiss(); showSourceFilterSheet(); });
        viewedMenu.setOnClickListener(v -> {
            showViewedPosts = !showViewedPosts;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        joinedMenu.setOnClickListener(v -> {
            joinedOnlyFilter = !joinedOnlyFilter;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        videoMenu.setOnClickListener(v -> { dialog.dismiss(); showVideoFilterSheet(); });
        advancedMenu.setOnClickListener(v -> { dialog.dismiss(); showAdvancedDiscoveryFilterSheet(); });
        layoutMenu.setOnClickListener(v -> { dialog.dismiss(); showLayoutSheet(); });
        cacheMenu.setOnClickListener(v -> { dialog.dismiss(); showCacheControlSheet(); });
        diagnosticsMenu.setOnClickListener(v -> { dialog.dismiss(); showFeedDiagnosticsSheet(); });
        dialog.setContentView(scroll);
        dialog.show();
    }'''
main = replace_once(main, old_display, new_display, 'expanded display menu')

# Insert v3.9 helpers before the display menu.
helper_marker = '    private void showDisplaySubmenu() {'
helpers = r'''    private void loadDiscoverySets() {
        favoriteSubreddits.clear();
        includedSubreddits.clear();
        excludedSubreddits.clear();
        blockedCreators.clear();
        copyCleanSubreddits(prefs.getStringSet("favoriteSubredditsV1", null), favoriteSubreddits);
        copyCleanSubreddits(prefs.getStringSet("includedSubredditsV1", null), includedSubreddits);
        copyCleanSubreddits(prefs.getStringSet("excludedSubredditsV1", null), excludedSubreddits);
        Set<String> blocked = prefs.getStringSet("blockedCreatorsV1", null);
        if (blocked != null) {
            for (String value : blocked) {
                String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
                if (!clean.isEmpty()) blockedCreators.add(clean);
            }
        }
    }

    private void copyCleanSubreddits(Set<String> source, Set<String> target) {
        if (source == null) return;
        for (String value : source) {
            String clean = cleanSubredditName(value);
            if (!clean.isEmpty()) target.add(clean.toLowerCase(Locale.US));
        }
    }

    private void persistDiscoveryFilterPrefs() {
        prefs.edit()
                .putInt("peopleFilterMaskV1", peopleFilterMask)
                .putInt("sourceFilterMaskV1", sourceFilterMask)
                .putBoolean("showViewedPostsV1", showViewedPosts)
                .putBoolean("joinedOnlyFilterV1", joinedOnlyFilter)
                .putInt("minimumVideoHeightV1", minimumVideoHeight)
                .putInt("videoLengthModeV1", videoLengthMode)
                .putStringSet("favoriteSubredditsV1", new HashSet<>(favoriteSubreddits))
                .putStringSet("includedSubredditsV1", new HashSet<>(includedSubreddits))
                .putStringSet("excludedSubredditsV1", new HashSet<>(excludedSubreddits))
                .putStringSet("blockedCreatorsV1", new HashSet<>(blockedCreators))
                .apply();
    }

    private boolean isReadHiddenForDiscovery(RedditPost post) {
        return !showViewedPosts
                && post != null
                && post.id != null
                && !post.id.isEmpty()
                && hiddenPosts.containsKey(post.id);
    }

    private boolean passesDiscoveryFilters(RedditPost post) {
        if (post == null) return false;
        String community = cleanSubredditName(post.subreddit).toLowerCase(Locale.US);
        String author = post.author == null ? "" : post.author.trim().toLowerCase(Locale.US);

        if (!includedSubreddits.isEmpty() && !includedSubreddits.contains(community)) return false;
        if (excludedSubreddits.contains(community)) return false;
        if (!author.isEmpty() && blockedCreators.contains(author)) return false;
        if (joinedOnlyFilter && !subscriptionNames.isEmpty()
                && !subscriptionNames.contains(community)) return false;

        int sourceBit = 1;
        String origin = post.sourceOrigin == null ? "reddit" : post.sourceOrigin.toLowerCase(Locale.US);
        if (origin.equals("scrolller")) sourceBit = 2;
        else if (origin.equals("archive")) sourceBit = 4;
        if (sourceFilterMask != 0 && (sourceFilterMask & sourceBit) == 0) return false;

        boolean video = post.mediaKind == RedditPost.MediaKind.VIDEO
                || post.mediaKind == RedditPost.MediaKind.GIF;
        if (video && minimumVideoHeight > 0 && post.mediaHeight > 0
                && post.mediaHeight < minimumVideoHeight) return false;
        if (video && videoLengthMode != 0 && post.durationSeconds > 0) {
            if (videoLengthMode == 1 && post.durationSeconds > 30) return false;
            if (videoLengthMode == 2
                    && (post.durationSeconds <= 30 || post.durationSeconds > 120)) return false;
            if (videoLengthMode == 3 && post.durationSeconds <= 120) return false;
        }
        return true;
    }

    private void toggleFavoriteSubreddit(String value) {
        String clean = cleanSubredditName(value);
        if (clean.isEmpty()) return;
        String key = clean.toLowerCase(Locale.US);
        boolean added;
        if (favoriteSubreddits.contains(key)) {
            favoriteSubreddits.remove(key);
            added = false;
        } else {
            favoriteSubreddits.add(key);
            added = true;
        }
        persistDiscoveryFilterPrefs();
        setStatus((added ? "Favorited r/" : "Removed favorite r/") + clean, false);
    }

    private void addCommunitySection(LinearLayout body, String title, Set<String> communities, boolean favorites) {
        TextView heading = sectionTitle(title + " · " + communities.size());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(14);
        body.addView(heading, hp);
        if (communities.isEmpty()) {
            body.addView(bodyText(favorites ? "No favorite subreddits yet." : "No subscriptions loaded yet."));
            return;
        }
        ArrayList<String> sorted = new ArrayList<>(communities);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : sorted) {
            String clean = cleanSubredditName(name);
            if (clean.isEmpty()) continue;
            Button button = sheetButton((favorites ? "★ " : "r/") + (favorites ? "r/" : "") + clean);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> openSubredditFeed(clean));
        }
    }

    private ArrayList<String> communityCandidates() {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String name : favoriteSubreddits) {
            String clean = cleanSubredditName(name);
            if (!clean.isEmpty()) unique.putIfAbsent(clean.toLowerCase(Locale.US), clean);
        }
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) unique.putIfAbsent(clean.toLowerCase(Locale.US), clean);
        }
        for (String name : knownNsfwSubreddits()) {
            String clean = cleanSubredditName(name);
            if (!clean.isEmpty()) unique.putIfAbsent(clean.toLowerCase(Locale.US), clean);
        }
        ArrayList<String> out = new ArrayList<>(unique.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void installCommunityAutocomplete(AutoCompleteTextView input) {
        final ArrayList<String> all = communityCandidates();
        final Runnable refresh = () -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            String cleanQuery = cleanSubredditName(raw);
            ArrayList<String> matches = new ArrayList<>(all);
            if (!cleanQuery.isEmpty()) {
                matches.sort((a, b) -> Integer.compare(
                        FuzzySearch.score(cleanQuery, b), FuzzySearch.score(cleanQuery, a)));
                matches.removeIf(name -> FuzzySearch.score(cleanQuery, name)
                        < FuzzySearch.thresholdFor(FuzzySearch.normalize(cleanQuery).length()));
            }
            if (matches.size() > 30) matches.subList(30, matches.size()).clear();
            ArrayList<String> labels = new ArrayList<>();
            for (String name : matches) labels.add("r/" + name);
            input.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, labels));
            if (input.hasFocus() && !labels.isEmpty()) input.showDropDown();
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        });
        input.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) refresh.run(); });
        input.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            String clean = cleanSubredditName(item == null ? "" : item.toString());
            if (!clean.isEmpty()) input.setText(clean, false);
        });
        refresh.run();
    }

    private CheckBox filterCheckBox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(Color.WHITE);
        box.setChecked(checked);
        box.setPadding(dp(8), dp(3), dp(8), dp(3));
        return box;
    }

    private void showPeopleFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("People / content filter");
        body.addView(bodyText("Multi-select. Categories come only from explicit subreddit/title/flair metadata; the app never guesses identity from an image. No selection shows everything."));
        CheckBox straight = filterCheckBox("Straight", (peopleFilterMask & ContentTaxonomy.STRAIGHT) != 0);
        CheckBox gayLesbian = filterCheckBox("Gay / Lesbian", (peopleFilterMask & ContentTaxonomy.GAY_LESBIAN) != 0);
        CheckBox trans = filterCheckBox("Trans", (peopleFilterMask & ContentTaxonomy.TRANS) != 0);
        body.addView(straight);
        body.addView(gayLesbian);
        body.addView(trans);
        Button apply = sheetButton("Apply people filter");
        body.addView(apply, sectionButtonParams());
        apply.setOnClickListener(v -> {
            int mask = 0;
            if (straight.isChecked()) mask |= ContentTaxonomy.STRAIGHT;
            if (gayLesbian.isChecked()) mask |= ContentTaxonomy.GAY_LESBIAN;
            if (trans.isChecked()) mask |= ContentTaxonomy.TRANS;
            peopleFilterMask = mask;
            blockLgbtTopics = false;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private String sourceFilterLabel() {
        if (sourceFilterMask == 0 || sourceFilterMask == 7) return "All";
        ArrayList<String> labels = new ArrayList<>();
        if ((sourceFilterMask & 1) != 0) labels.add("Reddit");
        if ((sourceFilterMask & 2) != 0) labels.add("Scrolller");
        if ((sourceFilterMask & 4) != 0) labels.add("Archive");
        return String.join(" + ", labels);
    }

    private void showSourceFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Content sources");
        int current = sourceFilterMask == 0 ? 7 : sourceFilterMask;
        CheckBox reddit = filterCheckBox("Reddit", (current & 1) != 0);
        CheckBox scrolller = filterCheckBox("Scrolller", (current & 2) != 0);
        CheckBox archive = filterCheckBox("Archive / historical", (current & 4) != 0);
        body.addView(reddit);
        body.addView(scrolller);
        body.addView(archive);
        Button apply = sheetButton("Apply source filter");
        body.addView(apply, sectionButtonParams());
        apply.setOnClickListener(v -> {
            int mask = 0;
            if (reddit.isChecked()) mask |= 1;
            if (scrolller.isChecked()) mask |= 2;
            if (archive.isChecked()) mask |= 4;
            sourceFilterMask = (mask == 0 || mask == 7) ? 0 : mask;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showVideoFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Video length / quality");
        body.addView(bodyText("Filters use metadata when a source supplies it. Unknown duration/resolution is allowed rather than silently throwing a post away."));

        Button quality = sheetButton("Minimum resolution · "
                + (minimumVideoHeight <= 0 ? "Any" : minimumVideoHeight + "p+"));
        Button length = sheetButton("Length · " + videoLengthLabel());
        body.addView(quality, sectionButtonParams());
        body.addView(length, sectionButtonParams());
        quality.setOnClickListener(v -> {
            minimumVideoHeight = minimumVideoHeight <= 0 ? 720
                    : minimumVideoHeight == 720 ? 1080 : 0;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        length.setOnClickListener(v -> {
            videoLengthMode = (videoLengthMode + 1) % 4;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private String videoLengthLabel() {
        if (videoLengthMode == 1) return "Short · ≤30 sec";
        if (videoLengthMode == 2) return "Medium · 31–120 sec";
        if (videoLengthMode == 3) return "Long · >120 sec";
        return "Any";
    }

    private void showAdvancedDiscoveryFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Advanced feed filters");
        scroll.addView(body);
        body.addView(bodyText("Comma-separated names. Include is optional; when it is non-empty, only those subreddits are allowed."));

        EditText include = new EditText(this);
        include.setHint("Included subreddits (optional)");
        include.setText(String.join(", ", includedSubreddits));
        include.setTextColor(Color.WHITE);
        include.setHintTextColor(0xFF888888);
        body.addView(include, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        EditText exclude = new EditText(this);
        exclude.setHint("Excluded subreddits");
        exclude.setText(String.join(", ", excludedSubreddits));
        exclude.setTextColor(Color.WHITE);
        exclude.setHintTextColor(0xFF888888);
        body.addView(exclude, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        EditText creators = new EditText(this);
        creators.setHint("Blocked creators / usernames");
        creators.setText(String.join(", ", blockedCreators));
        creators.setTextColor(Color.WHITE);
        creators.setHintTextColor(0xFF888888);
        body.addView(creators, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        RedditPost current = currentPagerPost();
        if (current != null && current.author != null && !current.author.trim().isEmpty()) {
            String author = current.author.trim();
            Button blockCurrent = sheetButton("Block current creator · u/" + author);
            body.addView(blockCurrent, sectionButtonParams());
            blockCurrent.setOnClickListener(v -> {
                blockedCreators.add(author.toLowerCase(Locale.US));
                persistDiscoveryFilterPrefs();
                dialog.dismiss();
                reloadCurrent();
            });
        }

        Button save = sheetButton("Save advanced filters");
        body.addView(save, sectionButtonParams());
        save.setOnClickListener(v -> {
            includedSubreddits.clear();
            excludedSubreddits.clear();
            blockedCreators.clear();
            parseSubredditList(include.getText().toString(), includedSubreddits);
            parseSubredditList(exclude.getText().toString(), excludedSubreddits);
            for (String token : creators.getText().toString().split("[,\\s]+")) {
                String clean = token.trim().toLowerCase(Locale.US);
                if (clean.startsWith("u/")) clean = clean.substring(2);
                if (!clean.isEmpty()) blockedCreators.add(clean);
            }
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void parseSubredditList(String raw, Set<String> target) {
        if (raw == null) return;
        for (String token : raw.split("[,\\s]+")) {
            String clean = cleanSubredditName(token);
            if (!clean.isEmpty()) target.add(clean.toLowerCase(Locale.US));
        }
    }

    private RedditPost currentPagerPost() {
        if (postAdapter == null || pager == null || postAdapter.getItemCount() <= 0) return null;
        return postAdapter.getPost(Math.max(0, Math.min(pager.getCurrentItem(), postAdapter.getItemCount() - 1)));
    }

    private void showCacheControlSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Media cache");
        body.addView(bodyText("Shared video cache limit: 384 MB. Clearing it does not erase read/hidden history, presets, favorites, or account state."));
        Button cancel = sheetButton("Cancel pending preloads");
        Button clear = sheetButton("Clear media cache");
        body.addView(cancel, sectionButtonParams());
        body.addView(clear, sectionButtonParams());
        cancel.setOnClickListener(v -> {
            HighQualityPlayerFactory.cancelPendingPreloads();
            dialog.dismiss();
            setStatus("Pending media preloads cancelled.", false);
        });
        clear.setOnClickListener(v -> {
            HighQualityPlayerFactory.cancelPendingPreloads();
            dialog.dismiss();
            setStatus("Clearing media cache…", true);
            HighQualityPlayerFactory.clearCacheAsync(this,
                    () -> setStatus("Media cache cleared.", false));
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showFeedDiagnosticsSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Feed diagnostics");
        scroll.addView(body);
        String report = buildFeedDiagnostics();
        TextView text = bodyText(report);
        text.setTextIsSelectable(true);
        body.addView(text);
        Button copy = sheetButton("Copy diagnostics");
        body.addView(copy, sectionButtonParams());
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Reddit Media diagnostics", report));
            setStatus("Feed diagnostics copied.", false);
        });
        dialog.setContentView(scroll);
        dialog.show();
    }

    private String buildFeedDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("Context: ").append(context).append('\n');
        if (subreddit != null && !subreddit.isEmpty()) out.append("Subreddit: r/").append(subreddit).append('\n');
        out.append("Visible posts: ").append(postAdapter == null ? 0 : postAdapter.getItemCount()).append('\n');
        out.append("Read/hidden IDs: ").append(hiddenPosts.size()).append('\n');
        out.append("People: ").append(ContentTaxonomy.label(peopleFilterMask)).append('\n');
        out.append("Sources: ").append(sourceFilterLabel()).append('\n');
        out.append("Viewed: ").append(showViewedPosts ? "All" : "Unread only").append('\n');
        out.append("Subreddit scope: ").append(joinedOnlyFilter ? "Joined only" : "Any").append('\n');
        out.append("Include/exclude/blocked creators: ")
                .append(includedSubreddits.size()).append('/')
                .append(excludedSubreddits.size()).append('/')
                .append(blockedCreators.size()).append('\n');
        out.append("Video: ").append(minimumVideoHeight <= 0 ? "any resolution" : minimumVideoHeight + "p+")
                .append(" · ").append(videoLengthLabel()).append('\n');
        RedditPost post = currentPagerPost();
        if (post != null) {
            out.append("\nCurrent post\n");
            out.append("ID: ").append(post.id).append('\n');
            out.append("r/").append(post.subreddit).append(" · u/").append(post.author).append('\n');
            out.append("Origin: ").append(post.sourceOrigin).append('\n');
            out.append("Media: ").append(post.mediaKind).append(" · ")
                    .append(post.mediaWidth).append('x').append(post.mediaHeight).append('\n');
            out.append("Duration: ").append(post.durationSeconds > 0 ? post.durationSeconds + " sec" : "unknown").append('\n');
            out.append("People tags: ").append(ContentTaxonomy.label(ContentTaxonomy.classify(post))).append('\n');
            out.append("Saved: ").append(post.saved || savedPostIds.contains(post.id)).append('\n');
            out.append("Read/hidden: ").append(hiddenPosts.containsKey(post.id)).append('\n');
        }
        return out.toString();
    }

    private void loadPresetFilterSnapshots() {
        presetFilterSnapshots.clear();
        String raw = prefs.getString("subredditPresetFilterSnapshotsV1", "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray names = root.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i, "");
                JSONObject value = root.optJSONObject(name);
                if (!name.isEmpty() && value != null) presetFilterSnapshots.put(name, value.toString());
            }
        } catch (Exception ignored) {}
    }

    private void persistPresetFilterSnapshots() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, String> entry : presetFilterSnapshots.entrySet()) {
                root.put(entry.getKey(), new JSONObject(entry.getValue()));
            }
            prefs.edit().putString("subredditPresetFilterSnapshotsV1", root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void savePresetFilterSnapshot(String name) {
        if (name == null || name.isEmpty()) return;
        try {
            JSONObject value = new JSONObject();
            value.put("people", peopleFilterMask);
            value.put("sources", sourceFilterMask);
            value.put("viewed", showViewedPosts);
            value.put("joined", joinedOnlyFilter);
            value.put("height", minimumVideoHeight);
            value.put("length", videoLengthMode);
            presetFilterSnapshots.put(name, value.toString());
            persistPresetFilterSnapshots();
        } catch (Exception ignored) {}
    }

    private void applyPresetFilterSnapshot(String name) {
        String raw = presetFilterSnapshots.get(name);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject value = new JSONObject(raw);
            peopleFilterMask = value.optInt("people", peopleFilterMask);
            sourceFilterMask = value.optInt("sources", sourceFilterMask);
            showViewedPosts = value.optBoolean("viewed", showViewedPosts);
            joinedOnlyFilter = value.optBoolean("joined", joinedOnlyFilter);
            minimumVideoHeight = value.optInt("height", minimumVideoHeight);
            videoLengthMode = value.optInt("length", videoLengthMode);
            persistDiscoveryFilterPrefs();
        } catch (Exception ignored) {}
    }

    private void removePresetFilterSnapshot(String name) {
        if (name == null) return;
        presetFilterSnapshots.remove(name);
        persistPresetFilterSnapshots();
    }

'''
if 'private void loadDiscoverySets()' not in main:
    at = main.find(helper_marker)
    if at < 0:
        raise SystemExit('helper insertion marker missing')
    main = main[:at] + helpers + main[at:]

main_path.write_text(main)

# Add provenance and duration metadata to posts so source/video filters are real.
post_path = Path("app/src/main/java/com/scrolller/adblock/RedditPost.java")
post = post_path.read_text()
post = replace_once(
    post,
    '    public String searchMetadata = "";\n',
    '    public String searchMetadata = "";\n    public String sourceOrigin = "reddit";\n    public int durationSeconds = 0;\n',
    'post metadata fields')
post = replace_once(
    post,
    '''                + data.optString("subreddit_name_prefixed", "");
        return post;''',
    '''                + data.optString("subreddit_name_prefixed", "");
        post.sourceOrigin = "reddit";
        post.durationSeconds = mediaDurationSeconds(data);
        return post;''',
    'reddit post provenance')
old_scrolller = '''        return new RedditPost(
                "t3_" + redditId,
                decode(item.optString("title", "")),
                item.optString("username", ""),
                item.optString("subredditTitle", ""),
                redditPath,
                sourceUrl,
                0,
                item.optInt("commentsCount", 0),
                0L,
                false,
                item.optBoolean("isNsfw", false),
                kind,
                album,
                video,
                poster,
                width,
                height);'''
new_scrolller = '''        RedditPost post = new RedditPost(
                "t3_" + redditId,
                decode(item.optString("title", "")),
                item.optString("username", ""),
                item.optString("subredditTitle", ""),
                redditPath,
                sourceUrl,
                0,
                item.optInt("commentsCount", 0),
                0L,
                false,
                item.optBoolean("isNsfw", false),
                kind,
                album,
                video,
                poster,
                width,
                height);
        post.sourceOrigin = "scrolller";
        post.durationSeconds = Math.max(0,
                item.optInt("durationSeconds", item.optInt("duration", 0)));
        post.searchMetadata = decode(item.optString("description", "")) + " "
                + decode(item.optString("tags", ""));
        return post;'''
post = replace_once(post, old_scrolller, new_scrolller, 'scrolller provenance')
media_duration = r'''    private static int mediaDurationSeconds(JSONObject d) {
        if (d == null) return 0;
        JSONObject secure = d.optJSONObject("secure_media");
        JSONObject media = d.optJSONObject("media");
        JSONObject video = secure != null ? secure.optJSONObject("reddit_video") : null;
        if (video == null && media != null) video = media.optJSONObject("reddit_video");
        JSONObject preview = d.optJSONObject("preview");
        if (video == null && preview != null) video = preview.optJSONObject("reddit_video_preview");
        if (video == null) return 0;
        return Math.max(0, video.optInt("duration", 0));
    }

'''
if 'private static int mediaDurationSeconds' not in post:
    marker = '    private static JSONObject bestScrolllerSource(JSONArray sources) {'
    at = post.find(marker)
    if at < 0:
        raise SystemExit('duration insertion marker missing')
    post = post[:at] + media_duration + post[at:]
post_path.write_text(post)

# Add a safe cache clear action.
player_path = Path("app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java")
player = player_path.read_text()
cache_clear = r'''    static void clearCacheAsync(Context context, Runnable done) {
        cancelPendingPreloads();
        Context app = context.getApplicationContext();
        PRELOAD_EXECUTOR.execute(() -> {
            try {
                SimpleCache current = cache(app);
                for (String key : new java.util.HashSet<>(current.getKeys())) {
                    try { current.removeResource(key); } catch (Exception ignored) {}
                }
            } finally {
                if (done != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
                }
            }
        });
    }

'''
if 'static void clearCacheAsync' not in player:
    marker = '    private static CacheDataSource.Factory cachedDataSourceFactory'
    at = player.find(marker)
    if at < 0:
        raise SystemExit('cache helper insertion marker missing')
    player = player[:at] + cache_clear + player[at:]
player_path.write_text(player)

build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, 'versionCode 46', 'versionCode 47', 'versionCode')
build = replace_once(build, 'versionName "3.8.12"', 'versionName "3.9.0"', 'versionName')
build_path.write_text(build)

print("Applied Reddit Media v3.9.0 discovery, browse, favorites, filters and diagnostics migration")
