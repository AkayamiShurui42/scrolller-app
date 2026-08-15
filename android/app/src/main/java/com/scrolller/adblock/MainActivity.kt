package com.scrolller.adblock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DynamicTonalPalette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberSaveable
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
import com.scrolller.adblock.model.LayoutType
import com.scrolller.adblock.model.MainTab
import com.scrolller.adblock.model.NsfwMode
import com.scrolller.adblock.model.Post
import com.scrolller.adblock.model.Route
import com.scrolller.adblock.model.SearchResult
import com.scrolller.adblock.model.SortMode
import com.scrolller.adblock.model.ThemeMode
import com.scrolller.adblock.network.ScrolllerApi
import com.scrolller.adblock.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScrolllerNativeRoot() }
    }
}

private class NativeController(context: Context) {
    private val prefs = AppPreferences(context.applicationContext)

    var selectedTab by mutableStateOf(MainTab.HOME)
    var layoutType by mutableStateOf(prefs.layoutType)
    var nsfwMode by mutableStateOf(prefs.nsfwMode)
    var themeMode by mutableStateOf(prefs.themeMode)
    var autoplay by mutableStateOf(prefs.autoplay)
    var muted by mutableStateOf(prefs.muted)
    var sortMode by mutableStateOf(SortMode.RANDOM)

    val routes = mutableStateListOf<Route>()
    val favorites = mutableStateListOf<Post>().apply { addAll(prefs.loadFavorites()) }

    fun setLayout(value: LayoutType) {
        layoutType = value
        prefs.layoutType = value
    }

    fun setNsfw(value: NsfwMode) {
        nsfwMode = value
        prefs.nsfwMode = value
    }

    fun setTheme(value: ThemeMode) {
        themeMode = value
        prefs.themeMode = value
    }

    fun setAutoplay(value: Boolean) {
        autoplay = value
        prefs.autoplay = value
    }

    fun setMuted(value: Boolean) {
        muted = value
        prefs.muted = value
    }

    fun isFavorite(post: Post): Boolean = favorites.any { it.key == post.key }

    fun toggleFavorite(post: Post) {
        val index = favorites.indexOfFirst { it.key == post.key }
        if (index >= 0) favorites.removeAt(index) else favorites.add(0, post)
        prefs.saveFavorites(favorites)
    }

    fun filter(posts: List<Post>): List<Post> = when (nsfwMode) {
        NsfwMode.ALL -> posts
        NsfwMode.SFW -> posts.filterNot { it.isNsfw }
        NsfwMode.NSFW -> posts.filter { it.isNsfw }
    }

    fun openGallery(url: String, title: String) {
        routes.add(Route.Gallery(url, title))
    }

    fun openMedia(post: Post) {
        routes.add(Route.Media(post))
    }

    fun back(): Boolean {
        if (routes.isEmpty()) return false
        routes.removeAt(routes.lastIndex)
        return true
    }
}

