package com.scrolller.adblock;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
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
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements PostPagerAdapter.Listener {
    private static final String REDDIT = "https://www.reddit.com";

    private enum Screen { HOME, SEARCH, FAVORITES, ACCOUNT, USER }
    private enum BrowserPurpose { NONE, LOGIN, COMMENTS, SETTINGS }

    private static final class Subscription {
        final String name;
        final String title;
        Subscription(String name, String title) {
            this.name = name;
            this.title = title;
        }
    }

    private static final class NavState {
        final Screen screen;
        final String context;
        final String subreddit;
        final String query;
        final String profileUser;
        final String sort;
        final String topTime;
        final String media;
        final String layoutMode;
        final String searchScope;
        final int pagerPosition;

        NavState(
                Screen screen,
                String context,
                String subreddit,
                String query,
                String profileUser,
                String sort,
                String topTime,
                String media,
                String layoutMode,
                String searchScope,
                int pagerPosition
        ) {
            this.screen = screen;
            this.context = context;
            this.subreddit = subreddit;
            this.query = query;
            this.profileUser = profileUser;
            this.sort = sort;
            this.topTime = topTime;
            this.media = media;
            this.layoutMode = layoutMode;
            this.searchScope = searchScope;
            this.pagerPosition = pagerPosition;
        }
    }

    private FrameLayout root;
    private WebView sessionView;
    private FrameLayout appLayer;
    private ViewPager2 pager;
    private RecyclerView gridView;
    private PostPagerAdapter postAdapter;
    private GridPostAdapter gridAdapter;
    private LinearLayout topBar;
    private LinearLayout topHeader;
    private LinearLayout controlRow;
    private LinearLayout bottomBar;
    private TextView topTitle;
    private EditText searchInput;
    private Button searchGoButton;
    private Button feedButton;
    private Button sortButton;
    private Button filterButton;
    private Button layoutButton;
    private LinearLayout statusPanel;
    private TextView statusText;
    private ProgressBar progress;
    private ScrollView accountView;
    private Button browserBack;

    private RedditSessionEngine engine;
    private SharedPreferences prefs;
    private SeenPostStore seenPostStore;

    private Screen screen = Screen.HOME;
    private BrowserPurpose browserPurpose = BrowserPurpose.NONE;
    private String context = "home";
    private String subreddit = "";
    private String sort = "best";
    private String topTime = "day";
    private String media = "all";
    private String layoutMode = "fullscreen";
    private String searchScope = "global";
    private String query = "";
    private String profileUser = "";
    private String username = "";
    private String modhash = "";
    private String after = "";
    private boolean loading;
    private boolean feedLoading;
    private boolean searchLoading;
    private boolean initialized;
    private boolean muted = true;

    private static final int FEED_VISIBLE_AHEAD = 60;
    private static final int FEED_RESERVOIR_LOW = 100;
    private static final int FEED_RESERVOIR_MAX = 400;
    private static final int FEED_KEEP_BEHIND = 18;

    private int feedGeneration = 0;
    private int searchGeneration = 0;
    private boolean liveFeedExhausted = false;
    private boolean archiveFeedExhausted = false;
    private boolean archiveFeedLoading = false;
    private boolean subredditClearedShown = false;
    private String archiveBefore = "";

    private final Set<String> seenPostIds = new HashSet<>();
    private final Set<String> feedQueuedIds = new HashSet<>();
    private final Set<String> feedSeenCursors = new HashSet<>();
    private final ArrayList<RedditPost> feedReservoir = new ArrayList<>();
    private int systemTopPx;
    private int systemBottomPx;
    private int pendingRestorePosition = -1;

    private final ArrayList<Subscription> subscriptions = new ArrayList<>();
    private final Set<String> subscriptionNames = new HashSet<>();
    private final Deque<NavState> history = new ArrayDeque<>();

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);
        seenPostStore = new SeenPostStore(this);
        seenPostIds.addAll(seenPostStore.loadIds());
        sort = prefs.getString("sort", "best");
        topTime = prefs.getString("topTime", "day");
        media = prefs.getString("media", "all");
        layoutMode = prefs.getString("layout", "fullscreen");
        if (!layoutMode.equals("grid")) layoutMode = "fullscreen";
        searchScope = prefs.getString("searchScope", "global");
        query = prefs.getString("lastSearch", "");
        muted = prefs.getBoolean("muted", true);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            applySystemInsets(top, bottom);
            return insets;
        });

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
                            if (screen == Screen.FAVORITES) loadFavoritesInternal();
                        }
                    });
                }
            }
        });

        buildNativeUi();
        root.requestApplyInsets();
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
            @Override
            public void onPageSelected(int position) {
                postAdapter.setActivePosition(layoutMode.equals("fullscreen") ? position : -1);
                if (layoutMode.equals("fullscreen")
                        && screen != Screen.FAVORITES
                        && screen != Screen.ACCOUNT) {
                    markSeen(postAdapter.getPost(position));
                }

                if (screen == Screen.HOME && context.equals("subreddit")) {
                    ensureSubredditSupply(position);
                } else if (screen == Screen.HOME && !feedLoading && !after.isEmpty()
                        && position >= postAdapter.getItemCount() - 5) {
                    loadFeed(false);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_IDLE
                        && layoutMode.equals("fullscreen")
                        && screen == Screen.HOME
                        && context.equals("subreddit")) {
                    trimFullscreenWindowIfNeeded();
                }
            }
        });

        gridView = new RecyclerView(this);
        gridView.setBackgroundColor(Color.BLACK);
        gridView.setClipToPadding(false);
        gridView.setLayoutManager(new GridLayoutManager(this, 2));
        gridAdapter = new GridPostAdapter(this, this::openFullscreenAt);
        gridView.setAdapter(gridAdapter);
        gridView.setVisibility(View.GONE);
        gridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                if (!(lm instanceof GridLayoutManager)) return;
                GridLayoutManager glm = (GridLayoutManager) lm;
                int first = glm.findFirstVisibleItemPosition();
                int last = glm.findLastVisibleItemPosition();

                if (screen != Screen.FAVORITES && screen != Screen.ACCOUNT
                        && first >= 0 && last >= first) {
                    for (int i = first; i <= last; i++) markSeen(gridAdapter.getPost(i));
                }

                if (screen == Screen.HOME && context.equals("subreddit")) {
                    ensureSubredditSupply(Math.max(0, last));
                    if (first > FEED_KEEP_BEHIND * 2) {
                        final int trimFrom = first;
                        gridView.post(() -> trimGridWindowIfNeeded(trimFrom));
                    }
                } else if (screen == Screen.HOME && !feedLoading && !after.isEmpty()
                        && last >= gridAdapter.getItemCount() - 8) {
                    loadFeed(false);
                }
            }
        });
        appLayer.addView(gridView, match());

        accountView = new ScrollView(this);
        accountView.setFillViewport(true);
        accountView.setBackgroundColor(0xFF090909);
        accountView.setVisibility(View.GONE);
        appLayer.addView(accountView, match());

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF0000000, 0xC8000000, 0x65000000, 0x00000000}));
        appLayer.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.TOP));

        topHeader = new LinearLayout(this);
        topHeader.setOrientation(LinearLayout.HORIZONTAL);
        topHeader.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(topHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        topTitle = new TextView(this);
        topTitle.setText("Home");
        topTitle.setTextColor(Color.WHITE);
        topTitle.setTextSize(18);
        topTitle.setGravity(Gravity.CENTER);
        topTitle.setSingleLine(true);
        topTitle.setPadding(dp(8), 0, dp(8), 0);
        topHeader.addView(topTitle, new LinearLayout.LayoutParams(0, dp(42), 1f));
        topTitle.setOnClickListener(v -> {
            if (screen == Screen.HOME) showFeedSheet();
            else if (screen == Screen.USER && !profileUser.isEmpty()) {
                openBrowser(REDDIT + "/user/" + enc(profileUser) + "/", BrowserPurpose.SETTINGS);
            }
        });

        searchInput = new EditText(this);
        searchInput.setHint("Search Reddit media");
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(0xFF8E8E8E);
        searchInput.setTextSize(14);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setBackground(rounded(0xE51A1A1A, 14));
        searchInput.setVisibility(View.GONE);
        topHeader.addView(searchInput, new LinearLayout.LayoutParams(0, dp(40), 1f));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                beginSearch();
                return true;
            }
            return false;
        });

        searchGoButton = topPill("Search");
        searchGoButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams searchButtonParams = new LinearLayout.LayoutParams(dp(76), dp(38));
        searchButtonParams.leftMargin = dp(5);
        topHeader.addView(searchGoButton, searchButtonParams);
        searchGoButton.setOnClickListener(v -> beginSearch());

        controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER);
        topBar.addView(controlRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        feedButton = topPill("Feed");
        sortButton = topPill("Best");
        filterButton = topPill("All media");
        layoutButton = topPill("Fullscreen");
        controlRow.addView(feedButton, controlButtonParams());
        controlRow.addView(sortButton, controlButtonParams());
        controlRow.addView(filterButton, controlButtonParams());
        controlRow.addView(layoutButton, controlButtonParams());

        feedButton.setOnClickListener(v -> {
            if (screen == Screen.SEARCH) showScopeSheet();
            else showFeedSheet();
        });
        sortButton.setOnClickListener(v -> showSortSheet());
        filterButton.setOnClickListener(v -> showMediaSheet());
        layoutButton.setOnClickListener(v -> showLayoutSheet());

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFA000000, 0xD9000000, 0x70000000, 0x00000000}));
        appLayer.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM));

        addNavButton("⌂\nHome", () -> navigateHome("home", true));
        addNavButton("⌕\nSearch", this::openSearchScreen);
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
        appLayer.addView(statusPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

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
        applyLayoutVisibility();
    }

    private void applySystemInsets(int top, int bottom) {
        systemTopPx = Math.max(0, top);
        systemBottomPx = Math.max(0, bottom);
        if (topBar == null) return;

        boolean compactTop = screen == Screen.ACCOUNT;
        int topContent = compactTop ? dp(52) : dp(96);
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        tp.height = systemTopPx + topContent;
        topBar.setLayoutParams(tp);
        topBar.setPadding(dp(8), systemTopPx + dp(4), dp(8), dp(4));

        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
        bp.height = systemBottomPx + dp(64);
        bottomBar.setLayoutParams(bp);
        bottomBar.setPadding(dp(5), dp(3), dp(5), systemBottomPx + dp(3));

        gridView.setPadding(0, systemTopPx + dp(96), 0, systemBottomPx + dp(64));

        FrameLayout.LayoutParams ap = (FrameLayout.LayoutParams) accountView.getLayoutParams();
        ap.topMargin = systemTopPx + dp(52);
        ap.bottomMargin = systemBottomPx + dp(64);
        accountView.setLayoutParams(ap);

        FrameLayout.LayoutParams backParams = (FrameLayout.LayoutParams) browserBack.getLayoutParams();
        backParams.topMargin = systemTopPx + dp(8);
        browserBack.setLayoutParams(backParams);

        postAdapter.setSystemInsets(systemTopPx, systemBottomPx);
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
        if (!engine.isReady()) {
            if (done != null) done.run();
            return;
        }
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
                    JSONObject d = children.optJSONObject(i) != null
                            ? children.optJSONObject(i).optJSONObject("data") : null;
                    if (d == null) continue;
                    String name = d.optString("display_name", "");
                    if (name.isEmpty()) continue;
                    subscriptions.add(new Subscription(
                            name,
                            RedditPost.decode(d.optString("title", ""))));
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

    private NavState captureState() {
        int position = pager != null ? pager.getCurrentItem() : 0;
        return new NavState(
                screen,
                context,
                subreddit,
                query,
                profileUser,
                sort,
                topTime,
                media,
                layoutMode,
                searchScope,
                position);
    }

    private void pushCurrentState() {
        NavState current = captureState();
        if (!history.isEmpty() && sameState(history.peek(), current)) return;
        history.push(current);
        while (history.size() > 30) history.removeLast();
    }

    private boolean sameState(NavState a, NavState b) {
        return a.screen == b.screen
                && a.context.equals(b.context)
                && a.subreddit.equals(b.subreddit)
                && a.query.equals(b.query)
                && a.profileUser.equals(b.profileUser)
                && a.layoutMode.equals(b.layoutMode)
                && a.searchScope.equals(b.searchScope);
    }

    private void restoreState(NavState state) {
        screen = state.screen;
        context = state.context;
        subreddit = state.subreddit;
        query = state.query;
        profileUser = state.profileUser;
        sort = state.sort;
        topTime = state.topTime;
        media = state.media;
        layoutMode = state.layoutMode;
        searchScope = state.searchScope;
        pendingRestorePosition = Math.max(0, state.pagerPosition);

        accountView.setVisibility(View.GONE);
        updateChrome();

        if (screen == Screen.ACCOUNT) {
            showAccountInternal();
        } else if (screen == Screen.SEARCH) {
            applyLayoutVisibility();
            if (query.isEmpty()) {
                replacePosts(new ArrayList<>());
                setStatus("Search Reddit media by title, community, or creator.", false);
            } else {
                loadSearchInternal();
            }
        } else if (screen == Screen.FAVORITES) {
            loadFavoritesInternal();
        } else if (screen == Screen.USER) {
            loadUserProfileInternal();
        } else {
            applyLayoutVisibility();
            loadFeed(true);
        }
    }

    private void restorePendingPosition() {
        if (pendingRestorePosition < 0 || postAdapter.getItemCount() == 0) return;
        int target = Math.min(pendingRestorePosition, postAdapter.getItemCount() - 1);
        pendingRestorePosition = -1;
        pager.setCurrentItem(target, false);
        if (layoutMode.equals("grid")) gridView.scrollToPosition(target);
    }

    private void navigateHome(String which, boolean pushHistory) {
        if (pushHistory && !(screen == Screen.HOME && context.equals(which) && subreddit.isEmpty())) {
            pushCurrentState();
        }
        screen = Screen.HOME;
        context = which;
        subreddit = "";
        profileUser = "";
        after = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        updateChrome();
        loadFeed(true);
    }

    private void openSubredditFeed(String name) {
        if (name == null || name.isEmpty()) return;
        pushCurrentState();
        screen = Screen.HOME;
        context = "subreddit";
        subreddit = name;
        profileUser = "";
        after = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        updateChrome();
        loadFeed(true);
    }

    private void openSearchScreen() {
        if (screen != Screen.SEARCH) pushCurrentState();
        screen = Screen.SEARCH;
        context = "search";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        updateChrome();
        searchInput.setText(query);
        searchInput.setSelection(searchInput.length());
        if (query.isEmpty()) {
            replacePosts(new ArrayList<>());
            setStatus("Search Reddit media by title, community, or creator.", false);
            searchInput.requestFocus();
        } else {
            loadSearchInternal();
        }
    }

    private void beginSearch() {
        String next = searchInput.getText().toString().trim();
        if (next.isEmpty()) {
            query = "";
            prefs.edit().putString("lastSearch", "").apply();
            replacePosts(new ArrayList<>());
            setStatus("Enter a search term.", false);
            updateChrome();
            return;
        }
        query = next;
        prefs.edit().putString("lastSearch", query).apply();
        screen = Screen.SEARCH;
        loadSearchInternal();
    }

    private void openUserProfile(String name) {
        if (name == null || name.isEmpty()) return;
        pushCurrentState();
        screen = Screen.USER;
        profileUser = name;
        context = "user";
        subreddit = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        updateChrome();
        loadUserProfileInternal();
    }

    private void loadFeed(boolean reset) {
        if (!engine.isReady()) return;

        if (context.equals("subreddit")) {
            loadSubredditExhaustive(reset);
            return;
        }

        if (feedLoading) return;
        feedLoading = true;
        if (reset) {
            feedGeneration++;
            after = "";
            feedSeenCursors.clear();
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Loading media…", true);
        }
        fetchOrdinaryFeedPages(feedGeneration, reset, new ArrayList<>(), 0);
    }

    private void fetchOrdinaryFeedPages(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected,
            int page) {
        if (generation != feedGeneration || screen != Screen.HOME) return;

        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !feedSeenCursors.add(cursor)) {
            after = "";
            feedLoading = false;
            if (reset) replacePosts(collected); else appendUnique(collected);
            finishOrdinaryFeedUi();
            return;
        }

        engine.get(listingPath(cursor), result -> {
            if (generation != feedGeneration || screen != Screen.HOME) return;
            if (!result.ok) {
                feedLoading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Reddit feed failed: " + friendlyError(result), false);
                }
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
            if (collected.size() < 20 && !after.isEmpty() && page < 4) {
                fetchOrdinaryFeedPages(generation, reset, collected, page + 1);
                return;
            }

            feedLoading = false;
            if (reset) replacePosts(collected); else appendUnique(collected);
            finishOrdinaryFeedUi();
        });
    }

    private void finishOrdinaryFeedUi() {
        if (postAdapter.getItemCount() == 0) {
            setStatus("No media posts match this feed/filter.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

    private void loadSubredditExhaustive(boolean reset) {
        if (subreddit == null || subreddit.isEmpty()) return;

        if (reset) {
            feedGeneration++;
            feedLoading = false;
            archiveFeedLoading = false;
            liveFeedExhausted = false;
            archiveFeedExhausted = false;
            subredditClearedShown = false;
            after = "";
            archiveBefore = "";
            feedSeenCursors.clear();
            feedQueuedIds.clear();
            feedReservoir.clear();
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Loading unread r/" + subreddit + "…", true);
        }

        ensureSubredditSupply(Math.max(0, pager.getCurrentItem()));
    }

    private void ensureSubredditSupply(int position) {
        if (screen != Screen.HOME || !context.equals("subreddit")
                || subreddit == null || subreddit.isEmpty()) return;

        drainFeedReservoir(position);

        int ahead = Math.max(0, postAdapter.getItemCount() - position - 1);
        if (feedReservoir.size() >= FEED_RESERVOIR_LOW && ahead >= 20) return;

        if (!liveFeedExhausted) {
            if (!feedLoading) fetchNextLiveSubredditPage(feedGeneration);
            return;
        }

        if (!archiveFeedExhausted) {
            if (!archiveFeedLoading) fetchNextArchiveSubredditPage(feedGeneration);
            return;
        }

        if (feedReservoir.isEmpty() && ahead <= 0) {
            showSubredditCleared();
        }
    }

    private void fetchNextLiveSubredditPage(int generation) {
        if (generation != feedGeneration
                || screen != Screen.HOME
                || !context.equals("subreddit")) return;

        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !feedSeenCursors.add(cursor)) {
            liveFeedExhausted = true;
            feedLoading = false;
            ensureSubredditSupply(Math.max(0, pager.getCurrentItem()));
            return;
        }

        feedLoading = true;
        engine.get(listingPath(cursor), result -> {
            if (generation != feedGeneration
                    || screen != Screen.HOME
                    || !context.equals("subreddit")) return;

            feedLoading = false;
            if (!result.ok) {
                liveFeedExhausted = true;
                setStatus("Live Reddit ended; checking archive…", true);
                ensureSubredditSupply(Math.max(0, pager.getCurrentItem()));
                return;
            }

            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            ArrayList<RedditPost> additions = new ArrayList<>();

            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post) || isSeen(post)) continue;
                    additions.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            if (next.isEmpty() || next.equals(cursor)) {
                after = "";
                liveFeedExhausted = true;
            } else {
                after = next;
            }

            sortFeedBatch(additions);
            enqueueUnreadFeed(additions);
            drainFeedReservoir(Math.max(0, pager.getCurrentItem()));

            if (postAdapter.getItemCount() > 0) hideStatus();
            updateChrome();
            restorePendingPosition();

            if (feedReservoir.size() < FEED_RESERVOIR_LOW) {
                ensureSubredditSupply(Math.max(0, pager.getCurrentItem()));
            }
        });
    }

    private void fetchNextArchiveSubredditPage(int generation) {
        if (generation != feedGeneration
                || screen != Screen.HOME
                || !context.equals("subreddit")
                || archiveFeedExhausted
                || archiveFeedLoading) return;

        archiveFeedLoading = true;
        final String targetSubreddit = subreddit;
        final String before = archiveBefore;

        ArcticShiftClient.fetchSubredditPage(
                targetSubreddit,
                before,
                100,
                new ArcticShiftClient.PageCallback() {
                    @Override
                    public void onComplete(JSONArray items, String nextBefore) {
                        if (generation != feedGeneration
                                || screen != Screen.HOME
                                || !context.equals("subreddit")
                                || !targetSubreddit.equalsIgnoreCase(subreddit)) return;

                        archiveFeedLoading = false;
                        ArrayList<RedditPost> additions = new ArrayList<>();
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                RedditPost post = archivePost(items.optJSONObject(i));
                                if (post == null || !matchesMedia(post) || isSeen(post)) continue;
                                additions.add(post);
                            }
                        }

                        archiveBefore = nextBefore == null ? "" : nextBefore;
                        if (items == null || items.length() == 0 || archiveBefore.isEmpty()) {
                            archiveFeedExhausted = true;
                        }

                        sortFeedBatch(additions);
                        enqueueUnreadFeed(additions);
                        drainFeedReservoir(Math.max(0, pager.getCurrentItem()));

                        if (postAdapter.getItemCount() > 0) hideStatus();
                        if (feedReservoir.size() < FEED_RESERVOIR_LOW) {
                            ensureSubredditSupply(Math.max(0, pager.getCurrentItem()));
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (generation != feedGeneration) return;
                        archiveFeedLoading = false;
                        archiveFeedExhausted = true;
                        ensureSubredditSupply(Math.max(0, pager.getCurrentItem()));
                    }
                });
    }

    private RedditPost archivePost(JSONObject item) {
        if (item == null) return null;
        try {
            JSONObject data = new JSONObject(item.toString());
            String id = data.optString("id", "");
            if (id.isEmpty()) return null;
            if (data.optString("name", "").isEmpty()) data.put("name", "t3_" + id);
            if (data.optString("permalink", "").isEmpty()) {
                data.put("permalink", "/r/" + data.optString("subreddit", "")
                        + "/comments/" + id + "/");
            }
            if (data.optString("url_overridden_by_dest", "").isEmpty()
                    && !data.optString("url", "").isEmpty()) {
                data.put("url_overridden_by_dest", data.optString("url", ""));
            }
            long created = ArcticShiftClient.createdUtc(data.opt("created_utc"));
            if (created > 0L) data.put("created_utc", created);

            JSONObject child = new JSONObject();
            child.put("data", data);
            RedditPost post = RedditPost.fromChild(child);
            if (post != null) post.sourceOrigin = "archive";
            return post;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void sortFeedBatch(ArrayList<RedditPost> posts) {
        if (posts == null || posts.size() < 2) return;
        if (sort.equals("new") || sort.equals("rising")) {
            posts.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
        } else if (sort.equals("top")) {
            posts.sort((a, b) -> Integer.compare(b.score, a.score));
        } else {
            posts.sort((a, b) -> Double.compare(localHotScore(b), localHotScore(a)));
        }
    }

    private void enqueueUnreadFeed(List<RedditPost> incoming) {
        if (incoming == null || incoming.isEmpty()) return;

        for (RedditPost post : incoming) {
            if (feedReservoir.size() >= FEED_RESERVOIR_MAX) break;
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (!matchesMedia(post) || isSeen(post)) continue;

            String id = normalizePostId(post.id);
            if (id.isEmpty() || feedQueuedIds.contains(id)) continue;
            feedQueuedIds.add(id);
            feedReservoir.add(post);
        }
    }

    private void drainFeedReservoir(int currentPosition) {
        if (screen != Screen.HOME || !context.equals("subreddit")) return;

        int current = Math.max(0, currentPosition);
        int ahead = Math.max(0, postAdapter.getItemCount() - current - 1);
        int needed = Math.max(0, FEED_VISIBLE_AHEAD - ahead);
        if (postAdapter.getItemCount() == 0) needed = FEED_VISIBLE_AHEAD;

        ArrayList<RedditPost> release = new ArrayList<>();
        while (needed > 0 && !feedReservoir.isEmpty()) {
            RedditPost post = feedReservoir.remove(0);
            if (post == null || isSeen(post)) continue;
            release.add(post);
            needed--;
        }

        if (!release.isEmpty()) appendUnique(release);
    }

    private void showSubredditCleared() {
        if (subredditClearedShown) return;
        subredditClearedShown = true;
        setStatus("r/" + subreddit + " cleared · no unread media remains.", false);
    }

    private boolean isSeen(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return false;
        return seenPostIds.contains(normalizePostId(post.id));
    }

    private void markSeen(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        String id = normalizePostId(post.id);
        if (id.isEmpty() || !seenPostIds.add(id)) return;
        if (seenPostStore != null) seenPostStore.markSeenAsync(post);
    }

    private String normalizePostId(String id) {
        if (id == null) return "";
        String clean = id.trim().toLowerCase(Locale.US);
        if (clean.startsWith("t3_")) clean = clean.substring(3);
        return clean;
    }

    private void trimFullscreenWindowIfNeeded() {
        int position = pager.getCurrentItem();
        if (position <= FEED_KEEP_BEHIND * 2) return;

        int drop = position - FEED_KEEP_BEHIND;
        postAdapter.dropPrefix(drop);
        gridAdapter.dropPrefix(drop);
        int target = Math.min(FEED_KEEP_BEHIND, Math.max(0, postAdapter.getItemCount() - 1));
        pager.setCurrentItem(target, false);
        postAdapter.setActivePosition(target);
        ensureSubredditSupply(target);
    }

    private void trimGridWindowIfNeeded(int firstVisible) {
        if (layoutMode.equals("fullscreen") || firstVisible <= FEED_KEEP_BEHIND * 2) return;

        int drop = firstVisible - FEED_KEEP_BEHIND;
        postAdapter.dropPrefix(drop);
        gridAdapter.dropPrefix(drop);
        int target = Math.min(FEED_KEEP_BEHIND, Math.max(0, gridAdapter.getItemCount() - 1));
        gridView.scrollToPosition(target);
        ensureSubredditSupply(target);
    }

    private double localHotScore(RedditPost post) {
        long now = System.currentTimeMillis() / 1000L;
        long age = Math.max(1L, now - Math.max(0L, post.createdUtc));
        double hours = age / 3600.0;
        return (Math.max(0, post.score) + 1.0) / Math.pow(hours + 2.0, 0.82);
    }

    private String listingPath(String cursor) {
        String base;
        if (context.equals("home")) {
            base = sort.equals("best") ? "/.json" : "/" + sort + ".json";
        } else if (context.equals("popular")) {
            base = sort.equals("best") ? "/r/popular/hot.json" : "/r/popular/" + sort + ".json";
        } else {
            base = sort.equals("best")
                    ? "/r/" + enc(subreddit) + "/hot.json"
                    : "/r/" + enc(subreddit) + "/" + sort + ".json";
        }
        String path = base + "?limit=100&raw_json=1";
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        return path;
    }

    private boolean matchesMedia(RedditPost post) {
        if (media.equals("all")) return true;
        if (media.equals("image")) {
            return post.mediaKind == RedditPost.MediaKind.IMAGE
                    || post.mediaKind == RedditPost.MediaKind.GALLERY;
        }
        return post.mediaKind == RedditPost.MediaKind.VIDEO
                || post.mediaKind == RedditPost.MediaKind.EXTERNAL;
    }

    private void replacePosts(List<RedditPost> items) {
        postAdapter.setPosts(items);
        gridAdapter.setPosts(items);
    }

    private void appendUnique(List<RedditPost> incoming) {
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) ids.add(post.id);
        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) if (ids.add(post.id)) unique.add(post);
        postAdapter.appendPosts(unique);
        gridAdapter.appendPosts(unique);
    }

    private void openFullscreenAt(int position) {
        layoutMode = "fullscreen";
        prefs.edit().putString("layout", layoutMode).apply();
        updateChrome();
        applyLayoutVisibility();
        if (position >= 0 && position < postAdapter.getItemCount()) {
            pager.setCurrentItem(position, false);
            postAdapter.setActivePosition(position);
        }
    }

    private void applyLayoutVisibility() {
        if (screen == Screen.ACCOUNT) {
            pager.setVisibility(View.GONE);
            gridView.setVisibility(View.GONE);
            postAdapter.setActivePosition(-1);
            return;
        }
        boolean grid = layoutMode.equals("grid");
        pager.setVisibility(grid ? View.GONE : View.VISIBLE);
        gridView.setVisibility(grid ? View.VISIBLE : View.GONE);
        postAdapter.setActivePosition(grid ? -1 : pager.getCurrentItem());
    }

    private void loadSearchInternal() {
        if (!engine.isReady() || query == null || query.trim().isEmpty()) return;

        final int generation = ++searchGeneration;
        searchLoading = true;
        screen = Screen.SEARCH;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        setStatus("Searching " + searchScopeLabel() + " for “" + query + "”…", true);
        updateChrome();

        LinkedHashMap<String, RedditPost> collected = new LinkedHashMap<>();
        if (searchScope.equals("subscribed")) {
            ArrayList<String> groups = subscriptionSearchGroups();
            if (groups.isEmpty()) {
                searchLoading = false;
                setStatus(username.isEmpty()
                        ? "Sign in to search joined subreddits."
                        : "No joined subreddits are available to search.", false);
                return;
            }
            fetchSubscribedSearchGroup(generation, groups, 0, collected);
        } else {
            fetchGlobalSearchPage(generation, "", 0, collected);
        }
    }

    private String searchScopeLabel() {
        return searchScope.equals("subscribed") ? "joined subreddits" : "Reddit";
    }

    private ArrayList<String> subscriptionSearchGroups() {
        ArrayList<String> groups = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (Subscription sub : subscriptions) {
            if (sub == null || sub.name == null || sub.name.isEmpty()) continue;
            names.add(sub.name);
        }

        final int groupSize = 20;
        for (int i = 0; i < names.size(); i += groupSize) {
            StringBuilder group = new StringBuilder();
            int end = Math.min(names.size(), i + groupSize);
            for (int j = i; j < end; j++) {
                if (group.length() > 0) group.append('+');
                group.append(names.get(j));
            }
            if (group.length() > 0) groups.add(group.toString());
        }
        return groups;
    }

    private void fetchSubscribedSearchGroup(
            int generation,
            ArrayList<String> groups,
            int index,
            LinkedHashMap<String, RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        if (index >= groups.size()) {
            fetchArchiveSearchPage(generation, "", 0, collected);
            return;
        }

        String group = groups.get(index);
        String searchSort = remoteSearchSort();
        String path = "/r/" + group + "/search.json?q=" + enc(query)
                + "&restrict_sr=1&include_over_18=on&type=link"
                + "&limit=100&raw_json=1&sort=" + enc(searchSort);
        if (sort.equals("top")) path += "&t=" + enc(topTime);

        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (result.ok) {
                mergeSearchListing(result.jsonObject(), collected, true);
            }
            fetchSubscribedSearchGroup(generation, groups, index + 1, collected);
        });
    }

    private void fetchGlobalSearchPage(
            int generation,
            String cursor,
            int page,
            LinkedHashMap<String, RedditPost> collected) {
        if (!searchStillValid(generation)) return;

        String path = "/search.json?q=" + enc(query)
                + "&type=link&include_over_18=on&limit=100&raw_json=1"
                + "&sort=" + enc(remoteSearchSort());
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);

        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (!result.ok) {
                fetchArchiveSearchPage(generation, "", 0, collected);
                return;
            }

            JSONObject root = result.jsonObject();
            mergeSearchListing(root, collected, false);
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            String next = data != null ? data.optString("after", "") : "";

            if (!next.isEmpty() && page < 5) {
                fetchGlobalSearchPage(generation, next, page + 1, collected);
            } else {
                fetchArchiveSearchPage(generation, "", 0, collected);
            }
        });
    }

    private void fetchArchiveSearchPage(
            int generation,
            String before,
            int page,
            LinkedHashMap<String, RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        if (page >= 5) {
            finishSearchCollection(generation, collected);
            return;
        }

        ArcticShiftClient.searchPage(query, before, 100, new ArcticShiftClient.PageCallback() {
            @Override
            public void onComplete(JSONArray items, String nextBefore) {
                if (!searchStillValid(generation)) return;

                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        RedditPost post = archivePost(items.optJSONObject(i));
                        if (post == null || !matchesMedia(post) || !withinTopTime(post)) continue;
                        if (searchScope.equals("subscribed")
                                && !subscriptionNames.contains(
                                post.subreddit.toLowerCase(Locale.US))) continue;
                        if (searchRelevance(post) <= 0) continue;
                        mergeSearchPost(collected, post);
                    }
                }

                String next = nextBefore == null ? "" : nextBefore;
                if (!next.isEmpty() && items != null && items.length() > 0) {
                    fetchArchiveSearchPage(generation, next, page + 1, collected);
                } else {
                    finishSearchCollection(generation, collected);
                }
            }

            @Override
            public void onError(String error) {
                if (!searchStillValid(generation)) return;
                finishSearchCollection(generation, collected);
            }
        });
    }

    private void mergeSearchListing(
            JSONObject root,
            LinkedHashMap<String, RedditPost> collected,
            boolean requireSubscribed) {
        JSONObject data = root != null ? root.optJSONObject("data") : null;
        JSONArray children = data != null ? data.optJSONArray("children") : null;
        if (children == null) return;

        for (int i = 0; i < children.length(); i++) {
            RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
            if (post == null || !matchesMedia(post) || !withinTopTime(post)) continue;
            if (requireSubscribed
                    && !subscriptionNames.contains(post.subreddit.toLowerCase(Locale.US))) {
                continue;
            }
            mergeSearchPost(collected, post);
        }
    }

    private void mergeSearchPost(
            LinkedHashMap<String, RedditPost> collected,
            RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        String id = normalizePostId(post.id);
        if (id.isEmpty()) return;

        RedditPost existing = collected.get(id);
        if (existing == null || searchRelevance(post) > searchRelevance(existing)) {
            collected.put(id, post);
        }
    }

    private String remoteSearchSort() {
        if (sort.equals("best")) return "relevance";
        if (sort.equals("rising")) return "new";
        return sort;
    }

    private boolean searchStillValid(int generation) {
        return generation == searchGeneration
                && screen == Screen.SEARCH
                && query != null
                && !query.isEmpty();
    }

    private void finishSearchCollection(
            int generation,
            LinkedHashMap<String, RedditPost> collected) {
        if (!searchStillValid(generation)) return;

        searchLoading = false;
        ArrayList<RedditPost> results = new ArrayList<>(collected.values());
        sortSearchResults(results);
        replacePosts(results);

        if (results.isEmpty()) {
            setStatus("No matching media found for “" + query + "”.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

    private void sortSearchResults(ArrayList<RedditPost> results) {
        if (results == null || results.size() < 2) return;

        if (sort.equals("new")) {
            results.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
        } else if (sort.equals("top")) {
            results.sort((a, b) -> Integer.compare(b.score, a.score));
        } else if (sort.equals("hot") || sort.equals("rising")) {
            results.sort((a, b) -> Double.compare(localHotScore(b), localHotScore(a)));
        } else {
            results.sort((a, b) -> {
                int relevance = Integer.compare(searchRelevance(b), searchRelevance(a));
                if (relevance != 0) return relevance;
                return Integer.compare(b.score, a.score);
            });
        }
    }

    private int searchRelevance(RedditPost post) {
        if (post == null) return 0;
        String needle = normalizeSearchText(query);
        if (needle.isEmpty()) return 0;

        String title = normalizeSearchText(post.title);
        String subredditText = normalizeSearchText(post.subreddit);
        String authorText = normalizeSearchText(post.author);
        String metadata = normalizeSearchText(post.searchMetadata);
        String all = (title + " " + subredditText + " " + authorText + " " + metadata).trim();

        int score = 0;
        if (title.equals(needle)) score += 160;
        else if (title.contains(needle)) score += 100;
        if (subredditText.equals(needle)) score += 80;
        if (authorText.equals(needle)) score += 60;
        if (metadata.contains(needle)) score += 45;

        String[] terms = needle.split("\\s+");
        for (String term : terms) {
            if (term.isEmpty()) continue;
            if (title.contains(term)) score += 22;
            if (subredditText.contains(term)) score += 12;
            if (authorText.contains(term)) score += 10;
            if (metadata.contains(term)) score += 8;
        }
        return score;
    }

    private String normalizeSearchText(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9_]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean withinTopTime(RedditPost post) {
        if (!sort.equals("top") || topTime.equals("all")) return true;
        long seconds;
        if (topTime.equals("hour")) seconds = 3600L;
        else if (topTime.equals("day")) seconds = 86400L;
        else if (topTime.equals("week")) seconds = 604800L;
        else if (topTime.equals("month")) seconds = 2678400L;
        else if (topTime.equals("year")) seconds = 31536000L;
        else return true;

        long now = System.currentTimeMillis() / 1000L;
        return post != null && post.createdUtc > 0L
                && now - post.createdUtc <= seconds;
    }

    private void loadUserProfileInternal() {
        if (loading || !engine.isReady() || profileUser.isEmpty()) return;
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        setStatus("Loading u/" + profileUser + "…", true);
        loading = true;
        fetchUserPage("", new ArrayList<>(), 0);
    }

    private void fetchUserPage(String cursor, ArrayList<RedditPost> collected, int page) {
        String userSort = sort.equals("top") ? "top"
                : sort.equals("hot") ? "hot" : "new";
        String path = "/user/" + enc(profileUser)
                + "/submitted.json?limit=100&raw_json=1&sort=" + enc(userSort);
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);

        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                setStatus("Could not load u/" + profileUser + ": " + friendlyError(result), false);
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
            String next = data != null ? data.optString("after", "") : "";
            if (collected.size() < 24 && !next.isEmpty() && page < 4) {
                fetchUserPage(next, collected, page + 1);
                return;
            }
            loading = false;
            replacePosts(collected);
            if (collected.isEmpty()) {
                setStatus("u/" + profileUser + " has no matching media posts.", false);
            } else {
                hideStatus();
            }
            updateChrome();
            restorePendingPosition();
        });
    }

    private void loadFavorites() {
        if (screen != Screen.FAVORITES) pushCurrentState();
        screen = Screen.FAVORITES;
        profileUser = "";
        updateChrome();
        loadFavoritesInternal();
    }

    private void loadFavoritesInternal() {
        if (username.isEmpty()) {
            updateChrome();
            openBrowser(REDDIT + "/login/?dest=" + enc(REDDIT + "/"), BrowserPurpose.LOGIN);
            return;
        }
        if (loading) return;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
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
            replacePosts(items);
            if (items.isEmpty()) setStatus("No saved media posts yet.", false);
            else hideStatus();
            updateChrome();
            restorePendingPosition();
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
        if (screen != Screen.ACCOUNT) pushCurrentState();
        screen = Screen.ACCOUNT;
        profileUser = "";
        showAccountInternal();
    }

    private void showAccountInternal() {
        pager.setVisibility(View.GONE);
        gridView.setVisibility(View.GONE);
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        body.addView(sectionTitle(username.isEmpty() ? "Reddit account" : "u/" + username));

        if (username.isEmpty()) {
            body.addView(bodyText("Sign in to use Favorites and browse your subscribed communities."));
            Button login = sheetButton("Sign in to Reddit");
            body.addView(login, sectionButtonParams());
            login.setOnClickListener(v -> openBrowser(
                    REDDIT + "/login/?dest=" + enc(REDDIT + "/"),
                    BrowserPurpose.LOGIN));
        } else {
            Button myProfile = sheetButton("View my profile · u/" + username);
            body.addView(myProfile, sectionButtonParams());
            myProfile.setOnClickListener(v -> openUserProfile(username));

            Button settings = sheetButton("Open Reddit account settings");
            body.addView(settings, sectionButtonParams());
            settings.setOnClickListener(v -> openBrowser(
                    REDDIT + "/settings/account/",
                    BrowserPurpose.SETTINGS));

            TextView subsTitle = sectionTitle("Subscriptions · " + subscriptions.size());
            LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            stp.topMargin = dp(22);
            body.addView(subsTitle, stp);
            if (subscriptions.isEmpty()) body.addView(bodyText("No subscriptions returned yet."));
            for (Subscription sub : subscriptions) {
                Button b = sheetButton("r/" + sub.name
                        + (sub.title.isEmpty() ? "" : "\n" + sub.title));
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
        Button home = sheetButton("Home" + (context.equals("home") ? "  ✓" : ""));
        Button popular = sheetButton("Popular" + (context.equals("popular") ? "  ✓" : ""));
        body.addView(home, sectionButtonParams());
        body.addView(popular, sectionButtonParams());
        home.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("home", true);
        });
        popular.setOnClickListener(v -> {
            dialog.dismiss();
            navigateHome("popular", true);
        });

        if (!subscriptions.isEmpty()) {
            TextView t = sectionTitle("Subscriptions");
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(16);
            body.addView(t, tp);
            for (Subscription sub : subscriptions) {
                Button b = sheetButton("r/" + sub.name
                        + (context.equals("subreddit")
                        && subreddit.equalsIgnoreCase(sub.name) ? "  ✓" : ""));
                body.addView(b, sectionButtonParams());
                b.setOnClickListener(v -> {
                    dialog.dismiss();
                    openSubredditFeed(sub.name);
                });
            }
        } else if (username.isEmpty()) {
            Button login = sheetButton("Sign in for subscriptions");
            body.addView(login, sectionButtonParams());
            login.setOnClickListener(v -> {
                dialog.dismiss();
                openBrowser(
                        REDDIT + "/login/?dest=" + enc(REDDIT + "/"),
                        BrowserPurpose.LOGIN);
            });
        }
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showSortSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Choose sorting option");
        String[] values;
        if (screen == Screen.SEARCH) {
            values = new String[]{"best", "hot", "new", "top"};
        } else if (screen == Screen.USER) {
            values = new String[]{"best", "new", "hot", "top"};
        } else {
            values = new String[]{"best", "hot", "new", "top", "rising"};
        }
        for (String value : values) {
            Button b = sheetButton(label(value) + (sort.equals(value) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                sort = value;
                prefs.edit().putString("sort", sort).apply();
                dialog.dismiss();
                if (sort.equals("top")) showTopTimeSheet();
                else reloadCurrent();
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
            Button b = sheetButton((value.equals("all") ? "All time" : label(value))
                    + (topTime.equals(value) ? "  ✓" : ""));
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
        String[][] values = {
                {"all", "All media"},
                {"image", "Images"},
                {"video", "Video"}
        };
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

    private void showLayoutSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Layout");
        String[][] values = {
                {"fullscreen", "Fullscreen"},
                {"grid", "Grid"}
        };
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (layoutMode.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                int current = pager.getCurrentItem();
                layoutMode = pair[0];
                prefs.edit().putString("layout", layoutMode).apply();
                dialog.dismiss();
                updateChrome();
                applyLayoutVisibility();
                if (layoutMode.equals("grid")) {
                    gridView.scrollToPosition(Math.max(0, current));
                }
            });
        }
        dialog.setContentView(body);
        dialog.show();
    }

    private void showScopeSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Search scope");
        String[][] values = {
                {"global", "Global"},
                {"subscribed", "Subscribed only"}
        };
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (searchScope.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                searchScope = pair[0];
                prefs.edit().putString("searchScope", searchScope).apply();
                dialog.dismiss();
                if (!query.isEmpty()) loadSearchInternal();
                updateChrome();
            });
        }
        dialog.setContentView(body);
        dialog.show();
    }

    private void reloadCurrent() {
        updateChrome();
        if (screen == Screen.SEARCH) loadSearchInternal();
        else if (screen == Screen.FAVORITES) loadFavoritesInternal();
        else if (screen == Screen.USER) loadUserProfileInternal();
        else if (screen == Screen.HOME) loadFeed(true);
    }

    private void updateChrome() {
        if (topTitle == null) return;

        boolean searchMode = screen == Screen.SEARCH;
        topTitle.setVisibility(searchMode ? View.GONE : View.VISIBLE);
        searchInput.setVisibility(searchMode ? View.VISIBLE : View.GONE);
        searchGoButton.setVisibility(searchMode ? View.VISIBLE : View.GONE);
        if (searchMode && !searchInput.getText().toString().equals(query)) {
            searchInput.setText(query);
            searchInput.setSelection(searchInput.length());
        }

        String title;
        if (screen == Screen.FAVORITES) title = "Favorites";
        else if (screen == Screen.ACCOUNT) title = "Account";
        else if (screen == Screen.USER) title = "u/" + profileUser;
        else if (context.equals("subreddit")) title = "r/" + subreddit;
        else title = context.equals("popular") ? "Popular" : "Home";
        topTitle.setText(title);

        feedButton.setText(screen == Screen.SEARCH
                ? (searchScope.equals("global") ? "Global" : "Subs")
                : "Feed");
        sortButton.setText(label(sort));
        filterButton.setText(media.equals("all") ? "All media"
                : media.equals("image") ? "Images" : "Video");
        layoutButton.setText(layoutMode.equals("grid") ? "Grid" : "Fullscreen");

        controlRow.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
        feedButton.setVisibility(
                screen == Screen.HOME || screen == Screen.SEARCH ? View.VISIBLE : View.GONE);
        sortButton.setVisibility(
                screen == Screen.FAVORITES ? View.GONE : View.VISIBLE);
        filterButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
        layoutButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
        applySystemInsets(systemTopPx, systemBottomPx);
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
    public void onOpenUser(String username) {
        openUserProfile(username);
    }

    @Override
    public void onSave(RedditPost post) {
        if (username.isEmpty()) {
            openBrowser(
                    REDDIT + "/login/?dest=" + enc(REDDIT + "/"),
                    BrowserPurpose.LOGIN);
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
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(post.sourceUrl)));
        } catch (Exception ignored) {}
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
        bottomBar.addView(button, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        button.setOnClickListener(v -> action.run());
    }

    private Button topPill(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(rounded(0xD01B1B1B, 12));
        return button;
    }

    private LinearLayout.LayoutParams controlButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(
                    value == null ? "" : value,
                    StandardCharsets.UTF_8.toString());
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
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
        if (postAdapter != null && pager != null && layoutMode.equals("fullscreen")) {
            postAdapter.setActivePosition(pager.getCurrentItem());
        }
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
}
