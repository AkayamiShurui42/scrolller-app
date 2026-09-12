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
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
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
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements PostPagerAdapter.Listener {
    private static final String REDDIT = "https://www.reddit.com";
    private static final String[][] CURATED_CATEGORY_ROWS = {
            {"NSFW", "Adult", "General", "NSFW,gonewild,RealGirls"},
            {"NSFW", "Adult", "GIF & video", "nsfw_gif,NSFW_GIF"},
            {"NSFW", "Adult", "Couples", "couplesgonewild"},
            {"NSFW", "Adult", "Cosplay", "cosplaygirls"},
            {"NSFW", "Adult", "Lingerie", "lingerie"},
            {"NSFW", "Adult", "Curvy", "gonewildcurvy,curvy"},
            {"NSFW", "Adult", "Petite", "petitegonewild"},
            {"NSFW", "Adult", "Artistic", "NSFWart,ArtisticNSFW"}
    };
    private static final String[] QUALITY_SEED_SUBREDDITS = {
            "NSFW", "gonewild", "RealGirls", "nsfw_gif", "couplesgonewild",
            "cosplaygirls", "lingerie", "gonewildcurvy", "petitegonewild", "NSFWart"
    };
    private static final String[] QUALITY_TIME_WINDOWS = {"all", "year", "month"};

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
        final String favoriteSort;
        final String favoritesView;
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
                String favoriteSort,
                String favoritesView,
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
            this.favoriteSort = favoriteSort;
            this.favoritesView = favoritesView;
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
    private Button subscribeButton;
    private Button sortButton;
    private Button filterButton;
    private Button layoutButton;
    private LinearLayout statusPanel;
    private TextView statusText;
    private ProgressBar progress;
    private int statusGeneration = 0;
    private Runnable statusDismissRunnable;
    private ScrollView accountView;
    private Button browserBack;
    private Button compactMenuButton;
    private final LinkedHashMap<String, ArrayList<String>> subredditPresets = new LinkedHashMap<>();
    private final ArrayList<String> multiSubredditRound = new ArrayList<>();
    private final HashSet<String> multiSubredditSeenPostIds = new HashSet<>();
    private int multiSubredditRoundIndex = 0;
    private int multiSubredditGeneration = 0;
    private int multiSubredditRequestsThisLoad = 0;
    private long fullscreenVisitStartedAtMs = 0L;

    private RedditSessionEngine engine;
    private SharedPreferences prefs;
    private ReadHideStore readHideStore;

    private Screen screen = Screen.HOME;
    private BrowserPurpose browserPurpose = BrowserPurpose.NONE;
    private String context = "home";
    private String subreddit = "";
    private String sort = "random";
    private String topTime = "day";
    private String media = "all";
    private String layoutMode = "fullscreen";
    private String favoriteSort = "random";
    private String favoritesView = "saved";
    private String searchScope = "global";
    private String searchSubreddit = "";
    private String searchCategoryName = "";
    private final ArrayList<String> searchCategoryCommunities = new ArrayList<>();
    private final ArrayList<String> homeRandomRound = new ArrayList<>();
    private final HashSet<String> homeRandomSeenPostIds = new HashSet<>();
    private int homeRandomRoundIndex = 0;
    private int homeRandomGeneration = 0;
    private int homeRandomRequestsThisLoad = 0;
    private final ArrayList<RedditPost> searchCollectorSnapshot = new ArrayList<>();
    private int searchGeneration = 0;
    private String query = "";
    private String profileUser = "";
    private String username = "";
    private String modhash = "";
    private String after = "";
    private boolean loading;
    private boolean initialized;
    private boolean muted = true;
    private boolean fullscreenChromeVisible = true;
    private boolean blockLgbtTopics = false;
    private boolean blockGoreContent = false;

    // v3.9 discovery controls. A zero people/source mask means "all".
    private int peopleFilterMask = 0;
    private int sourceFilterMask = 0;
    private boolean showViewedPosts = false;
    private boolean joinedOnlyFilter = false;
    private int minimumVideoHeight = 0;
    private int videoLengthMode = 0; // 0 all, 1 <=30s, 2 31-120s, 3 >120s
    private final LinkedHashSet<String> favoriteSubreddits = new LinkedHashSet<>();
    private final LinkedHashSet<String> includedSubreddits = new LinkedHashSet<>();
    private final LinkedHashSet<String> excludedSubreddits = new LinkedHashSet<>();
    private final LinkedHashSet<String> blockedCreators = new LinkedHashSet<>();
    private final LinkedHashMap<String, String> presetFilterSnapshots = new LinkedHashMap<>();

    private int systemTopPx;
    private int systemBottomPx;
    private int pendingRestorePosition = -1;

    private final ArrayList<Subscription> subscriptions = new ArrayList<>();
    private final Set<String> subscriptionNames = new HashSet<>();
    private final Set<String> feedSeenPostIds = new HashSet<>();
    private final Set<String> feedSeenCursors = new HashSet<>();
    private final Set<String> savedPostIds = new HashSet<>();
    private final ArrayList<RedditPost> deferredAppends = new ArrayList<>();
    private boolean deferredAppendScheduled = false;
    private boolean archivePrefetchRunning = false;
    private boolean archivePrefetchDone = false;
    private boolean scrolllerPrefetchRunning = false;
    private boolean scrolllerPrefetchDone = false;
    private boolean historicalPrefetchRunning = false;
    private boolean historicalPrefetchDone = false;
    private boolean topAllArchiveRunning = false;
    private boolean topAllArchiveDone = false;
    private int feedGeneration = 0;
    private final LinkedHashMap<String, RedditPost> qualityCatalog = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> qualityAuthorHits = new LinkedHashMap<>();
    private String qualityQuery = "";
    private String qualityCategory = "all";
    private boolean qualityCatalogLoaded = false;
    private boolean qualityCrawlRunning = false;
    private boolean qualityCrawlDone = false;
    private int qualityCrawlGeneration = 0;
    private int archivePrefetchGeneration = 0;
    private final LinkedHashMap<String, RedditPost> hiddenPosts = new LinkedHashMap<>();
    private final Set<String> mediaReadyPostIds = new HashSet<>();
    private final Set<String> mediaFailedPostIds = new HashSet<>();
    private String lastFullscreenPostId = "";
    private boolean fullscreenUserGesture = false;
    private int pendingUserFullscreenPosition = -1;
    private final Deque<NavState> history = new ArrayDeque<>();

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.install(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });

        readHideStore = new ReadHideStore(this);
        LegacyHiddenPrefsPreflight.migrateIfNeeded(this, readHideStore);
        prefs = getSharedPreferences("native-redview", MODE_PRIVATE);
        loadSubredditPresets();
        sort = "random";
        topTime = prefs.getString("topTime", "day");
        media = prefs.getString("media", "all");
        layoutMode = prefs.getString("layout", "fullscreen");
        if (!layoutMode.equals("grid")) layoutMode = "fullscreen";
        searchScope = prefs.getString("searchScope", "global");
        searchSubreddit = prefs.getString("searchSubreddit", "");
        query = "";
        prefs.edit().remove("lastSearch").apply();
        muted = prefs.getBoolean("muted", true);
        // The old one-switch LGBTQ blocker is superseded by the explicit
        // multi-select people/content taxonomy below.
        blockLgbtTopics = false;
        prefs.edit().remove("blockLgbtTopics").apply();
        blockGoreContent = prefs.getBoolean("blockGoreContent", false);
        peopleFilterMask = prefs.getInt("peopleFilterMaskV1", 0);
        sourceFilterMask = prefs.getInt("sourceFilterMaskV1", 0);
        showViewedPosts = prefs.getBoolean("showViewedPostsV1", false);
        joinedOnlyFilter = prefs.getBoolean("joinedOnlyFilterV1", false);
        minimumVideoHeight = prefs.getInt("minimumVideoHeightV1", 0);
        videoLengthMode = prefs.getInt("videoLengthModeV1", 0);
        loadDiscoverySets();
        loadPresetFilterSnapshots();
        Set<String> persistedSavedIds = prefs.getStringSet("savedPostIds", null);
        if (persistedSavedIds != null) savedPostIds.addAll(persistedSavedIds);
        loadReadHideState();

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
        if (pager.getChildCount() > 0 && pager.getChildAt(0) instanceof RecyclerView) {
            RecyclerView pagerRecycler = (RecyclerView) pager.getChildAt(0);
            pagerRecycler.setItemAnimator(null);
            pagerRecycler.setItemViewCacheSize(2);
        }
        appLayer.addView(pager, match());

        postAdapter = new PostPagerAdapter(this, this);
        postAdapter.setMuted(muted);
        HighQualityPlayerFactory.warmup(getApplicationContext());
        pager.setAdapter(postAdapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    postAdapter.setPagerScrolling(true);
                    fullscreenUserGesture = true;
                    pendingUserFullscreenPosition = -1;
                } else if (state == ViewPager2.SCROLL_STATE_SETTLING) {
                    postAdapter.setPagerScrolling(true);
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    postAdapter.setPagerScrolling(false);
                    if (fullscreenUserGesture && pendingUserFullscreenPosition >= 0
                            && layoutMode.equals("fullscreen")
                            && screen != Screen.ACCOUNT
                            && screen != Screen.FAVORITES) {
                        trackFullscreenVisit(pendingUserFullscreenPosition);
                    }
                    fullscreenUserGesture = false;
                    pendingUserFullscreenPosition = -1;
                }
            }

            @Override
            public void onPageSelected(int position) {
                postAdapter.setActivePosition(layoutMode.equals("fullscreen") ? position : -1);
                boolean readEligible = layoutMode.equals("fullscreen")
                        && screen != Screen.ACCOUNT
                        && screen != Screen.FAVORITES;
                if (readEligible) {
                    if (fullscreenUserGesture) {
                        // The real read transition is committed only once the swipe settles.
                        pendingUserFullscreenPosition = position;
                    } else {
                        // Programmatic selections establish a baseline only. They never mark
                        // a post read and never mark a post read.
                        setFullscreenReadBaseline(position);
                    }
                } else {
                    lastFullscreenPostId = "";
                    pendingUserFullscreenPosition = -1;
                }
                // Preserve fullscreen chrome state across page changes.
                // Entering Fullscreen still starts hidden, but once the user taps
                // to reveal the overlay it stays visible until they tap to hide it.
                if (screen == Screen.HOME && !loading && !after.isEmpty()
                        && position >= postAdapter.getItemCount() - 60) {
                    loadFeed(false);
                }
            }
        });

        gridView = new RecyclerView(this);
        gridView.setBackgroundColor(Color.BLACK);
        gridView.setClipToPadding(false);
        gridView.setItemAnimator(null);
        gridView.setLayoutManager(new GridLayoutManager(this, 1));
        gridAdapter = new GridPostAdapter(this, this::openFullscreenAt);
        gridView.setAdapter(gridAdapter);
        gridView.setVisibility(View.GONE);
        gridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                if (!(lm instanceof GridLayoutManager)) return;
                int last = ((GridLayoutManager) lm).findLastVisibleItemPosition();
                if (screen == Screen.HOME && !loading && !after.isEmpty()
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

        subscribeButton = topPill("Subscribe");
        subscribeButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams subscribeParams = new LinearLayout.LayoutParams(dp(104), dp(38));
        subscribeParams.leftMargin = dp(4);
        subscribeParams.rightMargin = dp(4);
        topHeader.addView(subscribeButton, subscribeParams);
        subscribeButton.setOnClickListener(v -> toggleSubredditSubscription());

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
        searchGoButton.setOnClickListener(v -> {
            if (screen == Screen.SEARCH) beginSearch();
            else if (screen == Screen.HOME && context.equals("subreddit")) openSearchScreen();
        });

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
            else if (screen == Screen.FAVORITES) showFavoritesViewSheet();
            else if (screen == Screen.HOME && context.equals("quality")) showQualityBrowseSheet();
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

        // Status is intentionally a small transient bottom banner, never a
        // blocking center-screen loading card. Loading work happens silently.
        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.HORIZONTAL);
        statusPanel.setGravity(Gravity.CENTER_VERTICAL);
        statusPanel.setPadding(dp(14), dp(8), dp(14), dp(8));
        statusPanel.setBackground(rounded(0xE6151515, 999));
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER);
        statusPanel.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusParams.leftMargin = dp(12);
        statusParams.rightMargin = dp(12);
        statusParams.bottomMargin = dp(18);
        appLayer.addView(statusPanel, statusParams);
        statusPanel.setVisibility(View.GONE);

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
    
        installCompactNavigation();
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
    
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (gridView != null) gridView.setPadding(0, systemTopPx + dp(8), 0, systemBottomPx + dp(8));
        if (statusPanel != null && statusPanel.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams statusParams = (FrameLayout.LayoutParams) statusPanel.getLayoutParams();
            statusParams.bottomMargin = systemBottomPx + dp(18);
            statusPanel.setLayoutParams(statusParams);
        }

        if (compactMenuButton != null && compactMenuButton.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams menuParams = (FrameLayout.LayoutParams) compactMenuButton.getLayoutParams();
            menuParams.bottomMargin = systemBottomPx + dp(14);
            compactMenuButton.setLayoutParams(menuParams);
        }
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
        if (screen == Screen.HOME && context.equals("home") && sort.equals("random")
                && !subscriptions.isEmpty()) loadFeed(true);
        updateChrome();
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
                favoriteSort,
                favoritesView,
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
                && a.searchScope.equals(b.searchScope)
                && a.favoriteSort.equals(b.favoriteSort)
                && a.favoritesView.equals(b.favoritesView);
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
        favoriteSort = state.favoriteSort;
        favoritesView = state.favoritesView;
        pendingRestorePosition = Math.max(0, state.pagerPosition);

        accountView.setVisibility(View.GONE);
        updateChrome();

        if (screen == Screen.ACCOUNT) {
            showAccountInternal();
        } else if (screen == Screen.SEARCH) {
            query = "";
            prefs.edit().remove("lastSearch").apply();
            applyLayoutVisibility();
            replacePosts(new ArrayList<>());
            searchInput.setText("");
            setStatus("Search Reddit media by title, community, or creator.", false);
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
        if ("quality".equals(which)) which = "home";
        if (pushHistory && !(screen == Screen.HOME && context.equals(which) && subreddit.isEmpty())) {
            pushCurrentState();
        }
        sort = "random";
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
        sort = "random";
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
        boolean subredditEntry = screen == Screen.HOME
                && context.equals("subreddit")
                && subreddit != null
                && !subreddit.isEmpty();

        if (screen != Screen.SEARCH) {
            searchCollectorSnapshot.clear();
            searchCollectorSnapshot.addAll(postAdapter.getPosts());
            if (subredditEntry) {
                searchSubreddit = cleanSubredditName(subreddit);
            }
            pushCurrentState();
        }

        if (subredditEntry) {
            searchScope = "subreddit";
            prefs.edit()
                    .putString("searchScope", searchScope)
                    .putString("searchSubreddit", searchSubreddit)
                    .apply();
        }

        query = "";
        prefs.edit().remove("lastSearch").apply();
        screen = Screen.SEARCH;
        context = "search";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        searchInput.setText("");
        updateChrome();
        setStatus("Search " + searchScopeDescription() + " by title, community, or creator.", false);
        searchInput.requestFocus();
    }

    private void beginSearch() {
        String next = searchInput.getText().toString().trim();
        if (next.isEmpty()) {
            query = "";
            prefs.edit().remove("lastSearch").apply();
            replacePosts(new ArrayList<>());
            setStatus("Enter a search term.", false);
            updateChrome();
            return;
        }
        query = next;
        prefs.edit().remove("lastSearch").apply();
        searchInput.setText("");
        screen = Screen.SEARCH;
        loadSearchInternal();
    }

    private void openUserProfile(String name) {
        if (name == null || name.isEmpty()) return;
        loading = false;
        feedGeneration++;
        pushCurrentState();
        if (sort.equals("random")) sort = "new";
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
        if (loading && !reset) return;

        if (screen == Screen.HOME && context.equals("multi")) {
            loadMultiSubredditFair(reset);
            return;
        }

        if (reset) {
        if (screen == Screen.HOME
                && context.equals("home")
                && sort.equals("random")
                && !username.isEmpty()
                && !subscriptions.isEmpty()) {
            loadHomeSubscriptionRandom(reset);
            return;
        }

            feedGeneration++;
            loading = false;
            after = "";
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            archivePrefetchGeneration++;
            archivePrefetchRunning = false;
            archivePrefetchDone = false;
            scrolllerPrefetchRunning = false;
            scrolllerPrefetchDone = false;
            historicalPrefetchRunning = false;
            historicalPrefetchDone = false;
            topAllArchiveRunning = false;
            topAllArchiveDone = false;
            deferredAppends.clear();
            deferredAppendScheduled = false;
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Loading media…", true);
        }

        final int generation = feedGeneration;
        loading = true;
        fetchFeedPages(generation, reset, new ArrayList<>(), 0);
    }

    private void fetchFeedPages(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected,
            int page) {
        if (generation != feedGeneration || screen != Screen.HOME) return;

        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !feedSeenCursors.add(cursor)) {
            after = "";
            finishFeedCollection(generation, reset, collected);
            return;
        }

        String path = listingPath(cursor);
        engine.get(path, result -> {
            if (generation != feedGeneration || screen != Screen.HOME) return;
            if (!result.ok) {
                // If at least one live page already arrived, keep it instead of
                // turning a later pagination/rate-limit failure into a dead feed.
                if (!collected.isEmpty()) {
                    after = "";
                    finishFeedCollection(generation, reset, collected);
                    return;
                }
                if (context.equals("multi") && result.status == 404) {
                    after = "";
                    setStatus("Preset route unavailable; retrying subreddits individually…", true);
                    fetchMulti404Fallback(
                            generation,
                            reset,
                            collected,
                            multiCommunities(),
                            0);
                    return;
                }
                loading = false;
                if (context.equals("subreddit") && subreddit != null && !subreddit.isEmpty()) {
                    setStatus("Live Reddit unavailable; loading historical r/" + subreddit + "…", true);
                    prefetchHistoricalSubredditIfNeeded(true);
                    return;
                }
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Reddit feed failed: " + friendlyError(result), false);
                }
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    String key = canonicalPostKey(post);
                    if (key.isEmpty() || !feedSeenPostIds.add(key)) continue;
                    if (isReadHiddenForDiscovery(post)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            after = next;

            // Make subreddit/feed entry feel immediate. Final ordering still runs
            // once the small reservoir is complete.
            if (reset && page == 0 && !collected.isEmpty()) {
                replacePosts(new ArrayList<>(collected));
                hideStatus();
                updateChrome();
            }

            boolean topAll = sort.equals("top") && topTime.equals("all");
            boolean random = sort.equals("random");
            boolean oldest = sort.equals("oldest");
            boolean multi = context.equals("multi");
            int target = multi ? 100 : oldest ? 350 : random ? 120 : 30;
            int pageLimit = multi ? 1 : topAll ? 12 : oldest ? 5 : random ? 3 : 4;
            boolean canContinue = !next.isEmpty()
                    && !feedSeenCursors.contains(next)
                    && page + 1 < pageLimit;
            boolean needMore = topAll || random || oldest || collected.size() < target;

            if (canContinue && needMore) {
                root.postDelayed(
                        () -> fetchFeedPages(generation, reset, collected, page + 1),
                        180L);
                return;
            }

            finishFeedCollection(generation, reset, collected);
        });
    }

    private ArrayList<String> multiCommunities() {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        String[] parts = subreddit == null ? new String[0] : subreddit.split("\\+");
        for (String part : parts) {
            String clean = cleanSubredditName(part);
            if (!clean.isEmpty()) unique.add(clean);
        }
        return new ArrayList<>(unique);
    }

    private String singlePresetListingPath(String target) {
        String remoteSort = sort;
        if (remoteSort.equals("random") || remoteSort.equals("oldest")) remoteSort = "new";
        String base = remoteSort.equals("best")
                ? "/r/" + enc(target) + "/hot.json"
                : "/r/" + enc(target) + "/" + remoteSort + ".json";
        String path = base + "?limit=50&raw_json=1&show=all";
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        return path;
    }

    private void fetchMulti404Fallback(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected,
            ArrayList<String> communities,
            int index) {
        if (generation != feedGeneration || screen != Screen.HOME || !context.equals("multi")) return;

        if (index >= communities.size() || collected.size() >= 100) {
            after = "";
            boolean empty = collected.isEmpty();
            finishFeedCollection(generation, reset, collected);
            if (empty) setStatus("No accessible media matched this preset/filter.", false);
            return;
        }

        String target = communities.get(index);
        engine.get(singlePresetListingPath(target), result -> {
            if (generation != feedGeneration || screen != Screen.HOME || !context.equals("multi")) return;

            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !matchesMedia(post)) continue;
                        String key = canonicalPostKey(post);
                        if (key.isEmpty() || !feedSeenPostIds.add(key)) continue;
                        if (isReadHiddenForDiscovery(post)
                                || isSavedForUnread(post)
                                || isContentBlocked(post)) continue;
                        collected.add(post);
                    }
                }

                // Show the first successful subreddit immediately while the remaining
                // preset members continue through the serialized request queue.
                if (reset && postAdapter.getItemCount() == 0 && !collected.isEmpty()) {
                    replacePosts(new ArrayList<>(collected));
                    hideStatus();
                    updateChrome();
                }
            }

            root.postDelayed(
                    () -> fetchMulti404Fallback(
                            generation,
                            reset,
                            collected,
                            communities,
                            index + 1),
                    180L);
        });
    }

    private void finishFeedCollection(
            int generation,
            boolean reset,
            ArrayList<RedditPost> collected) {
        if (generation != feedGeneration || screen != Screen.HOME) return;
        loading = false;

        if (sort.equals("random")) {
            Collections.shuffle(collected);
        } else if (sort.equals("oldest")) {
            collected.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
        }

        if (reset && postAdapter.getItemCount() == 0) replacePosts(collected);
        else appendUnique(collected);

        if (postAdapter.getItemCount() == 0) {
            setStatus("No unique media posts match this feed/filter.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
        prefetchSubredditReservoir();

        if (context.equals("subreddit") && sort.equals("top") && topTime.equals("all")) {
            prefetchHistoricalTopAllIfNeeded(generation);
        }
    }

    private boolean homeSubscriptionRandomEnabled() {
        return screen == Screen.HOME
                && context.equals("home")
                && sort.equals("random")
                && !username.isEmpty()
                && !subscriptions.isEmpty();
    }

    private void loadHomeSubscriptionRandom(boolean reset) {
        if (!engine.isReady()) return;
        if (loading && !reset) return;

        if (reset) {
            feedGeneration++;
            homeRandomGeneration++;
            loading = false;
            after = "";
            homeRandomRound.clear();
            homeRandomRoundIndex = 0;
            homeRandomSeenPostIds.clear();
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            deferredAppends.clear();
            deferredAppendScheduled = false;
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Shuffling subscriptions…", true);
        }

        if (homeRandomRoundIndex >= homeRandomRound.size()) {
            prepareHomeRandomRound();
        }
        if (homeRandomRound.isEmpty()) {
            loading = false;
            setStatus("No subscriptions are available for Random.", false);
            return;
        }

        final int feedGen = feedGeneration;
        final int randomGen = homeRandomGeneration;
        homeRandomRequestsThisLoad = 0;
        loading = true;
        fetchHomeRandomRoundNext(feedGen, randomGen);
    }

    private void prepareHomeRandomRound() {
        homeRandomRound.clear();
        homeRandomRoundIndex = 0;
        HashSet<String> seen = new HashSet<>();
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            String key = clean.toLowerCase(Locale.US);
            if (!clean.isEmpty() && seen.add(key)) homeRandomRound.add(clean);
        }
        Collections.shuffle(homeRandomRound);
    }

    private boolean homeRandomContextValid(int feedGen, int randomGen) {
        return feedGen == feedGeneration
                && randomGen == homeRandomGeneration
                && homeSubscriptionRandomEnabled();
    }

    private void fetchHomeRandomRoundNext(int feedGen, int randomGen) {
        if (!homeRandomContextValid(feedGen, randomGen)) return;
        if (homeRandomRoundIndex >= homeRandomRound.size()) {
            loading = false;
            after = "home-random-round";
            if (postAdapter.getItemCount() == 0) {
                setStatus("No matching media was found across your subscriptions.", false);
            } else {
                hideStatus();
            }
            updateChrome();
            restorePendingPosition();
            return;
        }

        if (homeRandomRequestsThisLoad >= 12 && postAdapter.getItemCount() > 0) {
            loading = false;
            after = "home-random-round";
            hideStatus();
            updateChrome();
            restorePendingPosition();
            return;
        }

        final String targetSubreddit = homeRandomRound.get(homeRandomRoundIndex++);
        homeRandomRequestsThisLoad++;
        String path = "/r/" + enc(targetSubreddit)
                + "/new.json?limit=35&raw_json=1&show=all";
        engine.get(path, result -> {
            if (!homeRandomContextValid(feedGen, randomGen)) return;
            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                ArrayList<RedditPost> candidates = new ArrayList<>();
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !matchesMedia(post)) continue;
                        if (post.id == null || post.id.isEmpty()) continue;
                        if (isReadHiddenForDiscovery(post)
                                || isSavedForUnread(post)
                                || isContentBlocked(post)) continue;
                        String key = canonicalPostKey(post);
                        if (key.isEmpty() || homeRandomSeenPostIds.contains(key)) continue;
                        candidates.add(post);
                    }
                }

                if (!candidates.isEmpty()) {
                    Collections.shuffle(candidates);
                    RedditPost selected = candidates.get(0);
                    String key = canonicalPostKey(selected);
                    if (!key.isEmpty()) {
                        homeRandomSeenPostIds.add(key);
                        feedSeenPostIds.add(key);
                    }
                    ArrayList<RedditPost> one = new ArrayList<>();
                    one.add(selected);
                    appendUnique(one);
                    hideStatus();
                }
            }

            // Keep Reddit request cadence sane. This is intentionally much slower
            // than the old 55 ms loop and is chunked by normal pagination.
            root.postDelayed(
                    () -> fetchHomeRandomRoundNext(feedGen, randomGen),
                    280L);
        });
    }

    private String listingPath(String cursor) {
        if (context.equals("multi")) return multiListingPath(cursor);

        String remoteSort = sort;
        if (remoteSort.equals("random") || remoteSort.equals("oldest")) {
            remoteSort = "new";
        }

        String base;
        if (context.equals("home")) {
            base = remoteSort.equals("best") ? "/.json" : "/" + remoteSort + ".json";
        } else if (context.equals("popular")) {
            base = remoteSort.equals("best")
                    ? "/r/popular/hot.json"
                    : "/r/popular/" + remoteSort + ".json";
        } else {
            base = remoteSort.equals("best")
                    ? "/r/" + enc(subreddit) + "/hot.json"
                    : "/r/" + enc(subreddit) + "/" + remoteSort + ".json";
        }

        String path = base + "?limit=100&raw_json=1&show=all";
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        return path;
    }

    private boolean isSavedForUnread(RedditPost post) {
        return post != null
                && post.id != null
                && !post.id.isEmpty()
                && (post.saved || savedPostIds.contains(post.id));
    }

    private boolean isContentBlocked(RedditPost post) {
        if (post != null && !post.nsfw) return true;

        if (post == null) return false;
        if (!ContentTaxonomy.matches(post, peopleFilterMask)) return true;
        if (!passesDiscoveryFilters(post)) return true;

        String title = post.title == null ? "" : post.title;
        String community = post.subreddit == null ? "" : post.subreddit;
        String permalink = post.permalink == null ? "" : post.permalink;
        String sourceUrl = post.sourceUrl == null ? "" : post.sourceUrl;
        String normalized = normalizeFilterText(
                title + " " + community + " " + permalink + " " + sourceUrl);
        String communityKey = community.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]", "");

        if (blockLgbtTopics) {
            if (containsFilterPhrase(normalized,
                    "gay", "lesbian", "bisexual", "bi sexual", "pansexual", "pan sexual",
                    "homosexual", "homosexuality", "trans", "transgender", "transgendered",
                    "transsexual", "trans woman", "trans women", "trans man", "trans men",
                    "transfem", "trans fem", "transmasc", "trans masc",
                    "lgbt", "lgbtq", "lgbtqia", "queer",
                    "nonbinary", "non binary", "genderfluid", "gender fluid",
                    "genderqueer", "gender queer", "mtf", "ftm", "wlw")) {
                return true;
            }

            String[] blockedCommunityFragments = {
                    "asktransgender", "transgender", "transporn", "transgonewild",
                    "transpositive", "transadorable", "transpassing",
                    "gayporn", "gaybros", "gaymers", "lesbian", "bisexual",
                    "pansexual", "nonbinary", "genderfluid", "genderqueer",
                    "lgbt", "lgbtq", "queer", "mtf", "ftm"
            };
            for (String fragment : blockedCommunityFragments) {
                if (communityKey.contains(fragment)) return true;
            }
        }

        if (blockGoreContent) {
            if (containsFilterPhrase(normalized,
                    "gore", "gory", "blood", "bloody", "bloodshed", "nsfl",
                    "dismemberment", "dismembered", "decapitation", "decapitated",
                    "beheading", "beheaded", "mutilation", "mutilated",
                    "exposed organs", "open wound", "open wounds",
                    "graphic injury", "graphic injuries", "graphic death",
                    "violent death", "dead body", "dead bodies", "corpse", "corpses",
                    "human remains", "severed limb", "severed limbs",
                    "gunshot wound", "stab wound", "autopsy", "cadaver")) {
                return true;
            }

            String[] blockedCommunityFragments = {
                    "gore", "medicalgore", "eyeblech", "watchpeopledie",
                    "deadorvegetable", "makemycoffin", "nsfl"
            };
            for (String fragment : blockedCommunityFragments) {
                if (communityKey.contains(fragment)) return true;
            }
        }

        return false;
    }

    private static String normalizeFilterText(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return " " + clean + " ";
    }

    private static boolean containsFilterPhrase(String normalized, String... phrases) {
        for (String phrase : phrases) {
            String needle = normalizeFilterText(phrase);
            if (normalized.contains(needle)) return true;
        }
        return false;
    }

    private void persistSavedPostIds() {
        prefs.edit().putStringSet("savedPostIds", new HashSet<>(savedPostIds)).apply();
    }

    private boolean matchesMedia(RedditPost post) {
        if (media.equals("all")) return true;
        if (media.equals("image")) {
            return post.mediaKind == RedditPost.MediaKind.IMAGE
                    || post.mediaKind == RedditPost.MediaKind.GALLERY;
        }
        if (media.equals("video")) {
            return post.mediaKind == RedditPost.MediaKind.VIDEO
                    || post.mediaKind == RedditPost.MediaKind.GIF;
        }
        return false;
    }

    private boolean showingHiddenLibrary() {
        return screen == Screen.FAVORITES && favoritesView.equals("hidden");
    }

    private void replacePosts(List<RedditPost> items) {
        if (items != null) {
            ArrayList<RedditPost> nsfwOnly = new ArrayList<>();
            for (RedditPost candidate : items) {
                if (candidate != null && candidate.nsfw) nsfwOnly.add(candidate);
            }
            items = nsfwOnly;
        }

        lastFullscreenPostId = "";
        mediaReadyPostIds.clear();
        mediaFailedPostIds.clear();
        boolean hiddenLibrary = showingHiddenLibrary();
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        boolean randomFeed = screen == Screen.HOME && sort.equals("random");
        postAdapter.setHiddenMode(hiddenLibrary);

        Set<String> ids = new HashSet<>();
        Set<String> mediaKeys = new HashSet<>();
        ArrayList<RedditPost> visible = new ArrayList<>();
        if (items != null) {
            for (RedditPost post : items) {
                if (post == null || post.id == null || post.id.isEmpty()) continue;
                if (!hiddenLibrary && !favoritesSaved
                        && (isReadHiddenForDiscovery(post)
                        || isSavedForUnread(post)
                        || isContentBlocked(post))) continue;

                String key = canonicalPostKey(post);
                if (key.isEmpty() || !ids.add(key)) continue;
                if (randomFeed) {
                    String mediaKey = canonicalMediaKey(post);
                    if (!mediaKey.isEmpty() && !mediaKeys.add(mediaKey)) continue;
                }
                visible.add(post);
            }
        }
        postAdapter.setPosts(visible);
        gridAdapter.setPosts(visible);
        if (!visible.isEmpty()) hideStatus();
    }

    private void prefetchSubredditReservoir() {
        if (screen != Screen.HOME || !context.equals("subreddit")
                || subreddit == null || subreddit.isEmpty()) return;

        prefetchScrolllerSubredditIfNeeded();
        prefetchHistoricalSubredditIfNeeded(false);

        if (!loading && postAdapter.getItemCount() < 300 && after != null && !after.isEmpty()) {
            final int generation = archivePrefetchGeneration;
            root.postDelayed(() -> {
                if (generation != archivePrefetchGeneration) return;
                if (screen == Screen.HOME && context.equals("subreddit")
                        && !loading && postAdapter.getItemCount() < 300
                        && after != null && !after.isEmpty()) {
                    loadFeed(false);
                }
            }, 40);
            return;
        }

        if (sort.equals("random")
                && (postAdapter.getItemCount() >= 300 || after == null || after.isEmpty())) {
            prefetchRandomSubredditArchiveIfNeeded();
        }
    }

