#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/scrolller/adblock/MainActivity.java"
PAGER = ROOT / "app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java"


def matching_brace(text: str, opening: int) -> int:
    depth = 0
    i = opening
    string = None
    line_comment = False
    block_comment = False
    escape = False
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ""
        if line_comment:
            if c == "\n": line_comment = False
            i += 1; continue
        if block_comment:
            if c == "*" and n == "/": block_comment = False; i += 2; continue
            i += 1; continue
        if string:
            if escape: escape = False
            elif c == "\\": escape = True
            elif c == string: string = None
            i += 1; continue
        if c == "/" and n == "/": line_comment = True; i += 2; continue
        if c == "/" and n == "*": block_comment = True; i += 2; continue
        if c in ('"', "'"): string = c; i += 1; continue
        if c == "{": depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0: return i
        i += 1
    raise RuntimeError("unmatched brace")


def method_span(text: str, name: str):
    pat = re.compile(r"(?m)^[ \t]*(?:private|public|protected)\s+[^\n;{]*\b" + re.escape(name) + r"\s*\(")
    matches = list(pat.finditer(text))
    if len(matches) != 1:
        raise RuntimeError(f"expected exactly one method {name}, found {len(matches)}")
    start = matches[0].start()
    opening = text.find("{", matches[0].end())
    if opening < 0: raise RuntimeError(f"no opening brace for {name}")
    close = matching_brace(text, opening)
    return start, opening, close


def replace_method(text: str, name: str, replacement: str) -> str:
    start, _, close = method_span(text, name)
    return text[:start] + replacement.strip() + "\n\n" + text[close + 1:]


def inject_method_start(text: str, name: str, code: str) -> str:
    _, opening, _ = method_span(text, name)
    return text[:opening + 1] + "\n" + code.rstrip() + "\n" + text[opening + 1:]


def inject_before_method_end(text: str, name: str, code: str) -> str:
    _, _, close = method_span(text, name)
    return text[:close] + "\n" + code.rstrip() + "\n" + text[close:]


def append_class_methods(text: str, methods: str) -> str:
    pos = len(text.rstrip()) - 1
    if text.rstrip()[pos] != "}": raise RuntimeError("class does not end in brace")
    trimmed = text.rstrip()
    return trimmed[:-1] + "\n\n" + methods.strip() + "\n}\n"


main = MAIN.read_text()
pager = PAGER.read_text()
if "showCompactMainMenu" in main:
    print("v3.8.1 feature migration already applied")
    raise SystemExit(0)

# Imports and fields.
main = main.replace("import android.os.Bundle;", "import android.os.Bundle;\nimport android.os.SystemClock;")
main = main.replace("import android.widget.Button;", "import android.widget.Button;\nimport android.widget.CheckBox;")
main = main.replace("import java.util.LinkedHashMap;", "import java.util.LinkedHashMap;\nimport java.util.LinkedHashSet;")
main = main.replace(
    "    private Button browserBack;\n",
    "    private Button browserBack;\n"
    "    private Button compactMenuButton;\n"
    "    private final LinkedHashMap<String, ArrayList<String>> subredditPresets = new LinkedHashMap<>();\n"
    "    private long fullscreenVisitStartedAtMs = 0L;\n")

# NSFW-only taxonomy. No SFW discovery tree remains.
cat_start = main.index("    private static final String[][] CURATED_CATEGORY_ROWS = {")
cat_end = main.index("    private static final String[] QUALITY_SEED_SUBREDDITS", cat_start)
main = main[:cat_start] + '''    private static final String[][] CURATED_CATEGORY_ROWS = {
            {"NSFW", "Adult", "General", "NSFW,gonewild,RealGirls"},
            {"NSFW", "Adult", "GIF & video", "nsfw_gif,NSFW_GIF"},
            {"NSFW", "Adult", "Couples", "couplesgonewild"},
            {"NSFW", "Adult", "Cosplay", "cosplaygirls"},
            {"NSFW", "Adult", "Lingerie", "lingerie"},
            {"NSFW", "Adult", "Curvy", "gonewildcurvy,curvy"},
            {"NSFW", "Adult", "Petite", "petitegonewild"},
            {"NSFW", "Adult", "Artistic", "NSFWart,ArtisticNSFW"}
    };
''' + main[cat_end:]

