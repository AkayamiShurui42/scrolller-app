package com.scrolller.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.scrolller.app.data.model.ContentFilter
import com.scrolller.app.data.model.MediaFilter
import com.scrolller.app.data.model.MediaType
import com.scrolller.app.data.model.ScrolllerPost
import com.scrolller.app.data.model.SourceOriginFilter
import com.scrolller.app.data.model.ViewMode
import com.scrolller.app.data.repository.ScrolllerRepository
import com.scrolller.app.ui.components.AccountLoginSheet
import com.scrolller.app.ui.components.AlbumPager
import com.scrolller.app.ui.components.CollectionsSheet
import com.scrolller.app.ui.components.FilterSheet
import com.scrolller.app.ui.components.ImageViewer
import com.scrolller.app.ui.components.QualitySelectorDialog
import com.scrolller.app.ui.components.SearchSheet
import com.scrolller.app.ui.components.VideoPlayer
import com.scrolller.app.ui.theme.BlackBackground
import com.scrolller.app.ui.theme.DarkSurface
import com.scrolller.app.ui.theme.DarkSurfaceVariant
import com.scrolller.app.ui.theme.GoldAccent
import com.scrolller.app.ui.theme.NsfwBadgeRed
import com.scrolller.app.ui.theme.SfwBadgeGreen
import com.scrolller.app.ui.theme.TextPrimary
import com.scrolller.app.ui.theme.TextSecondary
import com.scrolller.app.ui.viewmodel.FeedUiState
import com.scrolller.app.ui.viewmodel.FeedViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    uiState: FeedUiState,
    onOpenBookmarks: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val savedSubreddits by viewModel.savedSubreddits.collectAsStateWithLifecycle()
    val cachedSubreddits by viewModel.cachedSubreddits.collectAsStateWithLifecycle()
    val favoritePosts by viewModel.favoritePosts.collectAsStateWithLifecycle()
    val recentPosts by viewModel.recentPosts.collectAsStateWithLifecycle()
    val totalCachedCount by viewModel.totalCachedPostCount.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val userCollections by viewModel.userCollections.collectAsStateWithLifecycle()

    var showFilterSheet by remember { mutableStateOf(false) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showCollectionsSheet by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var selectedPostForQuality by remember { mutableStateOf<ScrolllerPost?>(null) }
    var isUiChromeVisible by remember { mutableStateOf(true) }

    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val searchSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val collectionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accountSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(uiState.statusMessage) {
        if (uiState.statusMessage != null) {
            delay(3500)
            viewModel.clearStatusMessage()
        }
    }

    // Priority 1 BackHandler: Exit immersive fullscreen mode if chrome is hidden!
    BackHandler(enabled = !isUiChromeVisible) {
        isUiChromeVisible = true
    }

    // Priority 2 BackHandler: Navigate back in history or exit single-post Pager to Grid
    BackHandler(enabled = isUiChromeVisible && (viewModel.canNavigateBack() || uiState.viewMode == ViewMode.GRID)) {
        if (uiState.viewMode == ViewMode.GRID) {
            viewModel.setViewMode(ViewMode.PAGER)
        } else {
            viewModel.navigateBack()
        }
    }

    val pagerState = rememberPagerState(
        initialPage = uiState.activePageIndex,
        pageCount = { uiState.posts.size }
    )

    // Scroll pager to 0 when feed source/posts reset
    LaunchedEffect(uiState.source, uiState.filter) {
        if (uiState.activePageIndex == 0 && pagerState.currentPage != 0 && uiState.posts.isNotEmpty()) {
            runCatching { pagerState.scrollToPage(0) }
        }
    }

    // Sync active page and load more when reaching near the end of pager
    LaunchedEffect(pagerState, uiState.posts.size) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.setActivePageIndex(page)
            if (page >= uiState.posts.size - 4 && uiState.posts.isNotEmpty()) {
                viewModel.loadMore()
            }
        }
    }

    Scaffold(
        containerColor = BlackBackground,
        topBar = {
            AnimatedVisibility(
                visible = isUiChromeVisible || uiState.viewMode == ViewMode.GRID,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier.background(
                        if (uiState.viewMode == ViewMode.PAGER) Color.Black.copy(alpha = 0.72f) else DarkSurface
                    )
                ) {
                    TopAppBar(
                        title = {
                            Surface(
                                onClick = { showCollectionsSheet = true },
                                shape = RoundedCornerShape(20.dp),
                                color = DarkSurfaceVariant,
                                modifier = Modifier.testTag("feed_title_pill")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    if (uiState.isFromOfflineCache || uiState.isOfflineModeEnabled) {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = "Offline Cache",
                                            tint = GoldAccent,
                                            modifier = Modifier
                                                .size(15.dp)
                                                .padding(end = 4.dp)
                                        )
                                    }
                                    Text(
                                        text = uiState.title,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Select collection",
                                        tint = GoldAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (viewModel.canNavigateBack()) {
                                IconButton(
                                    onClick = { viewModel.navigateBack() },
                                    modifier = Modifier.testTag("feed_back_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = TextPrimary
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { showSearchSheet = true },
                                modifier = Modifier.testTag("search_button")
                            ) {
                                Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = TextPrimary)
                            }
                            IconButton(
                                onClick = { showFilterSheet = true },
                                modifier = Modifier.testTag("filter_button")
                            ) {
                                Icon(imageVector = Icons.Default.FilterList, contentDescription = "Filters", tint = TextPrimary)
                            }
                            IconButton(
                                onClick = { viewModel.toggleViewMode() },
                                modifier = Modifier.testTag("view_mode_toggle")
                            ) {
                                Icon(
                                    imageVector = if (uiState.viewMode == ViewMode.PAGER) Icons.Default.GridView else Icons.Default.ViewCarousel,
                                    contentDescription = if (uiState.viewMode == ViewMode.PAGER) "Switch to Grid View" else "Switch to Pager View",
                                    tint = GoldAccent
                                )
                            }
                            IconButton(
                                onClick = onOpenBookmarks,
                                modifier = Modifier.testTag("bookmarks_button")
                            ) {
                                Icon(imageVector = Icons.Default.Bookmark, contentDescription = "Offline & Saved Library", tint = TextPrimary)
                            }
                            IconButton(
                                onClick = { showAccountSheet = true },
                                modifier = Modifier.testTag("account_button")
                            ) {
                                Box {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = if (currentUser?.isSignedIn == true) "Account (${currentUser?.username})" else "Sign In",
                                        tint = if (currentUser?.isSignedIn == true) GoldAccent else TextPrimary
                                    )
                                    if (currentUser?.isSignedIn == true) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .align(Alignment.BottomEnd)
                                                .background(SfwBadgeGreen, CircleShape)
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )

                    // Interactive Media Context & Source Strip (Instant Context Switching!)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Content Rating Quick Context Pill (All / SFW / NSFW)
                        Surface(
                            onClick = {
                                val nextContent = when (uiState.filter.contentFilter) {
                                    ContentFilter.ALL -> ContentFilter.SFW
                                    ContentFilter.SFW -> ContentFilter.NSFW
                                    ContentFilter.NSFW -> ContentFilter.ALL
                                }
                                viewModel.updateContentFilter(nextContent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = when (uiState.filter.contentFilter) {
                                ContentFilter.NSFW -> NsfwBadgeRed.copy(alpha = 0.25f)
                                ContentFilter.SFW -> SfwBadgeGreen.copy(alpha = 0.25f)
                                ContentFilter.ALL -> GoldAccent.copy(alpha = 0.2f)
                            },
                            modifier = Modifier.testTag("content_context_pill")
                        ) {
                            Text(
                                text = "Content: ${uiState.filter.contentFilter.label}",
                                color = when (uiState.filter.contentFilter) {
                                    ContentFilter.NSFW -> NsfwBadgeRed
                                    ContentFilter.SFW -> SfwBadgeGreen
                                    ContentFilter.ALL -> GoldAccent
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        // Media Type Context Chips (All / Images / Videos / GIFs / Albums)
                        MediaFilter.entries.forEach { mf ->
                            val isSelected = uiState.filter.mediaFilter == mf
                            Surface(
                                onClick = { viewModel.updateMediaFilter(mf) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) GoldAccent else DarkSurfaceVariant.copy(alpha = 0.85f)
                            ) {
                                Text(
                                    text = mf.label,
                                    color = if (isSelected) BlackBackground else TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Source Origin Chips (Scrolller / Reddit / RedGIFs)
                        SourceOriginFilter.entries.filter { it != SourceOriginFilter.ALL }.forEach { origin ->
                            val isSelected = uiState.filter.sourceOrigin == origin
                            Surface(
                                onClick = {
                                    val next = if (isSelected) SourceOriginFilter.ALL else origin
                                    viewModel.updateSourceOrigin(next)
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) GoldAccent else DarkSurfaceVariant.copy(alpha = 0.65f)
                            ) {
                                Text(
                                    text = origin.label,
                                    color = if (isSelected) BlackBackground else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Grid Column Density Selector when in Grid Mode (1x - 5x)
                        if (uiState.viewMode == ViewMode.GRID) {
                            (1..5).forEach { cols ->
                                val isSelected = uiState.gridColumns == cols
                                Surface(
                                    onClick = { viewModel.setGridColumns(cols) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) GoldAccent else DarkSurfaceVariant
                                ) {
                                    Text(
                                        text = "${cols}col",
                                        color = if (isSelected) BlackBackground else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Held Subreddit Filters Bar (when subreddits are held down)
                    AnimatedVisibility(visible = uiState.filter.selectedSubreddits.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Held Subreddits:",
                                color = GoldAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            uiState.filter.selectedSubreddits.forEach { sub ->
                                Surface(
                                    onClick = { viewModel.toggleHeldSubredditFilter(sub) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = GoldAccent.copy(alpha = 0.22f)
                                ) {
                                    Text(
                                        text = "r/$sub ×",
                                        color = GoldAccent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Surface(
                                onClick = { viewModel.clearHeldSubredditFilters() },
                                shape = RoundedCornerShape(10.dp),
                                color = DarkSurfaceVariant
                            ) {
                                Text(
                                    text = "Clear All",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Offline Cache Mode Indicator Strip
                    AnimatedVisibility(visible = uiState.isFromOfflineCache || uiState.isOfflineModeEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurfaceVariant.copy(alpha = 0.92f))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = GoldAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Viewing Local Room Cache (${uiState.posts.size} items)",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Recent",
                                    color = GoldAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { viewModel.loadFeed("Recent") }
                                )
                                Text(
                                    text = "Saved",
                                    color = GoldAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { viewModel.loadFeed("Saved") }
                                )
                                if (uiState.isOfflineModeEnabled) {
                                    Text(
                                        text = "Go Online",
                                        color = SfwBadgeGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { viewModel.toggleOfflineMode() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (uiState.viewMode == ViewMode.GRID) padding else PaddingValues(0.dp))
        ) {
            when {
                uiState.isLoading && uiState.posts.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = GoldAccent)
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(text = "Loading media…", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }

                uiState.errorMessage != null && uiState.posts.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Offline or Unable to Load Feed",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.errorMessage,
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { viewModel.refresh() },
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = BlackBackground)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry", color = BlackBackground, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.loadFeed("OfflineAll") }
                                ) {
                                    Icon(imageVector = Icons.Default.History, contentDescription = null, tint = GoldAccent)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Browse Cached ($totalCachedCount)", color = GoldAccent)
                                }
                            }
                        }
                    }
                }

                uiState.posts.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Text(
                                text = "No posts match current view",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { showFilterSheet = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)
                                ) {
                                    Text("Adjust Filters", color = BlackBackground, fontWeight = FontWeight.Bold)
                                }
                                if (totalCachedCount > 0) {
                                    OutlinedButton(
                                        onClick = { viewModel.loadFeed("OfflineAll") }
                                    ) {
                                        Text("Offline Cache ($totalCachedCount)", color = GoldAccent)
                                    }
                                }
                            }
                        }
                    }
                }

                uiState.viewMode == ViewMode.PAGER -> {
                    // Full-screen snapping VerticalPager — NO .clickable on VerticalPager so swiping works 100%!
                    VerticalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        userScrollEnabled = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("feed_vertical_pager")
                    ) { page ->
                        val post = uiState.posts.getOrNull(page) ?: return@VerticalPager
                        val effectiveUrl = uiState.mediaUrlOverrides[post.postId] ?: post.mediaUrl

                        Box(modifier = Modifier.fillMaxSize()) {
                            // Media Layer
                            when (post.mediaType) {
                                MediaType.VIDEO, MediaType.GIF -> {
                                    VideoPlayer(
                                        videoUrl = effectiveUrl,
                                        isActive = (pagerState.currentPage == page),
                                        isMuted = uiState.isMuted,
                                        onToggleUiChrome = { isUiChromeVisible = !isUiChromeVisible },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                MediaType.ALBUM -> {
                                    AlbumPager(
                                        albumImages = post.album.ifEmpty { listOf(effectiveUrl) },
                                        onToggleUiChrome = { isUiChromeVisible = !isUiChromeVisible },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                MediaType.IMAGE -> {
                                    ImageViewer(
                                        imageUrl = effectiveUrl,
                                        contentDescription = post.title,
                                        onToggleUiChrome = { isUiChromeVisible = !isUiChromeVisible },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            // Bottom Gradient Overlay & Post Info
                            AnimatedVisibility(
                                visible = isUiChromeVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.align(Alignment.BottomCenter)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.5f),
                                                    Color.Black.copy(alpha = 0.92f)
                                                )
                                            )
                                        )
                                        .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 28.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        // Left info
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 12.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                val isSubHeld = uiState.filter.selectedSubreddits.contains(
                                                    post.subredditUrl.lowercase().trim()
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(
                                                            if (isSubHeld) GoldAccent else GoldAccent.copy(alpha = 0.2f)
                                                        )
                                                        .combinedClickable(
                                                            onClick = {
                                                                if (post.subredditUrl.isNotBlank()) {
                                                                    viewModel.loadFeed(post.subredditUrl)
                                                                }
                                                            },
                                                            onLongClick = {
                                                                if (post.subredditUrl.isNotBlank()) {
                                                                    viewModel.toggleHeldSubredditFilter(post.subredditUrl)
                                                                }
                                                            }
                                                        )
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = if (isSubHeld) "r/${post.subredditTitle} (Held)" else "r/${post.subredditTitle}",
                                                        color = if (isSubHeld) BlackBackground else GoldAccent,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                Surface(
                                                    onClick = {
                                                        val targetMedia = when (post.mediaType) {
                                                            MediaType.IMAGE -> MediaFilter.IMAGE
                                                            MediaType.VIDEO -> MediaFilter.VIDEO
                                                            MediaType.GIF -> MediaFilter.GIF
                                                            MediaType.ALBUM -> MediaFilter.ALBUM
                                                        }
                                                        val next = if (uiState.filter.mediaFilter == targetMedia) MediaFilter.ALL else targetMedia
                                                        viewModel.updateMediaFilter(next)
                                                    },
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = DarkSurfaceVariant.copy(alpha = 0.8f)
                                                ) {
                                                    Text(
                                                        text = post.mediaType.name,
                                                        color = TextSecondary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                    )
                                                }

                                                if (post.isNsfw) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = NsfwBadgeRed.copy(alpha = 0.25f)
                                                    ) {
                                                        Text(
                                                            text = "18+",
                                                            color = NsfwBadgeRed,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = "${page + 1}/${uiState.posts.size}",
                                                    color = TextSecondary,
                                                    fontSize = 11.sp
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Text(
                                                text = post.title,
                                                color = TextPrimary,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        // Right Action buttons
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Previous / Next Post Quick Step Buttons (in addition to vertical swipe!)
                                            if (page > 0) {
                                                IconButton(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            pagerState.animateScrollToPage(page - 1)
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .background(DarkSurfaceVariant.copy(alpha = 0.8f), CircleShape)
                                                        .testTag("prev_post_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.KeyboardArrowUp,
                                                        contentDescription = "Previous Post",
                                                        tint = Color.White
                                                    )
                                                }
                                            }

                                            if (page < uiState.posts.size - 1) {
                                                IconButton(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            pagerState.animateScrollToPage(page + 1)
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .background(DarkSurfaceVariant.copy(alpha = 0.8f), CircleShape)
                                                        .testTag("next_post_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.KeyboardArrowDown,
                                                        contentDescription = "Next Post",
                                                        tint = Color.White
                                                    )
                                                }
                                            }

                                            // Enter Fullscreen / Hide Chrome Button
                                            IconButton(
                                                onClick = { isUiChromeVisible = false },
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .background(DarkSurfaceVariant.copy(alpha = 0.8f), CircleShape)
                                                    .testTag("enter_fullscreen_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Fullscreen,
                                                    contentDescription = "Fullscreen Mode",
                                                    tint = Color.White
                                                )
                                            }

                                            // Favorite / Save to Room DB
                                            IconButton(
                                                onClick = { viewModel.toggleFavorite(post) },
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .background(DarkSurfaceVariant.copy(alpha = 0.8f), CircleShape)
                                                    .testTag("favorite_button")
                                            ) {
                                                Icon(
                                                    imageVector = if (post.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                    contentDescription = "Favorite",
                                                    tint = if (post.isFavorite) GoldAccent else Color.White
                                                )
                                            }

                                            // Cache Full Subreddit for Offline
                                            if (post.subredditUrl.isNotBlank()) {
                                                IconButton(
                                                    onClick = { viewModel.downloadSubredditForOffline(post.subredditUrl) },
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .background(DarkSurfaceVariant.copy(alpha = 0.8f), CircleShape)
                                                        .testTag("cache_subreddit_button")
                                                ) {
                                                    if (uiState.isDownloadingSubreddit) {
                                                        CircularProgressIndicator(
                                                            color = GoldAccent,
                                                            modifier = Modifier.size(20.dp),
                                                            strokeWidth = 2.dp
                                                        )
                                                    } else {
                                                        Icon(
                                                            imageVector = Icons.Default.CloudDownload,
                                                            contentDescription = "Cache Subreddit Offline",
                                                            tint = Color.White
                                                        )
                                                    }
                                                }
                                            }

                                            // Quality Resolution Variant Selector
                                            if (post.sourceVariants.size > 1) {
                                                IconButton(
                                                    onClick = { selectedPostForQuality = post },
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .background(DarkSurfaceVariant.copy(alpha = 0.8f), CircleShape)
                                                        .testTag("quality_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.HighQuality,
                                                        contentDescription = "Quality",
                                                        tint = Color.White
                                                    )
                                                }
                                            }

                                            // Mute toggle (for videos)
                                            if (post.mediaType == MediaType.VIDEO || post.mediaType == MediaType.GIF) {
                                                IconButton(
                                                    onClick = { viewModel.toggleMute() },
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .background(DarkSurfaceVariant.copy(alpha = 0.8f), CircleShape)
                                                        .testTag("mute_button")
                                                ) {
                                                    Icon(
                                                        imageVector = if (uiState.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                                        contentDescription = if (uiState.isMuted) "Unmute" else "Mute",
                                                        tint = Color.White
                                                    )
                                                }
                                            }

                                            // Share
                                            IconButton(
                                                onClick = {
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(Intent.EXTRA_SUBJECT, post.title)
                                                        putExtra(Intent.EXTRA_TEXT, "${post.title}\n${post.postUrl.ifBlank { post.mediaUrl }}")
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                                                },
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .background(DarkSurfaceVariant.copy(alpha = 0.8f), CircleShape)
                                                    .testTag("share_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = "Share",
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Persistent Floating Exit Fullscreen Button when UI chrome is hidden!
                    // Ensures user is NEVER stuck in fullscreen mode!
                    AnimatedVisibility(
                        visible = !isUiChromeVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 12.dp, end = 16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = {
                                    isUiChromeVisible = true
                                    viewModel.setViewMode(ViewMode.GRID)
                                },
                                shape = RoundedCornerShape(20.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier.testTag("fullscreen_to_grid_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GridView,
                                        contentDescription = "Grid View",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Grid",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Surface(
                                onClick = { isUiChromeVisible = true },
                                shape = RoundedCornerShape(20.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier.testTag("exit_fullscreen_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FullscreenExit,
                                        contentDescription = "Exit Fullscreen",
                                        tint = GoldAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Exit Fullscreen",
                                        color = GoldAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                uiState.viewMode == ViewMode.GRID -> {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(uiState.gridColumns),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(uiState.posts, key = { index, post -> "${post.id}_$index" }) { index, post ->
                            val thumb = post.posterUrl.ifBlank { post.mediaUrl }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(if (uiState.gridColumns == 1) 1.1f else 0.75f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkSurfaceVariant)
                                    .clickable {
                                        viewModel.setActivePageIndex(index)
                                        viewModel.setViewMode(ViewMode.PAGER)
                                        coroutineScope.launch {
                                            pagerState.scrollToPage(index)
                                        }
                                    }
                            ) {
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = post.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.Black.copy(alpha = 0.75f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        text = post.mediaType.name,
                                        color = TextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.75f))
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        text = post.title,
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "r/${post.subredditTitle}",
                                        color = GoldAccent,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Status Toast Pill
            AnimatedVisibility(
                visible = uiState.statusMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 128.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurfaceVariant.copy(alpha = 0.95f),
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = uiState.statusMessage.orEmpty(),
                        color = GoldAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }

    // Modal Bottom Sheets
    if (showFilterSheet) {
        val knownSubList = remember(savedSubreddits, cachedSubreddits) {
            (savedSubreddits.toList() + cachedSubreddits.map { it.subredditUrl } + ScrolllerRepository.DEFAULT_SUBREDDITS).distinct()
        }
        FilterSheet(
            currentFilter = uiState.filter,
            availableCategories = uiState.availableCategories,
            knownSubreddits = knownSubList,
            currentUsername = currentUser?.username?.takeIf { currentUser?.isSignedIn == true },
            sheetState = filterSheetState,
            onContentFilterChanged = { newContentFilter ->
                viewModel.loadCategoriesForContentFilter(newContentFilter)
            },
            onApplyFilter = { newFilter ->
                showFilterSheet = false
                viewModel.applyFilter(newFilter)
            },
            onOpenLogin = {
                showFilterSheet = false
                showAccountSheet = true
            },
            onDismiss = { showFilterSheet = false }
        )
    }

    if (showSearchSheet) {
        SearchSheet(
            sheetState = searchSheetState,
            currentSourceTitle = uiState.title,
            currentFilter = uiState.filter,
            searchContextMode = uiState.searchContextMode,
            isSearching = uiState.isSearchingSubreddits,
            subredditResults = uiState.subredditSearchResults,
            onSearchContextChange = { mode -> viewModel.setSearchContextMode(mode) },
            onUpdateMediaFilter = { mf -> viewModel.updateMediaFilter(mf) },
            onUpdateContentFilter = { cf -> viewModel.updateContentFilter(cf) },
            onToggleHeldSubredditFilter = { sub -> viewModel.toggleHeldSubredditFilter(sub) },
            onSearchPosts = { q, mode -> viewModel.searchPosts(q, mode) },
            onSearchSubreddits = { q -> viewModel.searchSubreddits(q) },
            onSelectSubreddit = { sub -> viewModel.loadFeed(sub) },
            onDismiss = { showSearchSheet = false }
        )
    }

    if (showCollectionsSheet) {
        CollectionsSheet(
            sheetState = collectionsSheetState,
            categories = uiState.availableCategories,
            savedSubreddits = savedSubreddits,
            cachedSubreddits = cachedSubreddits,
            userCollections = userCollections,
            totalCachedPostCount = totalCachedCount,
            recentPostCount = recentPosts.size,
            savedPostCount = favoritePosts.size,
            isOfflineModeEnabled = uiState.isOfflineModeEnabled,
            onToggleOfflineMode = { viewModel.toggleOfflineMode() },
            onSelectSource = { source -> viewModel.loadFeed(source) },
            onDownloadSubredditOffline = { sub -> viewModel.downloadSubredditForOffline(sub) },
            onDismiss = { showCollectionsSheet = false }
        )
    }

    if (showAccountSheet) {
        AccountLoginSheet(
            sheetState = accountSheetState,
            currentUser = currentUser,
            userCollections = userCollections,
            isLoggingIn = uiState.isLoggingIn,
            loginError = uiState.loginError,
            onLoginWithCredentials = { user, pass ->
                viewModel.loginWithCredentials(user, pass) {
                    showAccountSheet = false
                }
            },
            onCompleteWebSignIn = { tok, usr ->
                viewModel.completeWebSignIn(tok, usr) {
                    showAccountSheet = false
                }
            },
            onSelectUserCollection = { col ->
                viewModel.loadFeed("Collection:${col.id}:${col.title}")
            },
            onRefreshCollections = { viewModel.refreshUserCollections() },
            onSignOut = { viewModel.signOut() },
            onClearError = { viewModel.clearLoginError() },
            onDismiss = { showAccountSheet = false }
        )
    }

    selectedPostForQuality?.let { post ->
        QualitySelectorDialog(
            variants = post.sourceVariants,
            currentUrl = uiState.mediaUrlOverrides[post.postId] ?: post.mediaUrl,
            onSelectSource = { newUrl ->
                viewModel.selectSourceVariant(post.postId, newUrl)
            },
            onDismiss = { selectedPostForQuality = null }
        )
    }
}