private void showQualityBrowseSheet() {
        showPresetSubmenu();
    }



    private void addQualityCategoryButton(
            LinearLayout body,
            BottomSheetDialog dialog,
            String key,
            String label) {
        boolean selected = qualityCategory.equals(key);
        Button button = sheetButton(label + (selected ? "  ✓" : ""));
        body.addView(button, sectionButtonParams());
        button.setOnClickListener(v -> {
            qualityCategory = key;
            dialog.dismiss();
            renderQualityCatalog();
        });
    }

    private boolean eligibleQualityUnread(RedditPost post) {
        if (post == null || !matchesMedia(post)) return false;
        if (post.id == null || post.id.isEmpty()) return false;
        return !isReadHiddenForDiscovery(post)
                && !isSavedForUnread(post)
                && !isContentBlocked(post);
    }

    private boolean matchesQualityBrowse(RedditPost post) {
        if (post == null) return false;

        String category = qualityCategory == null ? "all" : qualityCategory;
        if (category.equals("ultra")) {
            long area = (long) Math.max(0, post.mediaWidth) * Math.max(0, post.mediaHeight);
            int longEdge = Math.max(Math.max(0, post.mediaWidth), Math.max(0, post.mediaHeight));
            if (area < 8_000_000L && longEdge < 3840) return false;
        } else if (category.equals("galleries")) {
            if (post.mediaKind != RedditPost.MediaKind.GALLERY) return false;
        } else if (category.equals("images")) {
            if (post.mediaKind != RedditPost.MediaKind.IMAGE) return false;
        } else if (category.startsWith("subreddit:")) {
            String target = category.substring("subreddit:".length());
            String actual = post.subreddit == null ? "" : post.subreddit.toLowerCase(Locale.US);
            if (!actual.equals(target)) return false;
        }

        String queryText = qualityQuery == null ? "" : qualityQuery.trim().toLowerCase(Locale.US);
        if (queryText.isEmpty()) return true;
        String haystack = ((post.title == null ? "" : post.title)
                + " r/" + (post.subreddit == null ? "" : post.subreddit)
                + " u/" + (post.author == null ? "" : post.author))
                .toLowerCase(Locale.US);
        return haystack.contains(queryText);
    }

