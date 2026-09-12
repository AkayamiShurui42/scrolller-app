package com.scrolller.adblock;

import java.util.Locale;

/**
 * Metadata-only adult-content taxonomy.
 *
 * This deliberately does not infer a person's sexuality or gender identity from
 * an image. A category is attached only when Reddit/Scrolller metadata, a
 * subreddit name, a title, flair, or other explicit text says so.
 */
final class ContentTaxonomy {
    static final int STRAIGHT = 1;
    static final int GAY_LESBIAN = 1 << 1;
    static final int TRANS = 1 << 2;
    static final int ALL = STRAIGHT | GAY_LESBIAN | TRANS;

    private ContentTaxonomy() {}

    static int classify(RedditPost post) {
        if (post == null) return 0;
        String text = normalize(
                safe(post.subreddit) + " "
                        + safe(post.title) + " "
                        + safe(post.searchMetadata) + " "
                        + safe(post.permalink));
        String community = compact(post.subreddit);
        int mask = 0;

        if (containsAny(text,
                " straight ", " heterosexual ", " hetero ",
                " boy girl ", " girl boy ", " man woman ", " woman man ",
                " male female ", " female male ", " husband wife ", " wife husband ",
                " m4f ", " f4m ", " m f ", " f m ")
                || containsCompactAny(community,
                "straightporn", "straightsex", "heteroporn", "heterosex",
                "boygirl", "girlboy", "m4f", "f4m")) {
            mask |= STRAIGHT;
        }

        if (containsAny(text,
                " gay ", " gays ", " lesbian ", " lesbians ", " sapphic ",
                " wlw ", " women loving women ", " men loving men ",
                " homosexual ", " homosexuality ", " gay men ", " gay women ")
                || containsCompactAny(community,
                "gayporn", "gaybrosgonewild", "gaybros", "gaymen", "gaysex",
                "lesbian", "lesbians", "lesbianporn", "dykesgonewild", "sapphic", "wlw")) {
            mask |= GAY_LESBIAN;
        }

        if (containsAny(text,
                " trans ", " transgender ", " transsexual ",
                " trans woman ", " trans women ", " trans girl ", " trans girls ",
                " trans man ", " trans men ", " trans guy ", " trans guys ",
                " mtf ", " ftm ", " transfem ", " trans fem ",
                " transmasc ", " trans masc ", " shemale ", " shemales ")
                || containsCompactAny(community,
                "trans", "transporn", "transgonewild", "transgirls", "transwomen",
                "transmen", "mtf", "ftm", "shemale")) {
            mask |= TRANS;
        }

        return mask;
    }

    static boolean matches(RedditPost post, int selectedMask) {
        if (selectedMask == 0) return true;
        return (classify(post) & selectedMask) != 0;
    }

    static String label(int mask) {
        if (mask == 0) return "All / unfiltered";
        StringBuilder out = new StringBuilder();
        if ((mask & STRAIGHT) != 0) append(out, "Straight");
        if ((mask & GAY_LESBIAN) != 0) append(out, "Gay / Lesbian");
        if ((mask & TRANS) != 0) append(out, "Trans");
        return out.toString();
    }

    private static void append(StringBuilder out, String value) {
        if (out.length() > 0) out.append(" + ");
        out.append(value);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static boolean containsCompactAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String normalize(String value) {
        String clean = safe(value).toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return " " + clean + " ";
    }

    private static String compact(String value) {
        return safe(value).toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
