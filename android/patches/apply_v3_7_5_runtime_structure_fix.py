from pathlib import Path
import re


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


# The prior runtime patch intentionally owns these method bodies. Re-extract its
# exact desired replacements, but install them with declaration-to-declaration
# boundaries. This removes any stale callback/method tails left by brace scanning.
feed_pages = extract_raw_assignment("feed_pages")
remote_search = extract_raw_assignment("remote_search")

s = replace_region(
    s,
    "    private void fetchFeedPages(",
    "    private String listingPath(",
    feed_pages,
    "fetchFeedPages -> listingPath",
)

s = replace_region(
    s,
    "    private void fetchRemoteSearchGroup(",
    "    private String remoteSearchPath(",
    remote_search,
    "fetchRemoteSearchGroup -> remoteSearchPath",
)

# Guard the exact failure mode that triggered this patch. Each owned method and
# its following declaration must occur exactly once, with no orphan callback tail
# between them.
checks = {
    "feed method": "    private void fetchFeedPages(",
    "listing path": "    private String listingPath(",
    "remote search method": "    private void fetchRemoteSearchGroup(",
    "remote search path": "    private String remoteSearchPath(",
}
for label, signature in checks.items():
    if s.count(signature) != 1:
        raise SystemExit(f"Expected exactly one {label}: {signature}")

feed_start = s.find("    private void fetchFeedPages(")
listing_start = s.find("    private String listingPath(", feed_start)
feed_gap = s[feed_start:listing_start]
if feed_gap.count("engine.get(path, result -> {") != 1:
    raise SystemExit("Stale or missing fetchFeedPages callback after hard-bound rewrite")

search_start = s.find("    private void fetchRemoteSearchGroup(")
search_path_start = s.find("    private String remoteSearchPath(", search_start)
search_gap = s[search_start:search_path_start]
if search_gap.count("engine.get(path, result -> {") != 1:
    raise SystemExit("Stale or missing fetchRemoteSearchGroup callback after hard-bound rewrite")

main_path.write_text(s)
print("Applied v3.7.5 hard-bound feed/search runtime structure fix")
