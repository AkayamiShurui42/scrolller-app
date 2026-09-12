package com.scrolller.adblock;

import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class RedditPost {
    public enum MediaKind { IMAGE, GALLERY, GIF, VIDEO, EXTERNAL }

    public final String id;
    public final String title;
    public final String author;
    public final String subreddit;
    public final String permalink;
    public final String sourceUrl;
    public final int score;
    public final int comments;
    public final long createdUtc;
    public boolean saved;
    public final boolean nsfw;
    public final MediaKind mediaKind;
    public final List<String> imageUrls;
    public String videoUrl;
    public final String posterUrl;
    public final int mediaWidth;
    public final int mediaHeight;
    public String searchMetadata = "";
    public String sourceOrigin = "reddit";
    public int durationSeconds = 0;

    RedditPost(
            String id,
            String title,
            String author,
            String subreddit,
            String permalink,
            String sourceUrl,
            int score,
            int comments,
            long createdUtc,
            boolean saved,
            boolean nsfw,
            MediaKind mediaKind,
            List<String> imageUrls,
            String videoUrl,
            String posterUrl,
            int mediaWidth,
            int mediaHeight
    ) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.subreddit = subreddit;
        this.permalink = permalink;
        this.sourceUrl = sourceUrl;
        this.score = score;
        this.comments = comments;
        this.createdUtc = createdUtc;
        this.saved = saved;
        this.nsfw = nsfw;
        this.mediaKind = mediaKind;
        this.imageUrls = imageUrls;
        this.videoUrl = videoUrl;
        this.posterUrl = posterUrl;
        this.mediaWidth = mediaWidth;
        this.mediaHeight = mediaHeight;
    }

    public static RedditPost fromChild(JSONObject child) {
        JSONObject data = child != null ? child.optJSONObject("data") : null;
        if (data == null || data.optBoolean("promoted") || data.optBoolean("is_sponsored")) return null;

        ParsedMedia media = parseMedia(data);
        if (media == null) {
            JSONArray crossposts = data.optJSONArray("crosspost_parent_list");
            if (crossposts != null && crossposts.length() > 0) {
                JSONObject parent = crossposts.optJSONObject(0);
                media = parseMedia(parent);
            }
        }
        if (media == null) return null;

        RedditPost post = new RedditPost(
                data.optString("name", ""),
                decode(data.optString("title", "")),
                data.optString("author", ""),
                data.optString("subreddit", ""),
                data.optString("permalink", ""),
                decode(data.optString("url_overridden_by_dest", data.optString("url", ""))),
                data.optInt("score", 0),
                data.optInt("num_comments", 0),
                (long) data.optDouble("created_utc", 0d),
                data.optBoolean("saved", false),
                data.optBoolean("over_18", false),
                media.kind,
                media.images,
                media.video,
                media.poster,
                media.width,
                media.height
        );
        post.searchMetadata = decode(data.optString("link_flair_text", "")) + " "
                + decode(data.optString("author_flair_text", "")) + " "
                + decode(data.optString("selftext", "")) + " "
                + data.optString("domain", "") + " "
                + data.optString("post_hint", "") + " "
                + data.optString("subreddit_name_prefixed", "");
        post.sourceOrigin = "reddit";
        post.durationSeconds = mediaDurationSeconds(data);
        return post;
    }

    public static RedditPost fromScrolller(JSONObject item) {
        if (item == null) return null;
        String redditPath = decode(item.optString("redditPath", ""));
        String redditId = redditIdFromPath(redditPath);
        if (redditId.isEmpty()) return null;

        ArrayList<String> album = new ArrayList<>();
        int width = 0;
        int height = 0;
        JSONArray albumContent = item.optJSONArray("albumContent");
        if (albumContent != null) {
            for (int i = 0; i < albumContent.length(); i++) {
                JSONObject media = albumContent.optJSONObject(i);
                JSONObject best = bestScrolllerSource(
                        media != null ? media.optJSONArray("mediaSources") : null);
                if (best == null) continue;
                String url = decode(best.optString("url", ""));
                if (url.isEmpty()) continue;
                album.add(url);
                if (width <= 0 || height <= 0) {
                    width = best.optInt("width", 0);
                    height = best.optInt("height", 0);
                }
            }
        }

        MediaKind kind;
        String video = "";
        String poster = "";
        String sourceUrl = "";
        if (album.size() > 1) {
            kind = MediaKind.GALLERY;
            sourceUrl = album.get(0);
            poster = album.get(0);
        } else {
            JSONObject best = bestScrolllerSource(item.optJSONArray("mediaSources"));
            if (best == null && album.size() == 1) {
                sourceUrl = album.get(0);
                width = Math.max(width, 0);
                height = Math.max(height, 0);
            } else if (best != null) {
                sourceUrl = decode(best.optString("url", ""));
                width = best.optInt("width", width);
                height = best.optInt("height", height);
            }
            if (sourceUrl.isEmpty()) return null;
            String lower = sourceUrl.toLowerCase();
            boolean stream = lower.matches(".*\\.(mp4|webm|m3u8)(\\?.*)?$");
            if (stream) {
                kind = item.optBoolean("hasAudio", false) ? MediaKind.VIDEO : MediaKind.GIF;
                video = sourceUrl;
                poster = "";
                album.clear();
            } else if (lower.matches(".*\\.gif(\\?.*)?$")) {
                kind = MediaKind.GIF;
                album.clear();
                album.add(sourceUrl);
                poster = sourceUrl;
            } else if (lower.matches(".*\\.(jpe?g|png|webp)(\\?.*)?$")) {
                kind = MediaKind.IMAGE;
                album.clear();
                album.add(sourceUrl);
                poster = sourceUrl;
            } else {
                return null;
            }
        }

        RedditPost post = new RedditPost(
                "t3_" + redditId,
                decode(item.optString("title", "")),
                item.optString("username", ""),
                item.optString("subredditTitle", ""),
                redditPath,
                sourceUrl,
                0,
                item.optInt("commentsCount", 0),
                0L,
                false,
                item.optBoolean("isNsfw", false),
                kind,
                album,
                video,
                poster,
                width,
                height);
        post.sourceOrigin = "scrolller";
        post.durationSeconds = Math.max(0,
                item.optInt("durationSeconds", item.optInt("duration", 0)));
        post.searchMetadata = decode(item.optString("description", "")) + " "
                + decode(item.optString("tags", ""));
        return post;
    }

    private static int mediaDurationSeconds(JSONObject d) {
        if (d == null) return 0;
        JSONObject secure = d.optJSONObject("secure_media");
        JSONObject media = d.optJSONObject("media");
        JSONObject video = secure != null ? secure.optJSONObject("reddit_video") : null;
        if (video == null && media != null) video = media.optJSONObject("reddit_video");
        JSONObject preview = d.optJSONObject("preview");
        if (video == null && preview != null) video = preview.optJSONObject("reddit_video_preview");
        if (video == null) return 0;
        return Math.max(0, video.optInt("duration", 0));
    }

    private static JSONObject bestScrolllerSource(JSONArray sources) {
        if (sources == null) return null;
        JSONObject best = null;
        long bestArea = -1L;
        boolean bestOptimized = true;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source == null || source.optString("url", "").isEmpty()) continue;
            long area = (long) Math.max(0, source.optInt("width", 0))
                    * Math.max(0, source.optInt("height", 0));
            boolean optimized = source.optBoolean("isOptimized", false);
            if (best == null || area > bestArea || (area == bestArea && bestOptimized && !optimized)) {
                best = source;
                bestArea = area;
                bestOptimized = optimized;
            }
        }
        return best;
    }

    private static String redditIdFromPath(String path) {
        if (path == null || path.isEmpty()) return "";
        String marker = "/comments/";
        int at = path.indexOf(marker);
        if (at < 0) return "";
        String tail = path.substring(at + marker.length());
        int slash = tail.indexOf('/');
        if (slash >= 0) tail = tail.substring(0, slash);
        return tail.replaceAll("[^A-Za-z0-9].*$", "");
    }

    private static ParsedMedia parseMedia(JSONObject d) {
        if (d == null) return null;

        if (d.optBoolean("is_gallery")) {
            JSONObject galleryData = d.optJSONObject("gallery_data");
            JSONArray items = galleryData != null ? galleryData.optJSONArray("items") : null;
            JSONObject metadata = d.optJSONObject("media_metadata");
            ArrayList<String> urls = new ArrayList<>();
            int width = 0;
            int height = 0;
            if (items != null && metadata != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    String mediaId = item != null ? item.optString("media_id", "") : "";
                    JSONObject m = metadata.optJSONObject(mediaId);
                    JSONObject source = m != null ? m.optJSONObject("s") : null;
                    if (source == null) continue;
                    String u = firstNonEmpty(source.optString("gif", ""), source.optString("u", ""), source.optString("mp4", ""));
                    if (!u.isEmpty()) {
                        urls.add(decode(u));
                        if (width <= 0 || height <= 0) {
                            width = positive(source.optInt("x", 0), m != null ? m.optInt("x", 0) : 0);
                            height = positive(source.optInt("y", 0), m != null ? m.optInt("y", 0) : 0);
                        }
                    }
                }
            }
            if (!urls.isEmpty()) return new ParsedMedia(MediaKind.GALLERY, urls, "", "", width, height);
        }

        JSONObject secureMedia = d.optJSONObject("secure_media");
        JSONObject media = d.optJSONObject("media");
        JSONObject redditVideo = secureMedia != null ? secureMedia.optJSONObject("reddit_video") : null;
        if (redditVideo == null && media != null) redditVideo = media.optJSONObject("reddit_video");
        JSONObject preview = d.optJSONObject("preview");
        JSONObject redditVideoPreview = preview != null ? preview.optJSONObject("reddit_video_preview") : null;
        boolean gifPreview = redditVideo == null
                && redditVideoPreview != null
                && !d.optBoolean("is_video", false);
        if (redditVideo == null) redditVideo = redditVideoPreview;
        if (redditVideo != null) {
            String video = decode(firstNonEmpty(
                    redditVideo.optString("dash_url", ""),
                    redditVideo.optString("hls_url", ""),
                    redditVideo.optString("fallback_url", "")
            ));
            if (!video.isEmpty()) {
                return new ParsedMedia(
                        gifPreview ? MediaKind.GIF : MediaKind.VIDEO,
                        new ArrayList<>(),
                        video,
                        previewImage(d),
                        positive(redditVideo.optInt("width", 0), previewWidth(d)),
                        positive(redditVideo.optInt("height", 0), previewHeight(d)));
            }
        }

        String direct = decode(firstNonEmpty(d.optString("url_overridden_by_dest", ""), d.optString("url", "")));
        String hint = d.optString("post_hint", "");
        String domain = d.optString("domain", "");
        int previewWidth = previewWidth(d);
        int previewHeight = previewHeight(d);

        if (direct.matches("(?i).*\\.(mp4|webm|m3u8)(\\?.*)?$")) {
            return new ParsedMedia(MediaKind.VIDEO, new ArrayList<>(), direct, previewImage(d), previewWidth, previewHeight);
        }

        if (direct.matches("(?i).*\\.gif(\\?.*)?$")) {
            ArrayList<String> one = new ArrayList<>();
            one.add(direct);
            return new ParsedMedia(MediaKind.GIF, one, "", direct, previewWidth, previewHeight);
        }

        if ("image".equals(hint)
                || direct.matches("(?i).*\\.(jpe?g|png|webp)(\\?.*)?$")
                || "i.redd.it".equalsIgnoreCase(domain)) {
            String image = !direct.isEmpty() ? direct : previewImage(d);
            if (!image.isEmpty()) {
                ArrayList<String> one = new ArrayList<>();
                one.add(image);
                return new ParsedMedia(MediaKind.IMAGE, one, "", image, previewWidth, previewHeight);
            }
        }

        // Reddit-hosted reddit_video_preview was already preferred above.
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

    private static JSONObject previewSource(JSONObject d) {
        JSONObject preview = d.optJSONObject("preview");
        JSONArray images = preview != null ? preview.optJSONArray("images") : null;
        JSONObject first = images != null ? images.optJSONObject(0) : null;
        return first != null ? first.optJSONObject("source") : null;
    }

    private static String previewImage(JSONObject d) {
        JSONObject source = previewSource(d);
        return decode(source != null ? source.optString("url", "") : "");
    }

    private static int previewWidth(JSONObject d) {
        JSONObject source = previewSource(d);
        return source != null ? source.optInt("width", 0) : 0;
    }

    private static int previewHeight(JSONObject d) {
        JSONObject source = previewSource(d);
        return source != null ? source.optInt("height", 0) : 0;
    }

    private static boolean isRedgifs(String url, String domain) {
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
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return "";
    }

    public static String decode(String text) {
        if (text == null) return "";
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString();
    }

    private static final class ParsedMedia {
        final MediaKind kind;
        final List<String> images;
        final String video;
        final String poster;
        final int width;
        final int height;

        ParsedMedia(MediaKind kind, List<String> images, String video, String poster, int width, int height) {
            this.kind = kind;
            this.images = images;
            this.video = video;
            this.poster = poster;
            this.width = width;
            this.height = height;
        }
    }
}
