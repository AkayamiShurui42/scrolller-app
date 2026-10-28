package com.scrolller.adblock;

public final class ForwardOnlySearchGestureTest {
    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ForwardOnlySearchGesture gesture = new ForwardOnlySearchGesture();
        float slop = 12f;

        gesture.down(100, 100);
        check(!gesture.shouldBlock(100, 96, slop), "small forward move must pass");
        check(!gesture.shouldBlock(100, 50, slop), "upward swipe must advance");
        check(!gesture.isBlocking(), "forward swipe must not latch");

        gesture.reset();
        gesture.down(50, 80);
        check(!gesture.shouldBlock(50, 90, slop), "touch slop must be respected");
        check(gesture.shouldBlock(50, 110, slop), "downward swipe must be blocked");
        check(gesture.shouldBlock(50, 40, slop), "a blocked gesture stays blocked");
        gesture.reset();
        check(!gesture.shouldBlock(50, 110, slop), "reset must clear the touch");

        gesture.down(30, 30);
        check(!gesture.shouldBlock(90, 40, slop), "horizontal gallery swipe must pass");
        check(!gesture.shouldBlock(33, 36, slop), "taps must pass");
        check(gesture.shouldBlock(32, 60, slop), "vertical backward drag must block");

        System.out.println("ForwardOnlySearchGesture upward advance, backward block, "
                + "horizontal controls and touch-slop tests passed");
    }
}
