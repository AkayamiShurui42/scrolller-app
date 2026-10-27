package com.scrolller.adblock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SearchRandomOrderTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        long seed = 81828384L;
        List<String> first = new ArrayList<>(Arrays.asList(
                "t3_a", "t3_b", "t3_c", "t3_d"));
        first.sort((a,b) -> SearchRandomOrder.compare(a,b,seed));
        List<String> firstSnapshot = new ArrayList<>(first);

        // Pagination adds results but cannot change the order of old posts.
        List<String> combined = new ArrayList<>(first);
        combined.addAll(Arrays.asList("t3_e", "t3_f", "t3_g"));
        combined.sort((a,b) -> SearchRandomOrder.compare(a,b,seed));
        combined.removeIf(s -> !firstSnapshot.contains(s));
        check(combined.equals(firstSnapshot), "incremental random reorder");

        check(SearchRandomOrder.rank("t3_a", seed)
                        == SearchRandomOrder.rank("t3_a", seed),
                "same seed changes order");
        check(SearchRandomOrder.rank("t3_a", seed)
                        != SearchRandomOrder.rank("t3_a", seed + 1),
                "new search did not reshuffle");
        check(SearchRandomOrder.compare("t3_a", "t3_b", seed)
                        == -SearchRandomOrder.compare("t3_b", "t3_a", seed),
                "comparison not symmetric");
        System.out.println("SearchRandomOrder stable pagination and new-search reshuffle passed");
    }
}
