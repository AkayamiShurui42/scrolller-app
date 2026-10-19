package com.scrolller.adblock;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

public final class AiGalleryActivity extends AppCompatActivity
        implements PostPagerAdapter.Listener {

    private enum LibraryMode { NONE, SAVED, HIDDEN, VIEWED }

    private FrameLayout root;
    private ViewPager2 pager;
    private RecyclerView grid;
    private PostPagerAdapter pagerAdapter;
    private GridPostAdapter gridAdapter;
    private LinearLayout bottomBar;
    private TextView status;
    private Button saveCommand;

    private AdultLibraryStore library;

    private final ArrayList<RedditPost> results = new ArrayList<>();
    private final Set<String> resultIds = new HashSet<>();
    private final Set<String> savedIds = new HashSet<>();
    private final Set<String> hiddenIds = new HashSet<>();
    private final Set<String> viewedIds = new HashSet<>();

    private String query = "";
    private String sort = "random";
    private String layoutMode = "fullscreen";
    private boolean aiOnly = true;
    private boolean useRule34 = true;
    private boolean useGelbooru = true;
    private boolean includeViewed = false;
    private boolean chromeVisible = true;

    private int generation = 0;
    private int pendingSources = 0;
    private int rule34Page = 0;
    private int gelbooruPage = 0;
    private boolean loadingMore = false;
    private LibraryMode libraryMode = LibraryMode.NONE;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        library = new AdultLibraryStore(this);
        savedIds.addAll(library.loadIds("saved"));
        hiddenIds.addAll(library.loadIds("hidden"));
        viewedIds.addAll(library.loadIds("viewed"));

        buildUi();
        runSearch(true);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pagerAdapter = new PostPagerAdapter(this, this);
        pager.setAdapter(pagerAdapter);
        root.addView(pager, match());

        grid = new RecyclerView(this);
        grid.setBackgroundColor(Color.BLACK);
        grid.setLayoutManager(new GridLayoutManager(this, 2));
        gridAdapter = new GridPostAdapter(this, this::openFullscreenAt);
        grid.setAdapter(gridAdapter);
        grid.setVisibility(View.GONE);
        root.addView(grid, match());

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setBackground(rounded(0xD91A1A1A, 999));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(12);
        root.addView(status, sp);
        status.setVisibility(View.GONE);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFA000000, 0xD9000000, 0x70000000, 0x00000000}));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66), Gravity.BOTTOM);
        root.addView(bottomBar, bp);

        addNavButton("Search", this::showSearchSheet);
        addNavButton("View", this::showViewSheet);
        addNavButton("Collections", this::showCollectionsSheet);
        saveCommand = addNavButton("Save", this::toggleCurrentSave);
        addNavButton("Settings", this::showSettingsSheet);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                pagerAdapter.setActivePosition(position);
                markViewed(position);
                updateSaveButton();
                if (libraryMode == LibraryMode.NONE
                        && position >= Math.max(0, pagerAdapter.getItemCount() - 18)) {
                    loadNextPage();
                }
            }
        });

        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (libraryMode != LibraryMode.NONE) return;
                GridLayoutManager lm = (GridLayoutManager) recyclerView.getLayoutManager();
                int last = lm != null ? lm.findLastVisibleItemPosition() : 0;
                if (last >= Math.max(0, gridAdapter.getItemCount() - 18)) loadNextPage();
            }
        });

        setChromeVisible(true);
    }

    private void runSearch(boolean reset) {
        if (reset) {
            generation++;
            results.clear();
            resultIds.clear();
            rule34Page = 0;
            gelbooruPage = 0;
            loadingMore = false;
            libraryMode = LibraryMode.NONE;
            pagerAdapter.setHiddenMode(false);
            replaceVisible(new ArrayList<>());
        }
        fetchPage(generation);
    }

    private void fetchPage(int requestGeneration) {
        if (loadingMore || requestGeneration != generation) return;

        int count = (useRule34 ? 1 : 0) + (useGelbooru ? 1 : 0);
        if (count == 0) {
            showStatus("Enable at least one source.");
            return;
        }

        loadingMore = true;
        pendingSources = count;
        showStatus(results.isEmpty()
                ? (query.isEmpty() ? "Loading AI images…" : "Searching tags…")
                : "Loading more…");

        if (useRule34) {
            final int page = rule34Page++;
            AdultImageSourceClient.fetch(
                    AdultImageSourceClient.Source.RULE34,
                    query, aiOnly, page, 200,
                    sourceCallback(requestGeneration));
        }

        if (useGelbooru) {
            final int page = gelbooruPage++;
            AdultImageSourceClient.fetch(
                    AdultImageSourceClient.Source.GELBOORU,
                    query, false, page, 100,
                    sourceCallback(requestGeneration));
        }
    }

    private AdultImageSourceClient.Callback sourceCallback(int requestGeneration) {
        return new AdultImageSourceClient.Callback() {
            @Override
            public void onComplete(
                    AdultImageSourceClient.Source source,
                    int page,
                    ArrayList<RedditPost> posts) {
                if (requestGeneration != generation || libraryMode != LibraryMode.NONE) return;
                mergeResults(posts);
                sourceFinished();
            }

            @Override
            public void onError(
                    AdultImageSourceClient.Source source,
                    int page,
                    String error) {
                if (requestGeneration != generation || libraryMode != LibraryMode.NONE) return;
                sourceFinished();
            }
        };
    }

    private void sourceFinished() {
        pendingSources = Math.max(0, pendingSources - 1);
        if (pendingSources > 0) return;
        loadingMore = false;

        sortResults();
        replaceVisible(filteredResults());

        if (pagerAdapter.getItemCount() == 0) {
            showStatus(query.isEmpty()
                    ? "No adult AI images returned."
                    : "No results for “" + query + "”.");
        } else {
            hideStatus();
        }
    }

    private void mergeResults(ArrayList<RedditPost> incoming) {
        if (incoming == null) return;
        for (RedditPost post : incoming) {
            if (post == null || post.id == null || post.id.isEmpty()) continue;
            if (hiddenIds.contains(post.id)) continue;
            if (!includeViewed && viewedIds.contains(post.id) && !savedIds.contains(post.id)) continue;
            if (!resultIds.add(post.id)) continue;

            post.saved = savedIds.contains(post.id);
            results.add(post);
            library.cacheAsync(post);
        }
    }

    private ArrayList<RedditPost> filteredResults() {
        ArrayList<RedditPost> visible = new ArrayList<>();
        for (RedditPost post : results) {
            if (post == null || hiddenIds.contains(post.id)) continue;
            if (!includeViewed && viewedIds.contains(post.id) && !savedIds.contains(post.id)) continue;
            post.saved = savedIds.contains(post.id);
            visible.add(post);
        }
        return visible;
    }

    private void sortResults() {
        if (sort.equals("random")) {
            Collections.shuffle(results);
        } else if (sort.equals("new")) {
            results.sort((a, b) -> Long.compare(b.createdUtc, a.createdUtc));
        } else if (sort.equals("oldest")) {
            results.sort(Comparator.comparingLong(a -> a.createdUtc));
        } else if (sort.equals("top")) {
            results.sort((a, b) -> Integer.compare(b.score, a.score));
        }
    }

    private void replaceVisible(ArrayList<RedditPost> posts) {
        pagerAdapter.setPosts(posts);
        gridAdapter.setPosts(posts);
        if (!posts.isEmpty()) {
            pager.setCurrentItem(0, false);
            pagerAdapter.setActivePosition(0);
            markViewed(0);
        }
        updateSaveButton();
    }

    private void loadNextPage() {
        if (loadingMore || libraryMode != LibraryMode.NONE) return;
        fetchPage(generation);
    }

    private void markViewed(int position) {
        if (libraryMode != LibraryMode.NONE) return;
        RedditPost post = pagerAdapter.getPost(position);
        if (post == null || post.id == null || post.id.isEmpty()) return;
        if (savedIds.contains(post.id)) return;
        if (viewedIds.add(post.id)) library.setViewedAsync(post, true);
    }

    private void toggleCurrentSave() {
        RedditPost post = currentPost();
        if (post == null) {
            showStatus("No current image.");
            return;
        }

        boolean next = !savedIds.contains(post.id);
        if (next) {
            savedIds.add(post.id);
            hiddenIds.remove(post.id);
            post.saved = true;
            library.setSavedAsync(post, true);
            library.setHiddenAsync(post, false);
            showStatus("Saved.");
        } else {
            savedIds.remove(post.id);
            post.saved = false;
            library.setSavedAsync(post, false);
            showStatus("Unsaved.");
        }
        pagerAdapter.refreshPost(post);
        updateSaveButton();
        hideStatusDelayed();
    }

    private void hideCurrent() {
        RedditPost post = currentPost();
        if (post == null) return;
        hiddenIds.add(post.id);
        savedIds.remove(post.id);
        post.saved = false;
        library.setHiddenAsync(post, true);
        library.setSavedAsync(post, false);

        if (libraryMode == LibraryMode.NONE) {
            results.remove(post);
            resultIds.remove(post.id);
            replaceVisible(filteredResults());
        } else {
            loadLibrary(libraryMode);
        }
        showStatus("Hidden.");
        hideStatusDelayed();
    }

    private void showSearchSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Search prompt / tags");

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(query);
        input.setHint("e.g. redhead elf forest");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF888888);
        input.setBackground(rounded(0xFF202020, 16));
        input.setPadding(dp(14), 0, dp(14), 0);
        body.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView hint = bodyText(
                "Search uses source prompt/tags. Spaces mean multiple tags; advanced booru syntax also works.");
        body.addView(hint);

        Button search = sheetButton("Search");
        body.addView(search, sectionButtonParams());
        search.setOnClickListener(v -> {
            query = input.getText().toString().trim();
            dialog.dismiss();
            runSearch(true);
        });

        Button clear = sheetButton("Trending AI images");
        body.addView(clear, sectionButtonParams());
        clear.setOnClickListener(v -> {
            query = "";
            dialog.dismiss();
            runSearch(true);
        });

        TextView presets = bodyText("Optional tag shortcuts");
        body.addView(presets);
        String[][] quick = {
                {"Fantasy", "fantasy"},
                {"Cyberpunk", "cyberpunk"},
                {"Elf", "elf"},
                {"Redhead", "redhead"},
                {"Goth", "goth"},
                {"Cosplay", "cosplay"}
        };
        for (String[] item : quick) {
            Button b = sheetButton(item[0]);
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                query = item[1];
                dialog.dismiss();
                runSearch(true);
            });
        }

        dialog.setContentView(body);
        dialog.show();
    }

    private void showViewSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("View");

        Button layout = sheetButton(layoutMode.equals("fullscreen")
                ? "Layout · Fullscreen" : "Layout · Grid");
        body.addView(layout, sectionButtonParams());
        layout.setOnClickListener(v -> {
            dialog.dismiss();
            setLayoutMode(layoutMode.equals("fullscreen") ? "grid" : "fullscreen");
        });

        String[] sorts = {"random", "new", "oldest", "top"};
        for (String value : sorts) {
            Button b = sheetButton("Sort · " + labelSort(value)
                    + (sort.equals(value) ? "  ✓" : ""));
            body.addView(b, sectionButtonParams());
            b.setOnClickListener(v -> {
                sort = value;
                dialog.dismiss();
                if (libraryMode == LibraryMode.NONE) {
                    sortResults();
                    replaceVisible(filteredResults());
                } else {
                    loadLibrary(libraryMode);
                }
            });
        }

        Button viewed = sheetButton(
                "Viewed · " + (includeViewed ? "Include" : "Unread only"));
        body.addView(viewed, sectionButtonParams());
        viewed.setOnClickListener(v -> {
            includeViewed = !includeViewed;
            dialog.dismiss();
            if (libraryMode == LibraryMode.NONE) replaceVisible(filteredResults());
        });

        Button hide = sheetButton("Hide current image/set");
        body.addView(hide, sectionButtonParams());
        hide.setOnClickListener(v -> {
            dialog.dismiss();
            hideCurrent();
        });

        dialog.setContentView(body);
        dialog.show();
    }

    private void showCollectionsSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Collections");

        Button saved = sheetButton("Saved");
        Button hidden = sheetButton("Hidden");
        Button viewed = sheetButton("Viewed");
        Button back = sheetButton("Back to search results");

        body.addView(saved, sectionButtonParams());
        body.addView(hidden, sectionButtonParams());
        body.addView(viewed, sectionButtonParams());
        body.addView(back, sectionButtonParams());

        saved.setOnClickListener(v -> {
            dialog.dismiss();
            loadLibrary(LibraryMode.SAVED);
        });
        hidden.setOnClickListener(v -> {
            dialog.dismiss();
            loadLibrary(LibraryMode.HIDDEN);
        });
        viewed.setOnClickListener(v -> {
            dialog.dismiss();
            loadLibrary(LibraryMode.VIEWED);
        });
        back.setOnClickListener(v -> {
            dialog.dismiss();
            libraryMode = LibraryMode.NONE;
            pagerAdapter.setHiddenMode(false);
            replaceVisible(filteredResults());
        });

        dialog.setContentView(body);
        dialog.show();
    }

    private void loadLibrary(LibraryMode mode) {
        libraryMode = mode;
        String flag = mode == LibraryMode.SAVED ? "saved"
                : mode == LibraryMode.HIDDEN ? "hidden" : "viewed";
        pagerAdapter.setHiddenMode(mode == LibraryMode.HIDDEN);
        showStatus("Loading " + flag + "…");
        library.loadFlaggedAsync(flag, 2000, posts -> {
            for (RedditPost post : posts) {
                if (post != null) post.saved = savedIds.contains(post.id);
            }
            sortPosts(posts);
            replaceVisible(posts);
            if (posts.isEmpty()) showStatus("No " + flag + " images.");
            else hideStatus();
        });
    }

    private void sortPosts(ArrayList<RedditPost> posts) {
        if (sort.equals("random")) Collections.shuffle(posts);
        else if (sort.equals("new")) posts.sort((a,b) -> Long.compare(b.createdUtc, a.createdUtc));
        else if (sort.equals("oldest")) posts.sort(Comparator.comparingLong(a -> a.createdUtc));
        else if (sort.equals("top")) posts.sort((a,b) -> Integer.compare(b.score, a.score));
    }

    private void showSettingsSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout body = sheetBody("Sources & search");

        Button ai = sheetButton("AI-only · " + (aiOnly ? "On" : "Off"));
        Button r34 = sheetButton("Rule34 · " + (useRule34 ? "On" : "Off"));
        Button gel = sheetButton("Gelbooru fallback · " + (useGelbooru ? "On" : "Off"));

        body.addView(ai, sectionButtonParams());
        body.addView(r34, sectionButtonParams());
        body.addView(gel, sectionButtonParams());
        body.addView(bodyText(
                "Rule34 is the primary AI-tag source. Gelbooru may require authentication or contain fewer AI-tagged posts, so failures are ignored."));

        ai.setOnClickListener(v -> {
            aiOnly = !aiOnly;
            ai.setText("AI-only · " + (aiOnly ? "On" : "Off"));
        });
        r34.setOnClickListener(v -> {
            useRule34 = !useRule34;
            r34.setText("Rule34 · " + (useRule34 ? "On" : "Off"));
        });
        gel.setOnClickListener(v -> {
            useGelbooru = !useGelbooru;
            gel.setText("Gelbooru fallback · " + (useGelbooru ? "On" : "Off"));
        });

        Button apply = sheetButton("Apply and reload");
        body.addView(apply, sectionButtonParams());
        apply.setOnClickListener(v -> {
            dialog.dismiss();
            runSearch(true);
        });

        dialog.setContentView(body);
        dialog.show();
    }

    private void setLayoutMode(String mode) {
        layoutMode = mode;
        boolean full = mode.equals("fullscreen");
        pager.setVisibility(full ? View.VISIBLE : View.GONE);
        grid.setVisibility(full ? View.GONE : View.VISIBLE);
        setChromeVisible(true);
        if (!full && gridAdapter.getItemCount() > 0) grid.scrollToPosition(0);
    }

    private void openFullscreenAt(int position) {
        setLayoutMode("fullscreen");
        if (position >= 0 && position < pagerAdapter.getItemCount()) {
            pager.setCurrentItem(position, false);
            pagerAdapter.setActivePosition(position);
        }
    }

    private RedditPost currentPost() {
        if (pagerAdapter == null || pagerAdapter.getItemCount() == 0) return null;
        int pos = layoutMode.equals("fullscreen")
                ? pager.getCurrentItem() : 0;
        return pagerAdapter.getPost(Math.max(0, Math.min(pos, pagerAdapter.getItemCount() - 1)));
    }

    private void updateSaveButton() {
        if (saveCommand == null) return;
        RedditPost post = currentPost();
        saveCommand.setText(post != null && savedIds.contains(post.id) ? "Saved" : "Save");
    }

    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        bottomBar.setVisibility(visible || layoutMode.equals("grid") ? View.VISIBLE : View.GONE);
        pagerAdapter.setChromeVisible(visible);
    }

    private Button addNavButton(String text, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(5), 0, dp(5), 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        bottomBar.addView(b, new LinearLayout.LayoutParams(0, dp(58), 1f));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout sheetBody(String title) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(28));
        body.setBackgroundColor(0xFF111111);

        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(Color.WHITE);
        h.setTextSize(20);
        h.setPadding(0, 0, 0, dp(10));
        body.addView(h);
        return body;
    }

    private TextView bodyText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFBDBDBD);
        t.setTextSize(13);
        t.setPadding(0, dp(7), 0, dp(9));
        return t;
    }

    private Button sheetButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(rounded(0xFF202020, 16));
        return b;
    }

    private LinearLayout.LayoutParams sectionButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        p.topMargin = dp(7);
        return p;
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        status.bringToFront();
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    private void hideStatusDelayed() {
        status.postDelayed(this::hideStatus, 900L);
    }

    private String labelSort(String value) {
        if (value.equals("new")) return "Newest";
        if (value.equals("oldest")) return "Oldest";
        if (value.equals("top")) return "Top";
        return "Random";
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onOpenSubreddit(String subreddit) {
        showSettingsSheet();
    }

    @Override
    public void onOpenUser(RedditPost post) {
        if (post == null || post.permalink == null || post.permalink.isEmpty()) return;
        openUrl(post.permalink);
    }

    @Override
    public void onToggleChrome() {
        if (!layoutMode.equals("fullscreen")) return;
        setChromeVisible(!chromeVisible);
    }

    @Override
    public void onMediaReady(RedditPost post) {}

    @Override
    public void onMediaFailed(RedditPost post) {}

    @Override
    public void onRestoreHidden(RedditPost post) {
        if (post == null) return;
        hiddenIds.remove(post.id);
        library.setHiddenAsync(post, false);
        loadLibrary(LibraryMode.HIDDEN);
    }

    @Override
    public void onSave(RedditPost post) {
        if (post == null) return;
        boolean next = !savedIds.contains(post.id);
        if (next) {
            savedIds.add(post.id);
            hiddenIds.remove(post.id);
        } else {
            savedIds.remove(post.id);
        }
        post.saved = next;
        library.setSavedAsync(post, next);
        if (next) library.setHiddenAsync(post, false);
        pagerAdapter.refreshPost(post);
        updateSaveButton();
    }

    @Override
    public void onComments(RedditPost post) {
        if (post != null) openUrl(post.permalink);
    }

    @Override
    public void onShare(RedditPost post) {
        if (post == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,
                post.permalink == null || post.permalink.isEmpty()
                        ? post.sourceUrl : post.permalink);
        startActivity(Intent.createChooser(share, "Share image"));
    }

    @Override
    public void onOpenExternal(RedditPost post) {
        if (post == null) return;
        openUrl(post.permalink == null || post.permalink.isEmpty()
                ? post.sourceUrl : post.permalink);
    }

    @Override
    public void onMutedChanged(boolean muted) {}

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (pagerAdapter != null) pagerAdapter.releaseAll();
        if (gridAdapter != null) gridAdapter.releaseAllPlayers();
        super.onDestroy();
    }
}
