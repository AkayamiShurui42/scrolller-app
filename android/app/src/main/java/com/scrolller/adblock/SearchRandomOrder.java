package com.scrolller.adblock;

/**
 * A stable randomized order per search submission.
 * Adding page two to page one cannot reorder page-one results.
 */
final class SearchRandomOrder {
    private SearchRandomOrder() {}

    static long rank(String postKey, long seed) {
        String key = postKey == null ? "" : postKey;
        long hash = seed ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001B3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        return hash ^ (hash >>> 33);
    }

    static int compare(String leftPostKey, String rightPostKey, long seed) {
        long left = rank(leftPostKey, seed);
        long right = rank(rightPostKey, seed);
        int result = Long.compareUnsigned(left, right);
        if (result != 0) return result;
        return (leftPostKey == null ? "" : leftPostKey)
                .compareTo(rightPostKey == null ? "" : rightPostKey);
    }
}
