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


# The runtime patch owns these method bodies. Re-extract its exact desired
# replacements, but install them against the actual immediately-following method
# declarations in the generated class. Home Random helpers are intentionally
# inserted between fetchFeedPages() and listingPath(), so they must be preserved.
feed_pages = extract_raw_assignment("feed_pages")
remote_search = extract_raw_assignment("remote_search")

s = replace_region(
    s,
    "    private void fetchFeedPages(",
    "    private boolean homeSubscriptionRandomEnabled() {",
    feed_pages,
    "fetchFeedPages -> homeSubscriptionRandomEnabled",
)

s = replace_region(
    s,
    "    private void fetchRemoteSearchGroup(",
    "    private String remoteSearchPath(",
    remote_search,
    "fetchRemoteSearchGroup -> remoteSearchPath",
)

checks = {
    "feed method": "    private void fetchFeedPages(",
    "Home Random gate": "    private boolean homeSubscriptionRandomEnabled() {",
    "Home Random loader": "    private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {",
    "listing path": "    private String listingPath(",
    "remote search method": "    private void fetchRemoteSearchGroup(",
    "remote search path": "    private String remoteSearchPath(",
}
for label, signature in checks.items():
    if s.count(signature) != 1:
        raise SystemExit(f"Expected exactly one {label}: {signature}")

feed_start = s.find("    private void fetchFeedPages(")
random_start = s.find("    private boolean homeSubscriptionRandomEnabled() {", feed_start)
feed_gap = s[feed_start:random_start]
if feed_gap.count("engine.get(path, result -> {") != 1:
    raise SystemExit("Stale or missing fetchFeedPages callback after hard-bound rewrite")

random_start = s.find("    private boolean homeSubscriptionRandomEnabled() {")
listing_start = s.find("    private String listingPath(", random_start)
if random_start < 0 or listing_start < 0 or listing_start <= random_start:
    raise SystemExit("Home Random helpers were not preserved before listingPath")
random_gap = s[random_start:listing_start]
for required in [
    "private void prepareHomeRandomRound() {",
    "Collections.shuffle(homeRandomRound);",
    "private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {",
    'after = "home-random-round";',
    "homeRandomRequestsThisLoad >= 12",
    "280L",
]:
    if required not in random_gap:
        raise SystemExit("Missing preserved Home Random runtime behavior: " + required)

search_start = s.find("    private void fetchRemoteSearchGroup(")
search_path_start = s.find("    private String remoteSearchPath(", search_start)
search_gap = s[search_start:search_path_start]
if search_gap.count("engine.get(path, result -> {") != 1:
    raise SystemExit("Stale or missing fetchRemoteSearchGroup callback after hard-bound rewrite")

main_path.write_text(s)
print("Applied v3.7.5 hard-bound feed/search structure fix with Home Random preserved")
