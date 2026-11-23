package com.scrolller.adblock;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure, clock-injected dwell tracker for Stream. It does no persistence or I/O.
 * MainActivity owns the final read/hide decision and saved-post exemptions.
 */
final class StreamReadTracker {
    private final Set<String> qualified = new HashSet<>();
    private String currentId;
    private long sinceMs = -1L;

    String sample(String postId, int visiblePx, int itemHeightPx, int viewportHeightPx,
                  boolean mediaRendered, boolean stationary, long nowMs) {
        if (postId == null || postId.isEmpty() || !stationary || !mediaRendered
                || nowMs < 0 || !StreamViewportPolicy.qualifies(
                        visiblePx, itemHeightPx, viewportHeightPx,
                        StreamViewportPolicy.MIN_DWELL_MS, true)) {
            resetContinuity();
            return null;
        }
        if (!postId.equals(currentId) || sinceMs < 0 || nowMs < sinceMs) {
            currentId = postId;
            sinceMs = nowMs;
            return null;
        }
        if (qualified.contains(postId)) return null;
        if (StreamViewportPolicy.qualifies(visiblePx, itemHeightPx, viewportHeightPx,
                nowMs - sinceMs, true)) {
            qualified.add(postId);
            return postId;
        }
        return null;
    }

    boolean hasQualified(String postId) {
        return postId != null && qualified.contains(postId);
    }

    void resetContinuity() {
        currentId = null;
        sinceMs = -1L;
    }
}
