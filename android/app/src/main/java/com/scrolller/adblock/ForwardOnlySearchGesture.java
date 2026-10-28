package com.scrolller.adblock;

/**
 * Recognizes a backward finger drag in a vertical search feed.
 *
 * Moving a finger down normally moves the content toward older items
 * (backward). A search session consumes posts in one direction only, so a
 * backward gesture is blocked without affecting taps, horizontal controls
 * or an upward drag to advance.
 *
 * Pure Java so swipe semantics can be regression tested outside Android.
 */
final class ForwardOnlySearchGesture {
    private float downX;
    private float downY;
    private boolean started;
    private boolean blocking;

    void down(float x, float y) {
        downX = x;
        downY = y;
        started = true;
        blocking = false;
    }

    boolean shouldBlock(float x, float y, float touchSlop) {
        if (!started) return false;
        if (blocking) return true;
        float deltaY = y - downY;
        float deltaX = Math.abs(x - downX);
        if (deltaY > Math.max(0f, touchSlop) && deltaY > deltaX) {
            blocking = true;
        }
        return blocking;
    }

    boolean isBlocking() {
        return blocking;
    }

    void reset() {
        started = false;
        blocking = false;
    }
}