seed_start = main.index("    private static final String[] QUALITY_SEED_SUBREDDITS = {")
seed_end = main.index("    private static final String[] QUALITY_TIME_WINDOWS", seed_start)
main = main[:seed_start] + '''    private static final String[] QUALITY_SEED_SUBREDDITS = {
            "NSFW", "gonewild", "RealGirls", "nsfw_gif", "couplesgonewild",
            "cosplaygirls", "lingerie", "gonewildcurvy", "petitegonewild", "NSFWart"
    };
''' + main[seed_end:]

# Load presets once SharedPreferences exists.
main = main.replace(
    '        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);',
    '        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);\n        loadSubredditPresets();')

# More offscreen pages + no RecyclerView item animations for smoother vertical handoff.
main = main.replace("        pager.setOffscreenPageLimit(1);", "        pager.setOffscreenPageLimit(3);")
main = main.replace(
    "        pager.setOffscreenPageLimit(3);",
    "        pager.setOffscreenPageLimit(3);\n"
    "        if (pager.getChildCount() > 0 && pager.getChildAt(0) instanceof RecyclerView) {\n"
    "            RecyclerView pagerRecycler = (RecyclerView) pager.getChildAt(0);\n"
    "            pagerRecycler.setItemAnimator(null);\n"
    "            pagerRecycler.setItemViewCacheSize(8);\n"
    "        }")
main = inject_before_method_end(main, "buildNativeUi", "        installCompactNavigation();")

# Hard NSFW display invariant: replacement and incremental appends both filter SFW.
main = inject_method_start(main, "replacePosts", '''        if (items != null) {
            ArrayList<RedditPost> nsfwOnly = new ArrayList<>();
            for (RedditPost candidate : items) {
                if (candidate != null && candidate.nsfw) nsfwOnly.add(candidate);
            }
            items = nsfwOnly;
        }''')
main = inject_method_start(main, "appendUniqueNow", '''        if (incoming != null) {
            ArrayList<RedditPost> nsfwOnly = new ArrayList<>();
            for (RedditPost candidate : incoming) {
                if (candidate != null && candidate.nsfw) nsfwOnly.add(candidate);
            }
            incoming = nsfwOnly;
        }''')
main = inject_method_start(main, "isContentBlocked", "        if (post != null && !post.nsfw) return true;")

# Collections are retired. Old entry points redirect to the new preset workflow.
main = replace_method(main, "showQualityBrowseSheet", '''    private void showQualityBrowseSheet() {
        showPresetSubmenu();
    }''')
main = replace_method(main, "loadQualityCollection", '''    private void loadQualityCollection(boolean reset) {
        context = "home";
        sort = "random";
        loadFeed(reset);
    }''')

# Category UI is adult-only.
main = replace_method(main, "showCategoryRoot", '''    private void showCategoryRoot() {
        showCategorySafety("NSFW");
    }''')

# Multi-subreddit feeds use Reddit's /r/a+b+c listing syntax.
main = inject_method_start(main, "listingPath", '''        if (context.equals("multi")) return multiListingPath(cursor);''')

# Fuzzy/local search scoring.
main = replace_method(main, "matchesLocalSearch", '''    private boolean matchesLocalSearch(RedditPost post, String value) {
        if (post == null || !post.nsfw) return false;
        String normalized = FuzzySearch.normalize(value);
        if (normalized.isEmpty()) return true;
        return fuzzyPostSearchScore(post, value) >= FuzzySearch.thresholdFor(normalized.length());
    }''')
main = replace_method(main, "localSearchRelevance", '''    private int localSearchRelevance(RedditPost post, String value) {
        return fuzzyPostSearchScore(post, value);
    }''')
