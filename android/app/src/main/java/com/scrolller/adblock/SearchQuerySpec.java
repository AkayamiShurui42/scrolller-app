package com.scrolller.adblock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Immutable search input interpretation. An r/ or u/ selector is a real scope
 * constraint, not part of the title keywords sent to Reddit. No Android
 * dependency so query semantics can be regression tested with plain javac.
 */
final class SearchQuerySpec {
    final String input;
    final String term;
    final String subreddit;
    final String author;
    final boolean browseSubreddit;
    final boolean authorHasTerm;

    private SearchQuerySpec(String input, String term, String subreddit,
                            String author, boolean browseSubreddit, boolean authorHasTerm) {
        this.input = input;
        this.term = term;
        this.subreddit = subreddit;
        this.author = author;
        this.browseSubreddit = browseSubreddit;
        this.authorHasTerm = authorHasTerm;
    }

    static SearchQuerySpec parse(String value) {
        String input = value == null ? "" : value.trim();
        String lower = input.toLowerCase(Locale.US);
        boolean subreddit = lower.startsWith("r/") || lower.startsWith("/r/");
        boolean author = lower.startsWith("u/") || lower.startsWith("/u/");
        if (subreddit || author) {
            String remainder = input.substring(lower.startsWith("/") ? 3 : 2);
            int space = remainder.indexOf(' ');
            String name = (space < 0 ? remainder : remainder.substring(0, space)).trim();
            if (name.matches("[A-Za-z0-9_]+")) {
                String terms = space < 0 ? "" : remainder.substring(space + 1).trim();
                if (subreddit) {
                    return new SearchQuerySpec(input, terms.isEmpty() ? name : terms,
                            name, "", terms.isEmpty(), false);
                }
                return new SearchQuerySpec(input, terms.isEmpty() ? name : terms,
                        "", name, false, !terms.isEmpty());
            }
        }
        return new SearchQuerySpec(input, input, "", "", false, false);
    }

    String redditTerm() {
        if (author.isEmpty()) return term;
        return "author:" + author + (authorHasTerm ? " " + term : "");
    }

    static List<String> suggestions(String typed, Collection<String> history,
                                     Collection<String> communities, int limit) {
        ArrayList<String> out = new ArrayList<>();
        if (typed == null || typed.trim().isEmpty() || limit <= 0) return out;
        String lower = typed.trim().toLowerCase(Locale.US);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        if (history != null) {
            // Recent search terms appear before subreddit suggestions.
            ArrayList<String> recent = new ArrayList<>(history);
            for (int i = recent.size() - 1; i >= 0; i--) {
                String candidate = recent.get(i);
                if (candidate == null || candidate.trim().isEmpty()) continue;
                if (candidate.toLowerCase(Locale.US).startsWith(lower)) result.add(candidate);
                if (result.size() >= limit) return new ArrayList<>(result);
            }
        }

        boolean explicitCommunity = lower.startsWith("r/") || lower.startsWith("/r/");
        String raw = explicitCommunity ? lower.substring(lower.startsWith("/") ? 3 : 2) : lower;
        if (communities != null && !raw.isEmpty()) {
            for (String value : communities) {
                if (value == null || value.trim().isEmpty()) continue;
                String name = value.trim();
                if (name.toLowerCase(Locale.US).startsWith(raw)) {
                    // Show plain names for plain queries so the dropdown's
                    // prefix filter doesn't hide every r/ result.
                    result.add(explicitCommunity ? "r/" + name : name);
                }
                if (result.size() >= limit) break;
            }
        }
        out.addAll(result);
        return out;
    }
}
