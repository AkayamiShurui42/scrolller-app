package com.scrolller.adblock;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements PostPagerAdapter.Listener {
    private static final String REDDIT = "https://www.reddit.com";

    private enum Screen { HOME, SEARCH, FAVORITES, ACCOUNT }
    private enum BrowserPurpose { NONE, LOGIN, COMMENTS, SETTINGS }

    private static final class Subscription {
        final String name;
        final String title;
        Subscription(String name, String title) { this.name = name; this.title = title; }
    }

    private FrameLayout root;
    private WebView sessionView;
    private FrameLayout appLayer;
    private ViewPager2 pager;
    private PostPagerAdapter postAdapter;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView topTitle;
    private Button feedButton;
    private Button sortButton;
    private Button filterButton;
    private LinearLayout statusPanel;
    private TextView statusText;
    private ProgressBar progress;
    private ScrollView accountView;
    private Button browserBack;

    private RedditSessionEngine engine;
    private SharedPreferences prefs;

    private Screen screen = Screen.HOME;
    private BrowserPurpose browserPurpose = BrowserPurpose.NONE;
    private String context = "home";
    private String subreddit = "";
    private String sort = "best";
    private String topTime = "day";
    private String media = "all";
    private String searchScope = "global";
    private String query = "";
    private String username = "";
    private String modhash = "";
    private String after = "";
    private boolean loading;
    private boolean initialized;
    private boolean muted = true;

    private final ArrayList<Subscription> subscriptions = new ArrayList<>();
    private final Set<String> subscriptionNames = new HashSet<>();

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);
        sort = prefs.getString("sort", "best");
        topTime = prefs.getString("topTime", "day");
        media = prefs.getString("media", "all");
        searchScope = prefs.getString("searchScope", "global");
        muted = prefs.getBoolean("muted", true);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        sessionView = new WebView(this);
        sessionView.setBackgroundColor(Color.BLACK);
        root.addView(sessionView, match());

        WebSettings settings = sessionView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(sessionView, true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        engine = new RedditSessionEngine(sessionView, this::onSessionReady);
        sessionView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleWebNavigation(request.getUrl() != null ? request.getUrl().toString() : null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleWebNavigation(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                engine.markReady(url);
                if (browserPurpose == BrowserPurpose.LOGIN && !isLoginUrl(url)) {
                    refreshIdentity(() -> {
                        if (!username.isEmpty()) {
                            closeBrowser();
                            loadSubscriptions(null);
                            if (screen == Screen.FAVORITES) loadFavorites();
                        }
                    });
                }
            }
        });

        buildNativeUi();
        sessionView.loadUrl(REDDIT + "/");
    }

    private void buildNativeUi() {
        appLayer = new FrameLayout(this);
        appLayer.setBackgroundColor(Color.BLACK);
        root.addView(appLayer, match());

        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setOffscreenPageLimit(1);
        appLayer.addView(pager, match());

        postAdapter = new PostPagerAdapter(this, this);
        postAdapter.setMuted(muted);
        pager.setAdapter(postAdapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                postAdapter.setActivePosition(position);
                if (screen == Screen.HOME && !loading && !after.isEmpty() && position >= postAdapter.getItemCount() - 5) {
                    loadFeed(false);
                }
            }
        });

        accountView = new ScrollView(this);
        accountView.setFillViewport(true);
        accountView.setBackgroundColor(0xFF090909);
        accountView.setVisibility(View.GONE);
        FrameLayout.LayoutParams accountParams = match();
        accountParams.topMargin = dp(50);
        accountParams.bottomMargin = dp(62);
        appLayer.addView(accountView, accountParams);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        topBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE8000000, 0xA8000000, 0x10000000}));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP);
        appLayer.addView(topBar, topParams);

        topTitle = new TextView(this);
        topTitle.setText("Home");
        topTitle.setTextColor(Color.WHITE);
        topTitle.setTextSize(17);
        topTitle.setGravity(Gravity.CENTER_VERTICAL);
        topTitle.setSingleLine(true);
        topTitle.setPadding(dp(4), 0, dp(8), 0);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        topBar.addView(topTitle, titleParams);
        topTitle.setOnClickListener(v -> showFeedSheet());

        feedButton = topPill("Feed");
        sortButton = topPill("Best");
        filterButton = topPill("All");
        topBar.addView(feedButton, topButtonParams());
        topBar.addView(sortButton, topButtonParams());
        topBar.addView(filterButton, topButtonParams());
        feedButton.setOnClickListener(v -> showFeedSheet());
        sortButton.setOnClickListener(v -> showSortSheet());
        filterButton.setOnClickListener(v -> showMediaSheet());

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(5), dp(3), dp(5), dp(3));
        bottomBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF5000000, 0xC9000000, 0x68000000}));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM);
        appLayer.addView(bottomBar, bottomParams);

        addNavButton("⌂\nHome", () -> openHome("home"));
        addNavButton("⌕\nSearch", this::showSearchDialog);
        addNavButton("★\nFavorites", this::loadFavorites);
        addNavButton("●\nAccount", this::showAccount);

        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setGravity(Gravity.CENTER);
        statusPanel.setPadding(dp(18), dp(15), dp(18), dp(15));
        statusPanel.setBackground(rounded(0xE6151515, 16));
        progress = new ProgressBar(this);
        statusPanel.addView(progress, new LinearLayout.LayoutParams(dp(38), dp(38)));
        statusText = new TextView(this);
        statusText.setText("Connecting to Reddit…");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(13);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(10), 0, 0);
        statusPanel.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        appLayer.addView(statusPanel, sp);

        browserBack = new Button(this);
        browserBack.setAllCaps(false);
        browserBack.setText("‹ Back");
        browserBack.setTextColor(Color.WHITE);
        browserBack.setTextSize(14);
        browserBack.setBackground(rounded(0xE6202020, 999));
        browserBack.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(88), dp(42), Gravity.TOP | Gravity.START);
        bp.topMargin = dp(8);
        bp.leftMargin = dp(8);
        root.addView(browserBack, bp);
        browserBack.setOnClickListener(v -> closeBrowser());

        updateChrome();
    }

    private void onSessionReady(String url) {
        if (initialized || browserPurpose != BrowserPurpose.NONE) return;
        initialized = true;
        setStatus("Loading Reddit media…", true);
        refreshIdentity(() -> loadSubscriptions(null));
        loadFeed(true);
    }

    private boolean handleWebNavigation(String url) {
        if (url == null) return true;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host != null && (host.equals("reddit.com") || host.endsWith(".reddit.com"))) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
        return true;
    }

    private boolean isLoginUrl(String url) {
        return url != null && (url.contains("/login") || url.contains("/register"));
    }

    private void refreshIdentity(@Nullable Runnable done) {
        if (!engine.isReady()) { if (done != null) done.run(); return; }
        engine.get("/api/me.json?raw_json=1", result -> {
            username = "";
            modhash = "";
            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            if (data != null) {
                username = data.optString("name", "");
                modhash = data.optString("modhash", "");
            }
            if (done != null) done.run();
            if (screen == Screen.ACCOUNT) renderAccount();
        });
    }

    private void loadSubscriptions(@Nullable Runnable done) {
        subscriptions.clear();
        subscriptionNames.clear();
        if (username.isEmpty()) {
            if (done != null) done.run();
            if (screen == Screen.ACCOUNT) renderAccount();
            return;
        }
        loadSubscriptionsPage("", 0, done);
    }

    private void loadSubscriptionsPage(String cursor, int page, @Nullable Runnable done) {
        if (page >= 12) {
            finishSubscriptions(done);
            return;
        }
        String path = "/subreddits/mine/subscriber.json?limit=100&raw_json=1";
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        engine.get(path, result -> {
            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject d = children.optJSONObject(i) != null ? children.optJSONObject(i).optJSONObject("data") : null;
                    if (d == null) continue;
                    String name = d.optString("display_name", "");
                    if (name.isEmpty()) continue;
                    subscriptions.add(new Subscription(name, RedditPost.decode(d.optString("title", ""))));
                    subscriptionNames.add(name.toLowerCase(Locale.US));
                }
            }
            String next = data != null ? data.optString("after", "") : "";
            if (!next.isEmpty()) loadSubscriptionsPage(next, page + 1, done);
            else finishSubscriptions(done);
        });
    }

    private void finishSubscriptions(@Nullable Runnable done) {
        subscriptions.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        if (done != null) done.run();
        if (screen == Screen.ACCOUNT) renderAccount();
    }

    private void openHome(String which) {
        screen = Screen.HOME;
        context = which;
        subreddit = "";
        after = "";
        accountView.setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);
        loadFeed(true);
    }

    private void openSubredditFeed(String name) {
        screen = Screen.HOME;
        context = "subreddit";
        subreddit = name;
        after = "";
        accountView.setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);
        loadFeed(true);
    }

    private void loadFeed(boolean reset) {
        if (loading || !engine.isReady()) return;
        loading = true;
        if (reset) {
            after = "";
            postAdapter.setPosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Loading media…", true);
        }
        fetchFeedPages(reset, new ArrayList<>(), 0);
    }

    private void fetchFeedPages(boolean reset, ArrayList<RedditPost> collected, int page) {
        String path = listingPath(after);
        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                if (postAdapter.getItemCount() == 0) setStatus("Reddit feed failed: " + friendlyError(result), false);
                return;
            }
            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post != null && matchesMedia(post)) collected.add(post);
                }
            }
            after = data != null ? data.optString("after", "") : "";
            if (collected.size() < 12 && !after.isEmpty() && page < 4) {
                fetchFeedPages(reset, collected, page + 1);
                return;
            }
            loading = false;
            if (reset) postAdapter.setPosts(collected); else appendUnique(collected);
            if (postAdapter.getItemCount() == 0) setStatus("No media posts match this feed/filter.", false);
            else hideStatus();
            updateChrome();
        });
    }

    private String listingPath(String cursor) {
        String base;
        if (context.equals("home")) {
            base = sort.equals("best") ? "/.json" : "/" + sort + ".json";
        } else if (context.equals("popular")) {
            base = sort.equals("best") ? "/r/popular/.json" : "/r/popular/" + sort + ".json";
        } else {
            base = sort.equals("best")
                    ? "/r/" + enc(subreddit) + "/.json"
                    : "/r/" + enc(subreddit) + "/" + sort + ".json";
        }
        String path = base + "?limit=100&raw_json=1";
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        return path;
    }

    private boolean matchesMedia(RedditPost post) {
        if (media.equals("all")) return true;
        if (media.equals("image")) return post.mediaKind == RedditPost.MediaKind.IMAGE || post.mediaKind == RedditPost.MediaKind.GALLERY;
        return post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.EXTERNAL;
    }

    private void appendUnique(List<RedditPost> incoming) {
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) ids.add(post.id);
        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) if (ids.add(post.id)) unique.add(post);
        postAdapter.appendPosts(unique);
    }

    private void showSearchDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        EditText input = new EditText(this);
        input.setHint("Search Reddit media");
        input.setText(query);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF858585);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF777777));
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        RadioGroup scopes = new RadioGroup(this);
        scopes.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton global = radio("Global", 1);
        RadioButton subs = radio("Subscribed", 2);
        scopes.addView(global);
        scopes.addView(subs);
        scopes.check(searchScope.equals("subscribed") ? 2 : 1);
        box.addView(scopes);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Search")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Search", (d, w) -> {
                    query = input.getText().toString().trim();
                    searchScope = scopes.getCheckedRadioButtonId() == 2 ? "subscribed" : "global";
                    prefs.edit().putString("searchScope", searchScope).apply();
                    if (!query.isEmpty()) loadSearch();
                })
                .show();
    }

    private RadioButton radio(String text, int id) {
        RadioButton b = new RadioButton(this);
        b.setId(id);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        return b;
    }

    private void loadSearch() {
        if (loading || !engine.isReady()) return;
        screen = Screen.SEARCH;
        accountView.setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);
        postAdapter.setPosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        setStatus("Searching…", true);
        loading = true;
        fetchSearchPage("", new ArrayList<>(), 0);
        updateChrome();
    }

    private void fetchSearchPage(String cursor, ArrayList<RedditPost> collected, int page) {
        String searchSort = sort.equals("best") ? "relevance" : (sort.equals("rising") ? "new" : sort);
        String path = "/search.json?q=" + enc(query) + "&type=link&limit=100&raw_json=1&sort=" + enc(searchSort);
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                setStatus("Search failed: " + friendlyError(result), false);
                return;
            }
            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (searchScope.equals("subscribed") && !subscriptionNames.contains(post.subreddit.toLowerCase(Locale.US))) continue;
                    collected.add(post);
                }
            }
            String next = data != null ? data.optString("after", "") : "";
            int maxPages = searchScope.equals("subscribed") ? 6 : 1;
            if (collected.size() < 20 && !next.isEmpty() && page + 1 < maxPages) {
                fetchSearchPage(next, collected, page + 1);
                return;
            }
            loading = false;
            postAdapter.setPosts(collected);
            if (collected.isEmpty()) setStatus("No matching media found.", false); else hideStatus();
        });
    }

    private void loadFavorites() {
        if (username.isEmpty()) {
            screen = Screen.FAVORITES;
            updateChrome();
            openBrowser(REDDIT + "/login/?dest=" + enc(REDDIT + "/"), BrowserPurpose.LOGIN);
            return;
        }
        if (loading) return;
        screen = Screen.FAVORITES;
        accountView.setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);
        postAdapter.setPosts(new ArrayList<>());
        setStatus("Loading Favorites…", true);
        loading = true;
        String path = "/user/" + enc(username) + "/saved.json?limit=100&raw_json=1";
        engine.get(path, result -> {
            loading = false;
            if (!result.ok) {
                setStatus("Favorites failed: " + friendlyError(result), false);
                return;
            }
            ArrayList<RedditPost> items = parseListing(result.jsonObject(), true);
            postAdapter.setPosts(items);
            if (items.isEmpty()) setStatus("No saved media posts yet.", false); else hideStatus();
            updateChrome();
        });
    }

    private ArrayList<RedditPost> parseListing(JSONObject root, boolean applyFilter) {
        ArrayList<RedditPost> items = new ArrayList<>();
        JSONObject data = root != null ? root.optJSONObject("data") : null;
        JSONArray children = data != null ? data.optJSONArray("children") : null;
        if (children == null) return items;
        for (int i = 0; i < children.length(); i++) {
            RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
            if (post != null && (!applyFilter || matchesMedia(post))) items.add(post);
        }
        return items;
    }

    private void showAccount() {
        screen = Screen.ACCOUNT;
        pager.setVisibility(View.GONE);
        accountView.setVisibility(View.VISIBLE);
        hideStatus();
        renderAccount();
        updateChrome();
    }

    private void renderAccount() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(24));
        accountView.removeAllViews();
        accountView.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = sectionTitle(username.isEmpty() ? "Reddit account" : "u/" + username);
        body.addView(heading);

        if (username.isEmpty()) {
            TextView note = bodyText("Sign in to use Favorites and browse your subscribed communities.");
            body.addView(note);
            Button login = sheetButton("Sign in to Reddit");
            body.addView(login, sectionButtonParams());
            login.setOnClickListener(v -> openBrowser(REDDIT + "/login/?dest=" + enc(REDDIT + "/"), BrowserPurpose.LOGIN));
        } else {
            Button settings = sheetButton("Open Reddit account settings");
            body.addView(settings, sectionButtonParams());
            settings.setOnClickListener(v -> openBrowser(REDDIT + "/settings/account/", BrowserPurpose.SETTINGS));

            TextView subsTitle = sectionTitle("Subscriptions · " + subscriptions.size());
            LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stp.topMargin = dp(22);
            body.addView(subsTitle, stp);
            if (subscriptions.isEmpty()) body.addView(bodyText("No subscriptions returned yet."));
            for (Subscription sub : subscriptions) {
                Button b = sheetButton("r/" + sub.name + (sub.title.isEmpty() ? "" : "\n" + sub.title));
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setMaxLines(2);
                body.addView(b, sectionButtonParams());
                b.setOnClickListener(v -> openSubredditFeed(sub.name));
            }
        }
    }

    private void showFeedSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Choose feed");
        scroll.addView(body);
        Button home = sheetButton("Home");
        Button popular = sheetButton("Popular");
        body.addView(home, sectionButtonParams());
        body.addView(popular, sectionButtonParams());
        home.setOnClickListener(v -> { dialog.dismiss(); openHome("home"); });
        popular.setOnClickListener(v -> { dialog.dismiss(); openHome("popular"); });

        if (!subscriptions.isEmpty()) {
            TextView t = sectionTitle("Subscriptions");
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(16);
            body.addView(t, tp);
            for (Subscription sub : subscriptions) {
                Button b = sheetButton("r/" + sub.name);
                body.addView(b, sectionButtonParams());
                b.setOnClickListener(v -> { dialog.dismiss(); openSubredditFeed(sub.name); });
            }
        } else if (username.isEmpty()) {
            Button login = sheetButton("Sign in for subscriptions");
            body.addView(login, sectionButtonParams());
            login.setOnClickListener(v -> { dialog.dismiss(); openBrowser(REDDIT + "/login/?dest=" + enc(REDDIT + "/"), BrowserPurpose.LOGIN); });
        }
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showSortSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Choose sorting option");
        String[] values = screen == Screen.SEARCH
                ? new String[]{"best", "hot", "new", "top"}
                : new String[]{"best", "hot", "new", "top", "rising"};
        for (String value : values) {
            Button b = sheetButton(label(value) + (sort.equals(value) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                sort = value;
                prefs.edit().putString("sort", sort).apply();
                dialog.dismiss();
                if (sort.equals("top")) showTopTimeSheet(); else reloadCurrent();
            });
        }
        dialog.setContentView(body);
        dialog.show();
    }

    private void showTopTimeSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Top timeframe");
        String[] values = {"hour", "day", "week", "month", "year", "all"};
        for (String value : values) {
            Button b = sheetButton((value.equals("all") ? "All time" : label(value)) + (topTime.equals(value) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                topTime = value;
                prefs.edit().putString("topTime", topTime).apply();
                dialog.dismiss();
                reloadCurrent();
            });
        }
        dialog.setContentView(body);
        dialog.show();
    }

    private void showMediaSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Media type");
        String[][] values = {{"all", "All media"}, {"image", "Images"}, {"video", "Video"}};
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (media.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                media = pair[0];
                prefs.edit().putString("media", media).apply();
                dialog.dismiss();
                reloadCurrent();
            });
        }
        dialog.setContentView(body);
        dialog.show();
    }

    private void reloadCurrent() {
        updateChrome();
        if (screen == Screen.SEARCH) loadSearch();
        else if (screen == Screen.FAVORITES) loadFavorites();
        else if (screen == Screen.HOME) loadFeed(true);
    }

    private void updateChrome() {
        if (topTitle == null) return;
        String title;
        if (screen == Screen.SEARCH) title = query.isEmpty() ? "Search" : "Search: " + query;
        else if (screen == Screen.FAVORITES) title = "Favorites";
        else if (screen == Screen.ACCOUNT) title = "Account";
        else if (context.equals("subreddit")) title = "r/" + subreddit;
        else title = context.equals("popular") ? "Popular" : "Home";
        topTitle.setText(title);
        sortButton.setText(label(sort));
        filterButton.setText(media.equals("all") ? "All" : media.equals("image") ? "Images" : "Video");
        feedButton.setVisibility(screen == Screen.HOME ? View.VISIBLE : View.GONE);
        sortButton.setVisibility(screen == Screen.HOME || screen == Screen.SEARCH ? View.VISIBLE : View.GONE);
        filterButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
    }

    private void openBrowser(String url, BrowserPurpose purpose) {
        browserPurpose = purpose;
        appLayer.setVisibility(View.GONE);
        browserBack.setVisibility(View.VISIBLE);
        sessionView.setVisibility(View.VISIBLE);
        sessionView.loadUrl(url);
    }

    private void closeBrowser() {
        browserPurpose = BrowserPurpose.NONE;
        browserBack.setVisibility(View.GONE);
        appLayer.setVisibility(View.VISIBLE);
        sessionView.loadUrl(REDDIT + "/");
    }

    @Override
    public void onOpenSubreddit(String subreddit) {
        openSubredditFeed(subreddit);
    }

    @Override
    public void onSave(RedditPost post) {
        if (username.isEmpty()) {
            openBrowser(REDDIT + "/login/?dest=" + enc(REDDIT + "/"), BrowserPurpose.LOGIN);
            return;
        }
        String body = "id=" + enc(post.id) + "&uh=" + enc(modhash);
        engine.postForm(post.saved ? "/api/unsave" : "/api/save", body, result -> {
            if (result.ok) {
                post.saved = !post.saved;
                postAdapter.refreshPost(post);
            } else {
                setStatus("Save failed: " + friendlyError(result), false);
            }
        });
    }

    @Override
    public void onComments(RedditPost post) {
        openBrowser(REDDIT + post.permalink, BrowserPurpose.COMMENTS);
    }

    @Override
    public void onShare(RedditPost post) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, REDDIT + post.permalink);
        startActivity(Intent.createChooser(share, "Share Reddit post"));
    }

    @Override
    public void onOpenExternal(RedditPost post) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(post.sourceUrl))); } catch (Exception ignored) {}
    }

    @Override
    public void onMutedChanged(boolean muted) {
        this.muted = muted;
        prefs.edit().putBoolean("muted", muted).apply();
    }

    private void setStatus(String message, boolean spinning) {
        statusPanel.setVisibility(View.VISIBLE);
        statusText.setText(message);
        progress.setVisibility(spinning ? View.VISIBLE : View.GONE);
    }

    private void hideStatus() {
        statusPanel.setVisibility(View.GONE);
    }

    private String friendlyError(RedditSessionEngine.ApiResult result) {
        if (!result.error.isEmpty()) return result.error;
        if (result.status > 0) return "HTTP " + result.status;
        return "unknown error";
    }

    private void addNavButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setBackgroundColor(Color.TRANSPARENT);
        bottomBar.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        button.setOnClickListener(v -> action.run());
    }

    private Button topPill(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setBackground(rounded(0xB31B1B1B, 999));
        return button;
    }

    private LinearLayout.LayoutParams topButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        p.leftMargin = dp(4);
        return p;
    }

    private LinearLayout sheetBody(String title) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(12), dp(14), dp(22));
        body.setBackgroundColor(0xFF111111);
        body.addView(sectionTitle(title));
        return body;
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(4), dp(4), dp(4), dp(10));
        return title;
    }

    private TextView bodyText(String text) {
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(0xFF999999);
        body.setTextSize(13);
        body.setPadding(dp(4), dp(4), dp(4), dp(8));
        return body;
    }

    private Button sheetButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(0);
        b.setBackground(rounded(0xFF1B1B1B, 13));
        return b;
    }

    private LinearLayout.LayoutParams sectionButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        p.topMargin = dp(7);
        return p;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.toString()); }
        catch (Exception ignored) { return value == null ? "" : value; }
    }

    private static String label(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (postAdapter != null) postAdapter.setActivePosition(-1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (postAdapter != null && pager != null) postAdapter.setActivePosition(pager.getCurrentItem());
    }

    @Override
    protected void onDestroy() {
        if (postAdapter != null) postAdapter.releaseAll();
        if (sessionView != null) sessionView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (browserPurpose != BrowserPurpose.NONE) {
            closeBrowser();
            return;
        }
        if (screen == Screen.ACCOUNT || screen == Screen.SEARCH || screen == Screen.FAVORITES || context.equals("subreddit") || context.equals("popular")) {
            openHome("home");
            return;
        }
        super.onBackPressed();
    }
}
