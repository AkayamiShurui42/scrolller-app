package com.scrolller.adblock;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative metadata-based roleplay blocking and discovery seeds for media made with AI.
 * Never guess that a picture is AI-generated from its appearance.
 *
 * Reddit communities appear/disappear and may change NSFW status. The user-set post
 * content mode and each post's nsfw flag remain the final visibility checks.
 */
final class CommunityContentRules {
    // Curated generative-media communities, rather than general AI news/chatbot help.
    static final String[] AI_ART_SFW = {
            "aiArt", "AIGeneratedArt", "AIArtists", "AiArtShow", "ImagineAiArt",
            "midjourney", "StableDiffusion", "deepdream", "MediaSynthesis",
            "bigsleep", "generateforme", "sdforall", "GenAIGallery"
    };
    static final String[] AI_VIDEO_SFW = {
            "aivideos", "aivideo", "AIVideos_SFW", "AiVideos_NoRules",
            "AIVideoSpace", "AImovies", "AICartoonAndAnimation",
            "AI_Short_Films", "aifilmmaking", "aivideomaking", "aivids"
    };
    static final String[] AI_MUSIC_SFW = { "aiMusic" };
    static final String[] AI_ART_NSFW = {
            "sdnsfw", "unstable_diffusion", "NSFWGenerators"
    };

    private static final Set<String> ROLEPLAY_COMMUNITIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "roleplay", "roleplaying", "roleplaypartnersearch", "roleplayingforreddit",
                    "roleplaytoo", "writtenroleplay", "advanceliteraterp", "advliteraterp",
                    "discordgrouprp", "discordroleplay", "discordrp", "roleplaydiscord",
                    "roleplaygroup", "roleplaygroups", "roleplayheaven", "fandomroleplayers",
                    "fandomrp", "bad rper stories", "badrperstories", "roleplayooc",
                    "dirtypenpals", "nsfw_roleplay", "18above_roleplay",
                    "gayrp", "hentaiandroleplay"
            )));

    private CommunityContentRules() {}

    static boolean isRoleplay(RedditPost post) {
        if (post == null) return false;
        String sr = compact(post.subreddit);
        if (ROLEPLAY_COMMUNITIES.contains(sr)) return true;
        // Dedicated roleplay communities often append _rp or roleplay to a theme.
        if (sr.startsWith("roleplay") || sr.endsWith("roleplay")
                || sr.endsWith("roleplayers") || sr.endsWith("roleplaying")) return true;
        // Do not block arbitrary occurrences of "rp" (sharp, corporate, etc).
        String explicit = spaced(safe(post.title) + " " + safe(post.searchMetadata));
        return hasPhrase(explicit,
                "roleplay", "roleplaying", "role player", "role players",
                "role playing", "role play", "rp partner", "rp partners",
                "rp request", "rp search", "roleplay partner", "erp request",
                "erp partner", "erotic roleplay", "written rp", "literate rp");
    }

    static String[] aiCommunities(String media, boolean nsfw) {
        if (nsfw) return "Art & images".equals(media) ? AI_ART_NSFW : new String[0];
        switch (media) {
            case "Art & images": return AI_ART_SFW.clone();
            case "Video & animation": return AI_VIDEO_SFW.clone();
            case "Music": return AI_MUSIC_SFW.clone();
            default: return new String[0];
        }
    }

    // Case insensitive, bounded phrases. False positives are more frustrating
    // than an occasional uncaught post.
    private static boolean hasPhrase(String normalized, String... needles) {
        for (String needle : needles) if (normalized.contains(" " + needle + " ")) return true;
        return false;
    }

    private static String compact(String text) {
        return safe(text).toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static String spaced(String text) {
        return " " + safe(text).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
