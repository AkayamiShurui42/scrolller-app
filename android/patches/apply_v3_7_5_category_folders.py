from pathlib import Path


def replace_java_method(text, signature, replacement, label):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Missing v3.7.5 category Java method: {label}: {signature}")
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"Missing v3.7.5 category opening brace: {label}")
    depth = 0
    in_string = False
    escaped = False
    for i in range(brace, len(text)):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                while end < len(text) and text[end] in "\r\n":
                    end += 1
                return text[:start] + replacement + text[end:]
    raise SystemExit(f"Unbalanced v3.7.5 category Java method: {label}")


path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
s = path.read_text()

# Scrolller-style hierarchy: safety root -> category -> subcategory -> communities.
# These are curated visual-media communities. The existing quality crawler remains
# available internally as a source, but the user-facing flat Quality surface stays removed.
const_anchor = '    private static final String REDDIT = "https://www.reddit.com";\n'
if const_anchor not in s:
    raise SystemExit("Missing v3.7.5 category constant anchor")
category_rows = r'''    private static final String[][] CURATED_CATEGORY_ROWS = {
            {"SFW", "Animals", "Cute & social", "aww,AnimalsBeingBros,AnimalsBeingDerps,Eyebleach,rarepuppers"},
            {"SFW", "Animals", "Wildlife", "WildlifePhotography,wildlife,NatureIsFuckingLit"},
            {"SFW", "Art & illustration", "General art", "Art,ArtPorn,Illustration"},
            {"SFW", "Art & illustration", "Imaginary worlds", "ImaginaryLandscapes,ImaginaryCharacters,ImaginaryTechnology,ImaginaryMonsters"},
            {"SFW", "Art & illustration", "Street & design", "Graffiti,DesignPorn"},
            {"SFW", "Photography", "General photography", "itookapicture,ExposurePorn,photocritique"},
            {"SFW", "Photography", "Landscapes", "EarthPorn,landscapephotography"},
            {"SFW", "Photography", "Space", "astrophotography,spaceporn"},
            {"SFW", "Nature", "Earth & weather", "EarthPorn,WeatherGifs,waterporn"},
            {"SFW", "Nature", "Macro & science", "MacroPorn,chemicalreactiongifs,physicsgifs"},
            {"SFW", "Architecture & places", "Cities", "CityPorn,urbanexploration"},
            {"SFW", "Architecture & places", "Buildings", "ArchitecturePorn,AbandonedPorn"},
            {"SFW", "Architecture & places", "Interiors", "CozyPlaces,RoomPorn"},
            {"SFW", "Wallpapers", "Desktop", "wallpapers,WidescreenWallpaper,wallpaper"},
            {"SFW", "Wallpapers", "Mobile & AMOLED", "MobileWallpaper,Amoledbackgrounds"},
            {"SFW", "Food", "Food photography", "food,FoodPorn"},
            {"SFW", "Food", "Cooking & baking", "Breadit,slowcooking,Pizza"},
            {"SFW", "Memes & humor", "General", "memes,funny,wholesomememes"},
            {"SFW", "Technology", "Setups & hardware", "battlestations,MechanicalKeyboards,pcmasterrace"},
            {"SFW", "Gaming", "Games & retro", "gaming,retrogaming,gamingphotography"},
            {"SFW", "Vehicles", "Cars", "carporn,Autos"},
            {"SFW", "Vehicles", "Motorcycles", "motorcycles"},

            {"NSFW", "Adult", "General", "NSFW,gonewild,RealGirls"},
            {"NSFW", "Adult", "GIF & video", "nsfw_gif,NSFW_GIF"},
            {"NSFW", "Adult", "Couples", "couplesgonewild"},
            {"NSFW", "Adult", "Cosplay", "cosplaygirls"},
            {"NSFW", "Adult", "Lingerie", "lingerie"},
            {"NSFW", "Adult", "Curvy", "gonewildcurvy,curvy"},
            {"NSFW", "Adult", "Petite", "petitegonewild"},
            {"NSFW", "Adult", "Artistic", "NSFWart,ArtisticNSFW"}
    };
'''
if "CURATED_CATEGORY_ROWS" not in s:
    s = s.replace(const_anchor, const_anchor + category_rows, 1)

# Category-scoped Search state. It is intentionally session-local because the
# folder itself is the source of truth and can be reopened from the Categories browser.
state_anchor = '    private String searchSubreddit = "";\n'
if state_anchor not in s:
    raise SystemExit("Missing v3.7.5 category search-state anchor")
