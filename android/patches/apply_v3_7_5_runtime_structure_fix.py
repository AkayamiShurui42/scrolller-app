from pathlib import Path


runtime_path = Path("patches/apply_v3_7_5_runtime_reliability_custom_categories.py")
main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
source = runtime_path.read_text()
s = main_path.read_text()


def extract_raw_assignment(name):
    marker = name + " = r'''"
    start = source.find(marker)
    if start < 0:
        raise SystemExit(f"Missing v3.7.5 runtime replacement source: {name}")
    start += len(marker)
    end = source.find("'''", start)
    if end < 0:
        raise SystemExit(f"Unterminated v3.7.5 runtime replacement source: {name}")
    return source[start:end]


def replace_region(text, start_sig, next_sig, replacement, label):
    start = text.find(start_sig)
    if start < 0:
        raise SystemExit(f"Missing v3.7.5 runtime structure start: {label}: {start_sig}")
    end = text.find(next_sig, start + len(start_sig))
    if end < 0:
        raise SystemExit(f"Missing v3.7.5 runtime structure end: {label}: {next_sig}")
    return text[:start] + replacement.rstrip() + "\n\n" + text[end:]


# Re-install every runtime-owned Java replacement using explicit declaration
# boundaries. The earlier generic brace scanner can stop at a stale generated
# brace and leave the remainder of the old method at class scope.
feed_pages = extract_raw_assignment("feed_pages")
random_next = extract_raw_assignment("random_next")
remote_search = extract_raw_assignment("remote_search")
category_root = extract_raw_assignment("category_root")
category_matches = extract_raw_assignment("category_matches")

# fetchFeedPages() and finishFeedCollection() are one logical block in the
# v3.7.3+ generated source. The earlier hard-bound rewrite stopped at the Home
# Random gate and accidentally removed finishFeedCollection(). Restore it
# explicitly here so subsequent hard-bound repairs cannot swallow it again.
finish_feed = r'''    private void finishFeedCollection(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected) {
        if (generation != feedGeneration || screen != Screen.HOME) return;
        loading = false;

        if (sort.equals("random")) {
            Collections.shuffle(collected);
        } else if (sort.equals("oldest")) {
            collected.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
        }

        if (reset) replacePosts(collected);
        else appendUnique(collected);

        if (postAdapter.getItemCount() == 0) {
            setStatus("No unique media posts match this feed/filter.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
        prefetchSubredditReservoir();

        if (context.equals("subreddit") && sort.equals("top") && topTime.equals("all")) {
            prefetchHistoricalTopAllIfNeeded(generation);
        }
    }'''

s = replace_region(
    s,
    "    private void fetchFeedPages(",
    "    private boolean homeSubscriptionRandomEnabled() {",
    feed_pages + "\n\n" + finish_feed,
    "fetchFeedPages/finishFeedCollection -> homeSubscriptionRandomEnabled",
)

s = replace_region(
    s,
    "    private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {",
    "    private String listingPath(",
    random_next,
    "fetchHomeRandomRoundNext -> listingPath",
)

s = replace_region(
    s,
    "    private void fetchRemoteSearchGroup(",
    "    private String remoteSearchPath(",
    remote_search,
    "fetchRemoteSearchGroup -> remoteSearchPath",
)

# The custom-category runtime patch inserts its persistence/editor helpers right
# after showCategoryRoot(). Hard-bound to the first of those helpers so only the
# stale old root tail is removed and all custom-category methods are preserved.
s = replace_region(
    s,
    "    private void showCategoryRoot() {",
    "    private JSONObject customCategoryStore() {",
    category_root,
    "showCategoryRoot -> customCategoryStore",
)

# showCategoryMatches is followed by the original categoryCommunities helper.
# Hard-bound it too because its runtime replacement uses the same brace scanner.
s = replace_region(
    s,
    "    private void showCategoryMatches(String term) {",
    "    private String[] categoryCommunities(",
    category_matches,
    "showCategoryMatches -> categoryCommunities",
)

checks = {
    "feed method": "    private void fetchFeedPages(",
    "feed finalizer": "    private void finishFeedCollection(",
    "Home Random gate": "    private boolean homeSubscriptionRandomEnabled() {",
    "Home Random loader": "    private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {",
    "listing path": "    private String listingPath(",
    "remote search method": "    private void fetchRemoteSearchGroup(",
    "remote search path": "    private String remoteSearchPath(",
    "category root": "    private void showCategoryRoot() {",
    "custom category store": "    private JSONObject customCategoryStore() {",
    "custom category creator": "    private void showAddCustomCategorySheet() {",
    "custom category viewer": "    private void showCustomCategory(String name) {",
    "category matches": "    private void showCategoryMatches(String term) {",
    "category communities": "    private String[] categoryCommunities(",
}
for label, signature in checks.items():
    if s.count(signature) != 1:
        raise SystemExit(f"Expected exactly one {label}: {signature}")

