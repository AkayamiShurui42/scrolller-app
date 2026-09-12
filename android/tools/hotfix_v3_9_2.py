from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


taxonomy_path = Path("app/src/main/java/com/scrolller/adblock/ContentTaxonomy.java")
taxonomy = taxonomy_path.read_text()

old_return = '''        return mask;
    }

    static boolean matches(RedditPost post, int selectedMask) {'''
new_return = '''        // Most general NSFW communities do not explicitly write "straight" in
        // every title/flair. If metadata contains no explicit gay/lesbian/trans
        // signal, classify the content stream as the straight/general bucket.
        // Explicit LGBT metadata above always wins and prevents this fallback.
        if (mask == 0 && post.nsfw) mask = STRAIGHT;

        return mask;
    }

    static boolean matches(RedditPost post, int selectedMask) {'''
taxonomy = replace_once(taxonomy, old_return, new_return, "straight/general fallback")
taxonomy_path.write_text(taxonomy)

main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()
old_help = '''        body.addView(bodyText("Multi-select. Categories come only from explicit subreddit/title/flair metadata; the app never guesses identity from an image. No selection shows everything."));'''
new_help = '''        body.addView(bodyText("Multi-select. Gay / Lesbian and Trans require explicit subreddit/title/flair metadata. General NSFW content with no explicit LGBT tag falls into Straight so normal adult feeds do not disappear. The app never guesses identity from an image. No selection shows everything."));'''
main = replace_once(main, old_help, new_help, "people filter help")
main_path.write_text(main)

build_path = Path("app/build.gradle")
build = build_path.read_text()
build = replace_once(build, 'versionCode 48', 'versionCode 49', 'versionCode')
build = replace_once(build, 'versionName "3.9.1"', 'versionName "3.9.2"', 'versionName')
build_path.write_text(build)

print("Applied v3.9.2 people taxonomy hotfix")
