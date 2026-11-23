package com.scrolller.adblock;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public final class StreamViewportPolicyTest {
    @Test public void tallImageCanBeViewedWithoutSeeingItsFullHeight() {
        assertTrue(StreamViewportPolicy.qualifies(700, 3000, 1000, 1000L, true));
        assertFalse(StreamViewportPolicy.qualifies(500, 3000, 1000, 1000L, true));
    }

    @Test public void requiresUninterruptedDwellAndRenderedMedia() {
        assertFalse(StreamViewportPolicy.qualifies(850, 1200, 1000, 999L, true));
        assertFalse(StreamViewportPolicy.qualifies(850, 1200, 1000, 1000L, false));
        assertTrue(StreamViewportPolicy.qualifies(850, 1200, 1000, 1000L, true));
    }

    @Test public void rejectsInvalidOrImpossibleGeometry() {
        assertFalse(StreamViewportPolicy.qualifies(1, 0, 1000, 1000L, true));
        assertFalse(StreamViewportPolicy.qualifies(800, 900, 0, 1000L, true));
        assertFalse(StreamViewportPolicy.qualifies(-5, 900, 1000, 1000L, true));
    }

    @Test public void selectsOnlyTheBestRenderedAndVisibleItem() {
        int index = StreamViewportPolicy.selectActivePosition(Arrays.asList(
                new StreamViewportPolicy.VisibleItem(4, -800, 250, true),
                new StreamViewportPolicy.VisibleItem(5, 250, 950, true),
                new StreamViewportPolicy.VisibleItem(6, 950, 1900, true)),
                0, 1000);
        assertEquals(5, index);
    }

    @Test public void unrenderedOrMostlyClippedItemsCannotBecomeActive() {
        int index = StreamViewportPolicy.selectActivePosition(Arrays.asList(
                new StreamViewportPolicy.VisibleItem(10, 200, 800, false),
                new StreamViewportPolicy.VisibleItem(11, 900, 1800, true)),
                0, 1000);
        assertEquals(-1, index);
    }
}
