package com.scrolller.adblock

import android.app.Activity
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
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
import java.time.Instant
import java.time.OffsetDateTime
import java.util.HashMap

class RedViewV2Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RedViewV2App() }
    }
}

private enum class V2MediaFilter { ALL, IMAGES, VIDEOS, ALBUMS }
private enum class V2SearchMode { POSTS, GALLERIES }
private enum class FavoriteSort { SAVED, NEWEST, OLDEST, RANDOM }

private sealed interface V2Route {
    data class Gallery(val url: String, val title: String) : V2Route
    data class Similar(val seed: Post) : V2Route
    data class Media(val posts: List<Post>, val initialIndex: Int) : V2Route
}

private class V2Controller(context: Context) {
    private val prefs = AppPreferences(context.applicationContext)
    private val galleryCache = mutableMapOf<String, GalleryInfo>()

    var selectedTab by mutableStateOf(MainTab.HOME)
    var nsfwMode by mutableStateOf(prefs.nsfwMode)
    var mediaFilter by mutableStateOf(V2MediaFilter.ALL)
    var themeMode by mutableStateOf(prefs.themeMode)
    var autoplay by mutableStateOf(prefs.autoplay)
    var muted by mutableStateOf(prefs.muted)
    var sortMode by mutableStateOf(SortMode.RANDOM)
    var favoriteSort by mutableStateOf(FavoriteSort.SAVED)

    val routes = mutableStateListOf<V2Route>()
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
            V2MediaFilter.ALL -> true
            V2MediaFilter.IMAGES -> !post.isVideo && post.albumSources.size <= 1
            V2MediaFilter.VIDEOS -> post.isVideo
            V2MediaFilter.ALBUMS -> post.albumSources.size > 1
        }
        contentOk && mediaOk
    }.distinctBy { it.key }

    fun sortedFavorites(): List<Post> {
        val base = filtered(favorites)
        return when (favoriteSort) {
            FavoriteSort.SAVED -> base
            FavoriteSort.NEWEST -> base.sortedByDescending { createdMillis(it.createdAt) }
            FavoriteSort.OLDEST -> base.sortedBy { createdMillis(it.createdAt) }
            FavoriteSort.RANDOM -> base.sortedBy { stableRandomKey(it.key) }
        }
    }

    fun isFavorite(post: Post): Boolean = favorites.any { it.key == post.key }

    fun toggleFavorite(post: Post) {
        val index = favorites.indexOfFirst { it.key == post.key }
        if (index >= 0) favorites.removeAt(index) else favorites.add(0, post)
        prefs.saveFavorites(favorites)
    }

    fun openGallery(url: String, title: String) {
        routes.add(V2Route.Gallery(url, title))
    }

    fun openMedia(post: Post, posts: List<Post>) {
        val start = posts.indexOfFirst { it.key == post.key }.coerceAtLeast(0)
        routes.add(V2Route.Media(posts, start))
    }

    fun openSimilar(post: Post) {
        if (routes.lastOrNull() is V2Route.Media) routes.removeAt(routes.lastIndex)
        routes.add(V2Route.Similar(post))
    }

    fun back(): Boolean {
        if (routes.isEmpty()) return false
        routes.removeAt(routes.lastIndex)
        return true
    }

    suspend fun loadGalleryCached(url: String, sort: SortMode): GalleryInfo {
        val key = "${url.trim().lowercase()}|${sort.name}"
        galleryCache[key]?.let { return it }
        val loaded = ScrolllerApi.loadGallery(url, sort)
        galleryCache[key] = loaded
        return loaded
    }
}

