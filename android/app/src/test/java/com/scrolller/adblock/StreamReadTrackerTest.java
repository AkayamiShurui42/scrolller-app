package com.scrolller.adblock;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StreamReadTrackerTest {
    @Test public void qualifiesOnceAfterARealContinuousSecond() {
        StreamReadTracker tracker = new StreamReadTracker();
        assertNull(tracker.sample("t3_A", 700, 2000, 1000, true, true, 100L));
        assertNull(tracker.sample("t3_A", 700, 2000, 1000, true, true, 1099L));
        assertEquals("t3_A", tracker.sample("t3_A", 700, 2000, 1000, true, true, 1100L));
        assertNull(tracker.sample("t3_A", 700, 2000, 1000, true, true, 1300L));
        assertTrue(tracker.hasQualified("t3_A"));
    }

    @Test public void aScrollResetsTheDwellClock() {
        StreamReadTracker tracker = new StreamReadTracker();
        tracker.sample("p", 800, 900, 1000, true, true, 0L);
        tracker.sample("p", 800, 900, 1000, true, false, 900L);
        assertNull(tracker.sample("p", 800, 900, 1000, true, true, 1000L));
        assertNull(tracker.sample("p", 800, 900, 1000, true, true, 1999L));
        assertEquals("p", tracker.sample("p", 800, 900, 1000, true, true, 2000L));
    }

    @Test public void switchingPostsOrLosingRenderedMediaResetsClock() {
        StreamReadTracker tracker = new StreamReadTracker();
        tracker.sample("a", 900, 900, 1000, true, true, 0L);
        tracker.sample("b", 900, 900, 1000, true, true, 900L);
        assertNull(tracker.sample("b", 900, 900, 1000, false, true, 1100L));
        assertNull(tracker.sample("b", 900, 900, 1000, true, true, 1200L));
        assertEquals("b", tracker.sample("b", 900, 900, 1000, true, true, 2200L));
        assertFalse(tracker.hasQualified("a"));
    }

    @Test public void undoMakesPreviouslyViewedPostEligibleAgain() {
        StreamReadTracker tracker = new StreamReadTracker();
        assertNull(tracker.sample("t3_undo", 800, 900, 1000, true, true, 0L));
        assertEquals("t3_undo",
                tracker.sample("t3_undo", 800, 900, 1000, true, true, 1000L));
        tracker.forget("t3_undo");
        assertFalse(tracker.hasQualified("t3_undo"));
        assertNull(tracker.sample("t3_undo", 800, 900, 1000, true, true, 1100L));
        assertEquals("t3_undo",
                tracker.sample("t3_undo", 800, 900, 1000, true, true, 2100L));
    }

    @Test public void interruptedVisibilityCannotAccumulateAcrossVisits() {
        StreamReadTracker tracker = new StreamReadTracker();
        tracker.sample("a", 800, 900, 1000, true, true, 0L);
        tracker.sample(null, 0, 0, 1000, false, false, 800L);
        assertNull(tracker.sample("a", 800, 900, 1000, true, true, 1000L));
        assertEquals("a", tracker.sample("a", 800, 900, 1000, true, true, 2000L));
    }
}