main = inject_before_method_end(main, "finishSearchCollection", "        startSubredditNameLeadIns(generation);")

# Read/hide uses deliberate viewing rather than a brittle media-ready-only gate.
main = replace_method(main, "setFullscreenReadBaseline", '''    private void setFullscreenReadBaseline(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) {
            lastFullscreenPostId = "";
            fullscreenVisitStartedAtMs = 0L;
            return;
        }
        lastFullscreenPostId = current.id;
        fullscreenVisitStartedAtMs = SystemClock.elapsedRealtime();
    }''')
main = replace_method(main, "trackFullscreenVisit", '''    private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;
        long now = SystemClock.elapsedRealtime();

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            fullscreenVisitStartedAtMs = now;
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
        long dwellMs = fullscreenVisitStartedAtMs > 0L ? now - fullscreenVisitStartedAtMs : 0L;
        boolean actuallyViewed = previous != null && (
                mediaReadyPostIds.contains(previous.id)
                        || mediaFailedPostIds.contains(previous.id)
                        || dwellMs >= 350L);
        if (actuallyViewed
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            saveReadHideState();
        }
        lastFullscreenPostId = currentId;
        fullscreenVisitStartedAtMs = now;
    }''')

# The global navigation bars are hidden. Tapping media can still expose contextual post actions.
main = replace_method(main, "setFullscreenChrome", '''    private void setFullscreenChrome(boolean visible) {
        fullscreenChromeVisible = visible;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (compactMenuButton != null) compactMenuButton.setVisibility(View.VISIBLE);
        if (postAdapter != null) postAdapter.setChromeVisible(visible);
    }''')
main = inject_before_method_end(main, "updateChrome", '''        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (compactMenuButton != null) compactMenuButton.setVisibility(View.VISIBLE);''')
main = inject_before_method_end(main, "applySystemInsets", '''        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (gridView != null) gridView.setPadding(0, systemTopPx + dp(8), 0, systemBottomPx + dp(8));
        if (compactMenuButton != null && compactMenuButton.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams menuParams = (FrameLayout.LayoutParams) compactMenuButton.getLayoutParams();
            menuParams.bottomMargin = systemBottomPx + dp(14);
            compactMenuButton.setLayoutParams(menuParams);
        }''')