if 'private String searchCategoryName = "";' not in s:
    s = s.replace(
        state_anchor,
        state_anchor
        + '    private String searchCategoryName = "";\n'
        + '    private final ArrayList<String> searchCategoryCommunities = new ArrayList<>();\n',
        1,
    )

# Replace the old flat curated-category injector with one folder entry.
folder_entry = r'''    private void addCuratedCommunityCategories(
            LinearLayout body, BottomSheetDialog dialog) {
        Button categories = sheetButton("Categories");
        body.addView(categories, sectionButtonParams());
        categories.setOnClickListener(v -> {
            dialog.dismiss();
            showCategoryRoot();
        });
    }

'''
s = replace_java_method(
    s,
    "    private void addCuratedCommunityCategories(",
    folder_entry,
    "category feed entry",
)

# Leave the older addCuratedCommunityCategory helper unused. Insert the new browser
# before showSortSheet so all generated methods remain class-scoped and stable.
insert_anchor = "    private void showSortSheet() {"
if insert_anchor not in s:
    raise SystemExit("Missing v3.7.5 category insertion anchor")

methods = r'''    private void showCategoryRoot() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Categories");
        scroll.addView(body);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search categories, subcategories, or communities");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFF8E8E8E);
        search.setTextSize(14);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(rounded(0xE51A1A1A, 14));
        body.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        Button find = sheetButton("Search category folders");
        body.addView(find, sectionButtonParams());
        find.setOnClickListener(v -> {
            String term = search.getText().toString().trim();
            if (term.isEmpty()) {
                search.requestFocus();
                return;
            }
            dialog.dismiss();
            showCategoryMatches(term);
        });

        TextView modeTitle = sectionTitle("Browse by safety");
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mtp.topMargin = dp(16);
        body.addView(modeTitle, mtp);

        Button sfw = sheetButton("SFW folders");
        Button nsfw = sheetButton("NSFW folders");
        body.addView(sfw, sectionButtonParams());
        body.addView(nsfw, sectionButtonParams());
        sfw.setOnClickListener(v -> {
            dialog.dismiss();
            showCategorySafety("SFW");
        });
        nsfw.setOnClickListener(v -> {
            dialog.dismiss();
            showCategorySafety("NSFW");
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCategorySafety(String safety) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody(safety + " categories");
        scroll.addView(body);

        HashSet<String> seen = new HashSet<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            if (!row[0].equals(safety) || !seen.add(row[1])) continue;
            String category = row[1];
            Button button = sheetButton(category);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                showCategoryFolder(safety, category);
            });
        }

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCategoryFolder(String safety, String category) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody(safety + " · " + category);
        scroll.addView(body);

        String[] all = categoryCommunities(safety, category, null);
        Button searchAll = sheetButton("Search all in " + category);
        body.addView(searchAll, sectionButtonParams());
        searchAll.setOnClickListener(v -> {
            dialog.dismiss();
            openCategorySearch(category, all);
        });

        HashSet<String> seen = new HashSet<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            if (!row[0].equals(safety) || !row[1].equals(category) || !seen.add(row[2])) continue;
            String subcategory = row[2];
            Button button = sheetButton(subcategory);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                showCategorySubfolder(safety, category, subcategory);
            });
        }

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCategorySubfolder(String safety, String category, String subcategory) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody(category + " · " + subcategory);
        scroll.addView(body);

        String[] communities = categoryCommunities(safety, category, subcategory);
        Button searchFolder = sheetButton("Search this folder");
        body.addView(searchFolder, sectionButtonParams());
        searchFolder.setOnClickListener(v -> {
            dialog.dismiss();
            openCategorySearch(category + " · " + subcategory, communities);
        });

        for (String community : communities) {
            if (community == null || community.trim().isEmpty()) continue;
            String clean = cleanSubredditName(community);
            if (clean.isEmpty()) continue;
            Button button = sheetButton("r/" + clean);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                openSubredditFeed(clean);
            });
        }

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCategoryMatches(String term) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Category search · " + term);
        scroll.addView(body);

        String needle = term.toLowerCase(Locale.US);
        int matches = 0;
        for (String[] row : CURATED_CATEGORY_ROWS) {
            String haystack = (row[0] + " " + row[1] + " " + row[2] + " " + row[3])
                    .toLowerCase(Locale.US);
            if (!haystack.contains(needle)) continue;
            matches++;
            String safety = row[0];
            String category = row[1];
            String subcategory = row[2];
            String[] communities = categoryCommunities(safety, category, subcategory);
            Button result = sheetButton(safety + " · " + category + " · " + subcategory);
            body.addView(result, sectionButtonParams());
            result.setOnClickListener(v -> {
                dialog.dismiss();
                showCategorySubfolder(safety, category, subcategory);
            });
        }

        if (matches == 0) {
            body.addView(bodyText("No category folders matched that search."));
        }

        dialog.setContentView(scroll);
        dialog.show();
    }

    private String[] categoryCommunities(String safety, String category, @Nullable String subcategory) {
        ArrayList<String> communities = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            if (!row[0].equals(safety) || !row[1].equals(category)) continue;
            if (subcategory != null && !row[2].equals(subcategory)) continue;
            for (String community : row[3].split(",")) {
                String clean = cleanSubredditName(community);
                if (!clean.isEmpty() && seen.add(clean.toLowerCase(Locale.US))) {
                    communities.add(clean);
                }
            }
        }
        return communities.toArray(new String[0]);
    }

    private void openCategorySearch(String title, String[] communities) {
        if (communities == null || communities.length == 0) return;
        if (screen != Screen.SEARCH) pushCurrentState();

        searchCategoryName = title == null ? "Category" : title;
        searchCategoryCommunities.clear();
        for (String community : communities) {
            String clean = cleanSubredditName(community);
            if (!clean.isEmpty()) searchCategoryCommunities.add(clean);
        }
        if (searchCategoryCommunities.isEmpty()) return;

        searchScope = "category";
        query = "";
        prefs.edit().remove("lastSearch").apply();
        screen = Screen.SEARCH;
        context = "search";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        searchInput.setText("");
        updateChrome();
        setStatus("Search " + searchCategoryName + " by title, community, or creator.", false);
        searchInput.requestFocus();
    }

    private ArrayList<String> categorySearchGroups() {
        ArrayList<String> groups = new ArrayList<>();
        for (int i = 0; i < searchCategoryCommunities.size(); i += 10) {
            StringBuilder group = new StringBuilder();
            int end = Math.min(searchCategoryCommunities.size(), i + 10);
            for (int j = i; j < end; j++) {
                if (group.length() > 0) group.append('+');
                group.append(searchCategoryCommunities.get(j));
            }
            if (group.length() > 0) groups.add(group.toString());
        }
        return groups;
    }

'''
s = s.replace(insert_anchor, methods + insert_anchor, 1)

