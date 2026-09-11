from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old pattern, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/scrolller/adblock/MainActivity.java")
main = main_path.read_text()

old_menu = '''        body.addView(where);

        Button browse = sheetButton("Browse  ›");'''
new_menu = '''        body.addView(where);

        if (screen == Screen.HOME && context.equals("subreddit")
                && subreddit != null && !subreddit.isEmpty()) {
            boolean subscribed = subscriptionNames.contains(subreddit.toLowerCase(Locale.US));
            Button membership = sheetButton((subscribed ? "Leave / unsubscribe " : "Join / subscribe ")
                    + "r/" + subreddit);
            if (subscribed) membership.setTextColor(0xFFFFB0B0);
            body.addView(membership, sectionButtonParams());
            membership.setOnClickListener(v -> {
                dialog.dismiss();
                toggleSubredditSubscription();
            });
        }

        Button browse = sheetButton("Browse  ›");'''
main = replace_once(main, old_menu, new_menu, "compact membership action")

old_browse = '''        Button home = sheetButton("Home");
        Button subredditButton = sheetButton("Open subreddit…");
        Button categories = sheetButton("NSFW categories…");
        body.addView(home, sectionButtonParams());
        body.addView(subredditButton, sectionButtonParams());
        body.addView(categories, sectionButtonParams());'''
new_browse = '''        Button home = sheetButton("Home");
        Button subredditButton = sheetButton("Open subreddit…");
        Button categories = sheetButton("NSFW categories…");
        body.addView(home, sectionButtonParams());
        if (screen == Screen.HOME && context.equals("subreddit")
                && subreddit != null && !subreddit.isEmpty()) {
            boolean subscribed = subscriptionNames.contains(subreddit.toLowerCase(Locale.US));
            Button membership = sheetButton((subscribed ? "Leave / unsubscribe " : "Join / subscribe ")
                    + "r/" + subreddit);
            if (subscribed) membership.setTextColor(0xFFFFB0B0);
            body.addView(membership, sectionButtonParams());
            membership.setOnClickListener(v -> {
                dialog.dismiss();
                toggleSubredditSubscription();
            });
        }
        body.addView(subredditButton, sectionButtonParams());
        body.addView(categories, sectionButtonParams());'''
main = replace_once(main, old_browse, new_browse, "browse membership action")

old_button_text = '''            subscribeButton.setText(subscribed ? "Unsubscribe" : "Subscribe");'''
new_button_text = '''            subscribeButton.setText(subscribed ? "Leave" : "Join");'''
main = replace_once(main, old_button_text, new_button_text, "legacy membership labels")

old_result = '''            if (currentlySubscribed) {
                subscriptionNames.remove(key);
                for (int i = subscriptions.size() - 1; i >= 0; i--) {
                    if (subscriptions.get(i).name.equalsIgnoreCase(target)) {
                        subscriptions.remove(i);
                    }
                }
            } else if (subscriptionNames.add(key)) {
                subscriptions.add(new Subscription(target, ""));
                subscriptions.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            }

            updateChrome();'''
new_result = '''            if (currentlySubscribed) {
                subscriptionNames.remove(key);
                for (int i = subscriptions.size() - 1; i >= 0; i--) {
                    if (subscriptions.get(i).name.equalsIgnoreCase(target)) {
                        subscriptions.remove(i);
                    }
                }
            } else if (subscriptionNames.add(key)) {
                subscriptions.add(new Subscription(target, ""));
                subscriptions.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            }

            setStatus((currentlySubscribed ? "Left r/" : "Joined r/") + target, false);
            updateChrome();'''
main = replace_once(main, old_result, new_result, "membership confirmation")

main_path.write_text(main)

build_path = Path("app/build.gradle")
build = build_path.read_text()
if 'versionCode 46' not in build:
    build = replace_once(build, 'versionCode 45', 'versionCode 46', 'versionCode')
if 'versionName "3.8.12"' not in build:
    build = replace_once(build, 'versionName "3.8.11"', 'versionName "3.8.12"', 'versionName')
build_path.write_text(build)

print("Applied v3.8.12 subreddit membership hotfix")