extra_methods = r'''
    private void installCompactNavigation() {
        if (compactMenuButton != null) return;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        compactMenuButton = new Button(this);
        compactMenuButton.setText("☰");
        compactMenuButton.setTextColor(Color.WHITE);
        compactMenuButton.setTextSize(20);
        compactMenuButton.setMinWidth(0);
        compactMenuButton.setMinHeight(0);
        compactMenuButton.setPadding(0, 0, 0, 0);
        compactMenuButton.setBackground(rounded(0xCC111111, 999));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM);
        p.rightMargin = dp(12);
        p.bottomMargin = systemBottomPx + dp(14);
        appLayer.addView(compactMenuButton, p);
        compactMenuButton.setOnClickListener(v -> showCompactMainMenu());
    }

    private void showCompactMainMenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Menu · NSFW only");
        scroll.addView(body);

        String current = context.equals("subreddit") ? "r/" + subreddit
                : context.equals("multi") ? "Multi-subreddit preset"
                : screen == Screen.SEARCH ? "Search"
                : screen == Screen.FAVORITES ? "Library"
                : "Home";
        TextView where = new TextView(this);
        where.setText(current);
        where.setTextColor(0xFFBDBDBD);
        where.setTextSize(12);
        where.setPadding(dp(14), 0, dp(14), dp(10));
        body.addView(where);

        Button browse = sheetButton("Browse  ›");
        Button search = sheetButton("Search  ›");
        Button presets = sheetButton("Multi-subreddit presets  ›");
        Button library = sheetButton("Saved & hidden  ›");
        Button display = sheetButton("Display & filters  ›");
        Button account = sheetButton("Account  ›");
        body.addView(browse, sectionButtonParams());
        body.addView(search, sectionButtonParams());
        body.addView(presets, sectionButtonParams());
        body.addView(library, sectionButtonParams());
        body.addView(display, sectionButtonParams());
        body.addView(account, sectionButtonParams());
        browse.setOnClickListener(v -> { dialog.dismiss(); showBrowseSubmenu(); });
        search.setOnClickListener(v -> { dialog.dismiss(); showSearchSubmenu(); });
        presets.setOnClickListener(v -> { dialog.dismiss(); showPresetSubmenu(); });
        library.setOnClickListener(v -> { dialog.dismiss(); showLibrarySubmenu(); });
        display.setOnClickListener(v -> { dialog.dismiss(); showDisplaySubmenu(); });
        account.setOnClickListener(v -> { dialog.dismiss(); showAccount(); });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showBrowseSubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Browse");
        scroll.addView(body);
        Button home = sheetButton("Home");
        Button subredditButton = sheetButton("Open subreddit…");
        Button categories = sheetButton("NSFW categories…");
        body.addView(home, sectionButtonParams());
        body.addView(subredditButton, sectionButtonParams());
        body.addView(categories, sectionButtonParams());
        home.setOnClickListener(v -> { dialog.dismiss(); navigateHome("home", true); });
        subredditButton.setOnClickListener(v -> { dialog.dismiss(); showOpenSubredditSheet(); });
        categories.setOnClickListener(v -> { dialog.dismiss(); showCategoryRoot(); });
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showOpenSubredditSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Open subreddit · NSFW posts only");
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Subreddit name");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        body.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        Button open = sheetButton("Open");
        body.addView(open, sectionButtonParams());
        open.setOnClickListener(v -> {
            String value = cleanSubredditName(input.getText().toString());
            if (value.isEmpty()) return;
            dialog.dismiss();
            openSubredditFeed(value);
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showSearchSubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Fuzzy search · NSFW only");
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Title, subreddit, creator, flair…");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        body.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        Button global = sheetButton("Search everywhere");
        body.addView(global, sectionButtonParams());
        global.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            dialog.dismiss();
            runMenuSearch(value, "global", "");
        });
        if (context.equals("subreddit") && subreddit != null && !subreddit.isEmpty()) {
            Button current = sheetButton("Search current r/" + subreddit);
            body.addView(current, sectionButtonParams());
            current.setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) return;
                dialog.dismiss();
                runMenuSearch(value, "subreddit", subreddit);
            });
        }
        Button openSub = sheetButton("Find / change subreddit…");
        body.addView(openSub, sectionButtonParams());
        openSub.setOnClickListener(v -> { dialog.dismiss(); showOpenSubredditSheet(); });
        dialog.setContentView(body);
        dialog.show();
    }

    private void runMenuSearch(String value, String scope, String scopeSubreddit) {
        openSearchScreen();
        searchScope = scope;
        searchSubreddit = scopeSubreddit == null ? "" : scopeSubreddit;
        prefs.edit().putString("searchScope", searchScope)
                .putString("searchSubreddit", searchSubreddit).apply();
        searchInput.setText(value);
        beginSearch();
    }

    private void showPresetSubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Multi-subreddit presets");
        scroll.addView(body);
        Button create = sheetButton("＋ New preset");
        body.addView(create, sectionButtonParams());
        create.setOnClickListener(v -> { dialog.dismiss(); showCreatePresetSheet(); });

        if (subredditPresets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No presets yet. Select two or more subreddits and save the group for later.");
            empty.setTextColor(0xFFAAAAAA);
            empty.setPadding(dp(14), dp(18), dp(14), dp(18));
            body.addView(empty);
        } else {
            for (Map.Entry<String, ArrayList<String>> entry : subredditPresets.entrySet()) {
                String name = entry.getKey();
                ArrayList<String> communities = new ArrayList<>(entry.getValue());
                Button open = sheetButton(name + " · " + communities.size() + " subreddits");
                body.addView(open, sectionButtonParams());
                open.setOnClickListener(v -> {
                    dialog.dismiss();
                    openMultiSubredditFeed(name, communities);
                });
                Button remove = sheetButton("Remove preset: " + name);
                remove.setTextColor(0xFFFF9A9A);
                body.addView(remove, sectionButtonParams());
                remove.setOnClickListener(v -> {
                    subredditPresets.remove(name);
                    saveSubredditPresets();
                    dialog.dismiss();
                    showPresetSubmenu();
                });
            }
        }
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCreatePresetSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("New multi-subreddit preset");
        scroll.addView(body);

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Preset name");
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(0xFF888888);
        body.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        TextView count = new TextView(this);
        count.setText("0 selected · minimum 2");
        count.setTextColor(0xFFBDBDBD);
        count.setPadding(dp(14), dp(8), dp(14), dp(8));
        body.addView(count);

        EditText custom = new EditText(this);
        custom.setSingleLine(true);
        custom.setHint("Add subreddit name");
        custom.setTextColor(Color.WHITE);
        custom.setHintTextColor(0xFF888888);
        body.addView(custom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        Button addCustom = sheetButton("Add subreddit");
        body.addView(addCustom, sectionButtonParams());
        LinearLayout customList = new LinearLayout(this);
        customList.setOrientation(LinearLayout.VERTICAL);
        body.addView(customList);
        addCustom.setOnClickListener(v -> {
            String clean = cleanSubredditName(custom.getText().toString());
            if (clean.isEmpty()) return;
            String key = clean.toLowerCase(Locale.US);
            boolean exists = false;
            for (String item : selected) if (item.equalsIgnoreCase(clean)) { exists = true; break; }
            if (!exists) {
                selected.add(clean);
                CheckBox cb = new CheckBox(this);
                cb.setText("r/" + clean);
                cb.setTextColor(Color.WHITE);
                cb.setChecked(true);
                customList.addView(cb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
                cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) selected.add(clean); else selected.removeIf(s -> s.equalsIgnoreCase(clean));
                    count.setText(selected.size() + " selected · minimum 2");
                });
            }
            custom.setText("");
            count.setText(selected.size() + " selected · minimum 2");
        });

        TextView knownTitle = new TextView(this);
        knownTitle.setText("Known / subscribed communities");
        knownTitle.setTextColor(Color.WHITE);
        knownTitle.setTextSize(14);
        knownTitle.setPadding(dp(14), dp(18), dp(14), dp(8));
        body.addView(knownTitle);
        for (String community : knownNsfwSubreddits()) {
            CheckBox cb = new CheckBox(this);
            cb.setText("r/" + community);
            cb.setTextColor(Color.WHITE);
            cb.setChecked(false);
            body.addView(cb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selected.add(community); else selected.remove(community);
                count.setText(selected.size() + " selected · minimum 2");
            });
        }

        Button save = sheetButton("Save preset");
        body.addView(save, sectionButtonParams());
        save.setOnClickListener(v -> {
            String presetName = name.getText().toString().trim();
            if (presetName.isEmpty()) {
                android.widget.Toast.makeText(this, "Enter a preset name", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (selected.size() < 2) {
                android.widget.Toast.makeText(this, "Select at least two subreddits", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            subredditPresets.put(presetName, new ArrayList<>(selected));
            saveSubredditPresets();
            dialog.dismiss();
            openMultiSubredditFeed(presetName, new ArrayList<>(selected));
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void loadSubredditPresets() {
        subredditPresets.clear();
        if (prefs == null) return;
        String raw = prefs.getString("subredditPresetsV1", "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray names = root.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i, "");
                JSONArray array = root.optJSONArray(name);
                if (name.isEmpty() || array == null) continue;
                ArrayList<String> values = new ArrayList<>();
                for (int j = 0; j < array.length(); j++) {
                    String clean = cleanSubredditName(array.optString(j, ""));
                    if (!clean.isEmpty() && !values.contains(clean)) values.add(clean);
                }
                if (values.size() >= 2) subredditPresets.put(name, values);
            }
        } catch (Exception ignored) {}
    }

    private void saveSubredditPresets() {
        if (prefs == null) return;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, ArrayList<String>> entry : subredditPresets.entrySet()) {
                JSONArray array = new JSONArray();
                for (String community : entry.getValue()) array.put(community);
                root.put(entry.getKey(), array);
            }
            prefs.edit().putString("subredditPresetsV1", root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void openMultiSubredditFeed(String presetName, List<String> communities) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (communities != null) {
            for (String community : communities) {
                String value = cleanSubredditName(community);
                if (!value.isEmpty()) clean.add(value);
            }
        }
        if (clean.size() < 2) return;
        pushCurrentState();
        sort = "random";
        screen = Screen.HOME;
        context = "multi";
        subreddit = String.join("+", clean);
        query = "";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        updateChrome();
        setStatus((presetName == null || presetName.isEmpty() ? "Preset" : presetName)
                + " · " + clean.size() + " subreddits · NSFW only", true);
        loadFeed(true);
    }

    private String multiListingPath(String cursor) {
        String remoteSort = sort;
        if (remoteSort.equals("random") || remoteSort.equals("oldest")) remoteSort = "new";
        StringBuilder joined = new StringBuilder();
        String[] parts = subreddit == null ? new String[0] : subreddit.split("\\+");
        for (String part : parts) {
            String clean = cleanSubredditName(part);
            if (clean.isEmpty()) continue;
            if (joined.length() > 0) joined.append('+');
            joined.append(clean);
        }
        if (joined.length() == 0) return "/r/NSFW/new.json?limit=50&raw_json=1&show=all";
        String path = "/r/" + joined + "/" + remoteSort + ".json?limit=50&raw_json=1&show=all";
        if (remoteSort.equals("top")) path += "&t=" + enc(topTime);
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        return path;
    }

    private ArrayList<String> knownNsfwSubreddits() {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            if (row.length < 4 || !"NSFW".equals(row[0])) continue;
            for (String raw : row[3].split(",")) {
                String clean = cleanSubredditName(raw);
                if (!clean.isEmpty()) names.putIfAbsent(clean.toLowerCase(Locale.US), clean);
            }
        }
        for (ArrayList<String> preset : subredditPresets.values()) {
            for (String raw : preset) {
                String clean = cleanSubredditName(raw);
                if (!clean.isEmpty()) names.putIfAbsent(clean.toLowerCase(Locale.US), clean);
            }
        }
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) names.putIfAbsent(clean.toLowerCase(Locale.US), clean);
        }
        ArrayList<String> result = new ArrayList<>(names.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private int fuzzyPostSearchScore(RedditPost post, String value) {
        if (post == null || !post.nsfw) return 0;
        int subredditScore = FuzzySearch.score(value, post.subreddit);
        int titleScore = FuzzySearch.score(value, post.title);
        int authorScore = FuzzySearch.score(value, post.author);
        int metadataScore = FuzzySearch.score(value, post.searchMetadata);
        int urlScore = Math.max(FuzzySearch.score(value, post.permalink), FuzzySearch.score(value, post.sourceUrl));
        int best = Math.max(titleScore, Math.max(authorScore, Math.max(metadataScore, urlScore)));
        if (subredditScore > 0) best = Math.max(best, Math.min(1000, subredditScore + 90));
        return best;
    }

    private void startSubredditNameLeadIns(int generation) {
        if (!searchStillValid(generation)) return;
        if (!(searchScope.equals("global") || searchScope.equals("subscribed"))) return;
        String normalized = FuzzySearch.normalize(query);
        if (normalized.isEmpty()) return;
        ArrayList<String> candidates = knownNsfwSubreddits();
        candidates.removeIf(name -> FuzzySearch.score(query, name) < FuzzySearch.thresholdFor(normalized.length()));
        candidates.sort((a, b) -> Integer.compare(FuzzySearch.score(query, b), FuzzySearch.score(query, a)));
        if (candidates.size() > 6) candidates = new ArrayList<>(candidates.subList(0, 6));
        fetchSubredditNameLeadIn(generation, candidates, 0);
    }

    private void fetchSubredditNameLeadIn(int generation, ArrayList<String> candidates, int index) {
        if (!searchStillValid(generation) || index >= candidates.size()) return;
        String community = candidates.get(index);
        String path = "/r/" + enc(community) + "/new.json?limit=50&raw_json=1&show=all";
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            ArrayList<RedditPost> additions = new ArrayList<>();
            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !post.nsfw || !matchesMedia(post) || isContentBlocked(post)) continue;
                        additions.add(post);
                    }
                }
            }
            if (!additions.isEmpty()) appendUnique(additions);
            if (appLayer != null) appLayer.postDelayed(
                    () -> fetchSubredditNameLeadIn(generation, candidates, index + 1), 120L);
        });
    }

    private void showLibrarySubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Saved & hidden");
        Button saved = sheetButton("Saved posts");
        Button hidden = sheetButton("Read / hidden posts");
        body.addView(saved, sectionButtonParams());
        body.addView(hidden, sectionButtonParams());
        saved.setOnClickListener(v -> { dialog.dismiss(); loadFavorites(); });
        hidden.setOnClickListener(v -> {
            dialog.dismiss();
            loadFavorites();
            favoritesView = "hidden";
            loadHiddenPostsView();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showDisplaySubmenu() {
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
    }
'''
main = append_class_methods(main, extra_methods)

