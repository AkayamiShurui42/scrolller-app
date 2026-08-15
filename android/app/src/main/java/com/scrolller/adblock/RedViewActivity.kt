package com.scrolller.adblock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.scrolller.adblock.model.GalleryInfo
import com.scrolller.adblock.model.MainTab
import com.scrolller.adblock.model.NsfwMode
import com.scrolller.adblock.model.Post
import com.scrolller.adblock.model.SearchResult
import com.scrolller.adblock.model.SortMode
import com.scrolller.adblock.model.ThemeMode
import com.scrolller.adblock.network.ScrolllerApi
import com.scrolller.adblock.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.HashMap

class RedViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RedViewScrolllerApp() }
    }
}

private enum class MediaFilter { ALL, IMAGES, VIDEOS, ALBUMS }
private enum class SearchMode { POSTS, GALLERIES }

private sealed interface RedRoute {
    data class Gallery(val url: String, val title: String) : RedRoute
    data class Media(val posts: List<Post>, val initialIndex: Int) : RedRoute
}

private class RedController(context: Context) {
    private val prefs = AppPreferences(context.applicationContext)

    var selectedTab by mutableStateOf(MainTab.HOME)
    var nsfwMode by mutableStateOf(prefs.nsfwMode)
    var mediaFilter by mutableStateOf(MediaFilter.ALL)
    var themeMode by mutableStateOf(prefs.themeMode)
    var autoplay by mutableStateOf(prefs.autoplay)
    var muted by mutableStateOf(prefs.muted)
    var sortMode by mutableStateOf(SortMode.RANDOM)

    val routes = mutableStateListOf<RedRoute>()
    val favorites = mutableStateListOf<Post>().apply { addAll(prefs.loadFavorites()) }

    fun updateNsfw(value: NsfwMode) {
        nsfwMode = value
        prefs.nsfwMode = value
    }

    fun updateTheme(value: ThemeMode) {
        themeMode = value
        prefs.themeMode = value
    }

    fun updateAutoplay(value: Boolean) {
        autoplay = value
        prefs.autoplay = value
    }

    fun updateMuted(value: Boolean) {
        muted = value
        prefs.muted = value
    }

    fun filtered(posts: List<Post>): List<Post> = posts.filter { post ->
        val contentOk = when (nsfwMode) {
            NsfwMode.ALL -> true
            NsfwMode.SFW -> !post.isNsfw
            NsfwMode.NSFW -> post.isNsfw
        }
        val mediaOk = when (mediaFilter) {
            MediaFilter.ALL -> true
            MediaFilter.IMAGES -> !post.isVideo && post.albumSources.size <= 1
            MediaFilter.VIDEOS -> post.isVideo
            MediaFilter.ALBUMS -> post.albumSources.size > 1
        }
        contentOk && mediaOk
    }.distinctBy { it.key }

    fun isFavorite(post: Post): Boolean = favorites.any { it.key == post.key }

    fun toggleFavorite(post: Post) {
        val index = favorites.indexOfFirst { it.key == post.key }
        if (index >= 0) favorites.removeAt(index) else favorites.add(0, post)
        prefs.saveFavorites(favorites)
    }

    fun openGallery(url: String, title: String) {
        routes.add(RedRoute.Gallery(url, title))
    }

    fun openMedia(post: Post, posts: List<Post>) {
        val start = posts.indexOfFirst { it.key == post.key }.coerceAtLeast(0)
        routes.add(RedRoute.Media(posts, start))
    }

    fun back(): Boolean {
        if (routes.isEmpty()) return false
        routes.removeAt(routes.lastIndex)
        return true
    }
}