private void loadQualityCollection(boolean reset) {
        context = "home";
        sort = "random";
        loadFeed(reset);
    }



    private boolean qualityContextValid(int generation) {
        return generation == qualityCrawlGeneration
                && screen == Screen.HOME
                && context.equals("quality");
    }

    private void renderQualityCatalog() {
        ArrayList<RedditPost> visible = new ArrayList<>();
        for (RedditPost post : qualityCatalog.values()) {
            if (post == null || !matchesMedia(post)) continue;
            if (post.id == null || post.id.isEmpty()) continue;
            if (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post)) continue;
            if (!matchesQualityBrowse(post)) continue;
            visible.add(post);
        }
        orderQualityPosts(visible);
        replacePosts(visible);
        if (visible.isEmpty()) {
            boolean narrowed = (qualityQuery != null && !qualityQuery.trim().isEmpty())
                    || (qualityCategory != null && !qualityCategory.equals("all"));
            if (narrowed) {
                setStatus("No unread Quality posts match this search/category.", false);
            } else {
                setStatus(qualityCrawlRunning
                        ? "Finding high-resolution Reddit media…"
                        : "No unread high-resolution media is cached yet.", qualityCrawlRunning);
            }
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

    private void orderQualityPosts(ArrayList<RedditPost> posts) {
        if (sort.equals("random")) {
            Collections.shuffle(posts);
            return;
        }
        if (sort.equals("new") || sort.equals("rising")) {
            posts.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
            return;
        }
        posts.sort((a, b) -> {
            int quality = Long.compare(qualityRank(b), qualityRank(a));
            if (quality != 0) return quality;
            return Integer.compare(b.score, a.score);
        });
    }

    private void startQualityCrawlIfNeeded(int generation) {
        if (!qualityContextValid(generation) || qualityCrawlRunning || qualityCrawlDone) return;
        qualityCrawlRunning = true;
        qualityAuthorHits.clear();
        crawlQualitySeed(generation, 0, 0);
    }

    private void crawlQualitySeed(int generation, int seedIndex, int windowIndex) {
        if (!qualityContextValid(generation)) return;
        if (seedIndex >= QUALITY_SEED_SUBREDDITS.length) {
            crawlLearnedQualityAuthors(generation);
            return;
        }

        String community = QUALITY_SEED_SUBREDDITS[seedIndex];
        String timeframe = QUALITY_TIME_WINDOWS[windowIndex];
        String path = "/r/" + enc(community)
                + "/top.json?limit=100&raw_json=1&show=all&t=" + enc(timeframe);
        engine.get(path, result -> {
            if (!qualityContextValid(generation)) return;
            if (result.ok) {
                acceptQualityListing(result.jsonObject());
            }

            int nextWindow = windowIndex + 1;
            int nextSeed = seedIndex;
            if (nextWindow >= QUALITY_TIME_WINDOWS.length) {
                nextWindow = 0;
                nextSeed++;
            }
            final int scheduledSeed = nextSeed;
            final int scheduledWindow = nextWindow;
            root.postDelayed(
                    () -> crawlQualitySeed(generation, scheduledSeed, scheduledWindow),
                    180L);
        });
    }

    private void acceptQualityListing(JSONObject rootJson) {
        JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
        JSONArray children = data != null ? data.optJSONArray("children") : null;
        if (children == null) return;

        ArrayList<RedditPost> additions = new ArrayList<>();
        for (int i = 0; i < children.length(); i++) {
            RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
            if (post == null || !qualifiesForQualityCatalog(post)) continue;
            if (post.id == null || post.id.isEmpty()) continue;

            boolean fresh = qualityCatalog.putIfAbsent(post.id, post) == null;
            if (!fresh) continue;

            if (post.author != null && !post.author.isEmpty() && !"[deleted]".equals(post.author)) {
                int weight = qualityRank(post) >= 10_000_000L ? 2 : 1;
                qualityAuthorHits.put(
                        post.author,
                        qualityAuthorHits.getOrDefault(post.author, 0) + weight);
            }

            if (matchesMedia(post)
                    && !isReadHiddenForDiscovery(post)
                    && !isSavedForUnread(post)
                    && !isContentBlocked(post)) {
                additions.add(post);
            }
        }

        if (!additions.isEmpty()) {
            orderQualityPosts(additions);
            appendUnique(additions);
            hideStatus();
        }
    }

    private boolean qualifiesForQualityCatalog(RedditPost post) {
        if (post.mediaKind != RedditPost.MediaKind.IMAGE
                && post.mediaKind != RedditPost.MediaKind.GALLERY) return false;
        int width = Math.max(0, post.mediaWidth);
        int height = Math.max(0, post.mediaHeight);
        if (width <= 0 || height <= 0) return false;

        long area = (long) width * height;
        int longEdge = Math.max(width, height);
        int shortEdge = Math.min(width, height);
        int gallerySize = post.imageUrls != null ? post.imageUrls.size() : 0;

        boolean highResolution = area >= 6_000_000L
                && longEdge >= 3000
                && shortEdge >= 1400;
        boolean richGallery = post.mediaKind == RedditPost.MediaKind.GALLERY
                && gallerySize >= 3
                && area >= 4_000_000L
                && longEdge >= 2400
                && shortEdge >= 1200;
        return highResolution || richGallery;
    }

    private long qualityRank(RedditPost post) {
        if (post == null) return 0L;
        long area = (long) Math.max(0, post.mediaWidth) * Math.max(0, post.mediaHeight);
        int gallerySize = post.imageUrls != null ? post.imageUrls.size() : 0;
        long galleryBonus = post.mediaKind == RedditPost.MediaKind.GALLERY
                ? Math.min(24, gallerySize) * 600_000L : 0L;
        long redditSignal = Math.min(50_000, Math.max(0, post.score)) * 250L;
        return area + galleryBonus + redditSignal;
    }

    private void crawlLearnedQualityAuthors(int generation) {
        if (!qualityContextValid(generation)) return;
        ArrayList<Map.Entry<String, Integer>> ranked = new ArrayList<>(qualityAuthorHits.entrySet());
        ranked.removeIf(entry -> entry.getValue() < 2);
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        ArrayList<String> authors = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ranked) {
            authors.add(entry.getKey());
            if (authors.size() >= 16) break;
        }
        crawlQualityAuthor(generation, authors, 0);
    }

    private void crawlQualityAuthor(int generation, ArrayList<String> authors, int index) {
        if (!qualityContextValid(generation)) return;
        if (index >= authors.size()) {
            finishQualityCrawl(generation);
            return;
        }

        String author = authors.get(index);
        String path = "/user/" + enc(author)
                + "/submitted.json?limit=100&raw_json=1&show=all&sort=top&t=all";
        engine.get(path, result -> {
            if (!qualityContextValid(generation)) return;
            if (result.ok) acceptQualityListing(result.jsonObject());
            root.postDelayed(() -> crawlQualityAuthor(generation, authors, index + 1), 180L);
        });
    }

    private void finishQualityCrawl(int generation) {
        if (!qualityContextValid(generation)) return;
        qualityCrawlRunning = false;
        qualityCrawlDone = true;
        QualityCatalogStore.save(this, new ArrayList<>(qualityCatalog.values()));
        if (postAdapter.getItemCount() == 0) {
            setStatus("No unread high-resolution media remains in the quality catalog.", false);
        } else {
            hideStatus();
        }
        updateChrome();
    }

    private void prefetchHistoricalSubredditIfNeeded(boolean forceFallback) {
        if (historicalPrefetchRunning || historicalPrefetchDone) return;
        if (screen != Screen.HOME || !context.equals("subreddit")) return;
        if (subreddit == null || subreddit.isEmpty()) return;
        if (!forceFallback && !sort.equals("random")) return;

        historicalPrefetchRunning = true;
        final int generation = archivePrefetchGeneration;
        final String targetSubreddit = subreddit;
        ArcticShiftClient.crawlSubreddit(targetSubreddit, 2400, new ArcticShiftClient.CrawlCallback() {
            @Override
            public void onBatch(JSONArray items) {
                if (!historicalSubredditContextValid(generation, targetSubreddit)) return;
                ArrayList<RedditPost> additions = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    RedditPost post = redditPostFromArcticArchive(items.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!feedSeenPostIds.add(canonicalPostKey(post))) continue;
                    if (isReadHiddenForDiscovery(post)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    additions.add(post);
                }
                if (!additions.isEmpty()) {
                    if (sort.equals("random")) Collections.shuffle(additions);
                    appendUnique(additions);
                    hideStatus();
                }
            }

            @Override
            public void onComplete() {
                if (generation != archivePrefetchGeneration) return;
                historicalPrefetchRunning = false;
                historicalPrefetchDone = true;
                if (historicalSubredditContextValid(generation, targetSubreddit)
                        && postAdapter.getItemCount() == 0) {
                    setStatus("No unread live or archived media remains in r/" + targetSubreddit + ".", false);
                }
            }

            @Override
            public void onError(String error) {
                if (generation != archivePrefetchGeneration) return;
                historicalPrefetchRunning = false;
                historicalPrefetchDone = true;
                if (historicalSubredditContextValid(generation, targetSubreddit)
                        && postAdapter.getItemCount() == 0) {
                    setStatus("Historical r/" + targetSubreddit + " unavailable: " + error, false);
                }
            }
        });
    }

    private boolean historicalSubredditContextValid(int generation, String targetSubreddit) {
        return generation == archivePrefetchGeneration
                && screen == Screen.HOME
                && context.equals("subreddit")
                && subreddit != null
                && subreddit.equalsIgnoreCase(targetSubreddit);
    }

    private void loadHistoricalUserProfile(boolean replaceEmpty) {
        if (profileUser == null || profileUser.isEmpty()) return;
        final String targetUser = profileUser;
        ArcticShiftClient.crawlAuthor(targetUser, 2400, new ArcticShiftClient.CrawlCallback() {
            private boolean replaced = false;

            @Override
            public void onBatch(JSONArray items) {
                if (screen != Screen.USER || !targetUser.equalsIgnoreCase(profileUser)) return;
                ArrayList<RedditPost> additions = archivePostsFromArray(items);
                if (additions.isEmpty()) return;
                if (replaceEmpty && !replaced && postAdapter.getItemCount() == 0) {
                    replaced = true;
                    replacePosts(additions);
                } else {
                    appendUnique(additions);
                }
                hideStatus();
                updateChrome();
            }

            @Override
            public void onComplete() {
                if (screen != Screen.USER || !targetUser.equalsIgnoreCase(profileUser)) return;
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("No unread archived media found for u/" + targetUser + ".", false);
                }
            }

            @Override
            public void onError(String error) {
                if (screen != Screen.USER || !targetUser.equalsIgnoreCase(profileUser)) return;
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Archived u/" + targetUser + " unavailable: " + error, false);
                }
            }
        });
    }

    private boolean tryHistoricalDirectSearch() {
        if (query == null) return false;
        String clean = query.trim();
        String lower = clean.toLowerCase(Locale.US);

        if (lower.startsWith("r/") || lower.startsWith("/r/")) {
            String target = clean.substring(lower.startsWith("/r/") ? 3 : 2).trim();
            target = target.replaceFirst("/.*$", "");
            if (!target.matches("[A-Za-z0-9_]{1,64}")) return false;
            loadHistoricalSearchCollection("subreddit", target);
            return true;
        }

        if (lower.startsWith("u/") || lower.startsWith("/u/")) {
            String target = clean.substring(lower.startsWith("/u/") ? 3 : 2).trim();
            target = target.replaceFirst("/.*$", "");
            if (!target.matches("[A-Za-z0-9_-]{1,32}")) return false;
            loadHistoricalSearchCollection("author", target);
            return true;
        }

        if (lower.startsWith("post:")) {
            String id = clean.substring(5).trim();
            loadHistoricalPostLookup(id);
            return true;
        }

        return false;
    }

    private void loadHistoricalSearchCollection(String kind, String target) {
        loading = true;
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        setStatus("Searching historical " + (kind.equals("author") ? "u/" : "r/") + target + "…", true);

        ArcticShiftClient.CrawlCallback callback = new ArcticShiftClient.CrawlCallback() {
            @Override
            public void onBatch(JSONArray items) {
                if (screen != Screen.SEARCH) return;
                ArrayList<RedditPost> additions = archivePostsFromArray(items);
                if (!additions.isEmpty()) {
                    appendUnique(additions);
                    hideStatus();
                }
            }

            @Override
            public void onComplete() {
                if (screen != Screen.SEARCH) return;
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("No unread archived media found for "
                            + (kind.equals("author") ? "u/" : "r/") + target + ".", false);
                } else {
                    hideStatus();
                }
                updateChrome();
            }

            @Override
            public void onError(String error) {
                if (screen != Screen.SEARCH) return;
                loading = false;
                if (postAdapter.getItemCount() == 0) {
                    setStatus("Historical lookup failed: " + error, false);
                }
            }
        };

        if (kind.equals("author")) ArcticShiftClient.crawlAuthor(target, 2400, callback);
        else ArcticShiftClient.crawlSubreddit(target, 2400, callback);
    }

    private void loadHistoricalPostLookup(String redditId) {
        loading = true;
        replacePosts(new ArrayList<>());
        setStatus("Looking up archived post…", true);
        ArcticShiftClient.lookupPost(redditId, new ArcticShiftClient.LookupCallback() {
            @Override
            public void onComplete(JSONArray items) {
                if (screen != Screen.SEARCH) return;
                loading = false;
                ArrayList<RedditPost> posts = archivePostsFromArray(items);
                replacePosts(posts);
                if (postAdapter.getItemCount() == 0) {
                    setStatus("That archived post has no recoverable unread media.", false);
                } else {
                    hideStatus();
                }
                updateChrome();
            }

            @Override
            public void onError(String error) {
                if (screen != Screen.SEARCH) return;
                loading = false;
                setStatus("Archived post lookup failed: " + error, false);
            }
        });
    }

    private ArrayList<RedditPost> archivePostsFromArray(JSONArray items) {
        ArrayList<RedditPost> posts = new ArrayList<>();
        if (items == null) return posts;
        for (int i = 0; i < items.length(); i++) {
            RedditPost post = redditPostFromArcticArchive(items.optJSONObject(i));
            if (post == null || !matchesMedia(post)) continue;
            if (post.id == null || post.id.isEmpty()) continue;
            if (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post)) continue;
            posts.add(post);
        }
        return posts;
    }

    private RedditPost redditPostFromArcticArchive(JSONObject item) {
        if (item == null) return null;
        try {
            JSONObject data = new JSONObject(item.toString());
            String id = data.optString("id", "");
            if (id.isEmpty()) return null;
            if (data.optString("name", "").isEmpty()) data.put("name", "t3_" + id);
            if (data.optString("permalink", "").isEmpty()) {
                String sr = data.optString("subreddit", "");
                data.put("permalink", "/r/" + sr + "/comments/" + id + "/");
            }
            if (data.optString("url_overridden_by_dest", "").isEmpty()
                    && !data.optString("url", "").isEmpty()) {
                data.put("url_overridden_by_dest", data.optString("url", ""));
            }
            JSONObject child = new JSONObject();
            child.put("data", data);
            RedditPost archived = RedditPost.fromChild(child);
            if (archived != null) archived.sourceOrigin = "archive";
            return archived;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void prefetchScrolllerSubredditIfNeeded() {
        if (scrolllerPrefetchRunning || scrolllerPrefetchDone) return;
        if (screen != Screen.HOME || !context.equals("subreddit") || !sort.equals("random")) return;
        if (subreddit == null || subreddit.isEmpty()) return;

        scrolllerPrefetchRunning = true;
        final int generation = archivePrefetchGeneration;
        final String targetSubreddit = subreddit;
        ScrolllerClient.crawlSubreddit(targetSubreddit, 600, new ScrolllerClient.Callback() {
            @Override
            public void onBatch(JSONArray items) {
                if (!scrolllerContextStillValid(generation, targetSubreddit)) return;
                ArrayList<RedditPost> additions = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    RedditPost post = RedditPost.fromScrolller(items.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!feedSeenPostIds.add(canonicalPostKey(post))) continue;
                    if (isReadHiddenForDiscovery(post) || isSavedForUnread(post) || isContentBlocked(post)) continue;
                    additions.add(post);
                    if (postAdapter.getItemCount() + additions.size() >= 1400) break;
                }
                if (!additions.isEmpty()) {
                    Collections.shuffle(additions);
                    appendUnique(additions);
                    hideStatus();
                }
            }

            @Override
            public void onComplete() {
                if (generation != archivePrefetchGeneration) return;
                scrolllerPrefetchRunning = false;
                scrolllerPrefetchDone = true;
            }

            @Override
            public void onError(String error) {
                if (generation != archivePrefetchGeneration) return;
                // Supplemental source failure must never break the Reddit feed.
                scrolllerPrefetchRunning = false;
                scrolllerPrefetchDone = true;
            }
        });
    }

    private boolean scrolllerContextStillValid(int generation, String targetSubreddit) {
        return generation == archivePrefetchGeneration
                && screen == Screen.HOME
                && context.equals("subreddit")
                && sort.equals("random")
                && subreddit != null
                && subreddit.equalsIgnoreCase(targetSubreddit)
                && postAdapter.getItemCount() < 1400;
    }

    private void prefetchRandomSubredditArchiveIfNeeded() {
        if (archivePrefetchRunning || archivePrefetchDone) return;
        if (screen != Screen.HOME || !context.equals("subreddit") || !sort.equals("random")) return;
        if (subreddit == null || subreddit.isEmpty()) return;

        archivePrefetchRunning = true;
        final int generation = archivePrefetchGeneration;
        final String targetSubreddit = subreddit;
        fetchSubredditArchiveSource(generation, targetSubreddit, 0, "", new HashSet<>(), 0);
    }

    private boolean archiveContextStillValid(int generation, String targetSubreddit) {
        return generation == archivePrefetchGeneration
                && screen == Screen.HOME
                && context.equals("subreddit")
                && sort.equals("random")
                && subreddit != null
                && subreddit.equalsIgnoreCase(targetSubreddit);
    }

    private void fetchSubredditArchiveSource(
            int generation,
            String targetSubreddit,
            int source,
            String cursor,
            Set<String> sourceSeenCursors,
            int page) {
        if (!archiveContextStillValid(generation, targetSubreddit)) return;
        if (postAdapter.getItemCount() >= 800 || source >= 5) {
            archivePrefetchRunning = false;
            archivePrefetchDone = true;
            return;
        }
        if (!cursor.isEmpty() && !sourceSeenCursors.add(cursor)) {
            fetchSubredditArchiveSource(
                    generation, targetSubreddit, source + 1, "", new HashSet<>(), 0);
            return;
        }

        String path = archiveListingPath(targetSubreddit, source, cursor);
        engine.get(path, result -> {
            if (!archiveContextStillValid(generation, targetSubreddit)) return;
            if (!result.ok) {
                fetchSubredditArchiveSource(
                        generation, targetSubreddit, source + 1, "", new HashSet<>(), 0);
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            ArrayList<RedditPost> additions = new ArrayList<>();
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!feedSeenPostIds.add(canonicalPostKey(post))) continue;
                    if (isReadHiddenForDiscovery(post) || isSavedForUnread(post) || isContentBlocked(post)) continue;
                    additions.add(post);
                }
            }
            if (!additions.isEmpty()) {
                Collections.shuffle(additions);
                appendUnique(additions);
            }

            String next = data != null ? data.optString("after", "") : "";
            boolean canContinue = !next.isEmpty()
                    && !sourceSeenCursors.contains(next)
                    && page < 4
                    && postAdapter.getItemCount() < 800;
            if (canContinue) {
                fetchSubredditArchiveSource(
                        generation,
                        targetSubreddit,
                        source,
                        next,
                        sourceSeenCursors,
                        page + 1);
            } else {
                fetchSubredditArchiveSource(
                        generation, targetSubreddit, source + 1, "", new HashSet<>(), 0);
            }
        });
    }

    private String archiveListingPath(String targetSubreddit, int source, String cursor) {
        String base = "/r/" + enc(targetSubreddit);
        String path;
        switch (source) {
            case 0:
                path = base + "/top.json?limit=100&raw_json=1&show=all&t=all";
                break;
            case 1:
                path = base + "/top.json?limit=100&raw_json=1&show=all&t=year";
                break;
            case 2:
                path = base + "/top.json?limit=100&raw_json=1&show=all&t=month";
                break;
            case 3:
                path = base + "/hot.json?limit=100&raw_json=1&show=all";
                break;
            default:
                path = base + "/controversial.json?limit=100&raw_json=1&show=all&t=all";
                break;
        }
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        return path;
    }

    private void appendUnique(List<RedditPost> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        if (layoutMode.equals("fullscreen") && pager != null
                && pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            deferredAppends.addAll(incoming);
            scheduleDeferredAppend();
            return;
        }
        appendUniqueNow(incoming);
    }

    private void scheduleDeferredAppend() {
        if (deferredAppendScheduled || root == null) return;
        deferredAppendScheduled = true;
        root.postDelayed(this::flushDeferredAppends, 120L);
    }

    private void flushDeferredAppends() {
        deferredAppendScheduled = false;
        if (deferredAppends.isEmpty()) return;
        if (layoutMode.equals("fullscreen") && pager != null
                && pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            scheduleDeferredAppend();
            return;
        }
        ArrayList<RedditPost> batch = new ArrayList<>(deferredAppends);
        deferredAppends.clear();
        appendUniqueNow(batch);
    }

    private void appendUniqueNow(List<RedditPost> incoming) {
        if (incoming != null) {
            ArrayList<RedditPost> nsfwOnly = new ArrayList<>();
            for (RedditPost candidate : incoming) {
                if (candidate != null && candidate.nsfw) nsfwOnly.add(candidate);
            }
            incoming = nsfwOnly;
        }

        if (showingHiddenLibrary() || incoming == null || incoming.isEmpty()) return;
        boolean favoritesSaved = screen == Screen.FAVORITES && favoritesView.equals("saved");
        boolean randomFeed = screen == Screen.HOME && sort.equals("random");

        Set<String> ids = new HashSet<>();
        Set<String> mediaKeys = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty()) ids.add(key);
            if (randomFeed) {
                String mediaKey = canonicalMediaKey(post);
                if (!mediaKey.isEmpty()) mediaKeys.add(mediaKey);
            }
        }

        ArrayList<RedditPost> unique = new ArrayList<>();
        for (RedditPost post : incoming) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (!favoritesSaved
                    && (hiddenPosts.containsKey(post.id)
                    || isSavedForUnread(post)
                    || isContentBlocked(post))) continue;

            String key = canonicalPostKey(post);
            if (key.isEmpty() || !ids.add(key)) continue;
            if (randomFeed) {
                String mediaKey = canonicalMediaKey(post);
                if (!mediaKey.isEmpty() && !mediaKeys.add(mediaKey)) continue;
            }
            unique.add(post);
        }
        postAdapter.appendPosts(unique);
        gridAdapter.appendPosts(unique);
        if (!unique.isEmpty()) hideStatus();
    }

    private void prefetchHistoricalTopAllIfNeeded(int generation) {
        if (topAllArchiveRunning || topAllArchiveDone) return;
        if (generation != feedGeneration || screen != Screen.HOME) return;
        if (!context.equals("subreddit") || subreddit == null || subreddit.isEmpty()) return;
        if (!sort.equals("top") || !topTime.equals("all")) return;

        topAllArchiveRunning = true;
        final String targetSubreddit = subreddit;
        final int targetArchiveGeneration = archivePrefetchGeneration;
        final ArrayList<RedditPost> historical = new ArrayList<>();
        final Set<String> historicalIds = new HashSet<>();

        ArcticShiftClient.crawlSubreddit(targetSubreddit, 4000, new ArcticShiftClient.CrawlCallback() {
            @Override
            public void onBatch(JSONArray items) {
                if (!topAllContextStillValid(generation, targetArchiveGeneration, targetSubreddit)) return;
                for (int i = 0; i < items.length(); i++) {
                    RedditPost post = redditPostFromArcticArchive(items.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (isReadHiddenForDiscovery(post)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    String key = canonicalPostKey(post);
                    if (!key.isEmpty() && historicalIds.add(key)) historical.add(post);
                }
            }

            @Override
            public void onComplete() {
                if (!topAllContextStillValid(generation, targetArchiveGeneration, targetSubreddit)) return;
                topAllArchiveRunning = false;
                topAllArchiveDone = true;
                mergeHistoricalTopAll(generation, targetArchiveGeneration, targetSubreddit, historical);
            }

            @Override
            public void onError(String error) {
                if (!topAllContextStillValid(generation, targetArchiveGeneration, targetSubreddit)) return;
                topAllArchiveRunning = false;
                topAllArchiveDone = true;
            }
        });
    }

    private boolean topAllContextStillValid(
            int generation, int targetArchiveGeneration, String targetSubreddit) {
        return generation == feedGeneration
                && targetArchiveGeneration == archivePrefetchGeneration
                && screen == Screen.HOME
                && context.equals("subreddit")
                && sort.equals("top")
                && topTime.equals("all")
                && subreddit != null
                && subreddit.equalsIgnoreCase(targetSubreddit);
    }

    private void mergeHistoricalTopAll(
            int generation,
            int targetArchiveGeneration,
            String targetSubreddit,
            ArrayList<RedditPost> historical) {
        if (!topAllContextStillValid(generation, targetArchiveGeneration, targetSubreddit)) return;
        if (layoutMode.equals("fullscreen") && pager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) {
            root.postDelayed(() -> mergeHistoricalTopAll(
                    generation, targetArchiveGeneration, targetSubreddit, historical), 140L);
            return;
        }

        RedditPost current = postAdapter.getPost(pager.getCurrentItem());
        String currentKey = canonicalPostKey(current);
        ArrayList<RedditPost> merged = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (RedditPost post : postAdapter.getPosts()) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty() && ids.add(key)) merged.add(post);
        }
        for (RedditPost post : historical) {
            String key = canonicalPostKey(post);
            if (!key.isEmpty() && ids.add(key)) merged.add(post);
        }

        merged.sort((a, b) -> {
            int scoreOrder = Integer.compare(b.score, a.score);
            if (scoreOrder != 0) return scoreOrder;
            return Long.compare(b.createdUtc, a.createdUtc);
        });
        replacePosts(merged);

        if (!currentKey.isEmpty()) {
            for (int i = 0; i < postAdapter.getItemCount(); i++) {
                if (currentKey.equals(canonicalPostKey(postAdapter.getPost(i)))) {
                    pager.setCurrentItem(i, false);
                    if (layoutMode.equals("grid")) gridView.scrollToPosition(i);
                    break;
                }
            }
        }
        hideStatus();
        updateChrome();
    }

    private String canonicalPostKey(RedditPost post) {
        if (post == null) return "";
        String id = post.id == null ? "" : post.id.trim().toLowerCase(Locale.US);
        if (id.startsWith("t3_")) id = id.substring(3);
        if (!id.isEmpty()) return "id:" + id;
        String permalink = post.permalink == null ? "" : post.permalink.trim().toLowerCase(Locale.US);
        return permalink.isEmpty() ? "" : "path:" + permalink;
    }

    private String canonicalMediaKey(RedditPost post) {
        if (post == null) return "";
        String value = post.videoUrl != null && !post.videoUrl.isEmpty()
                ? post.videoUrl : post.sourceUrl;
        if ((value == null || value.isEmpty()) && post.imageUrls != null && !post.imageUrls.isEmpty()) {
            value = post.imageUrls.get(0);
        }
        if (value == null) return "";
        String clean = value.trim().toLowerCase(Locale.US);
        int queryAt = clean.indexOf('?');
        if (queryAt >= 0) clean = clean.substring(0, queryAt);
        int hashAt = clean.indexOf('#');
        if (hashAt >= 0) clean = clean.substring(0, hashAt);
        return clean;
    }

    private void openFullscreenAt(int position) {
        layoutMode = "fullscreen";
        fullscreenChromeVisible = true;
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
            gridAdapter.releaseAllPlayers();
            pager.setVisibility(View.GONE);
            gridView.setVisibility(View.GONE);
            postAdapter.setActivePosition(-1);
            setFullscreenChrome(true);
            return;
        }
        boolean grid = layoutMode.equals("grid");
        if (grid) {
            boolean wasHidden = gridView.getVisibility() != View.VISIBLE;
            pager.setVisibility(View.GONE);
            gridView.setVisibility(View.VISIBLE);
            if (wasHidden) gridAdapter.notifyDataSetChanged();
            postAdapter.setActivePosition(-1);
        } else {
            gridView.stopScroll();
            gridAdapter.releaseAllPlayers();
            gridView.setVisibility(View.GONE);
            pager.setVisibility(View.VISIBLE);
            postAdapter.setActivePosition(pager.getCurrentItem());
        }
        setFullscreenChrome(grid || fullscreenChromeVisible);
    }

    private void loadSearchInternal() {
        if (tryHistoricalDirectSearch()) return;
        if (!engine.isReady() || query.isEmpty()) return;
        screen = Screen.SEARCH;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);

        final int generation = ++searchGeneration;
        loading = true;
        setStatus("Searching " + searchScopeDescription() + " for “" + query + "”…", true);
        updateChrome();

        if (searchScope.equals("collector")) {
            ArrayList<RedditPost> matches = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (RedditPost post : searchCollectorSnapshot) {
                if (post == null || post.id == null || post.id.isEmpty()) continue;
                if (!matchesMedia(post) || !matchesLocalSearch(post, query)) continue;
                if (!localWithinTopTime(post)) continue;
                if (ids.add(post.id)) matches.add(post);
            }
            sortLocalSearch(matches);
            finishLocalSearchCollection(generation, matches);
            return;
        }

        if (searchScope.equals("favorites")) {
            if (username.isEmpty()) {
                loading = false;
                setStatus("Sign in to search Favorites.", false);
                return;
            }
            fetchFavoritesSearchPage(
                    generation, "", new ArrayList<>(), new HashSet<>(), new HashSet<>(), 0, 0);
            return;
        }

        if (searchScope.equals("category")) {
            ArrayList<String> groups = categorySearchGroups();
            if (groups.isEmpty()) {
                loading = false;
                setStatus("This category has no communities to search.", false);
                return;
            }
            fetchRemoteSearchGroup(
                    generation, groups, 0, "", new ArrayList<>(),
                    new HashSet<>(), new HashSet<>(), 0, 0);
            return;
        }

        if (searchScope.equals("subreddit")) {
            String target = cleanSubredditName(searchSubreddit);
            if (target.isEmpty()) {
                loading = false;
                setStatus("Choose a subreddit in Search source first.", false);
                return;
            }
            ArrayList<String> groups = new ArrayList<>();
            groups.add(target);
            fetchRemoteSearchGroup(
                    generation, groups, 0, "", new ArrayList<>(),
                    new HashSet<>(), new HashSet<>(), 0, 0);
            return;
        }

        if (searchScope.equals("subscribed")) {
            if (username.isEmpty()) {
                loading = false;
                setStatus("Sign in to search subscriptions.", false);
                return;
            }
            ArrayList<String> groups = subscriptionSearchGroups();
            if (groups.isEmpty()) {
                loading = false;
                setStatus("No subscribed communities are available to search.", false);
                return;
            }
            fetchRemoteSearchGroup(
                    generation, groups, 0, "", new ArrayList<>(),
                    new HashSet<>(), new HashSet<>(), 0, 0);
            return;
        }

        ArrayList<String> global = new ArrayList<>();
        global.add("__global__");
        fetchRemoteSearchGroup(
                generation, global, 0, "", new ArrayList<>(),
                new HashSet<>(), new HashSet<>(), 0, 0);
    }

    private boolean searchStillValid(int generation) {
        return generation == searchGeneration && screen == Screen.SEARCH;
    }

    private ArrayList<String> subscriptionSearchGroups() {
        ArrayList<String> names = new ArrayList<>();
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) names.add(clean);
        }
        ArrayList<String> groups = new ArrayList<>();
        for (int i = 0; i < names.size(); i += 10) {
            StringBuilder group = new StringBuilder();
            int end = Math.min(names.size(), i + 10);
            for (int j = i; j < end; j++) {
                if (group.length() > 0) group.append('+');
                group.append(names.get(j));
            }
            if (group.length() > 0) groups.add(group.toString());
        }
        return groups;
    }

    private void fetchRemoteSearchGroup(
            int generation,
            ArrayList<String> groups,
            int groupIndex,
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors,
            int page,
            int rawFetched) {
        if (!searchStillValid(generation)) return;
        if (groupIndex >= groups.size()) {
            finishSearchCollection(generation, collected);
            return;
        }
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            fetchRemoteSearchGroup(
                    generation, groups, groupIndex + 1, "", collected,
                    seenPostIds, new HashSet<>(), 0, 0);
            return;
        }

        String group = groups.get(groupIndex);
        String path = remoteSearchPath(group, cursor, rawFetched);
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (!result.ok) {
                // A failed group should not poison successful groups. Global and
                // single-subreddit searches still expose the useful Reddit error
                // when nothing at all has been returned.
                if (groupIndex + 1 < groups.size()) {
                    final int nextGroup = groupIndex + 1;
                    root.postDelayed(() -> fetchRemoteSearchGroup(
                            generation, groups, nextGroup, "", collected,
                            seenPostIds, new HashSet<>(), 0, 0), 220L);
                    return;
                }
                if (!collected.isEmpty()) {
                    finishSearchCollection(generation, collected);
                } else {
                    loading = false;
                    setStatus("Search failed: " + friendlyError(result), false);
                }
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            int pageRawCount = children != null ? children.length() : 0;
            int before = collected.size();
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!seenPostIds.add(post.id)) continue;
                    if (isReadHiddenForDiscovery(post)
                            || isSavedForUnread(post)
                            || isContentBlocked(post)) continue;
                    collected.add(post);
                }
            }

            // First useful page becomes visible now rather than after the entire
            // multi-page/multi-group search finishes.
            if (before == 0 && !collected.isEmpty() && postAdapter.getItemCount() == 0) {
                replacePosts(new ArrayList<>(collected));
                hideStatus();
                updateChrome();
            }

            String next = data != null ? data.optString("after", "") : "";
            int nextRawFetched = rawFetched + pageRawCount;
            boolean deepSingleSource = searchScope.equals("global")
                    || searchScope.equals("subreddit");
            int maxPages = deepSingleSource ? 4 : 1;
            boolean canContinue = !next.isEmpty()
                    && !seenCursors.contains(next)
                    && page + 1 < maxPages;

            if (canContinue) {
                root.postDelayed(() -> fetchRemoteSearchGroup(
                        generation, groups, groupIndex, next, collected,
                        seenPostIds, seenCursors, page + 1, nextRawFetched), 180L);
                return;
            }

            if (groupIndex + 1 < groups.size()) {
                final int nextGroup = groupIndex + 1;
                setStatus("Searching " + searchScopeDescription() + " · "
                        + (nextGroup + 1) + "/" + groups.size() + "…", true);
                root.postDelayed(() -> fetchRemoteSearchGroup(
                        generation, groups, nextGroup, "", collected,
                        seenPostIds, new HashSet<>(), 0, 0), 220L);
                return;
            }

            finishSearchCollection(generation, collected);
        });
    }

    private String remoteSearchPath(String group, String cursor, int rawFetched) {
        String searchSort = (sort.equals("random") || sort.equals("oldest")) ? "new"
                : sort.equals("best") ? "relevance"
                : sort.equals("rising") ? "new" : sort;
        boolean global = group.equals("__global__");
        String base = global ? "/search.json" : "/r/" + group + "/search.json";
        String path = base + "?q=" + enc(query)
                + "&type=link&limit=100&raw_json=1&show=all&sort=" + enc(searchSort)
                + "&restrict_sr=" + (global ? "off" : "on");
        if (sort.equals("top")) path += "&t=" + enc(topTime);
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        if (rawFetched > 0) path += "&count=" + rawFetched;
        return path;
    }

    private void finishSearchCollection(int generation, ArrayList<RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        loading = false;
        if (sort.equals("random")) {
            Collections.shuffle(collected);
        } else if (sort.equals("oldest")) {
            collected.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
        }
        replacePosts(collected);
        finishSearchUi(collected.size());
        startHiddenSearchLeadIns(generation);
        startCategoryMetadataLeadIns(generation);
    
        startSubredditNameLeadIns(generation);
}

    private void fetchFavoritesSearchPage(
            int generation,
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors,
            int page,
            int rawFetched) {
        if (!searchStillValid(generation)) return;
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            sortLocalSearch(collected);
            finishLocalSearchCollection(generation, collected);
            return;
        }

        String path = "/user/" + enc(username)
                + "/saved.json?limit=100&raw_json=1&show=all";
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        if (rawFetched > 0) path += "&count=" + rawFetched;
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (!result.ok) {
                loading = false;
                setStatus("Favorites search failed: " + friendlyError(result), false);
                return;
            }

            JSONObject rootJson = result.jsonObject();
            JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            int pageRawCount = children != null ? children.length() : 0;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post == null || !matchesMedia(post)) continue;
                    if (post.id == null || post.id.isEmpty()) continue;
                    if (!seenPostIds.add(post.id)) continue;
                    if (!matchesLocalSearch(post, query)) continue;
                    if (!localWithinTopTime(post)) continue;
                    collected.add(post);
                }
            }

            String next = data != null ? data.optString("after", "") : "";
            int nextRawFetched = rawFetched + pageRawCount;
            boolean canContinue = !next.isEmpty()
                    && !seenCursors.contains(next)
                    && page < 49;
            if (canContinue) {
                fetchFavoritesSearchPage(
                        generation, next, collected, seenPostIds, seenCursors,
                        page + 1, nextRawFetched);
                return;
            }
            sortLocalSearch(collected);
            finishLocalSearchCollection(generation, collected);
        });
    }