@Composable
private fun RedViewV2App() {
    val context = LocalContext.current
    val activity = context as? Activity
    val controller = remember { V2Controller(context) }
    var confirmExit by remember { mutableStateOf(false) }

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
        val topRoute = controller.routes.lastOrNull()
        val mediaRoute = topRoute as? V2Route.Media
        val backgroundRoute = if (mediaRoute != null) controller.routes.dropLast(1).lastOrNull() else topRoute

        BackHandler(enabled = true) {
            when {
                controller.routes.isNotEmpty() -> controller.back()
                controller.selectedTab != MainTab.HOME -> controller.selectedTab = MainTab.HOME
                else -> confirmExit = true
            }
        }

        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("Exit Scrolller Pro?") },
                text = { Text("You’re already at the main feed.") },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Cancel") } },
                confirmButton = {
                    TextButton(onClick = {
                        confirmExit = false
                        activity?.finish()
                    }) { Text("Exit") }
                }
            )
        }

        Box(Modifier.fillMaxSize()) {
            Scaffold(bottomBar = { V2BottomBar(controller) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (backgroundRoute) {
                        is V2Route.Gallery -> V2GalleryScreen(controller, backgroundRoute.url, backgroundRoute.title, false)
                        is V2Route.Similar -> SimilarScreen(controller, backgroundRoute.seed)
                        else -> when (controller.selectedTab) {
                            MainTab.HOME -> V2GalleryScreen(controller, "funny", "Home", true)
                            MainTab.FAVORITES -> V2FavoritesScreen(controller)
                            MainTab.SEARCH -> V2SearchScreen(controller)
                            MainTab.SETTINGS -> V2SettingsScreen(controller)
                        }
                    }
                }
            }

            if (mediaRoute != null) {
                V2FullscreenPager(controller, mediaRoute)
            }
        }
    }
}

@Composable
private fun V2BottomBar(controller: V2Controller) {
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
private fun V2GalleryScreen(controller: V2Controller, initialUrl: String, initialTitle: String, quickFeeds: Boolean) {
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
        runCatching { controller.loadGalleryCached(activeUrl, controller.sortMode) }
            .onSuccess {
                gallery = it
                if (!quickFeeds || activeUrl != "funny") activeTitle = it.title
            }
            .onFailure { error = it.message ?: "Unable to load gallery" }
        loading = false
    }

    if (showSort) V2SortDialog(controller.sortMode, { showSort = false }) {
        controller.sortMode = it
        showSort = false
    }
    if (showFilter) V2FilterDialog(controller) { showFilter = false }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (!quickFeeds) TextButton(onClick = { controller.back() }) { Text("←") }
            }
        )
        V2FeedControls(controller, { showSort = true }, { showFilter = true })
        if (quickFeeds) V2QuickFeeds(activeUrl) { url, title ->
            activeUrl = url
            activeTitle = title
        }
        when {
            loading -> V2LoadingPanel("Loading full gallery…")
            error != null -> V2ErrorPanel(error!!) { reloadKey++ }
            gallery != null -> V2MediaFeed(controller, controller.filtered(gallery!!.posts))
        }
    }
}

@Composable
private fun V2FeedControls(controller: V2Controller, onSort: () -> Unit, onFilter: () -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { FilterChip(false, onSort, label = { Text("Sort: ${v2SortLabel(controller.sortMode)}") }) }
        item {
            FilterChip(
                selected = controller.nsfwMode != NsfwMode.ALL || controller.mediaFilter != V2MediaFilter.ALL,
                onClick = onFilter,
                label = { Text("Filter: ${v2FilterSummary(controller)}") }
            )
        }
    }
}

@Composable
private fun V2QuickFeeds(activeUrl: String, onSelect: (String, String) -> Unit) {
    val feeds = listOf("funny" to "Funny", "pics" to "Pics", "videos" to "Videos", "aww" to "Aww", "art" to "Art")
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
private fun V2MediaFeed(controller: V2Controller, posts: List<Post>) {
    if (posts.isEmpty()) {
        V2EmptyPanel("No media matches this filter")
        return
    }

    val listState = rememberLazyListState()
    val activeIndex by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val start = layout.viewportStartOffset
            val end = layout.viewportEndOffset
            layout.visibleItemsInfo.maxByOrNull { info ->
                val visibleStart = maxOf(start, info.offset)
                val visibleEnd = minOf(end, info.offset + info.size)
                maxOf(0, visibleEnd - visibleStart)
            }?.index ?: -1
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        itemsIndexed(posts, key = { _, post -> post.key }) { index, post ->
            V2NaturalMediaPreview(controller, post, posts, active = index == activeIndex)
        }
    }
}