@Composable
private fun RedViewScrolllerApp() {
    val context = LocalContext.current
    val controller = remember { RedController(context) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (controller.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(colorScheme = colors) {
        val route = controller.routes.lastOrNull()
        BackHandler(enabled = route != null) { controller.back() }

        if (route is RedRoute.Media) {
            FullscreenPager(controller, route)
        } else {
            Scaffold(bottomBar = { RedBottomBar(controller) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (route) {
                        is RedRoute.Gallery -> GalleryScreen(
                            controller = controller,
                            initialUrl = route.url,
                            initialTitle = route.title,
                            quickFeeds = false
                        )
                        else -> when (controller.selectedTab) {
                            MainTab.HOME -> GalleryScreen(controller, "funny", "Home", true)
                            MainTab.FAVORITES -> FavoritesScreen(controller)
                            MainTab.SEARCH -> SearchScreen(controller)
                            MainTab.SETTINGS -> SettingsScreen(controller)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RedBottomBar(controller: RedController) {
    val tabs = listOf(
        Triple(MainTab.HOME, "⌂", "Home"),
        Triple(MainTab.FAVORITES, "♥", "Favorites"),
        Triple(MainTab.SEARCH, "⌕", "Search"),
        Triple(MainTab.SETTINGS, "⚙", "Settings")
    )
    NavigationBar {
        tabs.forEach { (tab, icon, label) ->
            NavigationBarItem(
                selected = controller.routes.isEmpty() && controller.selectedTab == tab,
                onClick = {
                    controller.routes.clear()
                    controller.selectedTab = tab
                },
                icon = { Text(icon) },
                label = { Text(label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreen(
    controller: RedController,
    initialUrl: String,
    initialTitle: String,
    quickFeeds: Boolean
) {
    var activeUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var activeTitle by remember(initialTitle) { mutableStateOf(initialTitle) }
    var gallery by remember(activeUrl, controller.sortMode) { mutableStateOf<GalleryInfo?>(null) }
    var loading by remember(activeUrl, controller.sortMode) { mutableStateOf(true) }
    var error by remember(activeUrl, controller.sortMode) { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var showSort by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(activeUrl, controller.sortMode, reloadKey) {
        loading = true
        error = null
        gallery = null
        runCatching { ScrolllerApi.loadGallery(activeUrl, controller.sortMode) }
            .onSuccess {
                gallery = it
                if (!quickFeeds || activeUrl != "funny") activeTitle = it.title
            }
            .onFailure { error = it.message ?: "Unable to load gallery" }
        loading = false
    }

    if (showSort) {
        ChoiceDialog(
            title = "Sort",
            values = SortMode.entries.toList(),
            selected = controller.sortMode,
            label = ::sortLabel,
            onDismiss = { showSort = false },
            onSelect = {
                controller.sortMode = it
                showSort = false
            }
        )
    }
    if (showFilter) {
        FilterDialog(controller) { showFilter = false }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (!quickFeeds) TextButton(onClick = { controller.back() }) { Text("←") }
            }
        )
        FeedControls(
            controller = controller,
            onSort = { showSort = true },
            onFilter = { showFilter = true }
        )

        if (quickFeeds) {
            QuickFeeds(activeUrl) { url, title ->
                activeUrl = url
                activeTitle = title
            }
        }

        when {
            loading -> LoadingPanel("Loading full gallery…")
            error != null -> ErrorPanel(error!!) { reloadKey++ }
            gallery != null -> MediaFeed(controller, controller.filtered(gallery!!.posts))
        }
    }
}

@Composable
private fun FeedControls(controller: RedController, onSort: () -> Unit, onFilter: () -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = false,
                onClick = onSort,
                label = { Text("Sort: ${sortLabel(controller.sortMode)}") }
            )
        }
        item {
            FilterChip(
                selected = controller.nsfwMode != NsfwMode.ALL || controller.mediaFilter != MediaFilter.ALL,
                onClick = onFilter,
                label = { Text("Filter: ${filterSummary(controller)}") }
            )
        }
    }
}

@Composable
private fun QuickFeeds(activeUrl: String, onSelect: (String, String) -> Unit) {
    val feeds = listOf(
        "funny" to "Funny",
        "pics" to "Pics",
        "videos" to "Videos",
        "aww" to "Aww",
        "art" to "Art"
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(feeds) { (url, title) ->
            FilterChip(
                selected = activeUrl.equals(url, ignoreCase = true),
                onClick = { onSelect(url, title) },
                label = { Text(title) }
            )
        }
    }
}

@Composable
private fun MediaFeed(controller: RedController, posts: List<Post>) {
    if (posts.isEmpty()) {
        EmptyPanel("No media matches this filter")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(posts, key = { it.key }) { post ->
            NaturalMediaPreview(controller, post, posts)
        }
    }
}

@Composable
private fun NaturalMediaPreview(controller: RedController, post: Post, posts: List<Post>) {
    val images = post.thumbnailCandidates()
    val videos = if (images.isEmpty()) post.videoCandidates() else emptyList()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(naturalAspectRatio(post))
            .background(Color.Black)
            .clickable { controller.openMedia(post, posts) }
    ) {
        RemoteBitmap(
            imageCandidates = images,
            videoCandidates = videos,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        FavoriteButton(
            favorite = controller.isFavorite(post),
            onClick = { controller.toggleFavorite(post) },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
        )
    }
}

private fun naturalAspectRatio(post: Post): Float {
    val source = post.allSources()
        .filter { it.width > 0 && it.height > 0 }
        .maxByOrNull { it.area }
        ?: return 1f
    val ratio = source.width.toFloat() / source.height.toFloat()
    return if (ratio.isFinite() && ratio > 0f) ratio else 1f
}

@Composable
private fun FavoriteButton(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.58f)
    ) {
        IconButton(onClick = onClick) {
            Text(
                text = if (favorite) "♥" else "♡",
                color = if (favorite) MaterialTheme.colorScheme.primary else Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(controller: RedController) {
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SearchMode.POSTS) }
    var postResults by remember { mutableStateOf<List<Post>>(emptyList()) }
    var galleryResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSort by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    if (showSort) {
        ChoiceDialog(
            title = "Sort",
            values = SortMode.entries.toList(),
            selected = controller.sortMode,
            label = ::sortLabel,
            onDismiss = { showSort = false },
            onSelect = {
                controller.sortMode = it
                showSort = false
            }
        )
    }
    if (showFilter) {
        FilterDialog(controller) { showFilter = false }
    }

    LaunchedEffect(query, mode, controller.sortMode, controller.nsfwMode) {
        val clean = query.trim()
        if (clean.length < 2) {
            postResults = emptyList()
            galleryResults = emptyList()
            loading = false
            error = null
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        error = null
        runCatching {
            if (mode == SearchMode.GALLERIES) {
                galleryResults = ScrolllerApi.searchGalleries(clean, controller.nsfwMode != NsfwMode.SFW)
            } else {
                postResults = searchPostsAcrossScrolller(clean, controller.sortMode, controller.nsfwMode != NsfwMode.SFW)
            }
        }.onFailure { error = it.message ?: "Search failed" }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Search") })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            singleLine = true,
            label = { Text(if (mode == SearchMode.POSTS) "Search posts and media" else "Search galleries") }
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = mode == SearchMode.POSTS,
                    onClick = { mode = SearchMode.POSTS },
                    label = { Text("Posts") }
                )
            }
            item {
                FilterChip(
                    selected = mode == SearchMode.GALLERIES,
                    onClick = { mode = SearchMode.GALLERIES },
                    label = { Text("Galleries") }
                )
            }
            item {
                FilterChip(selected = false, onClick = { showSort = true }, label = { Text("Sort") })
            }
            item {
                FilterChip(
                    selected = controller.nsfwMode != NsfwMode.ALL || controller.mediaFilter != MediaFilter.ALL,
                    onClick = { showFilter = true },
                    label = { Text("Filter") }
                )
            }
        }

        when {
            loading -> LoadingPanel(if (mode == SearchMode.POSTS) "Searching media…" else "Searching galleries…")
            error != null -> ErrorPanel(error!!, null)
            query.trim().length < 2 -> EmptyPanel("Search Scrolller")
            mode == SearchMode.POSTS -> {
                val visible = controller.filtered(postResults)
                if (visible.isEmpty()) EmptyPanel("No matching media") else MediaFeed(controller, visible)
            }
            galleryResults.isEmpty() -> EmptyPanel("No galleries found")
            else -> GallerySearchResults(controller, galleryResults)
        }
    }
}

private suspend fun searchPostsAcrossScrolller(query: String, sort: SortMode, includeNsfw: Boolean): List<Post> = coroutineScope {
    val groups = ScrolllerApi.searchGalleries(query, includeNsfw)
        .filter { it.itemCount != 0 && it.url.isNotBlank() }
        .take(8)

    val galleries = if (groups.isNotEmpty()) {
        groups.map { result ->
            async(Dispatchers.IO) {
                runCatching { ScrolllerApi.loadGallery(result.url, sort, limit = 600) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    } else {
        listOfNotNull(runCatching { ScrolllerApi.loadGallery(query, sort, limit = 600) }.getOrNull())
    }

    val corpus = galleries.flatMap { it.posts }.distinctBy { it.key }
    corpus
        .map { post -> post to postSearchScore(post, query) }
        .filter { it.second > 0 }
        .sortedWith(
            compareByDescending<Pair<Post, Int>> { it.second }
                .thenByDescending { parseCreatedAt(it.first.createdAt) }
        )
        .map { it.first }
}

private fun postSearchScore(post: Post, query: String): Int {
    val q = query.lowercase().trim()
    if (q.isBlank()) return 1
    val title = post.title.lowercase()
    val gallery = (post.subredditTitle + " " + post.subredditUrl).lowercase()
    val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
    var score = 0
    if (title == q) score += 180
    if (title.contains(q)) score += 120
    if (gallery == q || gallery.contains("r/$q")) score += 100
    if (gallery.contains(q)) score += 65
    words.forEach { word ->
        if (title.contains(word)) score += 18
        if (gallery.contains(word)) score += 8
    }
    return score
}

private fun parseCreatedAt(value: String): Long = value.toLongOrNull() ?: 0L

@Composable
private fun GallerySearchResults(controller: RedController, results: List<SearchResult>) {
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(results, key = { it.id.ifBlank { it.url } }) { result ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { controller.openGallery(result.url, result.title) },
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (result.isNsfw) Text("NSFW", color = MaterialTheme.colorScheme.error)
                    }
                    if (result.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            result.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${result.itemCount} posts", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesScreen(controller: RedController) {
    var showFilter by remember { mutableStateOf(false) }
    if (showFilter) FilterDialog(controller) { showFilter = false }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Favorites") })
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = controller.nsfwMode != NsfwMode.ALL || controller.mediaFilter != MediaFilter.ALL,
                    onClick = { showFilter = true },
                    label = { Text("Filter: ${filterSummary(controller)}") }
                )
            }
        }
        MediaFeed(controller, controller.filtered(controller.favorites))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(controller: RedController) {
    var chooseTheme by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    if (chooseTheme) {
        ChoiceDialog(
            "Theme",
            ThemeMode.entries.toList(),
            controller.themeMode,
            ::themeLabel,
            { chooseTheme = false }
        ) {
            controller.updateTheme(it)
            chooseTheme = false
        }
    }
    if (showFilter) FilterDialog(controller) { showFilter = false }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SettingsChoice("Content and media filter", filterSummary(controller)) { showFilter = true }
            }
            item {
                SettingsChoice("Theme", themeLabel(controller.themeMode)) { chooseTheme = true }
            }
            item {
                SettingsSwitch("Autoplay videos", controller.autoplay) { controller.updateAutoplay(it) }
            }
            item {
                SettingsSwitch("Mute videos by default", controller.muted) { controller.updateMuted(it) }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Scrolller Native", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Single-feed RedView-style media client", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsChoice(title: String, value: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun FullscreenPager(controller: RedController, route: RedRoute.Media) {
    if (route.posts.isEmpty()) return
    val start = route.initialIndex.coerceIn(0, route.posts.lastIndex)
    val pagerState = rememberPagerState(initialPage = start, pageCount = { route.posts.size })

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
        beyondViewportPageCount = 1,
        pageSpacing = 0.dp,
        key = { route.posts[it].key }
    ) { page ->
        val post = route.posts[page]
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FullscreenMedia(
                post = post,
                active = pagerState.currentPage == page,
                autoplay = controller.autoplay,
                muted = controller.muted
            )
            FavoriteButton(
                favorite = controller.isFavorite(post),
                onClick = { controller.toggleFavorite(post) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 10.dp)
            )
        }
    }
}

@Composable
private fun FullscreenMedia(post: Post, active: Boolean, autoplay: Boolean, muted: Boolean) {
    val video = post.videoCandidates().firstOrNull()
    val images = post.fullCandidates().filterNot {
        it.contains(".mp4", true) || it.contains(".webm", true)
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            video != null -> NativeVideo(video, autoplay, muted, active)
            images.isNotEmpty() -> RemoteBitmap(
                imageCandidates = images,
                videoCandidates = emptyList(),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            else -> MediaPlaceholder(Modifier.fillMaxSize(), "No media")
        }
    }
}

@Composable
private fun NativeVideo(url: String, autoplay: Boolean, muted: Boolean, active: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                tag = url
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { player ->
                    player.isLooping = true
                    player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                    if (autoplay && active) start()
                }
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                view.setVideoURI(Uri.parse(url))
            }
            if (active && autoplay) {
                if (!view.isPlaying) runCatching { view.start() }
            } else if (view.isPlaying) {
                view.pause()
            }
        }
    )
}

@Composable
private fun FilterDialog(controller: RedController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Content", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NsfwMode.entries.forEach { mode ->
                        FilterChip(
                            selected = controller.nsfwMode == mode,
                            onClick = { controller.updateNsfw(mode) },
                            label = { Text(nsfwShortLabel(mode)) }
                        )
                    }
                }
                Text("Media", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(MediaFilter.ALL, MediaFilter.IMAGES).forEach { mode ->
                        FilterChip(
                            selected = controller.mediaFilter == mode,
                            onClick = { controller.mediaFilter = mode },
                            label = { Text(mediaFilterLabel(mode)) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(MediaFilter.VIDEOS, MediaFilter.ALBUMS).forEach { mode ->
                        FilterChip(
                            selected = controller.mediaFilter == mode,
                            onClick = { controller.mediaFilter = mode },
                            label = { Text(mediaFilterLabel(mode)) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                values.forEach { value ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(value) },
                        color = if (value == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(label(value), modifier = Modifier.padding(14.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val redBitmapCache = object : LruCache<String, Bitmap>(32) {}

@Composable
private fun RemoteBitmap(
    imageCandidates: List<String>,
    videoCandidates: List<String>,
    modifier: Modifier,
    contentScale: ContentScale
) {
    val key = remember(imageCandidates, videoCandidates) { (imageCandidates + videoCandidates).joinToString("|") }
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        failed = false
        bitmap = withContext(Dispatchers.IO) {
            loadFirstBitmap(imageCandidates) ?: loadFirstVideoFrame(videoCandidates)
        }
        failed = bitmap == null
    }

    when {
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
        failed -> MediaPlaceholder(modifier, if (videoCandidates.isNotEmpty()) "VIDEO" else "No preview")
        else -> Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private fun loadFirstBitmap(urls: List<String>): Bitmap? {
    for (url in urls.distinct()) {
        redBitmapCache.get(url)?.let { return it }
        val loaded = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16)")
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        if (loaded != null) {
            redBitmapCache.put(url, loaded)
            return loaded
        }
    }
    return null
}

private fun loadFirstVideoFrame(urls: List<String>): Bitmap? {
    for (url in urls.distinct()) {
        val cacheKey = "video:$url"
        redBitmapCache.get(cacheKey)?.let { return it }
        val frame = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(url, HashMap())
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }.getOrNull()
        if (frame != null) {
            redBitmapCache.put(cacheKey, frame)
            return frame
        }
    }
    return null
}

@Composable
private fun MediaPlaceholder(modifier: Modifier, label: String) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White)
    }
}

@Composable
private fun LoadingPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message)
        }
    }
}

@Composable
private fun EmptyPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorPanel(message: String, retry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.padding(24.dp)) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn’t load", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (retry != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = retry) { Text("Retry") }
                }
            }
        }
    }
}

private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.RANDOM -> "Random"
    SortMode.HOT -> "Hot"
    SortMode.NEW -> "New"
    SortMode.TOP -> "Top"
}

private fun mediaFilterLabel(mode: MediaFilter): String = when (mode) {
    MediaFilter.ALL -> "All media"
    MediaFilter.IMAGES -> "Images"
    MediaFilter.VIDEOS -> "Videos"
    MediaFilter.ALBUMS -> "Albums"
}

private fun nsfwShortLabel(mode: NsfwMode): String = when (mode) {
    NsfwMode.ALL -> "All"
    NsfwMode.SFW -> "SFW"
    NsfwMode.NSFW -> "NSFW"
}

private fun filterSummary(controller: RedController): String =
    "${nsfwShortLabel(controller.nsfwMode)} · ${mediaFilterLabel(controller.mediaFilter)}"

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}
