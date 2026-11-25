package com.scrolller.adblock;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class StreamReturnAnchorTest {
    @Test public void returnsToOpenedPostWithOriginalPixelOffset() {
        StreamReturnAnchor anchor = new StreamReturnAnchor("t3_second", 1, -125);
        assertEquals(1, anchor.findPosition(Arrays.asList("t3_first", "t3_second", "t3_third")));
        assertEquals(-125, anchor.getTopOffsetPx());
    }

    @Test public void followsPostIdentityIfItemsWereInsertedBeforeIt() {
        StreamReturnAnchor anchor = new StreamReturnAnchor("t3_b", 1, 20);
        assertEquals(3, anchor.findPosition(Arrays.asList("t3_new", "t3_a", "t3_z", "t3_b")));
    }

    @Test public void fallsBackSafelyIfOpenedPostWasRemoved() {
        StreamReturnAnchor anchor = new StreamReturnAnchor("t3_removed", 3, -30);
        assertEquals(1, anchor.findPosition(Arrays.asList("t3_a", "t3_b")));
        assertEquals(-1, anchor.findPosition(Collections.emptyList()));
    }

    @Test public void normalizesBareRedditPostId() {
        StreamReturnAnchor anchor = new StreamReturnAnchor("t3_ABC", 0, 0);
        assertEquals(1, anchor.findPosition(Arrays.asList("t3_xyz", "abc")));
    }

    @Test public void neverRestoresUnrelatedNegativePosition() {
        StreamReturnAnchor anchor = new StreamReturnAnchor("", -1, 4);
        assertEquals(0, anchor.findPosition(Arrays.asList("a", "b")));
    }
}