@Composable
private fun V2NaturalMediaPreview(controller: V2Controller, post: Post, posts: List<Post>, active: Boolean) {
    val video = post.videoCandidates().firstOrNull()
    val images = post.thumbnailCandidates()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(v2NaturalAspectRatio(post))
            .background(Color.Black)
            .clickable { controller.openMedia(post, posts) }
    ) {
        if (video != null && active && controller.autoplay) {
            V2NativeVideo(video, controller.autoplay, controller.muted, true) {
                controller.openMedia(post, posts)
            }
        } else {
            V2RemoteBitmap(
                imageCandidates = images,
                videoCandidates = if (images.isEmpty()) post.videoCandidates() else emptyList(),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        V2FavoriteButton(
            favorite = controller.isFavorite(post),
            onClick = { controller.toggleFavorite(post) },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
        )
        V2ActionButton(
            text = "≈",
            onClick = { controller.openSimilar(post) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
        )
    }
}

private fun v2NaturalAspectRatio(post: Post): Float {
    val source = post.allSources().filter { it.width > 0 && it.height > 0 }.maxByOrNull { it.area } ?: return 1f
    val ratio = source.width.toFloat() / source.height.toFloat()
    return if (ratio.isFinite() && ratio > 0f) ratio else 1f
}

@Composable
private fun V2FavoriteButton(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    V2ActionButton(if (favorite) "♥" else "♡", onClick, modifier, favorite)
}

@Composable
private fun V2ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    Surface(modifier = modifier, shape = CircleShape, color = Color.Black.copy(alpha = 0.58f)) {
        IconButton(onClick = onClick) {
            Text(
                text = text,
                color = if (active) MaterialTheme.colorScheme.primary else Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V2SearchScreen(controller: V2Controller) {
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(V2SearchMode.POSTS) }
    var postResults by remember { mutableStateOf<List<Post>>(emptyList()) }
    var galleryResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSort by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    if (showSort) V2SortDialog(controller.sortMode, { showSort = false }) {
        controller.sortMode = it
        showSort = false
    }
    if (showFilter) V2FilterDialog(controller) { showFilter = false }

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
            if (mode == V2SearchMode.GALLERIES) {
                galleryResults = ScrolllerApi.searchGalleries(clean, controller.nsfwMode != NsfwMode.SFW)
            } else {
                postResults = v2SearchPosts(clean, controller.sortMode, controller.nsfwMode != NsfwMode.SFW)
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
            label = { Text(if (mode == V2SearchMode.POSTS) "Search posts and media" else "Search galleries") }
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { FilterChip(mode == V2SearchMode.POSTS, { mode = V2SearchMode.POSTS }, label = { Text("Posts") }) }
            item { FilterChip(mode == V2SearchMode.GALLERIES, { mode = V2SearchMode.GALLERIES }, label = { Text("Galleries") }) }
            item { FilterChip(false, { showSort = true }, label = { Text("Sort") }) }
            item {
                FilterChip(
                    controller.nsfwMode != NsfwMode.ALL || controller.mediaFilter != V2MediaFilter.ALL,
                    { showFilter = true },
                    label = { Text("Filter") }
                )
            }
        }

        when {
            loading -> V2LoadingPanel(if (mode == V2SearchMode.POSTS) "Searching media…" else "Searching galleries…")
            error != null -> V2ErrorPanel(error!!, null)
            query.trim().length < 2 -> V2EmptyPanel("Search Scrolller")
            mode == V2SearchMode.POSTS -> {
                val visible = controller.filtered(postResults)
                if (visible.isEmpty()) V2EmptyPanel("No matching media") else V2MediaFeed(controller, visible)
            }
            galleryResults.isEmpty() -> V2EmptyPanel("No galleries found")
            else -> V2GallerySearchResults(controller, galleryResults)
        }
    }
}

private suspend fun v2SearchPosts(query: String, sort: SortMode, includeNsfw: Boolean): List<Post> = coroutineScope {
    val groups = ScrolllerApi.searchGalleries(query, includeNsfw)
        .filter { it.itemCount != 0 && it.url.isNotBlank() }
        .take(8)
    val galleries = if (groups.isNotEmpty()) {
        groups.map { result ->
            async(Dispatchers.IO) { runCatching { ScrolllerApi.loadGallery(result.url, sort, limit = 600) }.getOrNull() }
        }.awaitAll().filterNotNull()
    } else {
        listOfNotNull(runCatching { ScrolllerApi.loadGallery(query, sort, limit = 600) }.getOrNull())
    }
    galleries.flatMap { it.posts }.distinctBy { it.key }
        .map { it to v2PostSearchScore(it, query) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<Post, Int>> { it.second }.thenByDescending { createdMillis(it.first.createdAt) })
        .map { it.first }
}

private fun v2PostSearchScore(post: Post, query: String): Int {
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

@Composable
private fun V2GallerySearchResults(controller: V2Controller, results: List<SearchResult>) {
    LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(results, key = { it.id.ifBlank { it.url } }) { result ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { controller.openGallery(result.url, result.title) },
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (result.isNsfw) Text("NSFW", color = MaterialTheme.colorScheme.error)
                    }
                    if (result.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(result.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
private fun V2FavoritesScreen(controller: V2Controller) {
    var showSort by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    if (showSort) {
        V2FavoriteSortDialog(controller.favoriteSort, { showSort = false }) {
            controller.favoriteSort = it
            showSort = false
        }
    }
    if (showFilter) V2FilterDialog(controller) { showFilter = false }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Favorites") })
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { FilterChip(false, { showSort = true }, label = { Text("Sort: ${favoriteSortLabel(controller.favoriteSort)}") }) }
            item {
                FilterChip(
                    controller.nsfwMode != NsfwMode.ALL || controller.mediaFilter != V2MediaFilter.ALL,
                    { showFilter = true },
                    label = { Text("Filter: ${v2FilterSummary(controller)}") }
                )
            }
        }
        V2MediaFeed(controller, controller.sortedFavorites())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimilarScreen(controller: V2Controller, seed: Post) {
    var results by remember(seed.key, controller.sortMode) { mutableStateOf<List<Post>>(emptyList()) }
    var loading by remember(seed.key, controller.sortMode) { mutableStateOf(true) }
    var error by remember(seed.key, controller.sortMode) { mutableStateOf<String?>(null) }
    var showSort by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    val query = remember(seed.key) { v2SimilarQuery(seed) }

    if (showSort) V2SortDialog(controller.sortMode, { showSort = false }) {
        controller.sortMode = it
        showSort = false
    }
    if (showFilter) V2FilterDialog(controller) { showFilter = false }

    LaunchedEffect(seed.key, controller.sortMode) {
        loading = true
        error = null
        runCatching { v2SearchPosts(query, controller.sortMode, controller.nsfwMode != NsfwMode.SFW) }
            .onSuccess { results = it }
            .onFailure { error = it.message ?: "Unable to find similar media" }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Similar") },
            navigationIcon = { TextButton(onClick = { controller.back() }) { Text("←") } }
        )
        V2FeedControls(controller, { showSort = true }, { showFilter = true })
        when {
            loading -> V2LoadingPanel("Finding similar media…")
            error != null -> V2ErrorPanel(error!!, null)
            else -> {
                val visible = controller.filtered(results)
                if (visible.isEmpty()) V2EmptyPanel("No similar media found") else V2MediaFeed(controller, visible)
            }
        }
    }
}

private fun v2SimilarQuery(post: Post): String {
    val stop = setOf("this", "that", "with", "from", "have", "your", "just", "when", "what", "there", "about", "into", "they", "them")
    val words = post.title.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length > 3 && it !in stop }
        .distinct()
        .take(6)
    return words.joinToString(" ").ifBlank { post.subredditTitle.ifBlank { post.subredditUrl } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V2SettingsScreen(controller: V2Controller) {
    var chooseTheme by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    if (chooseTheme) {
        V2ChoiceDialog("Theme", ThemeMode.entries.toList(), controller.themeMode, ::v2ThemeLabel, { chooseTheme = false }) {
            controller.updateTheme(it)
            chooseTheme = false
        }
    }
    if (showFilter) V2FilterDialog(controller) { showFilter = false }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { V2SettingsChoice("Content and media filter", v2FilterSummary(controller)) { showFilter = true } }
            item { V2SettingsChoice("Theme", v2ThemeLabel(controller.themeMode)) { chooseTheme = true } }
            item { V2SettingsSwitch("Autoplay videos", controller.autoplay) { controller.updateAutoplay(it) } }
            item { V2SettingsSwitch("Mute videos by default", controller.muted) { controller.updateMuted(it) } }
        }
    }
}

@Composable
private fun V2SettingsChoice(title: String, value: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun V2SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title)
            Switch(checked, onChange)
        }
    }
}

@Composable
private fun V2FullscreenPager(controller: V2Controller, route: V2Route.Media) {
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
            V2FullscreenMedia(post, pagerState.currentPage == page, controller.autoplay, controller.muted)
            V2FavoriteButton(
                controller.isFavorite(post),
                { controller.toggleFavorite(post) },
                Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 10.dp)
            )
            V2ActionButton(
                "≈",
                { controller.openSimilar(post) },
                Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = 10.dp)
            )
        }
    }
}

