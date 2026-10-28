package com.scrolller.adblock;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

public final class ReadMediaIdentityTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Set<String> initial = ReadMediaIdentity.keys(
                "https://v.redd.it/Foo42", "https://v.redd.it/foo42/DASH_720.mp4?source=fallback",
                "", Collections.emptyList());
        check(initial.size() == 1 && initial.contains("vreddit:foo42"),
                "Reddit video resolutions must share a key");
        check(ReadMediaIdentity.intersects(
                ReadMediaIdentity.keys("https://v.redd.it/foo42/DASH_480.mp4",
                        "", "", null), initial), "same video missing across feeds");

        Set<String> originalImage = ReadMediaIdentity.keys(
                "https://i.redd.it/Fooabc.jpg", "", "",
                Collections.singletonList("https://preview.redd.it/fooabc.jpg?width=640"));
        check(originalImage.contains("reddit-image:fooabc.jpg"),
                "Reddit image preview and original should match");

        Set<String> imgur = ReadMediaIdentity.keys("https://i.imgur.com/ABC123.gifv",
                "", "", Collections.emptyList());
        check(ReadMediaIdentity.intersects(imgur,
                ReadMediaIdentity.keys("https://i.imgur.com/abc123.mp4",
                        "", "", null)), "Imgur format variants should match");

        Set<String> redgifs = ReadMediaIdentity.keys("https://www.redgifs.com/watch/CoolSlug",
                "https://thumbs2.redgifs.com/Example-mobile.mp4",
                "", Collections.emptyList());
        check(redgifs.contains("redgifs:coolslug")
                        && redgifs.contains("redgifs-file:example"),
                "Redgifs watch and media identities must be stored");
        check(ReadMediaIdentity.intersects(redgifs,
                ReadMediaIdentity.keys("", "https://thumbs2.redgifs.com/Example-hd.mp4",
                        "", null)), "Redgifs media quality variants must match");

        check(ReadMediaIdentity.keys(
                "https://www.reddit.com/r/cats/comments/abc/post/", "", "", null).isEmpty(),
                "Reddit permalink is not a media key");
        check(ReadMediaIdentity.keys("https://example.org/article/123", "", "", null).isEmpty(),
                "Arbitrary articles must not share media identities");
        check(!ReadMediaIdentity.intersects(
                ReadMediaIdentity.keys("https://i.redd.it/a.jpg", "", "", null),
                ReadMediaIdentity.keys("https://i.redd.it/b.jpg", "", "", null)),
                "Unrelated images must remain separate");
        check(ReadMediaIdentity.keys(null, "", "", Arrays.asList(null, "")).isEmpty(),
                "missing media should not generate ambiguous identity");
        System.out.println("ReadMediaIdentity aliases, safety and cross-source matching passed");
    }
}
