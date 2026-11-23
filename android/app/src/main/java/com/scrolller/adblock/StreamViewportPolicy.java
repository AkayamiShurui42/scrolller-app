package com.scrolller.adblock;

import java.util.List;

/**
 * Pure geometry and visibility policy for Stream read classification.
 * Only one sufficiently visible, rendered row is considered at a time.
 */
final class StreamViewportPolicy {
    static final long MIN_DWELL_MS = 1000L;
    static final int MIN_VISIBLE_PERCENT = 60;

    static final class VisibleItem {
        final int position;
        final int top;
        final int bottom;
        final boolean rendered;

        VisibleItem(int position, int top, int bottom, boolean rendered) {
            this.position = position;
            this.top = top;
            this.bottom = bottom;
            this.rendered = rendered;
        }
    }

    private StreamViewportPolicy() {}

    static boolean qualifies(int visiblePx, int itemHeightPx, int viewportHeightPx,
                             long continuousVisibleMs, boolean mediaRendered) {
        if (!mediaRendered || visiblePx <= 0 || itemHeightPx <= 0 || viewportHeightPx <= 0
                || continuousVisibleMs < MIN_DWELL_MS) return false;
        int eligibleHeight = Math.min(itemHeightPx, viewportHeightPx);
        int clampedVisible = Math.min(visiblePx, eligibleHeight);
        return (long) clampedVisible * 100L
                >= (long) eligibleHeight * MIN_VISIBLE_PERCENT;
    }

    static int selectActivePosition(List<VisibleItem> visible,
                                    int viewportTop, int viewportBottom) {
        if (visible == null || viewportBottom <= viewportTop) return -1;
        int viewportHeight = viewportBottom - viewportTop;
        double viewportCenter = ((double) viewportTop + viewportBottom) / 2d;
        int bestPosition = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        int bestVisiblePx = 0;

        for (VisibleItem item : visible) {
            if (item == null || !item.rendered || item.position < 0
                    || item.bottom <= item.top) continue;
            int clipTop = Math.max(viewportTop, item.top);
            int clipBottom = Math.min(viewportBottom, item.bottom);
            int visiblePx = Math.max(0, clipBottom - clipTop);
            if (!qualifies(visiblePx, item.bottom - item.top,
                    viewportHeight, MIN_DWELL_MS, true)) continue;

            double visibleCenter = ((double) clipTop + clipBottom) / 2d;
            double distance = Math.abs(visibleCenter - viewportCenter);
            if (bestPosition < 0 || distance < bestDistance
                    || (distance == bestDistance && visiblePx > bestVisiblePx)
                    || (distance == bestDistance && visiblePx == bestVisiblePx
                        && item.position < bestPosition)) {
                bestPosition = item.position;
                bestDistance = distance;
                bestVisiblePx = visiblePx;
            }
        }
        return bestPosition;
    }
}
