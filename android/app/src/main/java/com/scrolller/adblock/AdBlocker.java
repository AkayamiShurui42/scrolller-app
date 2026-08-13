package com.scrolller.adblock;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Strong network filter based on the older working Scrolller build.
 *
 * Rather than trying to enumerate every ad network, normal page/media resources
 * are allowlisted. Common ad/analytics hosts receive a harmless successful JS
 * response so Scrolller's anti-adblock checks do not interpret a hard failure as
 * a blocker signal.
 */
public final class AdBlocker {
    private static final String[] ALLOWED_HOST_SUFFIXES = new String[] {
            "scrolller.com",
            "reddit.com",
            "redd.it",
            "redgifs.com",
            "imgur.com",
            "gfycat.com",
            "googleapis.com",
            "gstatic.com",
            "google.com",
            "googleusercontent.com"
    };

    private static final String[] MOCK_SCRIPT_HOST_SUFFIXES = new String[] {
            "doubleclick.net",
            "googlesyndication.com",
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com"
    };

    private static final String[] BLOCKED_URL_PARTS = new String[] {
            "/adsbygoogle.js",
            "/pagead/",
            "/adservice/",
            "/adserver/",
            "/advertisement/",
            "/prebid/",
            "/prebid.js",
            "/gpt.js",
            "/pubads_",
            "exoclick",
            "juicyads",
            "realsrv",
            "popads",
            "trafficjunky",
            "onclickads",
            "cant3am"
    };

    private AdBlocker() {}

    public static WebResourceResponse maybeBlock(String url) {
        if (url == null || url.isEmpty()) return null;

        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String lowerUrl = url.toLowerCase(Locale.US);

            // Local/blob/data resources are not network ads and must remain usable.
            if (scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            if (host == null || host.isEmpty()) return null;

            String lowerHost = host.toLowerCase(Locale.US);

            // Older working behavior: satisfy common anti-adblock probes with a
            // successful, empty JavaScript resource instead of a network error.
            if (matchesSuffix(lowerHost, MOCK_SCRIPT_HOST_SUFFIXES)) {
                return mockScriptResponse();
            }

            // Catch first-party or unusual-path ad resources before the allowlist.
            for (String part : BLOCKED_URL_PARTS) {
                if (lowerUrl.contains(part)) return emptyResponse();
            }

            // The older build's important difference: everything outside known
            // Scrolller/content/login domains is blocked by default.
            if (!matchesSuffix(lowerHost, ALLOWED_HOST_SUFFIXES)) {
                return emptyResponse();
            }
        } catch (Exception ignored) {
            return emptyResponse();
        }

        return null;
    }

    private static boolean matchesSuffix(String host, String[] suffixes) {
        for (String suffix : suffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private static WebResourceResponse mockScriptResponse() {
        byte[] body = "/* Scrolller Pro: ad probe satisfied */".getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse(
                "application/javascript",
                StandardCharsets.UTF_8.name(),
                new ByteArrayInputStream(body)
        );
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse(
                "text/plain",
                StandardCharsets.UTF_8.name(),
                new ByteArrayInputStream(new byte[0])
        );
    }
}
