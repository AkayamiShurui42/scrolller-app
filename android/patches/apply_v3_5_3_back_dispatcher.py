from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.5.3 patch target: {label}\n{old[:360]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/MainActivity.java')
s = path.read_text()

s = replace_required(
    s,
    'import androidx.annotation.Nullable;\nimport androidx.appcompat.app.AppCompatActivity;',
    'import androidx.annotation.Nullable;\nimport androidx.activity.OnBackPressedCallback;\nimport androidx.appcompat.app.AppCompatActivity;',
    'OnBackPressedCallback import')

s = replace_required(
    s,
    '''        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);''',
    '''        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });

        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);''',
    'register modern back dispatcher callback')

old_back = '''    @Override
    public void onBackPressed() {
        if (browserPurpose != BrowserPurpose.NONE) {
            closeBrowser();
            return;
        }

        if (!history.isEmpty()) {
            restoreState(history.pop());
            return;
        }

        boolean rootHome = screen == Screen.HOME
                && context.equals("home")
                && subreddit.isEmpty();
        if (!rootHome) {
            navigateHome("home", false);
            return;
        }

        moveTaskToBack(true);
    }
}'''

new_back = '''    private void handleBackNavigation() {
        if (browserPurpose != BrowserPurpose.NONE) {
            closeBrowser();
            return;
        }

        if (!history.isEmpty()) {
            restoreState(history.pop());
            return;
        }

        boolean rootHome = screen == Screen.HOME
                && context.equals("home")
                && subreddit.isEmpty();
        if (!rootHome) {
            navigateHome("home", false);
            return;
        }

        // At the real root, preserve the task and send it to Recents/Home instead
        // of finishing MainActivity. Reopening the app resumes the existing task.
        moveTaskToBack(true);
    }
}'''

s = replace_required(s, old_back, new_back, 'replace deprecated onBackPressed override')
path.write_text(s)
print('Applied v3.5.3 AndroidX back-dispatcher navigation fix')
