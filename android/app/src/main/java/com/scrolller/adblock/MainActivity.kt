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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.weight
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
        setContent { NativeScrolllerApp() }
    }
}

private class AppController(context: Context) {
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

    fun filtered(posts: List<Post>): List<Post> = when (nsfwMode) {
        NsfwMode.ALL -> posts
        NsfwMode.SFW -> posts.filterNot { it.isNsfw }
        NsfwMode.NSFW -> posts.filter { it.isNsfw }
    }

    fun isFavorite(post: Post): Boolean = favorites.any { it.key == post.key }

    fun toggleFavorite(post: Post) {
        val index = favorites.indexOfFirst { it.key == post.key }
        if (index >= 0) favorites.removeAt(index) else favorites.add(0, post)
        prefs.saveFavorites(favorites)
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
private fun NativeScrolllerApp() {
    val context = LocalContext.current
    val controller = remember { AppController(context) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (controller.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(colorScheme = scheme) {
        BackHandler(enabled = controller.routes.isNotEmpty()) { controller.back() }
        val route = controller.routes.lastOrNull()
        if (route is Route.Media) {
            MediaScreen(controller, route.post)
        } else {
            Scaffold(bottomBar = { BottomBar(controller) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (route) {
                        is Route.Gallery -> GalleryScreen(
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
private fun BottomBar(controller: AppController) {
    val entries = listOf(
        Triple(MainTab.HOME, "⌂", "Home"),
        Triple(MainTab.FAVORITES, "♥", "Favorites"),
        Triple(MainTab.SEARCH, "⌕", "Search"),
        Triple(MainTab.SETTINGS, "⚙", "Settings")
    )
    NavigationBar {
        entries.forEach { (tab, glyph, label) ->
            NavigationBarItem(
                selected = controller.routes.isEmpty() && controller.selectedTab == tab,
                onClick = {
                    controller.routes.clear()
                    controller.selectedTab = tab
                },
                icon = { Text(glyph) },
                label = { Text(label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreen(
    controller: AppController,
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
    var showSortDialog by remember { mutableStateOf(false) }

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

    if (showSortDialog) {
        ChoiceDialog(
            title = "Choose sorting option",
            values = SortMode.entries.toList(),
            selected = controller.sortMode,
            label = ::sortLabel,
            onDismiss = { showSortDialog = false },
            onSelect = {
                controller.sortMode = it
                showSortDialog = false
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(activeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    gallery?.let {
                        Text(
                            "${controller.filtered(it.posts).size} posts",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            navigationIcon = {
                if (!quickFeeds) TextButton(onClick = { controller.back() }) { Text("←") }
            },
            actions = {
                TextButton(onClick = { showSortDialog = true }) { Text(sortLabel(controller.sortMode)) }
                TextButton(onClick = {
                    controller.setLayout(
                        if (controller.layoutType == LayoutType.GRID) LayoutType.LIST else LayoutType.GRID
                    )
                }) {
                    Text(if (controller.layoutType == LayoutType.GRID) "List" else "Grid")
                }
            }
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
            gallery != null -> PostFeed(controller, controller.filtered(gallery!!.posts))
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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
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
private fun PostFeed(controller: AppController, posts: List<Post>) {
    if (posts.isEmpty()) {
        EmptyPanel("No posts match this filter")
        return
    }

    if (controller.layoutType == LayoutType.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(posts, key = { it.key }) { post -> PostCard(controller, post, true) }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(posts, key = { it.key }) { post -> PostCard(controller, post, false) }
        }
    }
}

@Composable
private fun PostCard(controller: AppController, post: Post, compact: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { controller.openMedia(post) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            MediaThumb(
                post,
                Modifier.fillMaxWidth().height(if (compact) 190.dp else 330.dp)
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
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (post.isNsfw) {
                        Text("NSFW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
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
private fun MediaThumb(post: Post, modifier: Modifier) {
    val images = post.thumbnailCandidates()
    val videos = post.videoCandidates()
    RemoteBitmap(
        imageCandidates = images,
        videoCandidates = if (images.isEmpty()) videos else emptyList(),
        modifier = modifier,
        contentScale = ContentScale.Crop,
        fullQuality = false
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(controller: AppController) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, controller.nsfwMode) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            loading = false
            error = null
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search for galleries") }
        )
        when {
            loading -> LoadingPanel("Searching…")
            error != null -> ErrorPanel(error!!, null)
            query.trim().length < 2 -> EmptyPanel("Search Scrolller galleries")
            results.isEmpty() -> EmptyPanel("No galleries found")
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.id.ifBlank { it.url } }) { result ->
                    SearchResultCard(controller, result)
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(controller: AppController, result: SearchResult) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { controller.openGallery(result.url, result.title) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(result.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesScreen(controller: AppController) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Favorites") },
            actions = {
                TextButton(onClick = {
                    controller.setLayout(
                        if (controller.layoutType == LayoutType.GRID) LayoutType.LIST else LayoutType.GRID
                    )
                }) { Text(if (controller.layoutType == LayoutType.GRID) "List" else "Grid") }
            }
        )
        val visible = controller.filtered(controller.favorites)
        if (visible.isEmpty()) EmptyPanel("Favorite a post and it will stay here")
        else PostFeed(controller, visible)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(controller: AppController) {
    var layoutDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var contentDialog by remember { mutableStateOf(false) }

    if (layoutDialog) ChoiceDialog(
        "Layout",
        LayoutType.entries.toList(),
        controller.layoutType,
        { pretty(it.name) },
        { layoutDialog = false }
    ) { controller.setLayout(it); layoutDialog = false }

    if (themeDialog) ChoiceDialog(
        "Theme",
        ThemeMode.entries.toList(),
        controller.themeMode,
        { pretty(it.name) },
        { themeDialog = false }
    ) { controller.setTheme(it); themeDialog = false }

    if (contentDialog) ChoiceDialog(
        "Content filter",
        NsfwMode.entries.toList(),
        controller.nsfwMode,
        ::nsfwLabel,
        { contentDialog = false }
    ) { controller.setNsfw(it); contentDialog = false }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        LazyColumn {
            item { SettingChoice("Layout", pretty(controller.layoutType.name)) { layoutDialog = true } }
            item { SettingChoice("Theme", pretty(controller.themeMode.name)) { themeDialog = true } }
            item { SettingChoice("Content", nsfwLabel(controller.nsfwMode)) { contentDialog = true } }
            item { SettingSwitch("Autoplay videos", controller.autoplay, controller::setAutoplay) }
            item { SettingSwitch("Mute videos", controller.muted, controller::setMuted) }
            item {
                Column(Modifier.padding(18.dp)) {
                    Text("Scrolller Pro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Native media client · direct Scrolller gallery API",
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(horizontal = 18.dp, vertical = 12.dp),
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
    selected: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun MediaScreen(controller: AppController, post: Post) {
    val videoUrl = post.videoCandidates().firstOrNull()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (videoUrl != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        val mediaController = MediaController(context)
                        mediaController.setAnchorView(this)
                        setMediaController(mediaController)
                        setVideoURI(Uri.parse(videoUrl))
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            val volume = if (controller.muted) 0f else 1f
                            player.setVolume(volume, volume)
                            if (controller.autoplay) start()
                        }
                    }
                },
                update = { view -> if (controller.autoplay && !view.isPlaying) view.start() }
            )
        } else {
            RemoteBitmap(
                imageCandidates = post.fullCandidates(),
                videoCandidates = emptyList(),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                fullQuality = true
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            color = Color.Black.copy(alpha = 0.62f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { controller.back() }) { Text("←", color = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(post.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        post.subredditTitle.ifBlank { post.subredditUrl },
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
                TextButton(onClick = { controller.toggleFavorite(post) }) {
                    Text(if (controller.isFavorite(post)) "♥" else "♡", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RemoteBitmap(
    imageCandidates: List<String>,
    videoCandidates: List<String>,
    modifier: Modifier,
    contentScale: ContentScale,
    fullQuality: Boolean
) {
    var bitmap by remember(imageCandidates, videoCandidates, fullQuality) { mutableStateOf<Bitmap?>(null) }
    var done by remember(imageCandidates, videoCandidates, fullQuality) { mutableStateOf(false) }

    LaunchedEffect(imageCandidates, videoCandidates, fullQuality) {
        done = false
        bitmap = withContext(Dispatchers.IO) {
            NativeBitmapCache.loadFirst(imageCandidates, fullQuality)
                ?: NativeBitmapCache.videoFrame(videoCandidates)
        }
        done = true
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } ?: if (!done) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        } else {
            Text("▶", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

private object NativeBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun loadFirst(urls: List<String>, fullQuality: Boolean): Bitmap? {
        for (url in urls.distinct()) {
            cache.get(url)?.let { return it }
            val bitmap = runCatching { loadUrl(url, fullQuality) }.getOrNull() ?: continue
            cache.put(url, bitmap)
            return bitmap
        }
        return null
    }

    private fun loadUrl(url: String, fullQuality: Boolean): Bitmap? {
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

    fun videoFrame(urls: List<String>): Bitmap? {
        for (url in urls.take(2)) {
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
private fun LoadingPanel(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorPanel(text: String, retry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Unable to load", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (retry != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = retry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun EmptyPanel(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun pretty(value: String): String = value.lowercase().replaceFirstChar { it.uppercase() }
