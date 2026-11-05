package com.scrolller.adblock;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Media-level identity shared by Search, Home, stream/grid, and historical
 * sources. Only provider-specific media addresses create cross-post aliases.
 * Reddit post/permalink URLs are deliberately not treated as media URLs.
 */
final class ReadMediaIdentity {
    private ReadMediaIdentity() {}

    static Set<String> keys(String sourceUrl, String videoUrl,
                            String posterUrl, List<String> images) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        add(keys, sourceUrl);
        add(keys, videoUrl);
        if (images != null) for (String image : images) add(keys, image);
        // Poster URLs are included after primary media, never as fallback
        // post IDs; they can help relate preview and original image hosts.
        add(keys, posterUrl);
        return keys;
    }

    static boolean intersects(Collection<String> candidates, Set<String> hiddenKeys) {
        if (candidates == null || hiddenKeys == null || hiddenKeys.isEmpty()) return false;
        for (String key : candidates) if (hiddenKeys.contains(key)) return true;
        return false;
    }

    private static void add(Set<String> keys, String input) {
        if (input == null || input.trim().isEmpty()) return;
        String raw = input.trim().toLowerCase(Locale.US);
        if (raw.startsWith("redgifs:")) {
            String name = raw.substring("redgifs:".length()).trim();
            if (!name.isEmpty()) keys.add("redgifs:" + name);
            return;
        }
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null || path.isEmpty()) return;
            host = host.toLowerCase(Locale.US);
            path = path.toLowerCase(Locale.US);
            String[] segments = path.split("/");
            String first = "";
            String last = "";
            for (String segment : segments) {
                if (segment == null || segment.isEmpty()) continue;
                if (first.isEmpty()) first = segment;
                last = segment;
            }
            if (last.isEmpty()) return;

            if (host.equals("v.redd.it")) {
                if (!first.isEmpty()) keys.add("vreddit:" + first);
                return;
            }
            if (host.equals("i.redd.it") || host.equals("preview.redd.it")) {
                keys.add("reddit-image:" + last);
                return;
            }
            if (host.equals("i.imgur.com") || host.equals("imgur.com")
                    || host.equals("www.imgur.com")) {
                if (path.startsWith("/a/") || path.startsWith("/gallery/")) {
                    keys.add("imgur-album:" + last);
                } else if (!last.equals("gallery")) {
                    keys.add("imgur:" + withoutExtension(last));
                }
                return;
            }
            if (host.equals("redgifs.com") || host.equals("www.redgifs.com")) {
                if (path.startsWith("/watch/") || path.startsWith("/ifr/")) {
                    keys.add("redgifs:" + last);
                }
                return;
            }
            if (host.endsWith(".redgifs.com") || host.endsWith("redgifsusercontent.com")) {
                String media = withoutExtension(last)
                        .replaceAll("-(mobile|sd|hd|480p|720p|1080p)$", "");
                if (!media.isEmpty()) keys.add("redgifs-file:" + media);
                return;
            }
            // Never identify an arbitrary Reddit permalink, subreddit, or
            // external article as a media asset. Only a likely media file path.
            if (!last.matches(".*\\.(jpe?g|png|webp|avif|gif|gifv|mp4|webm|m3u8)$")) {
                return;
            }
            if (host.endsWith("reddit.com") || host.equals("redd.it")) return;
            keys.add("media-url:" + host + path);
        } catch (IllegalArgumentException ignored) {
            // A bad or provider-specific URL cannot break the read/hide pipeline.
        }
    }

    private static String withoutExtension(String name) {
        return name.replaceFirst("\\.(jpe?g|png|webp|avif|gif|gifv|mp4|webm|m3u8)$", "");
    }
}