# Category becomes a first-class Search source when launched from a folder.
load_anchor = '''        if (searchScope.equals("subreddit")) {
'''
category_branch = '''        if (searchScope.equals("category")) {
            ArrayList<String> groups = categorySearchGroups();
            if (groups.isEmpty()) {
                loading = false;
                setStatus("This category has no communities to search.", false);
                return;
            }
            fetchRemoteSearchGroup(
                    generation, groups, 0, "", new ArrayList<>(),
                    new HashSet<>(), new HashSet<>(), 0, 0);
            return;
        }

'''
if load_anchor not in s:
    raise SystemExit("Missing v3.7.5 category loadSearch anchor")
s = s.replace(load_anchor, category_branch + load_anchor, 1)

# Search chrome names the category source rather than collapsing it to Global/Subs.
s = s.replace(
    '        if (searchScope.equals("collector")) return "Collector";\n',
    '        if (searchScope.equals("collector")) return "Collector";\n'
    '        if (searchScope.equals("category")) return searchCategoryName.isEmpty() ? "a category" : searchCategoryName;\n',
    1,
)
s = s.replace(
    '        if (searchScope.equals("collector")) return "Collector";\n',
    '        if (searchScope.equals("collector")) return "Collector";\n'
    '        if (searchScope.equals("category")) return searchCategoryName.isEmpty() ? "Category" : searchCategoryName;\n',
    1,
)

# If the scope sheet is opened while category search is active, expose the
# current folder as a selectable source in addition to the ordinary sources.
scope_anchor = '''        TextView subTitle = sectionTitle("Subreddit search");
'''
category_scope_ui = '''        if (!searchCategoryCommunities.isEmpty()) {
            Button categorySource = sheetButton(
                    "Category · " + searchCategoryName
                    + (searchScope.equals("category") ? "  ✓" : ""));
            body.addView(categorySource, sectionButtonParams());
            categorySource.setOnClickListener(v -> {
                searchScope = "category";
                dialog.dismiss();
                if (!query.isEmpty()) loadSearchInternal();
                updateChrome();
            });
        }

'''
if scope_anchor not in s:
    raise SystemExit("Missing v3.7.5 category scope-sheet anchor")
s = s.replace(scope_anchor, category_scope_ui + scope_anchor, 1)

path.write_text(s)
print("Applied v3.7.5 Scrolller-style category folders and category search")
