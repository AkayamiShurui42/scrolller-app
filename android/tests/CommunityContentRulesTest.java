package com.scrolller.adblock;

final class RedditPost {
    String subreddit;
    String title;
    String searchMetadata;
    RedditPost(String subreddit, String title, String searchMetadata) {
        this.subreddit = subreddit;
        this.title = title;
        this.searchMetadata = searchMetadata;
    }
}

public final class CommunityContentRulesTest {
    private static void check(boolean expected, boolean actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + " got " + actual);
    }

    public static void main(String[] args) {
        check(true, CommunityContentRules.isRoleplay(
                new RedditPost("RoleplayPartnerSearch", "Looking for an RP partner", "")), "RP community");
        check(true, CommunityContentRules.isRoleplay(
                new RedditPost("dirtypenpals", "A request", "")), "Adult RP community");
        check(true, CommunityContentRules.isRoleplay(
                new RedditPost("somewhere", "Looking for a role play partner", "")), "Explicit title");
        check(true, CommunityContentRules.isRoleplay(
                new RedditPost("other", "Normal title", "Flair: Roleplay")), "Explicit flair");
        check(false, CommunityContentRules.isRoleplay(
                new RedditPost("technology", "Raspberry Pi works", "")), "RP shouldn't match Raspberry");
        check(false, CommunityContentRules.isRoleplay(
                new RedditPost("corporate", "This is sharp", "")), "RP shouldn't match substrings");
        check(false, CommunityContentRules.isRoleplay(
                new RedditPost("OnePlus", "A normal game", "")), "No RP false positive");
        check(false, CommunityContentRules.isRoleplay(null), "Null safety");
        if (CommunityContentRules.aiCommunities("Art & images", false).length < 8)
            throw new AssertionError("SFW art discovery seeds missing");
        if (CommunityContentRules.aiCommunities("Video & animation", false).length < 5)
            throw new AssertionError("SFW video discovery seeds missing");
        if (CommunityContentRules.aiCommunities("Art & images", true).length < 2)
            throw new AssertionError("NSFW art discovery seeds missing");
        if (CommunityContentRules.aiCommunities("Video & animation", true).length != 0)
            throw new AssertionError("Unverified adult video community seeds");
        System.out.println("Roleplay and AI registry: all assertions passed");
    }
}
