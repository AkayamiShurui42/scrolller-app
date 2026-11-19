package com.scrolller.adblock;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Content-neutral local AND matching for comma-separated search tags. */
final class SearchTerms {
    final List<String> required;
    final List<String> excluded;
    final boolean commaMode;

    private SearchTerms(List<String> required, List<String> excluded, boolean commaMode) {
        this.required = Collections.unmodifiableList(required);
        this.excluded = Collections.unmodifiableList(excluded);
        this.commaMode = commaMode;
    }

    static SearchTerms parse(String input) {
        String raw = input == null ? "" : input.trim();
        boolean commas = raw.contains(",");
        ArrayList<String> must = new ArrayList<>();
        ArrayList<String> not = new ArrayList<>();
        if (commas) {
            for (String token : raw.split(",", -1)) {
                String clause = token.trim();
                if (clause.isEmpty()) continue;
                boolean negative = clause.startsWith("-");
                if (negative) clause = clause.substring(1).trim();
                if (clause.length() >= 2 && ((clause.startsWith("\"") && clause.endsWith("\""))
                        || (clause.startsWith("'") && clause.endsWith("'")))) {
                    clause = clause.substring(1, clause.length() - 1);
                }
                String text = normalize(clause);
                if (text.isEmpty()) continue;
                ArrayList<String> list = negative ? not : must;
                if (!list.contains(text) && list.size() < 20) list.add(text);
            }
        }
        return new SearchTerms(must, not, commas);
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    boolean matches(String... metadata) {
        StringBuilder all = new StringBuilder(" ");
        for (String value : metadata) {
            String tokenized = normalize(value);
            if (!tokenized.isEmpty()) all.append(tokenized).append(' ');
        }
        String text = all.toString();
        for (String part : required) if (!text.contains(" " + part + " ")) return false;
        for (String part : excluded) if (text.contains(" " + part + " ")) return false;
        return true;
    }

    /** Candidate retrieval can use first positive term, local matcher enforces all terms. */
    static String seed(String input) {
        SearchTerms terms = parse(input);
        if (!terms.commaMode) return input == null ? "" : input.trim();
        return terms.required.isEmpty() ? "" : terms.required.get(0);
    }
}
