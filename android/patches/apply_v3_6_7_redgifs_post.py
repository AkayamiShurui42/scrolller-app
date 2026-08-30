from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.7 RedGIFs post target: {label}\n{old[:700]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/RedditPost.java')
s = path.read_text()

s = replace_required(
    s,
    '    public final String videoUrl;\n',
    '    public String videoUrl;\n',
    'mutable resolved video URL')

# Insert provider fallback at the single terminal parseMedia failure instead of
# depending on the exact image-handling block produced by older patches.
anchor = '''        return null;
    }

    private static JSONObject previewSource(JSONObject d) {'''
insert = '''        // Reddit-hosted reddit_video_preview was already preferred above.
        // Keep otherwise-unresolved RedGIFs posts so HD media can be resolved lazily.
        if (isRedgifs(direct, domain)) {
            String redgifsId = extractRedgifsId(direct);
            if (!redgifsId.isEmpty()) {
                return new ParsedMedia(
                        MediaKind.GIF,
                        new ArrayList<>(),
                        "redgifs:" + redgifsId,
                        previewImage(d),
                        previewWidth,
                        previewHeight);
            }
        }

        return null;
    }

    private static JSONObject previewSource(JSONObject d) {'''
s = replace_required(s, anchor, insert, 'terminal parseMedia provider fallback')

s = replace_required(
    s,
    '''    private static int positive(int first, int fallback) {
        return first > 0 ? first : Math.max(0, fallback);
    }''',
    '''    private static boolean isRedgifs(String url, String domain) {
        String u = url == null ? "" : url.toLowerCase();
        String d = domain == null ? "" : domain.toLowerCase();
        return d.contains("redgifs.com") || u.contains("redgifs.com/");
    }

    private static String extractRedgifsId(String url) {
        if (url == null || url.isEmpty()) return "";
        String clean = url;
        int cut = clean.indexOf('?');
        if (cut >= 0) clean = clean.substring(0, cut);
        cut = clean.indexOf('#');
        if (cut >= 0) clean = clean.substring(0, cut);

        String lower = clean.toLowerCase();
        String[] markers = {"/watch/", "/ifr/", "/i/"};
        for (String marker : markers) {
            int at = lower.indexOf(marker);
            if (at < 0) continue;
            String tail = clean.substring(at + marker.length());
            int slash = tail.indexOf('/');
            if (slash >= 0) tail = tail.substring(0, slash);
            int dot = tail.indexOf('.');
            if (dot >= 0) tail = tail.substring(0, dot);
            tail = tail.replaceAll("[^A-Za-z0-9].*$", "");
            if (!tail.isEmpty()) return tail;
        }
        return "";
    }

    private static int positive(int first, int fallback) {
        return first > 0 ? first : Math.max(0, fallback);
    }''',
    'RedGIFs URL helpers')

path.write_text(s)
print('Applied v3.6.7 unresolved RedGIFs post retention')