@Composable
private fun V2FullscreenMedia(post: Post, active: Boolean, autoplay: Boolean, muted: Boolean) {
    val video = post.videoCandidates().firstOrNull()
    val images = post.fullCandidates().filterNot { it.contains(".mp4", true) || it.contains(".webm", true) }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            video != null -> V2NativeVideo(video, autoplay, muted, active, null)
            images.isNotEmpty() -> V2RemoteBitmap(images, emptyList(), Modifier.fillMaxSize(), ContentScale.Fit)
            else -> V2MediaPlaceholder(Modifier.fillMaxSize(), "No media")
        }
    }
}

@Composable
private fun V2NativeVideo(url: String, autoplay: Boolean, muted: Boolean, active: Boolean, onTap: (() -> Unit)?) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                tag = url
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { player ->
                    player.isLooping = true
                    player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                    if (autoplay && active) start() else seekTo(1)
                }
                setOnClickListener {
                    if (onTap != null) onTap()
                    else if (isPlaying) pause() else start()
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
private fun V2FilterDialog(controller: V2Controller, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Content", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NsfwMode.entries.forEach { mode ->
                        FilterChip(controller.nsfwMode == mode, { controller.updateNsfw(mode) }, label = { Text(v2NsfwLabel(mode)) })
                    }
                }
                Text("Media", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(V2MediaFilter.ALL, V2MediaFilter.IMAGES).forEach { mode ->
                        FilterChip(controller.mediaFilter == mode, { controller.mediaFilter = mode }, label = { Text(v2MediaFilterLabel(mode)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(V2MediaFilter.VIDEOS, V2MediaFilter.ALBUMS).forEach { mode ->
                        FilterChip(controller.mediaFilter == mode, { controller.mediaFilter = mode }, label = { Text(v2MediaFilterLabel(mode)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun V2SortDialog(selected: SortMode, onDismiss: () -> Unit, onSelect: (SortMode) -> Unit) {
    V2ChoiceDialog("Sort", SortMode.entries.toList(), selected, ::v2SortLabel, onDismiss, onSelect)
}

@Composable
private fun V2FavoriteSortDialog(selected: FavoriteSort, onDismiss: () -> Unit, onSelect: (FavoriteSort) -> Unit) {
    V2ChoiceDialog("Sort favorites", FavoriteSort.entries.toList(), selected, ::favoriteSortLabel, onDismiss, onSelect)
}

@Composable
private fun <T> V2ChoiceDialog(
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

private val v2BitmapCache = object : LruCache<String, Bitmap>(48) {}

@Composable
private fun V2RemoteBitmap(imageCandidates: List<String>, videoCandidates: List<String>, modifier: Modifier, contentScale: ContentScale) {
    val key = remember(imageCandidates, videoCandidates) { (imageCandidates + videoCandidates).joinToString("|") }
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        failed = false
        bitmap = withContext(Dispatchers.IO) { v2LoadFirstBitmap(imageCandidates) ?: v2LoadFirstVideoFrame(videoCandidates) }
        failed = bitmap == null
    }

    when {
        bitmap != null -> Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = contentScale)
        failed -> V2MediaPlaceholder(modifier, if (videoCandidates.isNotEmpty()) "VIDEO" else "No preview")
        else -> Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private fun v2LoadFirstBitmap(urls: List<String>): Bitmap? {
    for (url in urls.distinct()) {
        v2BitmapCache.get(url)?.let { return it }
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
            v2BitmapCache.put(url, loaded)
            return loaded
        }
    }
    return null
}

private fun v2LoadFirstVideoFrame(urls: List<String>): Bitmap? {
    for (url in urls.distinct()) {
        val cacheKey = "video:$url"
        v2BitmapCache.get(cacheKey)?.let { return it }
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
            v2BitmapCache.put(cacheKey, frame)
            return frame
        }
    }
    return null
}

@Composable
private fun V2MediaPlaceholder(modifier: Modifier, label: String) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) { Text(label, color = Color.White) }
}

@Composable
private fun V2LoadingPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message)
        }
    }
}

@Composable
private fun V2EmptyPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun V2ErrorPanel(message: String, retry: (() -> Unit)?) {
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

private fun createdMillis(value: String): Long {
    if (value.isBlank()) return 0L
    value.toLongOrNull()?.let { return it }
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrDefault(0L)
}

private fun stableRandomKey(value: String): Int {
    var h = 0x45d9f3b
    value.forEach { h = (h * 31) xor it.code }
    return h
}

private fun v2SortLabel(mode: SortMode): String = when (mode) {
    SortMode.RANDOM -> "Random"
    SortMode.HOT -> "Hot"
    SortMode.NEW -> "New"
    SortMode.TOP -> "Top"
}

private fun favoriteSortLabel(mode: FavoriteSort): String = when (mode) {
    FavoriteSort.SAVED -> "Recently saved"
    FavoriteSort.NEWEST -> "Newest post"
    FavoriteSort.OLDEST -> "Oldest post"
    FavoriteSort.RANDOM -> "Random"
}

private fun v2MediaFilterLabel(mode: V2MediaFilter): String = when (mode) {
    V2MediaFilter.ALL -> "All media"
    V2MediaFilter.IMAGES -> "Images"
    V2MediaFilter.VIDEOS -> "Videos"
    V2MediaFilter.ALBUMS -> "Albums"
}

private fun v2NsfwLabel(mode: NsfwMode): String = when (mode) {
    NsfwMode.ALL -> "All"
    NsfwMode.SFW -> "SFW"
    NsfwMode.NSFW -> "NSFW"
}

private fun v2FilterSummary(controller: V2Controller): String =
    "${v2NsfwLabel(controller.nsfwMode)} · ${v2MediaFilterLabel(controller.mediaFilter)}"

private fun v2ThemeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}
