package com.scrolller.adblock;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Lightweight network-level ad/tracker blocker for the embedded Scrolller website. */
public final class AdBlocker {
    private static final String[] BLOCKED_HOST_SUFFIXES = new String[] {
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adnxs.com",
            "adsrvr.org",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "outbrain.com",
            "amazon-adsystem.com"
    };

    private static final String[] BLOCKED_URL_PARTS = new String[] {
            "/adsbygoogle.js",
            "/pagead/",
            "/adservice/",
            "/adserver/",
            "/advertisement/"
    };

    private AdBlocker() {}

    public static WebResourceResponse maybeBlock(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String lowerUrl = url.toLowerCase(Locale.US);
            if (host != null) {
                String lowerHost = host.toLowerCase(Locale.US);
                for (String suffix : BLOCKED_HOST_SUFFIXES) {
                    if (lowerHost.equals(suffix) || lowerHost.endsWith("." + suffix)) {
                        return emptyResponse();
                    }
                }
            }
            for (String part : BLOCKED_URL_PARTS) {
                if (lowerUrl.contains(part)) return emptyResponse();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse(
                "text/plain",
                StandardCharsets.UTF_8.name(),
                new ByteArrayInputStream(new byte[0])
        );
    }
}
