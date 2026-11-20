package com.scrolller.adblock;
import org.junit.Test;
import static org.junit.Assert.*;
public class MultiFeedBatchPolicyTest {
    @Test public void emptyFeedAdvancesPastInitialBatch() {
        assertTrue(MultiFeedBatchPolicy.continueEmptyBatch(0, 12, 200, false));
        assertTrue(MultiFeedBatchPolicy.continueEmptyBatch(0, 24, 200, false));
    }
    @Test public void boundedScanStopsWhenResultsOrFailureOrLimit() {
        assertFalse(MultiFeedBatchPolicy.continueEmptyBatch(1, 12, 200, false));
        assertFalse(MultiFeedBatchPolicy.continueEmptyBatch(0, 12, 200, true));
        assertFalse(MultiFeedBatchPolicy.continueEmptyBatch(0, 72, 200, false));
        assertFalse(MultiFeedBatchPolicy.continueEmptyBatch(0, 12, 12, false));
    }
}
