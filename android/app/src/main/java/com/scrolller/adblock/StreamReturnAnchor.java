package com.scrolller.adblock;

import java.util.List;
import java.util.Locale;

/** Original Stream viewport anchor for a temporary Fullscreen media inspection. */
final class StreamReturnAnchor {
    private final String postId;
    private final int originalPosition;
    private final int topOffsetPx;

    StreamReturnAnchor(String postId, int originalPosition, int topOffsetPx) {
        this.postId = normalize(postId);
        this.originalPosition = originalPosition;
        this.topOffsetPx = topOffsetPx;
    }

    int getTopOffsetPx() {
        return topOffsetPx;
    }

    int findPosition(List<String> currentPostIds) {
        if (currentPostIds == null || currentPostIds.isEmpty()) return -1;
        if (!postId.isEmpty()) {
            for (int i = 0; i < currentPostIds.size(); i++) {
                if (postId.equals(normalize(currentPostIds.get(i)))) return i;
            }
        }
        return Math.max(0, Math.min(originalPosition, currentPostIds.size() - 1));
    }

    private static String normalize(String id) {
        if (id == null) return "";
        String normalized = id.trim().toLowerCase(Locale.US);
        return normalized.startsWith("t3_") ? normalized.substring(3) : normalized;
    }
}
