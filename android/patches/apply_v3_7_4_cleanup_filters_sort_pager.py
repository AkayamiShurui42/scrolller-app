from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Missing v3.7.4 target: {label}\n{old[:1000]}")
    return text.replace(old, new, 1)


def replace_java_method(text, signature, replacement, label):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Missing v3.7.4 Java method: {label}: {signature}")
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"Missing v3.7.4 opening brace: {label}")
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
    raise SystemExit(f"Unbalanced v3.7.4 Java method: {label}")


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
s = main_path.read_text()

# ---------------------------------------------------------------------------
# 1) Remove the standalone Quality collection surface.
# Keep the useful seed communities, but surface them in the ordinary feed picker
# as curated categories. This eliminates the duplicate Quality feed/crawler UI.
# ---------------------------------------------------------------------------
quality_decl = '''        Button quality = sheetButton("Quality collection" + (context.equals("quality") ? "  ✓" : ""));
'''
if quality_decl in s:
    s = s.replace(quality_decl, "", 1)

quality_add = '''        body.addView(quality, sectionButtonParams());
'''
if quality_add in s:
    s = s.replace(quality_add, "", 1)

quality_listener = '''        quality.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("quality", true);
        });
'''
if quality_listener in s:
    s = s.replace(quality_listener, "", 1)

popular_listener = '''        popular.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("popular", true);
        });
'''
if popular_listener not in s:
    raise SystemExit("Missing v3.7.4 target: feed picker popular listener")
s = s.replace(
    popular_listener,
    popular_listener + '''        addCuratedCommunityCategories(body, dialog);
''',
    1,
)

sort_anchor = "    private void showSortSheet() {"
if sort_anchor not in s:
    raise SystemExit("Missing v3.7.4 target: showSortSheet anchor")

curated_helpers = r'''    private void addCuratedCommunityCategories(
            LinearLayout body, BottomSheetDialog dialog) {
        addCuratedCommunityCategory(body, dialog, "Photography", new String[]{
                "itookapicture", "WildlifePhotography", "ExposurePorn"
        });
        addCuratedCommunityCategory(body, dialog, "Nature & space", new String[]{
                "EarthPorn", "astrophotography", "spaceporn"
        });
        addCuratedCommunityCategory(body, dialog, "Architecture & cities", new String[]{
                "ArchitecturePorn", "CityPorn"
        });
        addCuratedCommunityCategory(body, dialog, "Wallpapers & art", new String[]{
                "WidescreenWallpaper", "wallpapers", "wallpaper", "ImaginaryLandscapes"
        });
    }

    private void addCuratedCommunityCategory(
            LinearLayout body,
            BottomSheetDialog dialog,
            String title,
            String[] communities) {
        TextView category = sectionTitle(title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(16);
        body.addView(category, titleParams);

        for (String community : communities) {
            Button button = sheetButton("r/" + community);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                openSubredditFeed(community);
            });
        }
    }

'''
s = s.replace(sort_anchor, curated_helpers + sort_anchor, 1)

# If a stale in-memory/history state somehow asks for the removed context,
# normalize it back to Home instead of routing into an invalid listing.
navigate_sig = "    private void navigateHome(String which, boolean pushHistory) {"
navigate_start = s.find(navigate_sig)
if navigate_start >= 0:
    brace = s.find("{", navigate_start)
    insertion = '\n        if ("quality".equals(which)) which = "home";'
    if insertion.strip() not in s[navigate_start:navigate_start + 500]:
        s = s[:brace + 1] + insertion + s[brace + 1:]


