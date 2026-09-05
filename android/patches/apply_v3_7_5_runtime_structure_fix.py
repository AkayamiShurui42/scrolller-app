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
# declarations. This avoids the stale-tail failure mode of the earlier brace
# scanner while preserving the other generated helper methods around them.
feed_pages = extract_raw_assignment("feed_pages")
random_next = extract_raw_assignment("random_next")
remote_search = extract_raw_assignment("remote_search")

s = replace_region(
    s,
    "    private void fetchFeedPages(",
    "    private boolean homeSubscriptionRandomEnabled() {",
    feed_pages,
    "fetchFeedPages -> homeSubscriptionRandomEnabled",
)

# fetchHomeRandomRoundNext is the last Home Random helper immediately before
# listingPath(). Hard-bound it too. The compiler diagnostic showed the stale old
# engine.get callback beginning at MainActivity.java:1167 after this method.
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
random_gate_start = s.find("    private boolean homeSubscriptionRandomEnabled() {", feed_start)
feed_gap = s[feed_start:random_gate_start]
if feed_gap.count("engine.get(path, result -> {") != 1:
    raise SystemExit("Stale or missing fetchFeedPages callback after hard-bound rewrite")

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

main_path.write_text(s)
print("Applied v3.7.5 hard-bound feed, Home Random, and Search runtime structure fix")
