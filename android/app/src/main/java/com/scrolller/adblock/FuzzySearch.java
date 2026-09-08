package com.scrolller.adblock;

import java.text.Normalizer;
import java.util.Locale;

final class FuzzySearch {
    private FuzzySearch() {}

    static String normalize(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.US)
                .trim();
        if (s.startsWith("/r/")) s = s.substring(3);
        else if (s.startsWith("r/")) s = s.substring(2);
        else if (s.startsWith("/u/")) s = s.substring(3);
        else if (s.startsWith("u/")) s = s.substring(2);
        return s.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    static int score(String query, String candidate) {
        String q = normalize(query);
        String c = normalize(candidate);
        if (q.isEmpty() || c.isEmpty()) return 0;
        if (q.equals(c)) return 1000;
        if (c.startsWith(q)) return 940;
        if (c.contains(q)) return 880;
        if (q.contains(c) && c.length() >= 3) return 820;

        int best = similarity(q, c);
        String[] qWords = q.split(" ");
        String[] cWords = c.split(" ");
        int matched = 0;
        int total = 0;
        for (String qw : qWords) {
            if (qw.isEmpty()) continue;
            total++;
            int wordBest = 0;
            for (String cw : cWords) {
                if (cw.isEmpty()) continue;
                if (qw.equals(cw)) wordBest = Math.max(wordBest, 1000);
                else if (cw.startsWith(qw) || qw.startsWith(cw)) wordBest = Math.max(wordBest, 900);
                else if (cw.contains(qw) || qw.contains(cw)) wordBest = Math.max(wordBest, 840);
                else wordBest = Math.max(wordBest, similarity(qw, cw));
            }
            if (wordBest >= thresholdFor(qw.length())) matched++;
            best = Math.max(best, wordBest);
        }
        if (total > 1 && matched == total) best = Math.max(best, 900);
        else if (total > 1 && matched > 0) best = Math.max(best, 650 + (200 * matched / total));
        return Math.min(1000, best);
    }

    static boolean matches(String query, String candidate) {
        String q = normalize(query);
        return score(q, candidate) >= thresholdFor(q.length());
    }

    static int thresholdFor(int queryLength) {
        if (queryLength <= 2) return 940;
        if (queryLength <= 4) return 760;
        if (queryLength <= 7) return 670;
        return 610;
    }

    private static int similarity(String a, String b) {
        if (a.equals(b)) return 1000;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1000;
        int distance = damerauLevenshtein(a, b);
        return Math.max(0, 1000 - (distance * 1000 / max));
    }

    private static int damerauLevenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(
                        Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, d[i - 2][j - 2] + 1);
                }
                d[i][j] = value;
            }
        }
        return d[a.length()][b.length()];
    }
}