# ---------------------------------------------------------------------------
# 2) Strengthen content filtering.
# The old filter used title + subreddit only. Include the post path/source URL,
# expand explicit topical aliases, and keep the final replacement/append gates
# authoritative so every discovery source receives the same rejection.
# ---------------------------------------------------------------------------
content_filter = r'''    private boolean isContentBlocked(RedditPost post) {
        if (post == null) return false;

        String title = post.title == null ? "" : post.title;
        String community = post.subreddit == null ? "" : post.subreddit;
        String permalink = post.permalink == null ? "" : post.permalink;
        String sourceUrl = post.sourceUrl == null ? "" : post.sourceUrl;
        String normalized = normalizeFilterText(
                title + " " + community + " " + permalink + " " + sourceUrl);
        String communityKey = community.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]", "");

        if (blockLgbtTopics) {
            if (containsFilterPhrase(normalized,
                    "gay", "lesbian", "bisexual", "bi sexual", "pansexual", "pan sexual",
                    "homosexual", "homosexuality", "trans", "transgender", "transgendered",
                    "transsexual", "trans woman", "trans women", "trans man", "trans men",
                    "transfem", "trans fem", "transmasc", "trans masc",
                    "lgbt", "lgbtq", "lgbtqia", "queer",
                    "nonbinary", "non binary", "genderfluid", "gender fluid",
                    "genderqueer", "gender queer", "mtf", "ftm", "wlw")) {
                return true;
            }

            String[] blockedCommunityFragments = {
                    "asktransgender", "transgender", "transporn", "transgonewild",
                    "transpositive", "transadorable", "transpassing",
                    "gayporn", "gaybros", "gaymers", "lesbian", "bisexual",
                    "pansexual", "nonbinary", "genderfluid", "genderqueer",
                    "lgbt", "lgbtq", "queer", "mtf", "ftm"
            };
            for (String fragment : blockedCommunityFragments) {
                if (communityKey.contains(fragment)) return true;
            }
        }

        if (blockGoreContent) {
            if (containsFilterPhrase(normalized,
                    "gore", "gory", "blood", "bloody", "bloodshed", "nsfl",
                    "dismemberment", "dismembered", "decapitation", "decapitated",
                    "beheading", "beheaded", "mutilation", "mutilated",
                    "exposed organs", "open wound", "open wounds",
                    "graphic injury", "graphic injuries", "graphic death",
                    "violent death", "dead body", "dead bodies", "corpse", "corpses",
                    "human remains", "severed limb", "severed limbs",
                    "gunshot wound", "stab wound", "autopsy", "cadaver")) {
                return true;
            }

            String[] blockedCommunityFragments = {
                    "gore", "medicalgore", "eyeblech", "watchpeopledie",
                    "deadorvegetable", "makemycoffin", "nsfl"
            };
            for (String fragment : blockedCommunityFragments) {
                if (communityKey.contains(fragment)) return true;
            }
        }

        return false;
    }

'''
s = replace_java_method(
    s,
    "    private boolean isContentBlocked(RedditPost post) {",
    content_filter,
    "content filter",
)


# ---------------------------------------------------------------------------
# 3) Correct sort routing.
# Reddit has no ordinary RANDOM or OLDEST listing endpoint. Use /new as the
# reservoir for those client-side modes; Random shuffles it, Oldest sorts it
# ascending. Increase the Oldest reservoir so it is not just a reversed tiny page.
# ---------------------------------------------------------------------------
listing_path = r'''    private String listingPath(String cursor) {
        String remoteSort = sort;
        if (remoteSort.equals("random") || remoteSort.equals("oldest")) {
            remoteSort = "new";
        }

        String base;
        if (context.equals("home")) {
            base = remoteSort.equals("best") ? "/.json" : "/" + remoteSort + ".json";
        } else if (context.equals("popular")) {
            base = remoteSort.equals("best")
                    ? "/r/popular/hot.json"
                    : "/r/popular/" + remoteSort + ".json";
        } else {
            base = remoteSort.equals("best")
                    ? "/r/" + enc(subreddit) + "/hot.json"
                    : "/r/" + enc(subreddit) + "/" + remoteSort + ".json";
        }

        String path = base + "?limit=100&raw_json=1&show=all";
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        return path;
    }

'''
s = replace_java_method(
    s,
    "    private String listingPath(String cursor) {",
    listing_path,
    "listingPath",
)

# Feed collection depth for client-side order modes.
old_target = '            int target = clientOrder ? 120 : 30;'
if old_target not in s:
    raise SystemExit("Missing v3.7.4 target: client-order feed target")
s = s.replace(
    old_target,
    '            int target = sort.equals("oldest") ? 1200 : sort.equals("random") ? 400 : 30;',
    1,
)

# Search remote routing: RANDOM/OLDEST use Reddit NEW as the reservoir.
old_search_sort = '''        String searchSort = sort.equals("random") ? "new"
                : sort.equals("best") ? "relevance"
                : sort.equals("rising") ? "new" : sort;'''
new_search_sort = '''        String searchSort = (sort.equals("random") || sort.equals("oldest")) ? "new"
                : sort.equals("best") ? "relevance"
                : sort.equals("rising") ? "new" : sort;'''
