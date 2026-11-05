package com.scrolller.adblock;

import java.util.Arrays;
import java.util.List;

public final class SearchQuerySpecTest {
    private static void eq(Object expected, Object got) {
        if (!expected.equals(got)) throw new AssertionError(expected + " != " + got);
    }

    public static void main(String[] args) {
        SearchQuerySpec normal = SearchQuerySpec.parse("chubby");
        eq("chubby", normal.term);
        eq("chubby", normal.redditTerm());
        eq("", normal.subreddit);
        eq("", normal.author);

        SearchQuerySpec sub = SearchQuerySpec.parse("r/PixelArt");
        eq("PixelArt", sub.subreddit);
        eq("PixelArt", sub.term);
        eq(true, sub.browseSubreddit);

        SearchQuerySpec subWithTerm = SearchQuerySpec.parse("r/PixelArt cyberpunk city");
        eq("PixelArt", subWithTerm.subreddit);
        eq("cyberpunk city", subWithTerm.term);
        eq(false, subWithTerm.browseSubreddit);

        SearchQuerySpec author = SearchQuerySpec.parse("u/exampleCreator waterfall");
        eq("exampleCreator", author.author);
        eq("author:exampleCreator waterfall", author.redditTerm());
        eq("waterfall", author.term);
        SearchQuerySpec authorOnly = SearchQuerySpec.parse("/u/exampleCreator");
        eq("author:exampleCreator", authorOnly.redditTerm());

        List<String> suggestions = SearchQuerySpec.suggestions("ch",
            Arrays.asList("landscape", "chubby", "chubby art"),
            Arrays.asList("ChubbyCats", "Nature"), 6);
        eq("chubby art", suggestions.get(0));
        eq("chubby", suggestions.get(1));
        eq(true, suggestions.contains("ChubbyCats"));

        eq("r/ChubbyCats", SearchQuerySpec.suggestions("r/ch",
            Arrays.asList("landscape"), Arrays.asList("ChubbyCats"), 4).get(0));
        eq(true, SearchQuerySpec.suggestions(" ", Arrays.asList("chubby"),
            Arrays.asList("ChubbyCats"), 5).isEmpty());
        System.out.println("SearchQuerySpec parsing, filters and autocomplete passed");
    }
}