private boolean matchesLocalSearch(RedditPost post, String value) {
        if (post == null || !post.nsfw) return false;
        String normalized = FuzzySearch.normalize(value);
        if (normalized.isEmpty()) return true;
        return fuzzyPostSearchScore(post, value) >= FuzzySearch.thresholdFor(normalized.length());
    }



    private String normalizeLocalSearch(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US)
                .replaceAll("(^|\\s)[ru]/", "$1")
                .replaceAll("[^a-z0-9_]+", " ")
                .trim();
    }
    private int localSearchRelevance(RedditPost post) {
    return fuzzyPostSearchScore(post, query);
}



    private double localHotScore(RedditPost post) {
        long now = System.currentTimeMillis() / 1000L;
        long age = Math.max(1L, now - Math.max(0L, post.createdUtc));
        double hours = age / 3600.0;
        return (Math.max(0, post.score) + 1.0) / Math.pow(hours + 2.0, 0.82);
    }

    private boolean localWithinTopTime(RedditPost post) {
        if (!sort.equals("top") || topTime.equals("all")) return true;
        long seconds;
        if (topTime.equals("hour")) seconds = 3600L;
        else if (topTime.equals("day")) seconds = 86400L;
        else if (topTime.equals("week")) seconds = 604800L;
        else if (topTime.equals("month")) seconds = 2678400L;
        else if (topTime.equals("year")) seconds = 31536000L;
        else return true;
        long now = System.currentTimeMillis() / 1000L;
        return post.createdUtc > 0L && now - post.createdUtc <= seconds;
    }

    private void sortLocalSearch(ArrayList<RedditPost> items) {
        if (sort.equals("random")) {
            Collections.shuffle(items);
        } else if (sort.equals("new")) {
            items.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
        } else if (sort.equals("oldest")) {
            items.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
        } else if (sort.equals("top")) {
            items.sort((a, b) -> Integer.compare(b.score, a.score));
        } else if (sort.equals("hot")) {
            items.sort((a, b) -> Double.compare(localHotScore(b), localHotScore(a)));
        } else {
            items.sort((a, b) -> {
                int relevance = Integer.compare(localSearchRelevance(b), localSearchRelevance(a));
                if (relevance != 0) return relevance;
                return Integer.compare(b.score, a.score);
            });
        }
    }

    private void finishLocalSearchCollection(int generation, ArrayList<RedditPost> collected) {
        if (!searchStillValid(generation)) return;
        loading = false;
        lastFullscreenPostId = "";
        mediaReadyPostIds.clear();
        mediaFailedPostIds.clear();
        postAdapter.setHiddenMode(false);
        postAdapter.setPosts(collected);
        gridAdapter.setPosts(collected);
        finishSearchUi(collected.size());
    }

    private void finishSearchUi(int count) {
        if (count <= 0) {
            setStatus("No matching media found for “" + query + "” in "
                    + searchScopeDescription() + ".", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

    private String categoryLabelsForSubreddit(String subredditName) {
        String clean = cleanSubredditName(subredditName);
        if (clean.isEmpty()) return "";
        StringBuilder labels = new StringBuilder();
        HashSet<String> seen = new HashSet<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            boolean contains = false;
            for (String community : row[3].split(",")) {
                if (clean.equalsIgnoreCase(cleanSubredditName(community))) {
                    contains = true;
                    break;
                }
            }
            if (!contains) continue;
            for (int i = 0; i < 3; i++) {
                String label = row[i];
                String key = label.toLowerCase(Locale.US);
                if (seen.add(key)) {
                    if (labels.length() > 0) labels.append(' ');
                    labels.append(label);
                }
            }
        }
        return labels.toString();
    }

    private Set<String> hiddenSearchAllowedSubreddits() {
        if (searchScope.equals("subreddit")) {
            HashSet<String> one = new HashSet<>();
            String clean = cleanSubredditName(searchSubreddit);
            if (!clean.isEmpty()) one.add(clean.toLowerCase(Locale.US));
            return one;
        }
        if (searchScope.equals("subscribed")) {
            HashSet<String> allowed = new HashSet<>();
            for (Subscription sub : subscriptions) {
                if (sub == null) continue;
                String clean = cleanSubredditName(sub.name);
                if (!clean.isEmpty()) allowed.add(clean.toLowerCase(Locale.US));
            }
            return allowed;
        }
        if (searchScope.equals("category")) {
            HashSet<String> allowed = new HashSet<>();
            for (String community : searchCategoryCommunities) {
                String clean = cleanSubredditName(community);
                if (!clean.isEmpty()) allowed.add(clean.toLowerCase(Locale.US));
            }
            return allowed;
        }
        return null;
    }

    private void startHiddenSearchLeadIns(int generation) {
        if (!searchStillValid(generation) || query == null || query.trim().isEmpty()) return;
        if (searchScope.equals("collector") || searchScope.equals("favorites")) return;

        Set<String> allowed = hiddenSearchAllowedSubreddits();
        String exactSubreddit = searchScope.equals("subreddit")
                ? cleanSubredditName(searchSubreddit) : "";
        PullPushSearchClient.searchLeadIns(query, allowed, exactSubreddit,
                new PullPushSearchClient.Callback() {
            @Override
            public void onComplete(ArrayList<String> submissionIds) {
                if (!searchStillValid(generation) || submissionIds == null || submissionIds.isEmpty()) return;
                fetchSearchLeadInPosts(generation, submissionIds, 0, new ArrayList<>());
            }

            @Override
            public void onError(String error) {
                // Hidden index failure must never break ordinary Reddit search.
            }
        });
    }

    private void fetchSearchLeadInPosts(
            int generation,
            ArrayList<String> ids,
            int offset,
            ArrayList<RedditPost> additions) {
        if (!searchStillValid(generation)) return;
        if (offset >= ids.size()) {
            if (!additions.isEmpty()) {
                if (sort.equals("random")) Collections.shuffle(additions);
                else if (sort.equals("oldest")) additions.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
                appendUnique(additions);
                if (postAdapter.getItemCount() > 0) hideStatus();
                updateChrome();
            }
            return;
        }

        int end = Math.min(ids.size(), offset + 50);
        StringBuilder joined = new StringBuilder();
        for (int i = offset; i < end; i++) {
            String id = ids.get(i);
            if (id == null || id.isEmpty()) continue;
            if (joined.length() > 0) joined.append(',');
            joined.append(id);
        }
        if (joined.length() == 0) {
            fetchSearchLeadInPosts(generation, ids, end, additions);
            return;
        }

        String path = "/by_id/" + joined + ".json?raw_json=1&show=all";
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !matchesMedia(post)) continue;
                        if (post.id == null || post.id.isEmpty()) continue;
                        if (isReadHiddenForDiscovery(post)
                                || isSavedForUnread(post)
                                || isContentBlocked(post)) continue;
                        additions.add(post);
                    }
                }
            }
            fetchSearchLeadInPosts(generation, ids, end, additions);
        });
    }

    private ArrayList<String> categoryCommunitiesMatchingSearch(String value) {
        String nq = normalizeLocalSearch(value);
        ArrayList<String> matches = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if (nq.isEmpty()) return matches;
        for (String[] row : CURATED_CATEGORY_ROWS) {
            String labels = normalizeLocalSearch(row[0] + " " + row[1] + " " + row[2]);
            if (!labels.contains(nq) && !nq.contains(labels)) continue;
            for (String community : row[3].split(",")) {
                String clean = cleanSubredditName(community);
                String key = clean.toLowerCase(Locale.US);
                if (!clean.isEmpty() && seen.add(key)) matches.add(clean);
            }
        }
        return matches;
    }

    private void startCategoryMetadataLeadIns(int generation) {
        if (!searchStillValid(generation)) return;
        ArrayList<String> communities = categoryCommunitiesMatchingSearch(query);
        if (communities.isEmpty()) return;

        Set<String> allowed = hiddenSearchAllowedSubreddits();
        if (allowed != null && !allowed.isEmpty()) {
            communities.removeIf(name -> !allowed.contains(name.toLowerCase(Locale.US)));
        }
        if (communities.isEmpty()) return;

        ArrayList<String> groups = new ArrayList<>();
        for (int i = 0; i < communities.size(); i += 10) {
            StringBuilder group = new StringBuilder();
            int end = Math.min(communities.size(), i + 10);
            for (int j = i; j < end; j++) {
                if (group.length() > 0) group.append('+');
                group.append(communities.get(j));
            }
            if (group.length() > 0) groups.add(group.toString());
        }
        fetchCategoryMetadataGroup(generation, groups, 0);
    }

    private void fetchCategoryMetadataGroup(int generation, ArrayList<String> groups, int index) {
        if (!searchStillValid(generation) || index >= groups.size()) return;
        String path = "/r/" + groups.get(index) + "/new.json?limit=100&raw_json=1&show=all";
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            if (result.ok) {
                ArrayList<RedditPost> additions = parseListing(result.jsonObject(), true);
                appendUnique(additions);
                if (postAdapter.getItemCount() > 0) hideStatus();
            }
            fetchCategoryMetadataGroup(generation, groups, index + 1);
        });
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
                setStatus("Live profile unavailable; loading archived u/" + profileUser + "…", true);
                loadHistoricalUserProfile(true);
                return;
            }
            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            JSONArray children = data != null ? data.optJSONArray("children") : null;
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                    if (post != null && matchesMedia(post)
                            && post.id != null && !post.id.isEmpty()
                            && !isReadHiddenForDiscovery(post)) collected.add(post);
                }
            }
            String next = data != null ? data.optString("after", "") : "";
            if (collected.size() < 45 && !next.isEmpty() && page < 20) {
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
            loadHistoricalUserProfile(false);
        });
    }

    private void loadFavorites() {
        if (screen != Screen.FAVORITES) pushCurrentState();
        favoriteSort = "random";
        favoritesView = "saved";
        screen = Screen.FAVORITES;
        profileUser = "";
        updateChrome();
        loadFavoritesInternal();
    }

    private void loadFavoritesInternal() {
        if (favoritesView.equals("hidden")) {
            loadHiddenPostsView();
            return;
        }
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
        fetchFavoritesPage("", new ArrayList<>(), new HashSet<>(), new HashSet<>());
    }

    private void fetchFavoritesPage(
            String cursor,
            ArrayList<RedditPost> collected,
            Set<String> seenPostIds,
            Set<String> seenCursors) {
        if (!cursor.isEmpty() && !seenCursors.add(cursor)) {
            finishFavoritesCollection(collected);
            return;
        }

        String path = "/user/" + enc(username) + "/saved.json?limit=100&raw_json=1";
        if (!cursor.isEmpty()) path += "&after=" + enc(cursor);
        engine.get(path, result -> {
            if (!result.ok) {
                loading = false;
                setStatus("Favorites failed: " + friendlyError(result), false);
                return;
            }

            JSONObject root = result.jsonObject();
            JSONObject data = root != null ? root.optJSONObject("data") : null;
            ArrayList<RedditPost> pageItems = parseListing(root, true);
            for (RedditPost post : pageItems) {
                if (post.id == null || post.id.isEmpty()) continue;
                if (seenPostIds.add(post.id)) collected.add(post);
            }

            String next = data != null ? data.optString("after", "") : "";
            if (!next.isEmpty() && !seenCursors.contains(next)) {
                fetchFavoritesPage(next, collected, seenPostIds, seenCursors);
                return;
            }

            finishFavoritesCollection(collected);
        });
    }

    private void finishFavoritesCollection(ArrayList<RedditPost> collected) {
        loading = false;
        for (RedditPost savedPost : collected) {
            if (savedPost != null && savedPost.id != null && !savedPost.id.isEmpty()) {
                savedPostIds.add(savedPost.id);
            }
        }
        persistSavedPostIds();
        boolean hiddenChanged = false;
        for (RedditPost savedPost : collected) {
            if (savedPost != null && savedPost.id != null && hiddenPosts.containsKey(savedPost.id)) {
                hiddenPosts.remove(savedPost.id);
                readHideStore.deleteAsync(savedPost.id);
                hiddenChanged = true;
            }
        }
        if (hiddenChanged) trimHiddenPostCache();
        applyFavoriteOrdering(collected);
        replacePosts(collected);
        if (collected.isEmpty()) {
            setStatus("No unique saved media posts match this filter.", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

    private void applyFavoriteOrdering(ArrayList<RedditPost> items) {
        switch (favoriteSort) {
            case "saved_oldest":
                Collections.reverse(items);
                break;
            case "post_newest":
                items.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
                break;
            case "post_oldest":
                items.sort((a, b) -> Long.compare(a.createdUtc, b.createdUtc));
                break;
            case "random":
                Collections.shuffle(items);
                break;
            default:
                break;
        }
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

        TextView filterTitle = sectionTitle("Content filters");
        LinearLayout.LayoutParams filterTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        filterTitleParams.topMargin = dp(14);
        body.addView(filterTitle, filterTitleParams);
        body.addView(bodyText("These filters reject posts from Unread discovery only. Saved and Hidden libraries are unchanged."));

        Button peopleFilter = sheetButton("People / content · " + ContentTaxonomy.label(peopleFilterMask));
        body.addView(peopleFilter, sectionButtonParams());
        peopleFilter.setOnClickListener(v -> showPeopleFilterSheet());

        Button goreFilter = sheetButton("Gore / blood · " + (blockGoreContent ? "Blocked" : "Allowed"));
        body.addView(goreFilter, sectionButtonParams());
        goreFilter.setOnClickListener(v -> {
            blockGoreContent = !blockGoreContent;
            prefs.edit().putBoolean("blockGoreContent", blockGoreContent).apply();
            renderAccount();
        });

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
        addCuratedCommunityCategories(body, dialog);

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

    private void showFavoritesViewSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Favorites library");
        Button saved = sheetButton("Saved posts" + (favoritesView.equals("saved") ? "  ✓" : ""));
        Button hidden = sheetButton("Hidden posts · " + hiddenPosts.size()
                + (favoritesView.equals("hidden") ? "  ✓" : ""));
        body.addView(saved, sectionButtonParams());
        body.addView(hidden, sectionButtonParams());
        saved.setOnClickListener(v -> {
            favoritesView = "saved";
            dialog.dismiss();
            loadFavoritesInternal();
            updateChrome();
        });
        hidden.setOnClickListener(v -> {
            favoritesView = "hidden";
            dialog.dismiss();
            loadHiddenPostsView();
            updateChrome();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void addCuratedCommunityCategories(
            LinearLayout body, BottomSheetDialog dialog) {
        Button categories = sheetButton("Categories");
        body.addView(categories, sectionButtonParams());
        categories.setOnClickListener(v -> {
            dialog.dismiss();
            showCategoryRoot();
        });
    }

    private void addCuratedCommunityCategory(
            LinearLayout body,
            BottomSheetDialog dialog,
            String title,
            String[] communities) {
        TextView category = sectionTitle(title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(16);
        body.addView(category, titleParams);

        for (String community : communities) {
            Button button = sheetButton("r/" + community);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                openSubredditFeed(community);
            });
        }
    }

private void showCategoryRoot() {
        showCategorySafety("NSFW");
    }



    private JSONObject customCategoryStore() {
        try {
            return new JSONObject(prefs.getString("customCategories", "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private ArrayList<String> customCategoryNames() {
        ArrayList<String> names = new ArrayList<>();
        JSONObject store = customCategoryStore();
        JSONArray keys = store.names();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String name = keys.optString(i, "").trim();
                if (!name.isEmpty()) names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private String[] customCategoryCommunities(String name) {
        ArrayList<String> communities = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        JSONObject store = customCategoryStore();
        JSONArray values = store.optJSONArray(name);
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                String clean = cleanSubredditName(values.optString(i, ""));
                String key = clean.toLowerCase(Locale.US);
                if (!clean.isEmpty() && seen.add(key)) communities.add(clean);
            }
        }
        return communities.toArray(new String[0]);
    }

    private void showAddCustomCategorySheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Add category");
        scroll.addView(body);

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Category name");
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(0xFF8E8E8E);
        name.setBackground(rounded(0xE51A1A1A, 14));
        name.setPadding(dp(12), 0, dp(12), 0);
        body.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        EditText first = new EditText(this);
        first.setSingleLine(true);
        first.setHint("First subreddit");
        first.setTextColor(Color.WHITE);
        first.setHintTextColor(0xFF8E8E8E);
        first.setBackground(rounded(0xE51A1A1A, 14));
        first.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        fieldParams.topMargin = dp(10);
        body.addView(first, fieldParams);

        EditText second = new EditText(this);
        second.setSingleLine(true);
        second.setHint("Second subreddit");
        second.setTextColor(Color.WHITE);
        second.setHintTextColor(0xFF8E8E8E);
        second.setBackground(rounded(0xE51A1A1A, 14));
        second.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        secondParams.topMargin = dp(10);
        body.addView(second, secondParams);

        EditText more = new EditText(this);
        more.setSingleLine(false);
        more.setMinLines(2);
        more.setHint("More subreddits (optional, comma separated)");
        more.setTextColor(Color.WHITE);
        more.setHintTextColor(0xFF8E8E8E);
        more.setBackground(rounded(0xE51A1A1A, 14));
        more.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        moreParams.topMargin = dp(10);
        body.addView(more, moreParams);

        Button save = sheetButton("Save category");
        body.addView(save, sectionButtonParams());
        save.setOnClickListener(v -> {
            String categoryName = name.getText().toString().trim();
            String firstName = cleanSubredditName(first.getText().toString());
            String secondName = cleanSubredditName(second.getText().toString());
            if (categoryName.isEmpty()) {
                name.setError("Name this category");
                name.requestFocus();
                return;
            }
            if (firstName.isEmpty()) {
                first.setError("Pick a subreddit");
                first.requestFocus();
                return;
            }
            if (secondName.isEmpty()) {
                second.setError("Pick a second subreddit");
                second.requestFocus();
                return;
            }

            ArrayList<String> communities = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            String[] raw = (firstName + "," + secondName + "," + more.getText().toString())
                    .split("[,\\n\\r\\t ]+");
            for (String value : raw) {
                String clean = cleanSubredditName(value);
                String key = clean.toLowerCase(Locale.US);
                if (!clean.isEmpty() && seen.add(key)) communities.add(clean);
            }
            if (communities.size() < 2) {
                second.setError("Choose two different subreddits");
                second.requestFocus();
                return;
            }

            try {
                JSONObject store = customCategoryStore();
                JSONArray values = new JSONArray();
                for (String community : communities) values.put(community);
                store.put(categoryName, values);
                prefs.edit().putString("customCategories", store.toString()).apply();
            } catch (Exception ignored) {
                return;
            }
            dialog.dismiss();
            showCategoryRoot();
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCustomCategory(String name) {
        String[] communities = customCategoryCommunities(name);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody(name);
        scroll.addView(body);

        Button search = sheetButton("Search this category");
        body.addView(search, sectionButtonParams());
        search.setOnClickListener(v -> {
            dialog.dismiss();
            openCategorySearch(name, communities);
        });

        for (String community : communities) {
            Button button = sheetButton("r/" + community);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                openSubredditFeed(community);
            });
        }

        Button delete = sheetButton("Delete category");
        body.addView(delete, sectionButtonParams());
        delete.setOnClickListener(v -> {
            try {
                JSONObject store = customCategoryStore();
                store.remove(name);
                prefs.edit().putString("customCategories", store.toString()).apply();
            } catch (Exception ignored) {}
            dialog.dismiss();
            showCategoryRoot();
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCategorySafety(String safety) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody(safety + " categories");
        scroll.addView(body);

        HashSet<String> seen = new HashSet<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            if (!row[0].equals(safety) || !seen.add(row[1])) continue;
            String category = row[1];
            Button button = sheetButton(category);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                showCategoryFolder(safety, category);
            });
        }

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCategoryFolder(String safety, String category) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody(safety + " · " + category);
        scroll.addView(body);

        String[] all = categoryCommunities(safety, category, null);
        Button searchAll = sheetButton("Search all in " + category);
        body.addView(searchAll, sectionButtonParams());
        searchAll.setOnClickListener(v -> {
            dialog.dismiss();
            openCategorySearch(category, all);
        });

        HashSet<String> seen = new HashSet<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            if (!row[0].equals(safety) || !row[1].equals(category) || !seen.add(row[2])) continue;
            String subcategory = row[2];
            Button button = sheetButton(subcategory);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                showCategorySubfolder(safety, category, subcategory);
            });
        }

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCategorySubfolder(String safety, String category, String subcategory) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody(category + " · " + subcategory);
        scroll.addView(body);

        String[] communities = categoryCommunities(safety, category, subcategory);
        Button searchFolder = sheetButton("Search this folder");
        body.addView(searchFolder, sectionButtonParams());
        searchFolder.setOnClickListener(v -> {
            dialog.dismiss();
            openCategorySearch(category + " · " + subcategory, communities);
        });

        for (String community : communities) {
            if (community == null || community.trim().isEmpty()) continue;
            String clean = cleanSubredditName(community);
            if (clean.isEmpty()) continue;
            Button button = sheetButton("r/" + clean);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
                dialog.dismiss();
                openSubredditFeed(clean);
            });
        }

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCategoryMatches(String term) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Category search · " + term);
        scroll.addView(body);

        String needle = term.toLowerCase(Locale.US);
        int matches = 0;

        for (String name : customCategoryNames()) {
            StringBuilder customHaystack = new StringBuilder(name);
            for (String community : customCategoryCommunities(name)) {
                customHaystack.append(' ').append(community);
            }
            if (!customHaystack.toString().toLowerCase(Locale.US).contains(needle)) continue;
            matches++;
            Button result = sheetButton("My category · " + name);
            body.addView(result, sectionButtonParams());
            result.setOnClickListener(v -> {
                dialog.dismiss();
                showCustomCategory(name);
            });
        }

        for (String[] row : CURATED_CATEGORY_ROWS) {
            String haystack = (row[0] + " " + row[1] + " " + row[2] + " " + row[3])
                    .toLowerCase(Locale.US);
            if (!haystack.contains(needle)) continue;
            matches++;
            String safety = row[0];
            String category = row[1];
            String subcategory = row[2];
            Button result = sheetButton(safety + " · " + category + " · " + subcategory);
            body.addView(result, sectionButtonParams());
            result.setOnClickListener(v -> {
                dialog.dismiss();
                showCategorySubfolder(safety, category, subcategory);
            });
        }

        if (matches == 0) {
            body.addView(bodyText("No categories matched that search."));
        }

        dialog.setContentView(scroll);
        dialog.show();
    }

    private String[] categoryCommunities(String safety, String category, @Nullable String subcategory) {
        ArrayList<String> communities = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            if (!row[0].equals(safety) || !row[1].equals(category)) continue;
            if (subcategory != null && !row[2].equals(subcategory)) continue;
            for (String community : row[3].split(",")) {
                String clean = cleanSubredditName(community);
                if (!clean.isEmpty() && seen.add(clean.toLowerCase(Locale.US))) {
                    communities.add(clean);
                }
            }
        }
        return communities.toArray(new String[0]);
    }

    private void openCategorySearch(String title, String[] communities) {
        if (communities == null || communities.length == 0) return;
        if (screen != Screen.SEARCH) pushCurrentState();

        searchCategoryName = title == null ? "Category" : title;
        searchCategoryCommunities.clear();
        for (String community : communities) {
            String clean = cleanSubredditName(community);
            if (!clean.isEmpty()) searchCategoryCommunities.add(clean);
        }
        if (searchCategoryCommunities.isEmpty()) return;

        searchScope = "category";
        query = "";
        prefs.edit().remove("lastSearch").apply();
        screen = Screen.SEARCH;
        context = "search";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        replacePosts(new ArrayList<>());
        pager.setCurrentItem(0, false);
        searchInput.setText("");
        updateChrome();
        setStatus("Search " + searchCategoryName + " by title, community, or creator.", false);
        searchInput.requestFocus();
    }

    private ArrayList<String> categorySearchGroups() {
        ArrayList<String> groups = new ArrayList<>();
        for (int i = 0; i < searchCategoryCommunities.size(); i += 10) {
            StringBuilder group = new StringBuilder();
            int end = Math.min(searchCategoryCommunities.size(), i + 10);
            for (int j = i; j < end; j++) {
                if (group.length() > 0) group.append('+');
                group.append(searchCategoryCommunities.get(j));
            }
            if (group.length() > 0) groups.add(group.toString());
        }
        return groups;
    }

    private void showSortSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Choose sorting option");

        String[] values;
        if (screen == Screen.SEARCH) {
            values = new String[]{"best", "hot", "new", "oldest", "top", "random"};
        } else if (screen == Screen.USER) {
            values = new String[]{"best", "new", "oldest", "hot", "top", "random"};
        } else {
            values = new String[]{"best", "hot", "new", "oldest", "top", "rising", "random"};
        }

        for (String value : values) {
            Button button = sheetButton(label(value) + (sort.equals(value) ? "  ✓" : ""));
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> {
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

    private void showHiddenManageSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Manage hidden posts");
        scroll.addView(body);

        Button all = sheetButton("Clear hidden list · restore all (" + hiddenPosts.size() + ")");
        Button images = sheetButton("Restore images / galleries");
        Button videos = sheetButton("Restore videos / GIFs");
        body.addView(all, sectionButtonParams());
        body.addView(images, sectionButtonParams());
        body.addView(videos, sectionButtonParams());

        all.setOnClickListener(v -> {
            dialog.dismiss();
            restoreHiddenGroup("all", "");
        });
        images.setOnClickListener(v -> {
            dialog.dismiss();
            restoreHiddenGroup("images", "");
        });
        videos.setOnClickListener(v -> {
            dialog.dismiss();
            restoreHiddenGroup("videos", "");
        });

        ArrayList<String> communities = new ArrayList<>(readHideStore.listCommunities());
        communities.sort(String.CASE_INSENSITIVE_ORDER);
        if (!communities.isEmpty()) {
            TextView title = sectionTitle("Restore by subreddit");
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(14);
            body.addView(title, tp);
            for (String community : communities) {
                Button b = sheetButton("r/" + community);
                body.addView(b, sectionButtonParams());
                b.setOnClickListener(v -> {
                    dialog.dismiss();
                    restoreHiddenGroup("subreddit", community);
                });
            }
        }
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void restoreHiddenGroup(String kind, String community) {
        ArrayList<String> removeIds = new ArrayList<>(readHideStore.deleteGroup(kind, community));
        for (String id : removeIds) {
            hiddenPosts.remove(id);
            feedSeenPostIds.remove(id);
        }
        loadHiddenPostsView();
    }

    private void showFavoriteSortSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Favorites order");
        String[][] values = {
                {"random", "Randomize"},
                {"saved_newest", "Recently saved"},
                {"saved_oldest", "Oldest saved"},
                {"post_newest", "Newest post"},
                {"post_oldest", "Oldest post"}
        };
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (favoriteSort.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                favoriteSort = pair[0];
                dialog.dismiss();
                loadFavoritesInternal();
            });
        }
        dialog.setContentView(body);
        dialog.show();
    }

    private String favoriteSortLabel() {
        switch (favoriteSort) {
            case "saved_newest": return "Recent saved";
            case "saved_oldest": return "Oldest saved";
            case "post_newest": return "Newest post";
            case "post_oldest": return "Oldest post";
            default: return "Random";
        }
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
                {"video", "Videos / GIFs"}
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
                {"grid", "Stream"}
        };
        for (String[] pair : values) {
            Button b = sheetButton(pair[1] + (layoutMode.equals(pair[0]) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                int current = pager.getCurrentItem();
                layoutMode = pair[0];
                fullscreenChromeVisible = true;
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
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Search source");
        scroll.addView(body);

        String[][] values = {
                {"global", "Global Reddit"},
                {"subscribed", "Subscriptions"},
                {"favorites", "Favorites / Saved"},
                {"collector", "Collector · current app collection"}
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

        if (!searchCategoryCommunities.isEmpty()) {
            Button categorySource = sheetButton(
                    "Category · " + searchCategoryName
                    + (searchScope.equals("category") ? "  ✓" : ""));
            body.addView(categorySource, sectionButtonParams());
            categorySource.setOnClickListener(v -> {
                searchScope = "category";
                dialog.dismiss();
                if (!query.isEmpty()) loadSearchInternal();
                updateChrome();
            });
        }

        TextView subTitle = sectionTitle("Subreddit search");
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(16);
        body.addView(subTitle, stp);

        EditText subredditInput = new EditText(this);
        subredditInput.setSingleLine(true);
        subredditInput.setHint("Subreddit name");
        subredditInput.setText(searchSubreddit);
        subredditInput.setTextColor(Color.WHITE);
        subredditInput.setHintTextColor(0xFF8E8E8E);
        subredditInput.setTextSize(14);
        subredditInput.setPadding(dp(12), 0, dp(12), 0);
        subredditInput.setBackground(rounded(0xE51A1A1A, 14));
        body.addView(subredditInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        Button subredditButton = sheetButton(
                searchSubreddit.isEmpty()
                        ? "Use subreddit"
                        : "Subreddit · r/" + searchSubreddit
                        + (searchScope.equals("subreddit") ? "  ✓" : ""));
        body.addView(subredditButton, sectionButtonParams());
        subredditButton.setOnClickListener(v -> {
            String target = cleanSubredditName(subredditInput.getText().toString());
            if (target.isEmpty()) {
                subredditInput.requestFocus();
                return;
            }
            searchSubreddit = target;
            searchScope = "subreddit";
            prefs.edit()
                    .putString("searchScope", searchScope)
                    .putString("searchSubreddit", searchSubreddit)
                    .apply();
            dialog.dismiss();
            if (!query.isEmpty()) loadSearchInternal();
            updateChrome();
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private String cleanSubredditName(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.toLowerCase(Locale.US).startsWith("r/")) clean = clean.substring(2);
        return clean.replaceAll("[^A-Za-z0-9_]", "");
    }

    private String searchScopeDescription() {
        if (searchScope.equals("subscribed")) return "subscriptions";
        if (searchScope.equals("favorites")) return "Favorites";
        if (searchScope.equals("collector")) return "Collector";
        if (searchScope.equals("category")) {
            return searchCategoryName.isEmpty() ? "a category" : searchCategoryName;
        }
        if (searchScope.equals("subreddit")) {
            return searchSubreddit.isEmpty() ? "a subreddit" : "r/" + searchSubreddit;
        }
        return "global Reddit";
    }

    private String searchScopeLabel() {
        if (searchScope.equals("subscribed")) return "Subs";
        if (searchScope.equals("favorites")) return "Saved";
        if (searchScope.equals("collector")) return "Collector";
        if (searchScope.equals("category")) {
            return searchCategoryName.isEmpty() ? "Category" : searchCategoryName;
        }
        if (searchScope.equals("subreddit")) {
            return searchSubreddit.isEmpty() ? "Subreddit" : "r/" + searchSubreddit;
        }
        return "Global";
    }

    private void loadHiddenPostsView() {
        loading = false;
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        ArrayList<RedditPost> items = new ArrayList<>();
        for (RedditPost post : readHideStore.loadRecent(1000)) {
            if (post != null && matchesMedia(post)) items.add(post);
        }
        applyFavoriteOrdering(items);
        replacePosts(items);
        if (items.isEmpty()) {
            setStatus("No locally hidden posts.", false);
        } else if (hiddenPosts.size() > 1000) {
            setStatus("Showing the 1,000 most recent hidden posts of " + hiddenPosts.size() + ".", false);
        } else {
            hideStatus();
        }
        updateChrome();
        restorePendingPosition();
    }

private void setFullscreenReadBaseline(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) {
            lastFullscreenPostId = "";
            fullscreenVisitStartedAtMs = 0L;
            return;
        }
        lastFullscreenPostId = current.id;
        fullscreenVisitStartedAtMs = SystemClock.elapsedRealtime();
    }



private void trackFullscreenVisit(int position) {
        RedditPost current = postAdapter.getPost(position);
        if (current == null || current.id == null || current.id.isEmpty()) return;
        String currentId = current.id;
        long now = SystemClock.elapsedRealtime();

        if (lastFullscreenPostId.isEmpty()) {
            lastFullscreenPostId = currentId;
            fullscreenVisitStartedAtMs = now;
            return;
        }
        if (lastFullscreenPostId.equals(currentId)) return;

        RedditPost previous = null;
        for (RedditPost candidate : postAdapter.getPosts()) {
            if (candidate != null && lastFullscreenPostId.equals(candidate.id)) {
                previous = candidate;
                break;
            }
        }
        long dwellMs = fullscreenVisitStartedAtMs > 0L ? now - fullscreenVisitStartedAtMs : 0L;
        boolean actuallyViewed = previous != null && (
                mediaReadyPostIds.contains(previous.id)
                        || mediaFailedPostIds.contains(previous.id)
                        || dwellMs >= 350L);
        if (actuallyViewed
                && previous.id != null && !previous.id.isEmpty()
                && !previous.saved
                && !savedPostIds.contains(previous.id)
                && !hiddenPosts.containsKey(previous.id)) {
            hiddenPosts.put(previous.id, previous);
            readHideStore.hideAsync(previous);
            trimHiddenPostCache();
        }
        lastFullscreenPostId = currentId;
        fullscreenVisitStartedAtMs = now;
    }



    private void restoreHiddenPost(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        hiddenPosts.remove(post.id);
        feedSeenPostIds.remove(post.id);
        readHideStore.deleteAsync(post.id);
        if (showingHiddenLibrary()) loadHiddenPostsView();
        else reloadCurrent();
    }

    private void loadReadHideState() {
        hiddenPosts.clear();
        try {
            // One-time migration from the old giant SharedPreferences JSON. For a
            // very large legacy value, scan IDs directly instead of constructing a
            // JSONArray/JSONObject graph and duplicating tens of MB on the heap.
            String legacy = prefs.getString("hiddenPosts", "");
            if (legacy != null && !legacy.isEmpty() && !legacy.equals("[]")) {
                LinkedHashSet<String> legacyIds = scanLegacyHiddenIds(legacy);
                if (legacy.length() <= 2_000_000) {
                    ArrayList<RedditPost> legacyPosts = new ArrayList<>();
                    try {
                        JSONArray hidden = new JSONArray(legacy);
                        for (int i = 0; i < hidden.length(); i++) {
                            RedditPost post = postFromJson(hidden.optJSONObject(i));
                            if (post != null && post.id != null && !post.id.isEmpty()) {
                                legacyPosts.add(post);
                            }
                        }
                    } catch (Exception ignored) {}
                    readHideStore.importPosts(legacyPosts);
                }
                readHideStore.importIds(legacyIds);
                // commit() is intentional for the one-time migration so the huge
                // legacy string is removed from the in-memory preference map now.
                prefs.edit()
                        .remove("hiddenPosts")
                        .remove("pendingReadPosts")
                        .commit();
            } else {
                prefs.edit().remove("pendingReadPosts").apply();
            }

            for (String id : readHideStore.loadIds()) {
                if (id != null && !id.isEmpty()) hiddenPosts.put(id, null);
            }
            for (RedditPost post : readHideStore.loadRecent(400)) {
                if (post != null && post.id != null && !post.id.isEmpty()) {
                    hiddenPosts.put(post.id, post);
                }
            }
            trimHiddenPostCache();
        } catch (Exception ignored) {
            prefs.edit().remove("hiddenPosts").remove("pendingReadPosts").apply();
        }
    }

    private LinkedHashSet<String> scanLegacyHiddenIds(String legacy) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (legacy == null || legacy.isEmpty()) return ids;
        final String marker = "\"id\":\"";
        int from = 0;
        while (from < legacy.length()) {
            int start = legacy.indexOf(marker, from);
            if (start < 0) break;
            start += marker.length();
            int end = legacy.indexOf('"', start);
            if (end < 0) break;
            String id = legacy.substring(start, end);
            if (!id.isEmpty()) ids.add(id);
            from = end + 1;
        }
        return ids;
    }

    private void trimHiddenPostCache() {
        int materialized = 0;
        for (RedditPost post : hiddenPosts.values()) if (post != null) materialized++;
        if (materialized <= 400) return;
        for (Map.Entry<String, RedditPost> entry : hiddenPosts.entrySet()) {
            if (materialized <= 400) break;
            if (entry.getValue() != null) {
                entry.setValue(null);
                materialized--;
            }
        }
    }

    private void saveReadHideState() {
        // v3.8.9 intentionally never serializes the full hidden library. Each hide
        // or restore is persisted incrementally by ReadHideStore.
        prefs.edit().remove("hiddenPosts").remove("pendingReadPosts").apply();
        trimHiddenPostCache();
    }

    private JSONObject postToJson(RedditPost post) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", post.id);
        o.put("title", post.title);
        o.put("author", post.author);
        o.put("subreddit", post.subreddit);
        o.put("permalink", post.permalink);
        o.put("sourceUrl", post.sourceUrl);
        o.put("score", post.score);
        o.put("comments", post.comments);
        o.put("createdUtc", post.createdUtc);
        o.put("saved", post.saved);
        o.put("nsfw", post.nsfw);
        o.put("mediaKind", post.mediaKind.name());
        JSONArray images = new JSONArray();
        for (String url : post.imageUrls) images.put(url);
        o.put("imageUrls", images);
        o.put("videoUrl", post.videoUrl);
        o.put("posterUrl", post.posterUrl);
        o.put("mediaWidth", post.mediaWidth);
        o.put("mediaHeight", post.mediaHeight);
        o.put("searchMetadata", post.searchMetadata == null ? "" : post.searchMetadata);
        return o;
    }

    private RedditPost postFromJson(JSONObject o) {
        if (o == null) return null;
        try {
            ArrayList<String> images = new ArrayList<>();
            JSONArray imageArray = o.optJSONArray("imageUrls");
            if (imageArray != null) {
                for (int i = 0; i < imageArray.length(); i++) {
                    String url = imageArray.optString(i, "");
                    if (!url.isEmpty()) images.add(url);
                }
            }
            RedditPost.MediaKind kind = RedditPost.MediaKind.valueOf(
                    o.optString("mediaKind", RedditPost.MediaKind.IMAGE.name()));
            RedditPost post = new RedditPost(
                    o.optString("id", ""),
                    o.optString("title", ""),
                    o.optString("author", ""),
                    o.optString("subreddit", ""),
                    o.optString("permalink", ""),
                    o.optString("sourceUrl", ""),
                    o.optInt("score", 0),
                    o.optInt("comments", 0),
                    o.optLong("createdUtc", 0L),
                    o.optBoolean("saved", false),
                    o.optBoolean("nsfw", false),
                    kind,
                    images,
                    o.optString("videoUrl", ""),
                    o.optString("posterUrl", ""),
                    o.optInt("mediaWidth", 0),
                    o.optInt("mediaHeight", 0));
            post.searchMetadata = o.optString("searchMetadata", "");
            return post;
        } catch (Exception ignored) {
            return null;
        }
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
        boolean subredditSearchEntry = screen == Screen.HOME && context.equals("subreddit");
        topTitle.setVisibility(searchMode ? View.GONE : View.VISIBLE);
        searchInput.setVisibility(searchMode ? View.VISIBLE : View.GONE);
        searchGoButton.setVisibility(
                searchMode || subredditSearchEntry ? View.VISIBLE : View.GONE);
        searchGoButton.setText("Search");

        boolean subredditScreen = screen == Screen.HOME
                && context.equals("subreddit")
                && subreddit != null
                && !subreddit.isEmpty();
        subscribeButton.setVisibility(subredditScreen ? View.VISIBLE : View.GONE);
        if (subredditScreen) {
            boolean subscribed = subscriptionNames.contains(subreddit.toLowerCase(Locale.US));
            subscribeButton.setText(subscribed ? "Leave" : "Join");
        }

        String title;
        if (screen == Screen.FAVORITES) title = "Favorites";
        else if (screen == Screen.ACCOUNT) title = "Account";
        else if (screen == Screen.USER) title = "u/" + profileUser;
        else if (context.equals("subreddit")) title = "r/" + subreddit;
        else if (context.equals("quality")) title = "Quality";
        else title = context.equals("popular") ? "Popular" : "Home";
        topTitle.setText(title);

        feedButton.setText(screen == Screen.SEARCH
                ? searchScopeLabel()
                : screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Hidden" : "Saved")
                : screen == Screen.HOME && context.equals("quality")
                ? "Browse"
                : "Feed");
        sortButton.setText(screen == Screen.FAVORITES
                ? (favoritesView.equals("hidden") ? "Manage" : favoriteSortLabel())
                : label(sort));
        filterButton.setText(media.equals("all") ? "All media"
                : media.equals("image") ? "Images" : "Video/GIF");
        layoutButton.setText(layoutMode.equals("grid") ? "Stream" : "Fullscreen");

        controlRow.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
        feedButton.setVisibility(
                screen == Screen.HOME || screen == Screen.SEARCH || screen == Screen.FAVORITES
                        ? View.VISIBLE : View.GONE);
        sortButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
        filterButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
        layoutButton.setVisibility(screen == Screen.ACCOUNT ? View.GONE : View.VISIBLE);
        applySystemInsets(systemTopPx, systemBottomPx);
        setFullscreenChrome(layoutMode.equals("grid") || fullscreenChromeVisible);
    
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (compactMenuButton != null) compactMenuButton.setVisibility(View.VISIBLE);
}

private void setFullscreenChrome(boolean visible) {
        fullscreenChromeVisible = visible;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (compactMenuButton != null) compactMenuButton.setVisibility(View.VISIBLE);
        if (postAdapter != null) postAdapter.setChromeVisible(visible);
    }



    private void toggleFullscreenChrome() {
        if (!layoutMode.equals("fullscreen") || screen == Screen.ACCOUNT) return;
        setFullscreenChrome(!fullscreenChromeVisible);
    }

    private void toggleSubredditSubscription() {
        if (screen != Screen.HOME || !context.equals("subreddit")
                || subreddit == null || subreddit.isEmpty()) return;

        if (username.isEmpty()) {
            openBrowser(
                    REDDIT + "/login/?dest=" + enc(REDDIT + "/r/" + subreddit + "/"),
                    BrowserPurpose.LOGIN);
            return;
        }

        final String target = subreddit;
        final String key = target.toLowerCase(Locale.US);
        final boolean currentlySubscribed = subscriptionNames.contains(key);

        subscribeButton.setEnabled(false);
        subscribeButton.setText(currentlySubscribed ? "Leaving…" : "Joining…");

        String body = "action=" + enc(currentlySubscribed ? "unsub" : "sub")
                + "&sr_name=" + enc(target)
                + "&uh=" + enc(modhash);
        engine.postForm("/api/subscribe", body, result -> {
            subscribeButton.setEnabled(true);
            if (!result.ok) {
                setStatus((currentlySubscribed ? "Unsubscribe" : "Subscribe")
                        + " failed: " + friendlyError(result), false);
                updateChrome();
                return;
            }

            if (currentlySubscribed) {
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
            updateChrome();
        });
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
    public void onOpenUser(RedditPost post) {
        if (post == null) return;
        String author = post.author == null ? "" : post.author.trim();
        if (!isDeletedAuthorName(author)) {
            openUserProfile(author);
            return;
        }

        setStatus("Resolving deleted account from archive…", true);
        ArcticShiftClient.lookupPost(post.id, new ArcticShiftClient.LookupCallback() {
            @Override
            public void onComplete(JSONArray items) {
                String archivedAuthor = "";
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        String candidate = item.optString("author", "").trim();
                        if (!isDeletedAuthorName(candidate)) {
                            archivedAuthor = candidate;
                            break;
                        }
                    }
                }
                if (archivedAuthor.isEmpty()) {
                    setStatus("The archived post exists, but its original account identity is unavailable.", false);
                    return;
                }
                openUserProfile(archivedAuthor);
            }

            @Override
            public void onError(String error) {
                setStatus("Could not resolve the deleted account from archive: " + error, false);
            }
        });
    }

    private boolean isDeletedAuthorName(String value) {
        if (value == null) return true;
        String clean = value.trim().toLowerCase(Locale.US);
        return clean.isEmpty()
                || clean.equals("[deleted]")
                || clean.equals("deleted")
                || clean.equals("[removed]")
                || clean.equals("removed");
    }

    @Override
    public void onToggleChrome() {
        toggleFullscreenChrome();
    }

    @Override
    public void onMediaReady(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        mediaFailedPostIds.remove(post.id);
        mediaReadyPostIds.add(post.id);
    }

    @Override
    public void onMediaFailed(RedditPost post) {
        if (post == null || post.id == null || post.id.isEmpty()) return;
        if (!mediaReadyPostIds.contains(post.id)) mediaFailedPostIds.add(post.id);
    }

    @Override
    public void onRestoreHidden(RedditPost post) {
        restoreHiddenPost(post);
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
                if (post.saved) {
                    if (post.id != null && !post.id.isEmpty()) savedPostIds.add(post.id);
                    persistSavedPostIds();
                    if (post.id != null && hiddenPosts.containsKey(post.id)) {
                        hiddenPosts.remove(post.id);
                        readHideStore.deleteAsync(post.id);
                        if (showingHiddenLibrary()) {
                            loadHiddenPostsView();
                            return;
                        }
                    }
                    // Save is an explicit state transition, not swipe/read progression.
                    // Remove only this manually-saved item from the active Unread
                    // collection and establish a fresh baseline for the item that
                    // shifts into its place. The automatic read path still never
                    // mutates the live pager collection.
                    if (screen != Screen.FAVORITES) {
                        removeSavedFromUnread(post.id);
                        return;
                    }
                    postAdapter.refreshPost(post);
                    return;
                }

                if (post.id != null && !post.id.isEmpty()) {
                    savedPostIds.remove(post.id);
                    feedSeenPostIds.remove(canonicalPostKey(post));
                }
                persistSavedPostIds();
                if (screen == Screen.FAVORITES && favoritesView.equals("saved")) {
                    // Favorites is passive, so a clean library reload is safe and
                    // makes an Unsave disappear from the Saved folder immediately.
                    loadFavoritesInternal();
                    return;
                }
                postAdapter.refreshPost(post);
            } else {
                setStatus("Save failed: " + friendlyError(result), false);
            }
        });
    }

    private void removeSavedFromUnread(String id) {
        if (id == null || id.isEmpty()) return;
        int previousPosition = pager != null ? pager.getCurrentItem() : 0;

        // A button-triggered removal is never allowed to masquerade as a swipe.
        fullscreenUserGesture = false;
        pendingUserFullscreenPosition = -1;
        lastFullscreenPostId = "";

        postAdapter.removePostById(id);
        gridAdapter.removePostById(id);

        int count = postAdapter.getItemCount();
        if (count <= 0) {
            setStatus("No unread media remains in this collection.", false);
            return;
        }

        int target = Math.max(0, Math.min(previousPosition, count - 1));
        pager.setCurrentItem(target, false);
        if (layoutMode.equals("grid")) {
            gridView.scrollToPosition(target);
        } else {
            setFullscreenReadBaseline(target);
            postAdapter.setActivePosition(target);
        }
        hideStatus();
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
        if (statusPanel == null || statusText == null) return;

        final int token = ++statusGeneration;
        if (root != null && statusDismissRunnable != null) {
            root.removeCallbacks(statusDismissRunnable);
        }

        // Never cover the feed with a loading card/spinner. The pager remains
        // interactive while network/archive/media work continues in the background.
        if (spinning) {
            progress.setVisibility(View.GONE);
            statusText.setText("");
            statusPanel.setVisibility(View.GONE);
            statusDismissRunnable = null;
            return;
        }

        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            hideStatus();
            return;
        }

        progress.setVisibility(View.GONE);
        statusText.setText(text);
        statusPanel.setVisibility(View.VISIBLE);

        // Informational/error banners are self-clearing so a late successful
        // async result can never leave a stale failure message stuck on screen.
        statusDismissRunnable = () -> {
            if (token != statusGeneration || statusPanel == null) return;
            statusPanel.setVisibility(View.GONE);
            statusText.setText("");
            statusDismissRunnable = null;
        };
        if (root != null) root.postDelayed(statusDismissRunnable, 3500L);
    }

    private void hideStatus() {
        statusGeneration++;
        if (root != null && statusDismissRunnable != null) {
            root.removeCallbacks(statusDismissRunnable);
        }
        statusDismissRunnable = null;
        if (progress != null) progress.setVisibility(View.GONE);
        if (statusText != null) statusText.setText("");
        if (statusPanel != null) statusPanel.setVisibility(View.GONE);
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

    private void handleBackNavigation() {
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

        // Only root Home can leave the app, and even there require confirmation.
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Exit Reddit Media?")
                .setMessage("There are no previous pages in this app history.")
                .setNegativeButton("Stay", null)
                .setPositiveButton("Exit", (dialog, which) -> moveTaskToBack(true))
                .show();
    }


private void installCompactNavigation() {
        if (compactMenuButton != null) return;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        compactMenuButton = new Button(this);
        compactMenuButton.setText("☰");
        compactMenuButton.setTextColor(Color.WHITE);
        compactMenuButton.setTextSize(20);
        compactMenuButton.setMinWidth(0);
        compactMenuButton.setMinHeight(0);
        compactMenuButton.setPadding(0, 0, 0, 0);
        compactMenuButton.setBackground(rounded(0xCC111111, 999));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM);
        p.rightMargin = dp(12);
        p.bottomMargin = systemBottomPx + dp(14);
        appLayer.addView(compactMenuButton, p);
        compactMenuButton.setOnClickListener(v -> showCompactMainMenu());
    }

    private void showCompactMainMenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Menu · NSFW only");
        scroll.addView(body);

        String current = context.equals("subreddit") ? "r/" + subreddit
                : context.equals("multi") ? "Multi-subreddit preset"
                : screen == Screen.SEARCH ? "Search"
                : screen == Screen.FAVORITES ? "Library"
                : "Home";
        TextView where = new TextView(this);
        where.setText(current);
        where.setTextColor(0xFFBDBDBD);
        where.setTextSize(12);
        where.setPadding(dp(14), 0, dp(14), dp(10));
        body.addView(where);

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

        Button browse = sheetButton("Browse  ›");
        Button search = sheetButton("Search  ›");
        Button presets = sheetButton("Multi-subreddit presets  ›");
        Button library = sheetButton("Saved & hidden  ›");
        Button display = sheetButton("Display & filters  ›");
        Button account = sheetButton("Account  ›");
        body.addView(browse, sectionButtonParams());
        body.addView(search, sectionButtonParams());
        body.addView(presets, sectionButtonParams());
        body.addView(library, sectionButtonParams());
        body.addView(display, sectionButtonParams());
        body.addView(account, sectionButtonParams());
        browse.setOnClickListener(v -> { dialog.dismiss(); showBrowseSubmenu(); });
        search.setOnClickListener(v -> { dialog.dismiss(); showSearchSubmenu(); });
        presets.setOnClickListener(v -> { dialog.dismiss(); showPresetSubmenu(); });
        library.setOnClickListener(v -> { dialog.dismiss(); showLibrarySubmenu(); });
        display.setOnClickListener(v -> { dialog.dismiss(); showDisplaySubmenu(); });
        account.setOnClickListener(v -> { dialog.dismiss(); showAccount(); });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showBrowseSubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Browse");
        scroll.addView(body);

        Button home = sheetButton("Browse all / Home");
        Button subredditButton = sheetButton("Find / open subreddit…");
        Button categories = sheetButton("NSFW categories…");
        body.addView(home, sectionButtonParams());
        body.addView(subredditButton, sectionButtonParams());
        body.addView(categories, sectionButtonParams());
        home.setOnClickListener(v -> { dialog.dismiss(); navigateHome("home", true); });
        subredditButton.setOnClickListener(v -> { dialog.dismiss(); showOpenSubredditSheet(); });
        categories.setOnClickListener(v -> { dialog.dismiss(); showCategoryRoot(); });

        if (screen == Screen.HOME && context.equals("subreddit")
                && subreddit != null && !subreddit.isEmpty()) {
            String target = subreddit;
            boolean subscribed = subscriptionNames.contains(target.toLowerCase(Locale.US));
            Button membership = sheetButton((subscribed ? "Leave / unsubscribe " : "Join / subscribe ")
                    + "r/" + target);
            if (subscribed) membership.setTextColor(0xFFFFB0B0);
            body.addView(membership, sectionButtonParams());
            membership.setOnClickListener(v -> { dialog.dismiss(); toggleSubredditSubscription(); });

            boolean favorite = favoriteSubreddits.contains(target.toLowerCase(Locale.US));
            Button favoriteButton = sheetButton((favorite ? "★ Remove favorite " : "☆ Favorite ")
                    + "r/" + target);
            body.addView(favoriteButton, sectionButtonParams());
            favoriteButton.setOnClickListener(v -> {
                dialog.dismiss();
                toggleFavoriteSubreddit(target);
            });
        }

        addCommunitySection(body, "Favorites", favoriteSubreddits, true);
        LinkedHashSet<String> subscribedNames = new LinkedHashSet<>();
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) subscribedNames.add(clean);
        }
        addCommunitySection(body, "Subscribed", subscribedNames, false);

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showOpenSubredditSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Find / open subreddit · NSFW posts only");
        scroll.addView(body);

        AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setSingleLine(true);
        input.setHint("Start typing a subreddit name");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        input.setThreshold(0);
        body.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        installCommunityAutocomplete(input);

        Button open = sheetButton("Open subreddit");
        body.addView(open, sectionButtonParams());
        open.setOnClickListener(v -> {
            String value = cleanSubredditName(input.getText().toString());
            if (value.isEmpty()) return;
            dialog.dismiss();
            openSubredditFeed(value);
        });

        addCommunitySection(body, "Favorites", favoriteSubreddits, true);
        LinkedHashSet<String> subscribedNames = new LinkedHashSet<>();
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) subscribedNames.add(clean);
        }
        addCommunitySection(body, "Subscribed", subscribedNames, false);

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showSearchSubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Fuzzy search · NSFW only");
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Title, subreddit, creator, flair…");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        body.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        Button global = sheetButton("Search everywhere");
        body.addView(global, sectionButtonParams());
        global.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            dialog.dismiss();
            runMenuSearch(value, "global", "");
        });
        if (context.equals("subreddit") && subreddit != null && !subreddit.isEmpty()) {
            Button current = sheetButton("Search current r/" + subreddit);
            body.addView(current, sectionButtonParams());
            current.setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) return;
                dialog.dismiss();
                runMenuSearch(value, "subreddit", subreddit);
            });
        }
        Button openSub = sheetButton("Find / change subreddit…");
        body.addView(openSub, sectionButtonParams());
        openSub.setOnClickListener(v -> { dialog.dismiss(); showOpenSubredditSheet(); });
        dialog.setContentView(body);
        dialog.show();
    }

    private void runMenuSearch(String value, String scope, String scopeSubreddit) {
        openSearchScreen();
        searchScope = scope;
        searchSubreddit = scopeSubreddit == null ? "" : scopeSubreddit;
        prefs.edit().putString("searchScope", searchScope)
                .putString("searchSubreddit", searchSubreddit).apply();
        searchInput.setText(value);
        beginSearch();
    }

    private void showPresetSubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Multi-subreddit presets");
        scroll.addView(body);
        Button create = sheetButton("＋ New preset");
        body.addView(create, sectionButtonParams());
        create.setOnClickListener(v -> { dialog.dismiss(); showCreatePresetSheet(); });

        if (subredditPresets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No presets yet. Select two or more subreddits and save the group for later.");
            empty.setTextColor(0xFFAAAAAA);
            empty.setPadding(dp(14), dp(18), dp(14), dp(18));
            body.addView(empty);
        } else {
            for (Map.Entry<String, ArrayList<String>> entry : subredditPresets.entrySet()) {
                String name = entry.getKey();
                ArrayList<String> communities = new ArrayList<>(entry.getValue());
                Button open = sheetButton(name + " · " + communities.size() + " subreddits");
                body.addView(open, sectionButtonParams());
                open.setOnClickListener(v -> {
                    dialog.dismiss();
                    applyPresetFilterSnapshot(name);
                    openMultiSubredditFeed(name, communities);
                });
                Button remove = sheetButton("Remove preset: " + name);
                remove.setTextColor(0xFFFF9A9A);
                body.addView(remove, sectionButtonParams());
                remove.setOnClickListener(v -> {
                    subredditPresets.remove(name);
                    removePresetFilterSnapshot(name);
                    saveSubredditPresets();
                    dialog.dismiss();
                    showPresetSubmenu();
                });
            }
        }
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void showCreatePresetSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("New multi-subreddit preset");
        scroll.addView(body);

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Preset name");
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(0xFF888888);
        body.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        TextView count = new TextView(this);
        count.setText("0 selected · minimum 2");
        count.setTextColor(0xFFBDBDBD);
        count.setPadding(dp(14), dp(8), dp(14), dp(8));
        body.addView(count);

        EditText custom = new EditText(this);
        custom.setSingleLine(true);
        custom.setHint("Add subreddit name");
        custom.setTextColor(Color.WHITE);
        custom.setHintTextColor(0xFF888888);
        body.addView(custom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        Button addCustom = sheetButton("Add subreddit");
        body.addView(addCustom, sectionButtonParams());
        LinearLayout customList = new LinearLayout(this);
        customList.setOrientation(LinearLayout.VERTICAL);
        body.addView(customList);
        addCustom.setOnClickListener(v -> {
            String clean = cleanSubredditName(custom.getText().toString());
            if (clean.isEmpty()) return;
            String key = clean.toLowerCase(Locale.US);
            boolean exists = false;
            for (String item : selected) if (item.equalsIgnoreCase(clean)) { exists = true; break; }
            if (!exists) {
                selected.add(clean);
                CheckBox cb = new CheckBox(this);
                cb.setText("r/" + clean);
                cb.setTextColor(Color.WHITE);
                cb.setChecked(true);
                customList.addView(cb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
                cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) selected.add(clean); else selected.removeIf(s -> s.equalsIgnoreCase(clean));
                    count.setText(selected.size() + " selected · minimum 2");
                });
            }
            custom.setText("");
            count.setText(selected.size() + " selected · minimum 2");
        });

        TextView knownTitle = new TextView(this);
        knownTitle.setText("Known / subscribed communities");
        knownTitle.setTextColor(Color.WHITE);
        knownTitle.setTextSize(14);
        knownTitle.setPadding(dp(14), dp(18), dp(14), dp(8));
        body.addView(knownTitle);
        for (String community : knownNsfwSubreddits()) {
            CheckBox cb = new CheckBox(this);
            cb.setText("r/" + community);
            cb.setTextColor(Color.WHITE);
            cb.setChecked(false);
            body.addView(cb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selected.add(community); else selected.remove(community);
                count.setText(selected.size() + " selected · minimum 2");
            });
        }

        Button save = sheetButton("Save preset");
        body.addView(save, sectionButtonParams());
        save.setOnClickListener(v -> {
            String presetName = name.getText().toString().trim();
            if (presetName.isEmpty()) {
                android.widget.Toast.makeText(this, "Enter a preset name", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (selected.size() < 2) {
                android.widget.Toast.makeText(this, "Select at least two subreddits", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            subredditPresets.put(presetName, new ArrayList<>(selected));
            saveSubredditPresets();
            savePresetFilterSnapshot(presetName);
            dialog.dismiss();
            openMultiSubredditFeed(presetName, new ArrayList<>(selected));
        });

        dialog.setContentView(scroll);
        dialog.show();
    }

    private void loadSubredditPresets() {
        subredditPresets.clear();
        if (prefs == null) return;
        String raw = prefs.getString("subredditPresetsV1", "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray names = root.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i, "");
                JSONArray array = root.optJSONArray(name);
                if (name.isEmpty() || array == null) continue;
                ArrayList<String> values = new ArrayList<>();
                for (int j = 0; j < array.length(); j++) {
                    String clean = cleanSubredditName(array.optString(j, ""));
                    if (!clean.isEmpty() && !values.contains(clean)) values.add(clean);
                }
                if (values.size() >= 2) subredditPresets.put(name, values);
            }
        } catch (Exception ignored) {}
    }

    private void saveSubredditPresets() {
        if (prefs == null) return;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, ArrayList<String>> entry : subredditPresets.entrySet()) {
                JSONArray array = new JSONArray();
                for (String community : entry.getValue()) array.put(community);
                root.put(entry.getKey(), array);
            }
            prefs.edit().putString("subredditPresetsV1", root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void openMultiSubredditFeed(String presetName, List<String> communities) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (communities != null) {
            for (String community : communities) {
                String value = cleanSubredditName(community);
                if (!value.isEmpty()) clean.add(value);
            }
        }
        if (clean.size() < 2) return;
        pushCurrentState();
        sort = "random";
        screen = Screen.HOME;
        context = "multi";
        subreddit = String.join("+", clean);
        query = "";
        profileUser = "";
        accountView.setVisibility(View.GONE);
        applyLayoutVisibility();
        updateChrome();
        setStatus((presetName == null || presetName.isEmpty() ? "Preset" : presetName)
                + " · " + clean.size() + " subreddits · NSFW only", true);
        loadFeed(true);
    }

    private void loadMultiSubredditFair(boolean reset) {
        if (!engine.isReady()) return;
        if (loading && !reset) return;

        if (reset) {
            feedGeneration++;
            multiSubredditGeneration++;
            loading = false;
            after = "";
            multiSubredditRound.clear();
            multiSubredditRoundIndex = 0;
            multiSubredditSeenPostIds.clear();
            feedSeenPostIds.clear();
            feedSeenCursors.clear();
            deferredAppends.clear();
            deferredAppendScheduled = false;
            replacePosts(new ArrayList<>());
            pager.setCurrentItem(0, false);
            setStatus("Preparing preset round…", true);
        }

        if (multiSubredditRoundIndex >= multiSubredditRound.size()) {
            prepareMultiSubredditRound();
        }
        if (multiSubredditRound.isEmpty()) {
            loading = false;
            setStatus("This preset has no usable subreddits.", false);
            return;
        }

        final int feedGen = feedGeneration;
        final int multiGen = multiSubredditGeneration;
        multiSubredditRequestsThisLoad = 0;
        loading = true;
        fetchMultiSubredditRoundNext(feedGen, multiGen);
    }

    private void prepareMultiSubredditRound() {
        multiSubredditRound.clear();
        multiSubredditRoundIndex = 0;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String community : multiCommunities()) {
            String clean = cleanSubredditName(community);
            if (!clean.isEmpty()) unique.add(clean);
        }
        multiSubredditRound.addAll(unique);
        Collections.shuffle(multiSubredditRound);
    }

    private boolean multiSubredditContextValid(int feedGen, int multiGen) {
        return feedGen == feedGeneration
                && multiGen == multiSubredditGeneration
                && screen == Screen.HOME
                && context.equals("multi");
    }

    private void fetchMultiSubredditRoundNext(int feedGen, int multiGen) {
        if (!multiSubredditContextValid(feedGen, multiGen)) return;

        if (multiSubredditRoundIndex >= multiSubredditRound.size()) {
            loading = false;
            after = "multi-round-complete";
            if (postAdapter.getItemCount() == 0) {
                setStatus("No accessible NSFW media matched this preset round.", false);
            } else {
                hideStatus();
            }
            updateChrome();
            restorePendingPosition();
            return;
        }

        // Keep enough posts buffered for smooth swiping without making 200 network
        // requests at once. The next chunk resumes at the next subreddit, so no
        // subreddit can repeat until the entire preset round has been attempted.
        if (multiSubredditRequestsThisLoad >= 12 && postAdapter.getItemCount() > 0) {
            loading = false;
            after = "multi-round-continue";
            hideStatus();
            updateChrome();
            restorePendingPosition();
            return;
        }

        final String target = multiSubredditRound.get(multiSubredditRoundIndex++);
        multiSubredditRequestsThisLoad++;
        engine.get(singlePresetListingPath(target), result -> {
            if (!multiSubredditContextValid(feedGen, multiGen)) return;

            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                ArrayList<RedditPost> candidates = new ArrayList<>();
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !post.nsfw || !matchesMedia(post)) continue;
                        if (post.id == null || post.id.isEmpty()) continue;
                        if (isReadHiddenForDiscovery(post)
                                || isSavedForUnread(post)
                                || isContentBlocked(post)) continue;
                        String key = canonicalPostKey(post);
                        if (key.isEmpty() || multiSubredditSeenPostIds.contains(key)) continue;
                        candidates.add(post);
                    }
                }

                if (!candidates.isEmpty()) {
                    if (sort.equals("random")) Collections.shuffle(candidates);
                    RedditPost selected = candidates.get(0);
                    String key = canonicalPostKey(selected);
                    if (!key.isEmpty()) {
                        multiSubredditSeenPostIds.add(key);
                        feedSeenPostIds.add(key);
                    }
                    ArrayList<RedditPost> one = new ArrayList<>();
                    one.add(selected);
                    appendUnique(one);
                    hideStatus();
                }
            }

            root.postDelayed(
                    () -> fetchMultiSubredditRoundNext(feedGen, multiGen),
                    180L);
        });
    }

    private String multiListingPath(String cursor) {
        String remoteSort = sort;
        if (remoteSort.equals("random") || remoteSort.equals("oldest")) remoteSort = "new";
        StringBuilder joined = new StringBuilder();
        String[] parts = subreddit == null ? new String[0] : subreddit.split("\\+");
        for (String part : parts) {
            String clean = cleanSubredditName(part);
            if (clean.isEmpty()) continue;
            if (joined.length() > 0) joined.append('+');
            joined.append(clean);
        }
        if (joined.length() == 0) return "/r/NSFW/new.json?limit=50&raw_json=1&show=all";
        String path = "/r/" + joined + "/" + remoteSort + ".json?limit=100&raw_json=1&show=all";
        if (remoteSort.equals("top")) path += "&t=" + enc(topTime);
        if (cursor != null && !cursor.isEmpty()) path += "&after=" + enc(cursor);
        return path;
    }

    private ArrayList<String> knownNsfwSubreddits() {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (String[] row : CURATED_CATEGORY_ROWS) {
            if (row.length < 4 || !"NSFW".equals(row[0])) continue;
            for (String raw : row[3].split(",")) {
                String clean = cleanSubredditName(raw);
                if (!clean.isEmpty()) names.putIfAbsent(clean.toLowerCase(Locale.US), clean);
            }
        }
        for (ArrayList<String> preset : subredditPresets.values()) {
            for (String raw : preset) {
                String clean = cleanSubredditName(raw);
                if (!clean.isEmpty()) names.putIfAbsent(clean.toLowerCase(Locale.US), clean);
            }
        }
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) names.putIfAbsent(clean.toLowerCase(Locale.US), clean);
        }
        ArrayList<String> result = new ArrayList<>(names.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private int fuzzyPostSearchScore(RedditPost post, String value) {
        if (post == null || !post.nsfw) return 0;
        int subredditScore = FuzzySearch.score(value, post.subreddit);
        int titleScore = FuzzySearch.score(value, post.title);
        int authorScore = FuzzySearch.score(value, post.author);
        int metadataScore = FuzzySearch.score(value, post.searchMetadata);
        int urlScore = Math.max(FuzzySearch.score(value, post.permalink), FuzzySearch.score(value, post.sourceUrl));
        int best = Math.max(titleScore, Math.max(authorScore, Math.max(metadataScore, urlScore)));
        if (subredditScore > 0) best = Math.max(best, Math.min(1000, subredditScore + 90));
        return best;
    }

    private void startSubredditNameLeadIns(int generation) {
        if (!searchStillValid(generation)) return;
        if (!(searchScope.equals("global") || searchScope.equals("subscribed"))) return;
        String normalized = FuzzySearch.normalize(query);
        if (normalized.isEmpty()) return;
        ArrayList<String> candidates = knownNsfwSubreddits();
        candidates.removeIf(name -> FuzzySearch.score(query, name) < FuzzySearch.thresholdFor(normalized.length()));
        candidates.sort((a, b) -> Integer.compare(FuzzySearch.score(query, b), FuzzySearch.score(query, a)));
        if (candidates.size() > 6) candidates = new ArrayList<>(candidates.subList(0, 6));
        fetchSubredditNameLeadIn(generation, candidates, 0);
    }

    private void fetchSubredditNameLeadIn(int generation, ArrayList<String> candidates, int index) {
        if (!searchStillValid(generation) || index >= candidates.size()) return;
        String community = candidates.get(index);
        String path = "/r/" + enc(community) + "/new.json?limit=50&raw_json=1&show=all";
        engine.get(path, result -> {
            if (!searchStillValid(generation)) return;
            ArrayList<RedditPost> additions = new ArrayList<>();
            if (result.ok) {
                JSONObject rootJson = result.jsonObject();
                JSONObject data = rootJson != null ? rootJson.optJSONObject("data") : null;
                JSONArray children = data != null ? data.optJSONArray("children") : null;
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        RedditPost post = RedditPost.fromChild(children.optJSONObject(i));
                        if (post == null || !post.nsfw || !matchesMedia(post) || isContentBlocked(post)) continue;
                        additions.add(post);
                    }
                }
            }
            if (!additions.isEmpty()) appendUnique(additions);
            if (appLayer != null) appLayer.postDelayed(
                    () -> fetchSubredditNameLeadIn(generation, candidates, index + 1), 120L);
        });
    }

    private void showLibrarySubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Saved & hidden");
        Button saved = sheetButton("Saved posts");
        Button hidden = sheetButton("Read / hidden posts");
        body.addView(saved, sectionButtonParams());
        body.addView(hidden, sectionButtonParams());
        saved.setOnClickListener(v -> { dialog.dismiss(); loadFavorites(); });
        hidden.setOnClickListener(v -> {
            dialog.dismiss();
            loadFavorites();
            favoritesView = "hidden";
            loadHiddenPostsView();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void loadDiscoverySets() {
        favoriteSubreddits.clear();
        includedSubreddits.clear();
        excludedSubreddits.clear();
        blockedCreators.clear();
        copyCleanSubreddits(prefs.getStringSet("favoriteSubredditsV1", null), favoriteSubreddits);
        copyCleanSubreddits(prefs.getStringSet("includedSubredditsV1", null), includedSubreddits);
        copyCleanSubreddits(prefs.getStringSet("excludedSubredditsV1", null), excludedSubreddits);
        Set<String> blocked = prefs.getStringSet("blockedCreatorsV1", null);
        if (blocked != null) {
            for (String value : blocked) {
                String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
                if (!clean.isEmpty()) blockedCreators.add(clean);
            }
        }
    }

    private void copyCleanSubreddits(Set<String> source, Set<String> target) {
        if (source == null) return;
        for (String value : source) {
            String clean = cleanSubredditName(value);
            if (!clean.isEmpty()) target.add(clean.toLowerCase(Locale.US));
        }
    }

    private void persistDiscoveryFilterPrefs() {
        prefs.edit()
                .putInt("peopleFilterMaskV1", peopleFilterMask)
                .putInt("sourceFilterMaskV1", sourceFilterMask)
                .putBoolean("showViewedPostsV1", showViewedPosts)
                .putBoolean("joinedOnlyFilterV1", joinedOnlyFilter)
                .putInt("minimumVideoHeightV1", minimumVideoHeight)
                .putInt("videoLengthModeV1", videoLengthMode)
                .putStringSet("favoriteSubredditsV1", new HashSet<>(favoriteSubreddits))
                .putStringSet("includedSubredditsV1", new HashSet<>(includedSubreddits))
                .putStringSet("excludedSubredditsV1", new HashSet<>(excludedSubreddits))
                .putStringSet("blockedCreatorsV1", new HashSet<>(blockedCreators))
                .apply();
    }

    private boolean isReadHiddenForDiscovery(RedditPost post) {
        return !showViewedPosts
                && post != null
                && post.id != null
                && !post.id.isEmpty()
                && hiddenPosts.containsKey(post.id);
    }

    private boolean shouldApplyJoinedOnlyFilter() {
        if (!joinedOnlyFilter) return false;
        if (screen == Screen.HOME) {
            if ("subreddit".equals(context) || "multi".equals(context)) return false;
        }
        if (screen == Screen.SEARCH && "subreddit".equals(searchScope)) return false;
        return true;
    }

    private boolean passesDiscoveryFilters(RedditPost post) {
        if (post == null) return false;
        String community = cleanSubredditName(post.subreddit).toLowerCase(Locale.US);
        String author = post.author == null ? "" : post.author.trim().toLowerCase(Locale.US);

        if (!includedSubreddits.isEmpty() && !includedSubreddits.contains(community)) return false;
        if (excludedSubreddits.contains(community)) return false;
        if (!author.isEmpty() && blockedCreators.contains(author)) return false;
        // Joined-only is a discovery constraint, not permission to erase a feed
        // the user explicitly opened. Direct subreddit, multi-preset, and
        // subreddit-scoped search contexts always honor the explicit selection.
        if (shouldApplyJoinedOnlyFilter() && !subscriptionNames.isEmpty()
                && !subscriptionNames.contains(community)) return false;

        int sourceBit = 1;
        String origin = post.sourceOrigin == null ? "reddit" : post.sourceOrigin.toLowerCase(Locale.US);
        if (origin.equals("scrolller")) sourceBit = 2;
        else if (origin.equals("archive")) sourceBit = 4;
        if (sourceFilterMask != 0 && (sourceFilterMask & sourceBit) == 0) return false;

        boolean video = post.mediaKind == RedditPost.MediaKind.VIDEO
                || post.mediaKind == RedditPost.MediaKind.GIF;
        if (video && minimumVideoHeight > 0 && post.mediaHeight > 0
                && post.mediaHeight < minimumVideoHeight) return false;
        if (video && videoLengthMode != 0 && post.durationSeconds > 0) {
            if (videoLengthMode == 1 && post.durationSeconds > 30) return false;
            if (videoLengthMode == 2
                    && (post.durationSeconds <= 30 || post.durationSeconds > 120)) return false;
            if (videoLengthMode == 3 && post.durationSeconds <= 120) return false;
        }
        return true;
    }

    private void toggleFavoriteSubreddit(String value) {
        String clean = cleanSubredditName(value);
        if (clean.isEmpty()) return;
        String key = clean.toLowerCase(Locale.US);
        boolean added;
        if (favoriteSubreddits.contains(key)) {
            favoriteSubreddits.remove(key);
            added = false;
        } else {
            favoriteSubreddits.add(key);
            added = true;
        }
        persistDiscoveryFilterPrefs();
        setStatus((added ? "Favorited r/" : "Removed favorite r/") + clean, false);
    }

    private void addCommunitySection(LinearLayout body, String title, Set<String> communities, boolean favorites) {
        TextView heading = sectionTitle(title + " · " + communities.size());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(14);
        body.addView(heading, hp);
        if (communities.isEmpty()) {
            body.addView(bodyText(favorites ? "No favorite subreddits yet." : "No subscriptions loaded yet."));
            return;
        }
        ArrayList<String> sorted = new ArrayList<>(communities);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : sorted) {
            String clean = cleanSubredditName(name);
            if (clean.isEmpty()) continue;
            Button button = sheetButton((favorites ? "★ " : "r/") + (favorites ? "r/" : "") + clean);
            body.addView(button, sectionButtonParams());
            button.setOnClickListener(v -> openSubredditFeed(clean));
        }
    }

    private ArrayList<String> communityCandidates() {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String name : favoriteSubreddits) {
            String clean = cleanSubredditName(name);
            if (!clean.isEmpty()) unique.putIfAbsent(clean.toLowerCase(Locale.US), clean);
        }
        for (Subscription sub : subscriptions) {
            if (sub == null) continue;
            String clean = cleanSubredditName(sub.name);
            if (!clean.isEmpty()) unique.putIfAbsent(clean.toLowerCase(Locale.US), clean);
        }
        for (String name : knownNsfwSubreddits()) {
            String clean = cleanSubredditName(name);
            if (!clean.isEmpty()) unique.putIfAbsent(clean.toLowerCase(Locale.US), clean);
        }
        ArrayList<String> out = new ArrayList<>(unique.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void installCommunityAutocomplete(AutoCompleteTextView input) {
        final ArrayList<String> all = communityCandidates();
        final Runnable refresh = () -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            String cleanQuery = cleanSubredditName(raw);
            ArrayList<String> matches = new ArrayList<>(all);
            if (!cleanQuery.isEmpty()) {
                matches.sort((a, b) -> Integer.compare(
                        FuzzySearch.score(cleanQuery, b), FuzzySearch.score(cleanQuery, a)));
                matches.removeIf(name -> FuzzySearch.score(cleanQuery, name)
                        < FuzzySearch.thresholdFor(FuzzySearch.normalize(cleanQuery).length()));
            }
            if (matches.size() > 30) matches.subList(30, matches.size()).clear();
            ArrayList<String> labels = new ArrayList<>();
            for (String name : matches) labels.add("r/" + name);
            input.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, labels));
            if (input.hasFocus() && !labels.isEmpty()) input.showDropDown();
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        });
        input.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) refresh.run(); });
        input.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            String clean = cleanSubredditName(item == null ? "" : item.toString());
            if (!clean.isEmpty()) input.setText(clean, false);
        });
        refresh.run();
    }

    private CheckBox filterCheckBox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(Color.WHITE);
        box.setChecked(checked);
        box.setPadding(dp(8), dp(3), dp(8), dp(3));
        return box;
    }

    private void showPeopleFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("People / content filter");
        body.addView(bodyText("Multi-select. Categories come only from explicit subreddit/title/flair metadata; the app never guesses identity from an image. No selection shows everything."));
        CheckBox straight = filterCheckBox("Straight", (peopleFilterMask & ContentTaxonomy.STRAIGHT) != 0);
        CheckBox gayLesbian = filterCheckBox("Gay / Lesbian", (peopleFilterMask & ContentTaxonomy.GAY_LESBIAN) != 0);
        CheckBox trans = filterCheckBox("Trans", (peopleFilterMask & ContentTaxonomy.TRANS) != 0);
        body.addView(straight);
        body.addView(gayLesbian);
        body.addView(trans);
        Button apply = sheetButton("Apply people filter");
        body.addView(apply, sectionButtonParams());
        apply.setOnClickListener(v -> {
            int mask = 0;
            if (straight.isChecked()) mask |= ContentTaxonomy.STRAIGHT;
            if (gayLesbian.isChecked()) mask |= ContentTaxonomy.GAY_LESBIAN;
            if (trans.isChecked()) mask |= ContentTaxonomy.TRANS;
            peopleFilterMask = mask;
            blockLgbtTopics = false;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private String sourceFilterLabel() {
        if (sourceFilterMask == 0 || sourceFilterMask == 7) return "All";
        ArrayList<String> labels = new ArrayList<>();
        if ((sourceFilterMask & 1) != 0) labels.add("Reddit");
        if ((sourceFilterMask & 2) != 0) labels.add("Scrolller");
        if ((sourceFilterMask & 4) != 0) labels.add("Archive");
        return String.join(" + ", labels);
    }

    private void showSourceFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Content sources");
        int current = sourceFilterMask == 0 ? 7 : sourceFilterMask;
        CheckBox reddit = filterCheckBox("Reddit", (current & 1) != 0);
        CheckBox scrolller = filterCheckBox("Scrolller", (current & 2) != 0);
        CheckBox archive = filterCheckBox("Archive / historical", (current & 4) != 0);
        body.addView(reddit);
        body.addView(scrolller);
        body.addView(archive);
        Button apply = sheetButton("Apply source filter");
        body.addView(apply, sectionButtonParams());
        apply.setOnClickListener(v -> {
            int mask = 0;
            if (reddit.isChecked()) mask |= 1;
            if (scrolller.isChecked()) mask |= 2;
            if (archive.isChecked()) mask |= 4;
            sourceFilterMask = (mask == 0 || mask == 7) ? 0 : mask;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showVideoFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Video length / quality");
        body.addView(bodyText("Filters use metadata when a source supplies it. Unknown duration/resolution is allowed rather than silently throwing a post away."));

        Button quality = sheetButton("Minimum resolution · "
                + (minimumVideoHeight <= 0 ? "Any" : minimumVideoHeight + "p+"));
        Button length = sheetButton("Length · " + videoLengthLabel());
        body.addView(quality, sectionButtonParams());
        body.addView(length, sectionButtonParams());
        quality.setOnClickListener(v -> {
            minimumVideoHeight = minimumVideoHeight <= 0 ? 720
                    : minimumVideoHeight == 720 ? 1080 : 0;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        length.setOnClickListener(v -> {
            videoLengthMode = (videoLengthMode + 1) % 4;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private String videoLengthLabel() {
        if (videoLengthMode == 1) return "Short · ≤30 sec";
        if (videoLengthMode == 2) return "Medium · 31–120 sec";
        if (videoLengthMode == 3) return "Long · >120 sec";
        return "Any";
    }

    private void showAdvancedDiscoveryFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Advanced feed filters");
        scroll.addView(body);
        body.addView(bodyText("Comma-separated names. Include is optional; when it is non-empty, only those subreddits are allowed."));

        EditText include = new EditText(this);
        include.setHint("Included subreddits (optional)");
        include.setText(String.join(", ", includedSubreddits));
        include.setTextColor(Color.WHITE);
        include.setHintTextColor(0xFF888888);
        body.addView(include, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        EditText exclude = new EditText(this);
        exclude.setHint("Excluded subreddits");
        exclude.setText(String.join(", ", excludedSubreddits));
        exclude.setTextColor(Color.WHITE);
        exclude.setHintTextColor(0xFF888888);
        body.addView(exclude, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        EditText creators = new EditText(this);
        creators.setHint("Blocked creators / usernames");
        creators.setText(String.join(", ", blockedCreators));
        creators.setTextColor(Color.WHITE);
        creators.setHintTextColor(0xFF888888);
        body.addView(creators, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        RedditPost current = currentPagerPost();
        if (current != null && current.author != null && !current.author.trim().isEmpty()) {
            String author = current.author.trim();
            Button blockCurrent = sheetButton("Block current creator · u/" + author);
            body.addView(blockCurrent, sectionButtonParams());
            blockCurrent.setOnClickListener(v -> {
                blockedCreators.add(author.toLowerCase(Locale.US));
                persistDiscoveryFilterPrefs();
                dialog.dismiss();
                reloadCurrent();
            });
        }

        Button save = sheetButton("Save advanced filters");
        body.addView(save, sectionButtonParams());
        save.setOnClickListener(v -> {
            includedSubreddits.clear();
            excludedSubreddits.clear();
            blockedCreators.clear();
            parseSubredditList(include.getText().toString(), includedSubreddits);
            parseSubredditList(exclude.getText().toString(), excludedSubreddits);
            for (String token : creators.getText().toString().split("[,\\s]+")) {
                String clean = token.trim().toLowerCase(Locale.US);
                if (clean.startsWith("u/")) clean = clean.substring(2);
                if (!clean.isEmpty()) blockedCreators.add(clean);
            }
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void parseSubredditList(String raw, Set<String> target) {
        if (raw == null) return;
        for (String token : raw.split("[,\\s]+")) {
            String clean = cleanSubredditName(token);
            if (!clean.isEmpty()) target.add(clean.toLowerCase(Locale.US));
        }
    }

    private RedditPost currentPagerPost() {
        if (postAdapter == null || pager == null || postAdapter.getItemCount() <= 0) return null;
        return postAdapter.getPost(Math.max(0, Math.min(pager.getCurrentItem(), postAdapter.getItemCount() - 1)));
    }

    private void showCacheControlSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Media cache");
        body.addView(bodyText("Shared video cache limit: 384 MB. Clearing it does not erase read/hidden history, presets, favorites, or account state."));
        Button cancel = sheetButton("Cancel pending preloads");
        Button clear = sheetButton("Clear media cache");
        body.addView(cancel, sectionButtonParams());
        body.addView(clear, sectionButtonParams());
        cancel.setOnClickListener(v -> {
            HighQualityPlayerFactory.cancelPendingPreloads();
            dialog.dismiss();
            setStatus("Pending media preloads cancelled.", false);
        });
        clear.setOnClickListener(v -> {
            HighQualityPlayerFactory.cancelPendingPreloads();
            dialog.dismiss();
            setStatus("Clearing media cache…", true);
            HighQualityPlayerFactory.clearCacheAsync(this,
                    () -> setStatus("Media cache cleared.", false));
        });
        dialog.setContentView(body);
        dialog.show();
    }

    private void showFeedDiagnosticsSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Feed diagnostics");
        scroll.addView(body);
        String report = buildFeedDiagnostics();
        TextView text = bodyText(report);
        text.setTextIsSelectable(true);
        body.addView(text);
        Button copy = sheetButton("Copy diagnostics");
        body.addView(copy, sectionButtonParams());
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Reddit Media diagnostics", report));
            setStatus("Feed diagnostics copied.", false);
        });
        dialog.setContentView(scroll);
        dialog.show();
    }

    private String buildFeedDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("Context: ").append(context).append('\n');
        if (subreddit != null && !subreddit.isEmpty()) out.append("Subreddit: r/").append(subreddit).append('\n');
        out.append("Visible posts: ").append(postAdapter == null ? 0 : postAdapter.getItemCount()).append('\n');
        out.append("Read/hidden IDs: ").append(hiddenPosts.size()).append('\n');
        out.append("People: ").append(ContentTaxonomy.label(peopleFilterMask)).append('\n');
        out.append("Sources: ").append(sourceFilterLabel()).append('\n');
        out.append("Viewed: ").append(showViewedPosts ? "All" : "Unread only").append('\n');
        out.append("Subreddit scope: ");
        if (!joinedOnlyFilter) out.append("Any");
        else if (!shouldApplyJoinedOnlyFilter()) out.append("Joined only (ignored for explicit selection)");
        else out.append("Joined only");
        out.append('\n');
        out.append("Include/exclude/blocked creators: ")
                .append(includedSubreddits.size()).append('/')
                .append(excludedSubreddits.size()).append('/')
                .append(blockedCreators.size()).append('\n');
        out.append("Video: ").append(minimumVideoHeight <= 0 ? "any resolution" : minimumVideoHeight + "p+")
                .append(" · ").append(videoLengthLabel()).append('\n');
        RedditPost post = currentPagerPost();
        if (post != null) {
            out.append("\nCurrent post\n");
            out.append("ID: ").append(post.id).append('\n');
            out.append("r/").append(post.subreddit).append(" · u/").append(post.author).append('\n');
            out.append("Origin: ").append(post.sourceOrigin).append('\n');
            out.append("Media: ").append(post.mediaKind).append(" · ")
                    .append(post.mediaWidth).append('x').append(post.mediaHeight).append('\n');
            out.append("Duration: ").append(post.durationSeconds > 0 ? post.durationSeconds + " sec" : "unknown").append('\n');
            out.append("People tags: ").append(ContentTaxonomy.label(ContentTaxonomy.classify(post))).append('\n');
            out.append("Saved: ").append(post.saved || savedPostIds.contains(post.id)).append('\n');
            out.append("Read/hidden: ").append(hiddenPosts.containsKey(post.id)).append('\n');
        }
        return out.toString();
    }

    private void loadPresetFilterSnapshots() {
        presetFilterSnapshots.clear();
        String raw = prefs.getString("subredditPresetFilterSnapshotsV1", "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray names = root.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i, "");
                JSONObject value = root.optJSONObject(name);
                if (!name.isEmpty() && value != null) presetFilterSnapshots.put(name, value.toString());
            }
        } catch (Exception ignored) {}
    }

    private void persistPresetFilterSnapshots() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, String> entry : presetFilterSnapshots.entrySet()) {
                root.put(entry.getKey(), new JSONObject(entry.getValue()));
            }
            prefs.edit().putString("subredditPresetFilterSnapshotsV1", root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void savePresetFilterSnapshot(String name) {
        if (name == null || name.isEmpty()) return;
        try {
            JSONObject value = new JSONObject();
            value.put("people", peopleFilterMask);
            value.put("sources", sourceFilterMask);
            value.put("viewed", showViewedPosts);
            value.put("joined", joinedOnlyFilter);
            value.put("height", minimumVideoHeight);
            value.put("length", videoLengthMode);
            presetFilterSnapshots.put(name, value.toString());
            persistPresetFilterSnapshots();
        } catch (Exception ignored) {}
    }

    private void applyPresetFilterSnapshot(String name) {
        String raw = presetFilterSnapshots.get(name);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject value = new JSONObject(raw);
            peopleFilterMask = value.optInt("people", peopleFilterMask);
            sourceFilterMask = value.optInt("sources", sourceFilterMask);
            showViewedPosts = value.optBoolean("viewed", showViewedPosts);
            joinedOnlyFilter = value.optBoolean("joined", joinedOnlyFilter);
            minimumVideoHeight = value.optInt("height", minimumVideoHeight);
            videoLengthMode = value.optInt("length", videoLengthMode);
            persistDiscoveryFilterPrefs();
        } catch (Exception ignored) {}
    }

    private void removePresetFilterSnapshot(String name) {
        if (name == null) return;
        presetFilterSnapshots.remove(name);
        persistPresetFilterSnapshots();
    }

    private void showDisplaySubmenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = sheetBody("Display & filters");
        scroll.addView(body);

        Button sortMenu = sheetButton("Sort…");
        Button mediaMenu = sheetButton("Media filter…");
        Button peopleMenu = sheetButton("People · " + ContentTaxonomy.label(peopleFilterMask));
        Button sourceMenu = sheetButton("Sources · " + sourceFilterLabel());
        Button viewedMenu = sheetButton("Viewed posts · " + (showViewedPosts ? "All" : "Unread only"));
        Button joinedMenu = sheetButton("Subreddits · " + (joinedOnlyFilter ? "Joined only" : "Any"));
        Button videoMenu = sheetButton("Video length / quality…");
        Button advancedMenu = sheetButton("Include / exclude / blocked creators…");
        Button layoutMenu = sheetButton("Layout…");
        Button cacheMenu = sheetButton("Media cache…");
        Button diagnosticsMenu = sheetButton("Feed diagnostics…");

        body.addView(sortMenu, sectionButtonParams());
        body.addView(mediaMenu, sectionButtonParams());
        body.addView(peopleMenu, sectionButtonParams());
        body.addView(sourceMenu, sectionButtonParams());
        body.addView(viewedMenu, sectionButtonParams());
        body.addView(joinedMenu, sectionButtonParams());
        body.addView(videoMenu, sectionButtonParams());
        body.addView(advancedMenu, sectionButtonParams());
        body.addView(layoutMenu, sectionButtonParams());
        body.addView(cacheMenu, sectionButtonParams());
        body.addView(diagnosticsMenu, sectionButtonParams());

        sortMenu.setOnClickListener(v -> { dialog.dismiss(); showSortSheet(); });
        mediaMenu.setOnClickListener(v -> { dialog.dismiss(); showMediaSheet(); });
        peopleMenu.setOnClickListener(v -> { dialog.dismiss(); showPeopleFilterSheet(); });
        sourceMenu.setOnClickListener(v -> { dialog.dismiss(); showSourceFilterSheet(); });
        viewedMenu.setOnClickListener(v -> {
            showViewedPosts = !showViewedPosts;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        joinedMenu.setOnClickListener(v -> {
            joinedOnlyFilter = !joinedOnlyFilter;
            persistDiscoveryFilterPrefs();
            dialog.dismiss();
            reloadCurrent();
        });
        videoMenu.setOnClickListener(v -> { dialog.dismiss(); showVideoFilterSheet(); });
        advancedMenu.setOnClickListener(v -> { dialog.dismiss(); showAdvancedDiscoveryFilterSheet(); });
        layoutMenu.setOnClickListener(v -> { dialog.dismiss(); showLayoutSheet(); });
        cacheMenu.setOnClickListener(v -> { dialog.dismiss(); showCacheControlSheet(); });
        diagnosticsMenu.setOnClickListener(v -> { dialog.dismiss(); showFeedDiagnosticsSheet(); });
        dialog.setContentView(scroll);
        dialog.show();
    }
}