if old_search_sort not in s:
    raise SystemExit("Missing v3.7.4 target: remote search sort mapping")
s = s.replace(old_search_sort, new_search_sort, 1)

# Remote search must actually apply the client-side Oldest order after all pages
# have been collected. Random remains a final shuffle.
old_finish_search = '''        if (sort.equals("random")) Collections.shuffle(collected);
        replacePosts(collected);'''
new_finish_search = '''        if (sort.equals("random")) {
            Collections.shuffle(collected);
        } else if (sort.equals("oldest")) {
            collected.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
        }
        replacePosts(collected);'''
if old_finish_search not in s:
    raise SystemExit("Missing v3.7.4 target: finishSearchCollection ordering")
s = s.replace(old_finish_search, new_finish_search, 1)

# Collector/Favorites local search previously had no Oldest branch.
old_local_sort = '''        } else if (sort.equals("new")) {
            items.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
        } else if (sort.equals("top")) {'''
new_local_sort = '''        } else if (sort.equals("new")) {
            items.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
        } else if (sort.equals("oldest")) {
            items.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
        } else if (sort.equals("top")) {'''
if old_local_sort not in s:
    raise SystemExit("Missing v3.7.4 target: local search oldest sort")
s = s.replace(old_local_sort, new_local_sort, 1)

# Make the sort menu consistent across feed/search/user contexts.
sort_sheet = r'''    private void showSortSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Choose sorting option");

        String[] values;
        if (screen == Screen.SEARCH) {
            values = new String[]{"best", "hot", "new", "oldest", "top", "random"};
        } else if (screen == Screen.USER) {
            values = new String[]{"best", "new", "oldest", "hot", "top", "random"};
        } else {
            values = new String[]{"best", "hot", "new", "oldest", "top", "rising", "random"};
        }

        for (String value : values) {
            Button button = sheetButton(label(value) + (sort.equals(value) ? "  ✓" : ""));
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                sort = value;
                prefs.edit().putString("sort", sort).apply();
                dialog.dismiss();
                if (sort.equals("top")) showTopTimeSheet();
                else reloadCurrent();
            });
        }

        dialog.setContentView(body);
        dialog.show();
    }

'''
s = replace_java_method(
    s,
    "    private void showSortSheet() {",
    sort_sheet,
    "sort sheet",
)

main_path.write_text(s)


# ---------------------------------------------------------------------------
# 4) Smooth post-to-post media transitions.
# Keep a poster behind video surfaces and make Media3's shutter transparent so
# the next card does not flash black while its first frame is being decoded.
# ---------------------------------------------------------------------------
pager_path = Path("app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java")
p = pager_path.read_text()

player_view_marker = '''                PlayerView playerView = new PlayerView(context);
                playerView.setUseController(false);
                playerView.setBackgroundColor(Color.BLACK);
                root.addView(playerView, fullParams());
'''
if player_view_marker not in p:
    raise SystemExit("Missing v3.7.4 target: fullscreen PlayerView creation")
player_view_replacement = '''                if (post.posterUrl != null && !post.posterUrl.isEmpty()) {
                    ImageView videoPoster = new ImageView(context);
                    videoPoster.setBackgroundColor(Color.BLACK);
                    videoPoster.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    root.addView(videoPoster, fullParams());
                    Glide.with(videoPoster).load(post.posterUrl).fitCenter().into(videoPoster);
                }

                PlayerView playerView = new PlayerView(context);
                playerView.setUseController(false);
                playerView.setKeepContentOnPlayerReset(true);
                playerView.setShutterBackgroundColor(Color.TRANSPARENT);
                playerView.setBackgroundColor(Color.TRANSPARENT);
                root.addView(playerView, fullParams());
'''
p = p.replace(player_view_marker, player_view_replacement, 1)

pager_path.write_text(p)


# Version bump after the v3.7.3 patch stack.
gradle = Path("app/build.gradle")
g = gradle.read_text()
if 'versionCode 31' not in g or 'versionName "3.7.3"' not in g:
    raise SystemExit("Missing v3.7.4 version targets")
g = g.replace("versionCode 31", "versionCode 32", 1)
g = g.replace('versionName "3.7.3"', 'versionName "3.7.4"', 1)
gradle.write_text(g)

print("Applied v3.7.4 Quality cleanup + curated categories + strict filters + sort/search + smooth pager")
