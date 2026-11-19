package com.scrolller.adblock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Avoid repeatedly requesting unavailable public providers; stores no search data. */
final class SearchProviderCooldown {
    private static final ConcurrentHashMap<String, Long> until = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> codes = new ConcurrentHashMap<>();
    private static final Pattern STATUS = Pattern.compile("\\bHTTP\\s+(\\d{3})\\b");

    static boolean allowed(String provider) {
        return System.nanoTime() / 1000000L >= until.getOrDefault(provider, 0L);
    }
    static int lastStatus(String provider) {
        return codes.getOrDefault(provider, 0);
    }
    static void failure(String provider, String detail) {
        Matcher matcher = STATUS.matcher(detail == null ? "" : detail);
        if (matcher.find()) {
            try { failure(provider, Integer.parseInt(matcher.group(1))); }
            catch (NumberFormatException ignored) {}
        }
    }
    static void failure(String provider, int status) {
        if (status < 400) return;
        codes.put(provider, status);
        long delay = status == 429 ? 60_000L
                : (status == 403 || status == 451) ? 90_000L
                : status >= 500 ? 60_000L
                : (status == 400 || status == 422) ? 20_000L : 10_000L;
        until.put(provider, System.nanoTime() / 1000000L + delay);
    }
    static void success(String provider) {
        until.remove(provider);
        codes.remove(provider);
    }
}
