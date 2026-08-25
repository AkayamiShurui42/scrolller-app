from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.5 patch target: {label}\n{old[:700]}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# MainActivity: fullscreen chrome is preloaded and visible by default. The user
# can hide/show it with a background media tap, and that state persists across
# fullscreen page changes.
# ---------------------------------------------------------------------------
main_path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = main_path.read_text()

s = replace_required(
    s,
    '    private boolean fullscreenChromeVisible = false;\n',
    '    private boolean fullscreenChromeVisible = true;\n',
    'fullscreen chrome defaults visible')

s = replace_required(
    s,
    '''    private void openFullscreenAt(int position) {
        layoutMode = "fullscreen";
        fullscreenChromeVisible = false;''',
    '''    private void openFullscreenAt(int position) {
        layoutMode = "fullscreen";
        fullscreenChromeVisible = true;''',
    'fullscreen entry starts with overlay visible')

s = replace_required(
    s,
    '''                layoutMode = pair[0];
                fullscreenChromeVisible = !layoutMode.equals("fullscreen");''',
    '''                layoutMode = pair[0];
                fullscreenChromeVisible = true;''',
    'layout switch keeps overlay preloaded visible')

main_path.write_text(s)

# ---------------------------------------------------------------------------
# PostPagerAdapter: keep one parent-level tap owner, but make it a true toggle.
# Action buttons and actual player controls are excluded from background taps,
# so Save/Comments/Share/mute/etc. never accidentally hide the overlay.
# ---------------------------------------------------------------------------
pager_path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
p = pager_path.read_text()

p = replace_required(
    p,
    'import android.graphics.Color;\n',
    'import android.graphics.Color;\nimport android.graphics.Rect;\n',
    'Rect import for action-control hit testing')

# The explicit Hide button is no longer needed: tapping the media background
# hides the already-attached overlay, and another tap reveals the same views.
p = replace_required(
    p,
    '''            Button hideChrome = pillButton("Hide");
            actions.addView(hideChrome, actionParams());
            hideChrome.setOnClickListener(v -> listener.onToggleChrome());

''',
    '',
    'remove explicit Hide control')

old_tap_class = '''    private final class TapFrameLayout extends FrameLayout {
        private float downX;
        private float downY;
        private long downAt;

        TapFrameLayout(Context context) {
            super(context);
        }

        @Override
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
        }
    }
'''

new_tap_class = '''    private final class TapFrameLayout extends FrameLayout {
        private float downX;
        private float downY;
        private long downAt;
        private boolean downOnActionControl;

        TapFrameLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                downAt = event.getEventTime();
                downOnActionControl = isActionControlAt(event.getRawX(), event.getRawY());
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !downOnActionControl) {
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                long elapsed = event.getEventTime() - downAt;
                if (dx <= dp(24) && dy <= dp(24) && elapsed <= 600) {
                    // Overlay views remain attached/preloaded; this only toggles
                    // their visibility. The same tap path hides and reveals them.
                    listener.onToggleChrome();
                }
            }
            return handled;
        }

        private boolean isActionControlAt(float rawX, float rawY) {
            return hitActionControl(this, Math.round(rawX), Math.round(rawY));
        }

        private boolean hitActionControl(View view, int rawX, int rawY) {
            if (view == null || view.getVisibility() != View.VISIBLE) return false;

            // Protect app action buttons and Media3 controller buttons from the
            // background overlay toggle. Their normal click behavior wins.
            if (view instanceof Button || view instanceof android.widget.ImageButton) {
                Rect bounds = new Rect();
                return view.getGlobalVisibleRect(bounds) && bounds.contains(rawX, rawY);
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    if (hitActionControl(group.getChildAt(i), rawX, rawY)) return true;
                }
            }
            return false;
        }
    }
'''

p = replace_required(p, old_tap_class, new_tap_class, 'preloaded background tap toggle dispatcher')

pager_path.write_text(p)
print('Applied v3.6.5 preloaded visible fullscreen overlay with background tap toggle')
