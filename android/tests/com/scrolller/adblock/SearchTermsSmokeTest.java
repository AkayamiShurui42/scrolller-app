package com.scrolller.adblock;

public final class SearchTermsSmokeTest {
    static void check(boolean success, String test) {
        if (!success) throw new AssertionError(test);
    }
    public static void main(String[] args) {
        SearchTerms and = SearchTerms.parse("red fox, snow");
        check(and.commaMode && and.required.size() == 2, "comma splitting");
        check(and.matches("A red fox", "snow"), "cross field AND");
        check(!and.matches("A red fox", "summer"), "missing term fails");
        check(SearchTerms.parse("fox, -snow").matches("fox winter"), "exclusion absent");
        check(!SearchTerms.parse("fox, -snow").matches("fox snow"), "exclusion present");
        check(SearchTerms.parse("forest, mountain, river").required.size() == 3, "three tags");
        check(SearchTerms.parse("'warm light', portrait").matches("portrait warm light"),
            "multiword phrase");
        check("red fox".equals(SearchTerms.seed("red fox, snow")), "no comma in archive seed");
        check(!SearchTerms.parse("single").commaMode, "single-term legacy");
        SearchProviderCooldown.failure("smoke", 521);
        check(!SearchProviderCooldown.allowed("smoke"), "source paused");
        check(SearchProviderCooldown.lastStatus("smoke") == 521, "status stored");
        SearchProviderCooldown.success("smoke");
        check(SearchProviderCooldown.allowed("smoke"), "source resumed");
        System.out.println("SearchTerms and cooldown tests passed");
    }
}
