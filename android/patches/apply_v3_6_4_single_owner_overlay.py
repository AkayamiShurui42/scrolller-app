from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.4 patch target: {label}\n{old[:620]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
p = path.read_text()

# The parent TapFrameLayout is the sole owner of revealing fullscreen chrome.
# Child media click listeners caused double toggles and inconsistent behavior
# across ImageView, PlayerView and nested gallery ViewPager2 implementations.
p = replace_required(
    p,
    '            root.setOnClickListener(v -> listener.onToggleChrome());\n            addMedia(post, position);',
    '            root.setOnClickListener(null);\n            addMedia(post, position);',
    'remove root click toggler')

p = replace_required(
    p,
    '                playerView.setOnClickListener(v -> listener.onToggleChrome());\n\n                Button mute',
    '                playerView.setOnClickListener(null);\n\n                Button mute',
    'remove PlayerView chrome toggle')

p = replace_required(
    p,
    '                image.setOnClickListener(v -> listener.onToggleChrome());\n\n                Button playPause',
    '                image.setOnClickListener(null);\n\n                Button playPause',
    'remove GIF image chrome toggle')

p = replace_required(
    p,
    '            image.setOnClickListener(v -> listener.onToggleChrome());\n        }',
    '            image.setOnClickListener(null);\n        }',
    'remove static image chrome toggle')

# Add an explicit Hide control. Chrome action buttons remain interactive without
# causing the parent to hide the overlay as a side effect.
p = replace_required(
    p,
    '''            Button share = pillButton("↗ Share");
            actions.addView(share, actionParams());
            share.setOnClickListener(v -> listener.onShare(post));

            TextView score = smallBadge("▲ " + compact(post.score));''',
    '''            Button share = pillButton("↗ Share");
            actions.addView(share, actionParams());
            share.setOnClickListener(v -> listener.onShare(post));

            Button hideChrome = pillButton("Hide");
            actions.addView(hideChrome, actionParams());
            hideChrome.setOnClickListener(v -> listener.onToggleChrome());

            TextView score = smallBadge("▲ " + compact(post.score));''',
    'explicit Hide overlay action')

# TapFrameLayout only REVEALS hidden chrome. It never hides visible chrome.
# This guarantees Save/Favorite controls remain available until the user presses
# the explicit Hide action, while still allowing child PlayerView/gallery gestures.
old_dispatch = '''        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                downAt = event.getEventTime();
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !chromeVisible) {
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                long elapsed = event.getEventTime() - downAt;
                if (dx <= dp(18) && dy <= dp(18) && elapsed <= 450) {
                    listener.onToggleChrome();
                }
            }
            return handled;
        }'''

new_dispatch = '''        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                downAt = event.getEventTime();
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !chromeVisible) {
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                long elapsed = event.getEventTime() - downAt;
                if (dx <= dp(24) && dy <= dp(24) && elapsed <= 600) {
                    // Sole reveal path. Never auto-hide chrome from a media tap.
                    listener.onToggleChrome();
                }
            }
            return handled;
        }'''

p = replace_required(p, old_dispatch, new_dispatch, 'single-owner reveal-only tap dispatcher')

path.write_text(p)
print('Applied v3.6.4 single-owner persistent fullscreen overlay')
