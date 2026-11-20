package com.scrolller.adblock;
/** Bounded automatic scanning for empty multi-community feeds. */
final class MultiFeedBatchPolicy {
    static final int BATCH_SIZE = 12;
    static final int MAX_AUTO_EMPTY_CHECKS = 72;
    static final int MAX_ARCHIVE_ERRORS = 3;
    private MultiFeedBatchPolicy() {}
    static boolean continueEmptyBatch(int visible, int checked, int total, boolean sourcesUnavailable) {
        return visible == 0 && checked < total && checked < MAX_AUTO_EMPTY_CHECKS
                && !sourcesUnavailable;
    }
}