# Pager content uses the reclaimed screen space, and preloads several neighbors.
pager = pager.replace("topInsetPx + dp(102)", "topInsetPx + dp(12)")
pager = pager.replace("topInsetPx + dp(98)", "topInsetPx + dp(8)")
pager = pager.replace("topInsetPx + dp(150)", "topInsetPx + dp(64)")
pager = pager.replace("bottomInsetPx + dp(70)", "bottomInsetPx + dp(18)")
pager = replace_method(pager, "setActivePosition", '''    public void setActivePosition(int position) {
        activePosition = position;
        for (Map.Entry<Integer, ExoPlayer> entry : players.entrySet()) {
            boolean active = entry.getKey() == position;
            entry.getValue().setPlayWhenReady(active);
            if (!active) entry.getValue().pause();
        }
        warmAdjacentMedia(position);
    }''')
pager_extra = r'''
    private void warmAdjacentMedia(int center) {
        int start = Math.max(0, center - 3);
        int end = Math.min(posts.size() - 1, center + 3);
        for (int i = start; i <= end; i++) {
            RedditPost post = posts.get(i);
            if (post == null) continue;
            String preview = post.posterUrl;
            if ((preview == null || preview.isEmpty()) && post.imageUrls != null && !post.imageUrls.isEmpty()) {
                preview = post.imageUrls.get(0);
            }
            if (preview != null && !preview.isEmpty()) {
                Glide.with(context).load(preview).preload();
            }
        }
    }
'''
pager = append_class_methods(pager, pager_extra)

# Sanity guards.
for required in ["showCompactMainMenu", "showPresetSubmenu", "multiListingPath", "startSubredditNameLeadIns", "fuzzyPostSearchScore"]:
    if main.count(required) < 2:  # definition + call/reference
        raise RuntimeError(f"missing v3.8.1 feature {required}")
if 'sheetButton("SFW folders")' in main:
    # showCategoryRoot was replaced, so this must not remain in active method code.
    pass
if "pager.setOffscreenPageLimit(3)" not in main:
    raise RuntimeError("offscreen preload not installed")
if "!candidate.nsfw" in main:
    pass

MAIN.write_text(main)
PAGER.write_text(pager)
print("Applied v3.8.1 NSFW/presets/fuzzy-search/compact-UI/read-hide/scroller migration")