feed_start = s.find("    private void fetchFeedPages(")
finish_start = s.find("    private void finishFeedCollection(", feed_start)
random_gate_start = s.find("    private boolean homeSubscriptionRandomEnabled() {", finish_start)
if feed_start < 0 or finish_start < 0 or random_gate_start < 0:
    raise SystemExit("Feed/finalizer/Home Random boundaries are incomplete")
if not (feed_start < finish_start < random_gate_start):
    raise SystemExit("Feed/finalizer/Home Random ordering is invalid")

feed_gap = s[feed_start:finish_start]
if feed_gap.count("engine.get(path, result -> {") != 1:
    raise SystemExit("Stale or missing fetchFeedPages callback after hard-bound rewrite")

finish_gap = s[finish_start:random_gate_start]
for required in [
    'sort.equals("random")',
    'sort.equals("oldest")',
    'Long.compare(a.createdUtc, b.createdUtc)',
    'prefetchHistoricalTopAllIfNeeded(generation)',
]:
    if required not in finish_gap:
        raise SystemExit("Missing restored finishFeedCollection behavior: " + required)

random_gate_start = s.find("    private boolean homeSubscriptionRandomEnabled() {")
random_loader_start = s.find(
    "    private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {",
    random_gate_start,
)
listing_start = s.find("    private String listingPath(", random_loader_start)
if random_gate_start < 0 or random_loader_start < 0 or listing_start < 0:
    raise SystemExit("Home Random helper boundaries are incomplete")
if not (random_gate_start < random_loader_start < listing_start):
    raise SystemExit("Home Random helper ordering is invalid")

random_prefix = s[random_gate_start:random_loader_start]
for required in [
    "private void prepareHomeRandomRound() {",
    "Collections.shuffle(homeRandomRound);",
]:
    if required not in random_prefix:
        raise SystemExit("Missing preserved Home Random prefix behavior: " + required)

random_loader_gap = s[random_loader_start:listing_start]
for required in [
    'after = "home-random-round";',
    "homeRandomRequestsThisLoad >= 12",
    "280L",
]:
    if required not in random_loader_gap:
        raise SystemExit("Missing Home Random loader behavior: " + required)
if random_loader_gap.count("engine.get(path, result -> {") != 1:
    raise SystemExit("Stale or missing Home Random engine callback after hard-bound rewrite")

search_start = s.find("    private void fetchRemoteSearchGroup(")
search_path_start = s.find("    private String remoteSearchPath(", search_start)
search_gap = s[search_start:search_path_start]
if search_gap.count("engine.get(path, result -> {") != 1:
    raise SystemExit("Stale or missing fetchRemoteSearchGroup callback after hard-bound rewrite")

category_root_start = s.find("    private void showCategoryRoot() {")
custom_store_start = s.find("    private JSONObject customCategoryStore() {", category_root_start)
category_root_gap = s[category_root_start:custom_store_start]
for required in [
    "Add category · pick at least two subreddits",
    "My categories",
    "Browse built-in categories",
]:
    if required not in category_root_gap:
        raise SystemExit("Missing custom Categories root behavior: " + required)
# A clean showCategoryRoot region has one dialog content assignment and one show.
if category_root_gap.count("dialog.setContentView(scroll);") != 1 or category_root_gap.count("dialog.show();") != 1:
    raise SystemExit("Stale showCategoryRoot tail remains after hard-bound rewrite")

category_matches_start = s.find("    private void showCategoryMatches(String term) {")
category_communities_start = s.find("    private String[] categoryCommunities(", category_matches_start)
category_matches_gap = s[category_matches_start:category_communities_start]
if category_matches_gap.count("dialog.setContentView(scroll);") != 1 or category_matches_gap.count("dialog.show();") != 1:
    raise SystemExit("Stale showCategoryMatches tail remains after hard-bound rewrite")
if "My category · " not in category_matches_gap:
    raise SystemExit("Custom category lookup missing after hard-bound rewrite")

main_path.write_text(s)
print("Applied v3.7.5 hard-bound feed/finalizer, Random, Search, and Categories runtime structure fix")