@Composable
private fun ScrolllerNativeRoot() {
    val context = LocalContext.current
    val controller = remember { NativeController(context) }
    val dark = when (controller.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(colorScheme = colors) {
        BackHandler(enabled = controller.routes.isNotEmpty()) { controller.back() }
        val route = controller.routes.lastOrNull()
        if (route is Route.Media) {
            MediaScreen(controller, route.post)
        } else {
            Scaffold(
                bottomBar = { BottomNavigation(controller) }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (route) {
                        is Route.Gallery -> GalleryScreen(controller, route.url, route.title, showQuickFeeds = false)
                        else -> when (controller.selectedTab) {
                            MainTab.HOME -> GalleryScreen(controller, "funny", "Home", showQuickFeeds = true)
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
private fun BottomNavigation(controller: NativeController) {
    NavigationBar {
        val tabs = listOf(
            MainTab.HOME to ("⌂" to "Home"),
            MainTab.FAVORITES to ("♥" to "Favorites"),
            MainTab.SEARCH to ("⌕" to "Search"),
            MainTab.SETTINGS to ("⚙" to "Settings")
        )
        tabs.forEach { (tab, labels) ->
            NavigationBarItem(
                selected = controller.routes.isEmpty() && controller.selectedTab == tab,
                onClick = {
                    controller.routes.clear()
                    controller.selectedTab = tab
                },
                icon = { Text(labels.first) },
                label = { Text(labels.second) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreen(
    controller: NativeController,
    galleryUrl: String,
    title: String,
    showQuickFeeds: Boolean
) {
    var activeUrl by rememberSaveable(showQuickFeeds) { mutableStateOf(galleryUrl) }
    var activeTitle by rememberSaveable(showQuickFeeds) { mutableStateOf(title) }
    var gallery by remember(activeUrl, controller.sortMode) { mutableStateOf<GalleryInfo?>(null) }
    var error by remember(activeUrl, controller.sortMode) { mutableStateOf<String?>(null) }
    var loading by remember(activeUrl, controller.sortMode) { mutableStateOf(true) }
    var sortDialog by remember { mutableStateOf(false) }

    LaunchedEffect(activeUrl, controller.sortMode) {
        loading = true
        error = null
        gallery = null
        runCatching { ScrolllerApi.loadGallery(activeUrl, controller.sortMode) }
            .onSuccess {
                gallery = it
                activeTitle = if (showQuickFeeds && activeUrl == "funny") "Home" else it.title
            }
            .onFailure { error = it.message ?: "Unable to load gallery" }
        loading = false
    }

    if (sortDialog) {
        ChoiceDialog(
            title = "Choose sorting option",
            values = SortMode.entries,
            label = { sortLabel(it) },
            selected = controller.sortMode,
            onDismiss = { sortDialog = false },
            onSelect = {
                controller.sortMode = it
                sortDialog = false
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (gallery != null) {
                        Text(
                            "${controller.filter(gallery!!.posts).size} posts · ${sortLabel(controller.sortMode)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            navigationIcon = {
                if (!showQuickFeeds) {
                    TextButton(onClick = { controller.back() }) { Text("←") }
                }
            },
            actions = {
                TextButton(onClick = { sortDialog = true }) { Text(sortLabel(controller.sortMode)) }
                TextButton(onClick = {
                    controller.setLayout(if (controller.layoutType == LayoutType.GRID) LayoutType.LIST else LayoutType.GRID)
                }) {
                    Text(if (controller.layoutType == LayoutType.GRID) "List" else "Grid")
                }
            }
        )

        if (showQuickFeeds) {
            QuickFeeds(activeUrl) { url, label ->
                activeUrl = url
                activeTitle = label
            }
        }

        when {
            loading -> LoadingPanel("Loading full gallery…")
            error != null -> ErrorPanel(error!!) {
                val old = activeUrl
                activeUrl = ""
                activeUrl = old
            }
            gallery != null -> PostFeed(controller, controller.filter(gallery!!.posts))
        }
    }
}

@Composable
private fun QuickFeeds(active: String, onSelect: (String, String) -> Unit) {
    val feeds = listOf(
        "funny" to "Funny",
        "pics" to "Pics",
        "videos" to "Videos",
        "aww" to "Aww",
        "art" to "Art"
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(feeds) { (url, label) ->
            FilterChip(
                selected = active.equals(url, true),
                onClick = { onSelect(url, label) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun PostFeed(controller: NativeController, posts: List<Post>) {
    if (posts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No posts match this filter")
        }
        return
    }

    if (controller.layoutType == LayoutType.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(posts, key = { it.key }) { post ->
                PostCard(controller, post, compact = true)
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(posts, key = { it.key }) { post ->
                PostCard(controller, post, compact = false)
            }
        }
    }
}

@Composable
private fun PostCard(controller: NativeController, post: Post, compact: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { controller.openMedia(post) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            MediaThumbnail(
                post = post,
                modifier = Modifier.fillMaxWidth().height(if (compact) 190.dp else 330.dp)
            )
            Column(Modifier.padding(10.dp)) {
                Text(
                    post.title,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        post.subredditTitle.ifBlank { post.subredditUrl },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (post.isNsfw) {
                        Text("NSFW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = { controller.toggleFavorite(post) }) {
                        Text(if (controller.isFavorite(post)) "♥" else "♡")
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(post: Post, modifier: Modifier) {
    val imageCandidates = post.thumbnailCandidates()
    when {
        imageCandidates.isNotEmpty() -> NetworkBitmap(
            candidates = imageCandidates,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            videoFallback = emptyList()
        )
        post.videoCandidates().isNotEmpty() -> NetworkBitmap(
            candidates = emptyList(),
            modifier = modifier,
            contentScale = ContentScale.Crop,
            videoFallback = post.videoCandidates()
        )
        else -> MediaPlaceholder(modifier, "No preview")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(controller: NativeController) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, controller.nsfwMode) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            error = null
            loading = false
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        error = null
        runCatching { ScrolllerApi.searchGalleries(q, controller.nsfwMode != NsfwMode.SFW) }
            .onSuccess { found ->
                results = when (controller.nsfwMode) {
                    NsfwMode.ALL -> found
                    NsfwMode.SFW -> found.filterNot { it.isNsfw }
                    NsfwMode.NSFW -> found.filter { it.isNsfw }
                }
            }
            .onFailure { error = it.message ?: "Search failed" }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Search") })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search for galleries") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        )
        when {
            loading -> LoadingPanel("Searching…")
            error != null -> ErrorPanel(error!!, null)
            query.trim().length < 2 -> EmptyMessage("Search Scrolller galleries")
            results.isEmpty() -> EmptyMessage("No galleries found")
            else -> LazyColumn(contentPadding = PaddingValues(8.dp)) {
                items(results, key = { it.id.ifBlank { it.url } }) { result ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { controller.openGallery(result.url, result.title) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(result.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (result.isNsfw) Text("NSFW", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                "${result.itemCount} posts · ${result.url}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (result.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(result.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesScreen(controller: NativeController) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Favorites") },
            actions = {
                TextButton(onClick = {
                    controller.setLayout(if (controller.layoutType == LayoutType.GRID) LayoutType.LIST else LayoutType.GRID)
                }) { Text(if (controller.layoutType == LayoutType.GRID) "List" else "Grid") }
            }
        )
        val visible = controller.filter(controller.favorites)
        if (visible.isEmpty()) EmptyMessage("Favorite a post and it will stay here")
        else PostFeed(controller, visible)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(controller: NativeController) {
    var layoutDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var nsfwDialog by remember { mutableStateOf(false) }

    if (layoutDialog) ChoiceDialog("Layout", LayoutType.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, controller.layoutType, { layoutDialog = false }) {
        controller.setLayout(it); layoutDialog = false
    }
    if (themeDialog) ChoiceDialog("Theme", ThemeMode.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, controller.themeMode, { themeDialog = false }) {
        controller.setTheme(it); themeDialog = false
    }
    if (nsfwDialog) ChoiceDialog("Content filter", NsfwMode.entries, { nsfwLabel(it) }, controller.nsfwMode, { nsfwDialog = false }) {
        controller.setNsfw(it); nsfwDialog = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            item { SettingChoice("Layout", controller.layoutType.name.lowercase().replaceFirstChar(Char::uppercase)) { layoutDialog = true } }
            item { SettingChoice("Theme", controller.themeMode.name.lowercase().replaceFirstChar(Char::uppercase)) { themeDialog = true } }
            item { SettingChoice("Content", nsfwLabel(controller.nsfwMode)) { nsfwDialog = true } }
            item { SettingSwitch("Autoplay videos", controller.autoplay) { controller.setAutoplay(it) } }
            item { SettingSwitch("Mute videos", controller.muted) { controller.setMuted(it) } }
            item {
                Column(Modifier.padding(18.dp)) {
                    Text("Scrolller Pro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Native media client · RedView-style shell · direct Scrolller gallery API",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingChoice(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    values: List<T>,
    label: (T) -> String,
    selected: T,
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
                        Text(label(value), Modifier.padding(12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun MediaScreen(controller: NativeController, post: Post) {
    val video = post.videoCandidates().firstOrNull()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (video != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        val controls = MediaController(context)
                        controls.setAnchorView(this)
                        setMediaController(controls)
                        setVideoURI(Uri.parse(video))
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            val volume = if (controller.muted) 0f else 1f
                            player.setVolume(volume, volume)
                            if (controller.autoplay) start()
                        }
                    }
                },
                update = { view ->
                    if (controller.autoplay && !view.isPlaying) view.start()
                }
            )
        } else {
            NetworkBitmap(
                candidates = post.fullCandidates(),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                videoFallback = emptyList(),
                fullQuality = true
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            color = Color.Black.copy(alpha = 0.62f)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { controller.back() }) { Text("←", color = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(post.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(post.subredditTitle, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { controller.toggleFavorite(post) }) {
                    Text(if (controller.isFavorite(post)) "♥" else "♡", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun NetworkBitmap(
    candidates: List<String>,
    modifier: Modifier,
    contentScale: ContentScale,
    videoFallback: List<String>,
    fullQuality: Boolean = false
) {
    var bitmap by remember(candidates, videoFallback, fullQuality) { mutableStateOf<Bitmap?>(null) }
    var done by remember(candidates, videoFallback, fullQuality) { mutableStateOf(false) }

    LaunchedEffect(candidates, videoFallback, fullQuality) {
        done = false
        bitmap = withContext(Dispatchers.IO) {
            BitmapCache.loadFirst(candidates, fullQuality)
                ?: BitmapCache.videoFrame(videoFallback)
        }
        done = true
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val value = bitmap
        if (value != null) {
            Image(
                bitmap = value.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else if (!done) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        } else {
            Text("▶", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

private object BitmapCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun loadFirst(candidates: List<String>, fullQuality: Boolean): Bitmap? {
        for (url in candidates.distinct()) {
            cache.get(url)?.let { return it }
            val decoded = runCatching { load(url, fullQuality) }.getOrNull() ?: continue
            cache.put(url, decoded)
            return decoded
        }
        return null
    }

    private fun load(url: String, fullQuality: Boolean): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) ScrolllerPro/1.5")
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = if (fullQuality) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            }
            return connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
        } finally {
            connection.disconnect()
        }
    }

    fun videoFrame(candidates: List<String>): Bitmap? {
        for (url in candidates.take(2)) {
            val key = "video:$url"
            cache.get(key)?.let { return it }
            val bitmap = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(url, hashMapOf("User-Agent" to "Mozilla/5.0"))
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }.getOrNull() ?: continue
            cache.put(key, bitmap)
            return bitmap
        }
        return null
    }
}

@Composable
private fun LoadingPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorPanel(message: String, retry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Unable to load", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (retry != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = retry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MediaPlaceholder(modifier: Modifier, message: String) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Text(message, color = Color.White.copy(alpha = 0.65f))
    }
}

private fun sortLabel(sort: SortMode): String = when (sort) {
    SortMode.RANDOM -> "Random"
    SortMode.HOT -> "Hot"
    SortMode.NEW -> "New"
    SortMode.TOP -> "Top"
}

private fun nsfwLabel(mode: NsfwMode): String = when (mode) {
    NsfwMode.ALL -> "Show all"
    NsfwMode.SFW -> "SFW only"
    NsfwMode.NSFW -> "NSFW only"
}
