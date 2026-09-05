from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()


def method_end(text, signature):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f'Missing v3.7.5 cleanup method: {signature}')
    brace = text.find('{', start + len(signature))
    if brace < 0:
        raise SystemExit(f'Missing v3.7.5 cleanup opening brace: {signature}')

    depth = 0
    in_string = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    escaped = False
    i = brace
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ''

        if in_line_comment:
            if ch == '\n':
                in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            if ch == '*' and nxt == '/':
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif ch == '\\':
                escaped = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if escaped:
                escaped = False
            elif ch == '\\':
                escaped = True
            elif ch == "'":
                in_char = False
            i += 1
            continue

        if ch == '/' and nxt == '/':
            in_line_comment = True
            i += 2
            continue
        if ch == '/' and nxt == '*':
            in_block_comment = True
            i += 2
            continue
        if ch == '"':
            in_string = True
        elif ch == "'":
            in_char = True
        elif ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1

    raise SystemExit(f'Unbalanced v3.7.5 cleanup method: {signature}')


def trim_adjacent_gap(text, signature, next_signature, label):
    end = method_end(text, signature)
    nxt = text.find(next_signature, end)
    if nxt < 0:
        raise SystemExit(f'Missing v3.7.5 cleanup next method: {label}: {next_signature}')
    gap = text[end:nxt]
    # These pairs are intentionally adjacent in the generated class. Any
    # non-whitespace content here is an orphaned tail left by an older patch.
    if gap.strip():
        print(f'Trimming stale generated tail after {label}')
        return text[:end] + '\n\n' + text[nxt:]
    return text


pairs = [
    ('    private void openSearchScreen() {',
     '    private void beginSearch() {',
     'openSearchScreen'),
    ('    private String searchScopeDescription() {',
     '    private String searchScopeLabel() {',
     'searchScopeDescription'),
    ('    private String searchScopeLabel() {',
     '    private void reloadCurrent() {',
     'searchScopeLabel'),
    ('    private void finishSearchCollection(int generation, ArrayList<RedditPost> collected) {',
     '    private void fetchFavoritesSearchPage(',
     'finishSearchCollection'),
    ('    private boolean matchesLocalSearch(RedditPost post, String value) {',
     '    private String normalizeLocalSearch(String value) {',
     'matchesLocalSearch'),
    ('    private int localSearchRelevance(RedditPost post) {',
     '    private double localHotScore(RedditPost post) {',
     'localSearchRelevance'),
]

for signature, next_signature, label in pairs:
    s = trim_adjacent_gap(s, signature, next_signature, label)

# Sanity checks around the exact failure seam.
open_end = method_end(s, '    private void openSearchScreen() {')
begin = s.find('    private void beginSearch() {', open_end)
if begin < 0 or s[open_end:begin].strip():
    raise SystemExit('v3.7.5 openSearchScreen seam is still structurally dirty')

path.write_text(s)
print('Applied v3.7.5 generated search-structure cleanup')
