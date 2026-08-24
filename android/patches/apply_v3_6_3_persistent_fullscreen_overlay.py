from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.3 patch target: {label}\n{old[:500]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

# The old fullscreen behavior forcibly hid app chrome every time ViewPager2
# selected another page. That made Save/Favorite controls disappear even after
# the user explicitly revealed them. Preserve the user's chosen chrome state
# across swipes instead. Fullscreen still starts hidden when entered; after that
# only an explicit tap toggles it.
s = replace_required(
    s,
    '''                if (layoutMode.equals("fullscreen") && fullscreenChromeVisible) {
                    setFullscreenChrome(false);
                }
                if (screen == Screen.HOME && !loading && !after.isEmpty()''',
    '''                // Preserve fullscreen chrome state across page changes.
                // Entering Fullscreen still starts hidden, but once the user taps
                // to reveal the overlay it stays visible until they tap to hide it.
                if (screen == Screen.HOME && !loading && !after.isEmpty()''',
    'remove automatic overlay hide on page selection')

path.write_text(s)
print('Applied v3.6.3 persistent fullscreen overlay state')
