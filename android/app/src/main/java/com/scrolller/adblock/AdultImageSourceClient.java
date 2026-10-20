package com.scrolller.adblock;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AdultImageSourceClient {
    enum Source { RULE34, SANKAKU, CREATEHENTAI }

    interface Callback {
        void onComplete(Source source, int page, ArrayList<RedditPost> posts);
        void onError(Source source, int page, String error);
    }

    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "adult-image-source");
                t.setDaemon(true);
                return t;
            });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Set<String> BLOCKED_TAGS = new HashSet<>();
    static {
        Collections.addAll(BLOCKED_TAGS,
                "loli", "lolicon", "shota", "shotacon", "underage", "minor",
                "minors", "child", "children", "childlike", "preteen", "teen",
                "teenager", "young", "young_looking", "baby", "infant", "toddler",
                "cub", "age_regression", "schoolgirl", "schoolboy",
                "real_person", "celebrity", "deepfake", "ai_deepfake",
                "gore", "blood", "guro", "snuff", "mutilation", "dismemberment",
                "decapitation", "corpse", "necrophilia", "rape", "noncon",
                "non-consensual", "forced", "bestiality", "zoophilia");
    }

    private AdultImageSourceClient() {}

    static void fetch(
            Source source,
            String userQuery,
            boolean aiOnly,
            int page,
            int limit,
            Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                if (source != Source.RULE34) {
                    throw new IllegalStateException(source.name() + " API adapter not yet verified; no unfiltered fallback permitted");
                }
                String tags = buildQuery(userQuery, true, source);
                int bounded = Math.max(1, Math.min(limit, 250));
                String url;

                if (source == Source.RULE34) {
                    url = "https://api.rule34.xxx/index.php?page=dapi&s=post&q=index"
                            + "&json=1&limit=" + bounded
                            + "&pid=" + Math.max(0, page)
                            + "&tags=" + enc(tags);
                } else {
                    url = "https://gelbooru.com/index.php?page=dapi&s=post&q=index"
                            + "&json=1&limit=" + bounded
                            + "&pid=" + Math.max(0, page)
                            + "&tags=" + enc(tags);
                }

                String text = get(url);
                ArrayList<RedditPost> posts = parse(source, text);
                MAIN.post(() -> callback.onComplete(source, page, posts));
            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Source request failed" : e.getMessage();
                MAIN.post(() -> callback.onError(source, page, message));
            }
        });
    }

    static String normalizeUserQuery(String raw) {
        if (raw == null) return "";
        String clean = raw.trim().toLowerCase(Locale.US);
        if (clean.isEmpty()) return "";

        // Preserve advanced booru syntax. Ordinary phrases become tag tokens.
        if (clean.contains(":") || clean.contains("*") || clean.contains("~")
                || clean.contains("(") || clean.contains(")")
                || clean.contains("{") || clean.contains("}")) {
            return clean;
        }

        ArrayList<String> tokens = new ArrayList<>();
        for (String part : clean.split("[,\\s]+")) {
            String token = part.replaceAll("[^a-z0-9_\\-]", "");
            if (!token.isEmpty()) tokens.add(token);
        }
        return String.join(" ", tokens);
    }

    private static String buildQuery(String raw, boolean aiOnly, Source source) {
        String user = normalizeUserQuery(raw);
        StringBuilder q = new StringBuilder("rating:explicit");

        // Rule34 has a large AI-tagged corpus. Gelbooru's AI corpus is much less
        // reliable, so AI is a preference there rather than a hard dependency.
        if (source == Source.RULE34) q.append(" ai_generated");

        if (!user.isEmpty()) q.append(' ').append(user);

        for (String blocked : BLOCKED_TAGS) {
            q.append(" -").append(blocked);
        }
        return q.toString();
    }

    private static ArrayList<RedditPost> parse(Source source, String text) throws Exception {
        JSONArray rows = null;
        try {
            rows = new JSONArray(text);
        } catch (Exception ignored) {
            JSONObject root = new JSONObject(text);
            rows = root.optJSONArray("post");
            if (rows == null) rows = root.optJSONArray("posts");
        }

        ArrayList<RawPost> raw = new ArrayList<>();
        if (rows == null) return new ArrayList<>();

        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;

            String tags = firstNonEmpty(
                    o.optString("tags", ""),
                    o.optString("tag_string", ""));
            if (!allowedTags(tags) || !hasVerifiedAiTag(tags)) continue;

            String rating = o.optString("rating", "");
            if (!rating.equalsIgnoreCase("explicit")
                    && !rating.equalsIgnoreCase("e")) continue;

            String file = firstNonEmpty(
                    o.optString("file_url", ""),
                    o.optString("source", ""));
            if (!staticImage(file)) continue;

            String id = String.valueOf(o.opt("id"));
            if (id == null || id.isEmpty() || id.equals("null")) continue;

            RawPost rp = new RawPost();
            rp.id = id;
            rp.parent = intValue(o.opt("parent_id"));
            rp.file = file;
            rp.preview = firstNonEmpty(
                    o.optString("sample_url", ""),
                    o.optString("preview_url", ""),
                    file);
            rp.tags = tags;
            rp.owner = firstNonEmpty(
                    o.optString("owner", ""),
                    o.optString("creator_id", ""),
                    o.optString("uploader", ""));
            rp.width = intValue(o.opt("width"));
            rp.height = intValue(o.opt("height"));
            rp.score = intValue(o.opt("score"));
            rp.created = parseTime(firstNonEmpty(
                    o.optString("created_at", ""),
                    o.optString("change", "")));
            raw.add(rp);
        }

        return groupSets(source, raw);
    }

    private static ArrayList<RedditPost> groupSets(Source source, List<RawPost> raw) {
        LinkedHashMap<String, ArrayList<RawPost>> groups = new LinkedHashMap<>();
        for (RawPost rp : raw) {
            String key = rp.parent > 0
                    ? "parent:" + rp.parent
                    : "single:" + rp.id;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rp);
        }

        ArrayList<RedditPost> out = new ArrayList<>();
        for (Map.Entry<String, ArrayList<RawPost>> entry : groups.entrySet()) {
            ArrayList<RawPost> set = entry.getValue();
            if (set.isEmpty()) continue;

            RawPost first = set.get(0);
            ArrayList<String> urls = new ArrayList<>();
            for (RawPost rp : set) {
                if (staticImage(rp.file)) urls.add(rp.file);
            }
            if (urls.isEmpty()) continue;

            RedditPost.MediaKind kind = urls.size() > 1
                    ? RedditPost.MediaKind.GALLERY
                    : RedditPost.MediaKind.IMAGE;
            String sourceName = "Rule34";
            String id = source.name().toLowerCase(Locale.US) + ":"
                    + (first.parent > 0 ? "set:" + first.parent : first.id);
            String permalink = "https://rule34.xxx/index.php?page=post&s=view&id=" + first.id;

            RedditPost post = new RedditPost(
                    id,
                    promptTitle(first.tags, urls.size()),
                    first.owner,
                    sourceName,
                    permalink,
                    urls.get(0),
                    first.score,
                    0,
                    first.created,
                    false,
                    true,
                    kind,
                    urls,
                    "",
                    first.preview,
                    first.width,
                    first.height);
            post.searchMetadata = first.tags == null ? "" : first.tags;
            post.sourceOrigin = source.name().toLowerCase(Locale.US);
            out.add(post);
        }
        return out;
    }

    private static String promptTitle(String tags, int imageCount) {
        String clean = tags == null ? "" : tags.trim();
        if (clean.isEmpty()) return imageCount > 1 ? imageCount + " image set" : "AI image";

        String[] parts = clean.split("\\s+");
        StringBuilder title = new StringBuilder();
        int added = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.equals("ai_generated") || part.startsWith("rating:")) continue;
            if (added > 0) title.append(" · ");
            title.append(part.replace('_', ' '));
            if (++added >= 6) break;
        }
        if (imageCount > 1) title.append(" · ").append(imageCount).append(" images");
        return title.length() == 0 ? "AI image" : title.toString();
    }

    private static boolean hasVerifiedAiTag(String tags) {
        if (tags == null) return false;
        for (String tag : tags.toLowerCase(Locale.US).split("\\s+")) {
            if ("ai_generated".equals(tag) || "ai-generated".equals(tag)
                    || "ai_created".equals(tag) || "ai-created".equals(tag)) return true;
        }
        return false;
    }

    private static boolean allowedTags(String tags) {
        String lower = tags == null ? "" : tags.toLowerCase(Locale.US);
        if (lower.isEmpty()) return false;
        for (String blocked : BLOCKED_TAGS) {
            String pattern = "(^|\\s)" + java.util.regex.Pattern.quote(blocked) + "(\\s|$)";
            if (lower.matches(".*" + pattern + ".*")) return false;
        }
        return true;
    }

    private static boolean blockedToken(String token) {
        String clean = token == null ? "" : token.replace("-", "").toLowerCase(Locale.US);
        return BLOCKED_TAGS.contains(clean);
    }

    private static boolean staticImage(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty(
                "User-Agent", "AIGallery/1.0 Android image-search");

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
        }
        connection.disconnect();

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status);
        }
        return body.toString();
    }

    private static long parseTime(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try {
            long numeric = Long.parseLong(value);
            return numeric > 10_000_000_000L ? numeric / 1000L : numeric;
        } catch (Exception ignored) {}

        String[] patterns = {
                "EEE MMM dd HH:mm:ss Z yyyy",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd HH:mm:ss"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(pattern, Locale.US);
                df.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = df.parse(value);
                if (date != null) return date.getTime() / 1000L;
            } catch (ParseException ignored) {}
        }
        return 0L;
    }

    private static int intValue(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(
                    value == null ? "" : value,
                    StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final class RawPost {
        String id;
        int parent;
        String file;
        String preview;
        String tags;
        String owner;
        int width;
        int height;
        int score;
        long created;
    }
}
