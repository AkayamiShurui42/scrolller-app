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
    public final String videoUrl;
    public final String posterUrl;
    public final int mediaWidth;
    public final int mediaHeight;

    private RedditPost(
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

        return new RedditPost(
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
