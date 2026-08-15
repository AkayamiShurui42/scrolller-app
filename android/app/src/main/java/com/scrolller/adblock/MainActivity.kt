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
import java.util.HashMap

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
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(colorScheme = colors) {
        val route = controller.routes.lastOrNull()
        BackHandler(enabled = route != null) { controller.back() }

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
                TextButton(onClick = { showSortDialog = true }) {
                    Text(sortLabel(controller.sortMode))
                }
                TextButton(
                    onClick = {
                        controller.setLayout(
                            if (controller.layoutType == LayoutType.GRID) LayoutType.LIST else LayoutType.GRID
                        )
                    }
                ) {
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
private fun PostCard(controller: AppController, post: Post, compact: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { controller.openMedia(post) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            MediaThumb(
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
                Text(
                    post.subredditTitle.ifBlank { post.subredditUrl },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (post.isNsfw) {
                        Text(
                            "NSFW",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(if (post.isVideo) "VIDEO" else "IMAGE", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { controller.toggleFavorite(post) }) {
                        Text(if (controller.isFavorite(post)) "♥ Saved" else "♡ Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumb(post: Post, modifier: Modifier) {
    RemoteBitmap(
        imageCandidates = post.thumbnailCandidates(),
        videoCandidates = if (post.thumbnailCandidates().isEmpty()) post.videoCandidates() else emptyList(),
        modifier = modifier,
        contentScale = ContentScale.Crop
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
        val clean = query.trim()
        if (clean.length < 2) {
            results = emptyList()
            loading = false
            error = null
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        error = null
        runCatching { ScrolllerApi.searchGalleries(clean, controller.nsfwMode != NsfwMode.SFW) }
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
            label = { Text("Search galleries") }
        )

        when {
            loading -> LoadingPanel("Searching…")
            error != null -> ErrorPanel(error!!, null)
            query.trim().length < 2 -> EmptyPanel("Search Scrolller galleries")
            results.isEmpty() -> EmptyPanel("No galleries found")
            else -> LazyColumn(
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.id.ifBlank { it.url } }) { result ->
                    SearchResultCard(result) {
                        controller.openGallery(result.url, result.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchResult, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesScreen(controller: AppController) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Favorites") })
        PostFeed(controller, controller.filtered(controller.favorites))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(controller: AppController) {
    var chooseLayout by remember { mutableStateOf(false) }
    var chooseNsfw by remember { mutableStateOf(false) }
    var chooseTheme by remember { mutableStateOf(false) }

    if (chooseLayout) {
        ChoiceDialog(
            "Layout",
            LayoutType.entries.toList(),
            controller.layoutType,
            { if (it == LayoutType.GRID) "Grid" else "List" },
            { chooseLayout = false }
        ) {
            controller.setLayout(it)
            chooseLayout = false
        }
    }
    if (chooseNsfw) {
        ChoiceDialog(
            "Content",
            NsfwMode.entries.toList(),
            controller.nsfwMode,
            ::nsfwLabel,
            { chooseNsfw = false }
        ) {
            controller.setNsfw(it)
            chooseNsfw = false
        }
    }
    if (chooseTheme) {
        ChoiceDialog(
            "Theme",
            ThemeMode.entries.toList(),
            controller.themeMode,
            ::themeLabel,
            { chooseTheme = false }
        ) {
            controller.setTheme(it)
            chooseTheme = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SettingsChoice("Layout", if (controller.layoutType == LayoutType.GRID) "Grid" else "List") {
                    chooseLayout = true
                }
            }
            item {
                SettingsChoice("Content filter", nsfwLabel(controller.nsfwMode)) {
                    chooseNsfw = true
                }
            }
            item {
                SettingsChoice("Theme", themeLabel(controller.themeMode)) {
                    chooseTheme = true
                }
            }
            item {
                SettingsSwitch("Autoplay videos", controller.autoplay) {
                    controller.setAutoplay(it)
                }
            }
            item {
                SettingsSwitch("Mute videos by default", controller.muted) {
                    controller.setMuted(it)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Scrolller Native", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "RedView-style native client · direct Scrolller media feed",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaScreen(controller: AppController, post: Post) {
    val video = post.videoCandidates().firstOrNull()
    val images = post.fullCandidates().filterNot { it.contains(".mp4", true) || it.contains(".webm", true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(post.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    TextButton(onClick = { controller.back() }) { Text("←") }
                },
                actions = {
                    TextButton(onClick = { controller.toggleFavorite(post) }) {
                        Text(if (controller.isFavorite(post)) "♥" else "♡")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (video != null) {
                NativeVideo(video, controller.autoplay, controller.muted)
            } else if (images.isNotEmpty()) {
                RemoteBitmap(
                    imageCandidates = images,
                    videoCandidates = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("No playable media", color = Color.White)
            }
        }
    }
}

@Composable
private fun NativeVideo(url: String, autoplay: Boolean, muted: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                val controls = MediaController(context)
                controls.setAnchorView(this)
                setMediaController(controls)
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { player ->
                    player.isLooping = true
                    if (muted) player.setVolume(0f, 0f)
                    if (autoplay) start()
                }
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                view.setVideoURI(Uri.parse(url))
            }
        }
    )
}

private val bitmapCache = object : LruCache<String, Bitmap>(32) {}

@Composable
private fun RemoteBitmap(
    imageCandidates: List<String>,
    videoCandidates: List<String>,
    modifier: Modifier,
    contentScale: ContentScale
) {
    val key = remember(imageCandidates, videoCandidates) {
        (imageCandidates + videoCandidates).joinToString("|")
    }
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
        else -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

private fun loadFirstBitmap(urls: List<String>): Bitmap? {
    for (url in urls.distinct()) {
        bitmapCache.get(url)?.let { return it }
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
            bitmapCache.put(url, loaded)
            return loaded
        }
    }
    return null
}

private fun loadFirstVideoFrame(urls: List<String>): Bitmap? {
    for (url in urls.distinct()) {
        val cacheKey = "video:$url"
        bitmapCache.get(cacheKey)?.let { return it }
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
            bitmapCache.put(cacheKey, frame)
            return frame
        }
    }
    return null
}

@Composable
private fun MediaPlaceholder(modifier: Modifier, label: String) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        color = if (value == selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(label(value), modifier = Modifier.padding(14.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.RANDOM -> "Random"
    SortMode.HOT -> "Hot"
    SortMode.NEW -> "New"
    SortMode.TOP -> "Top"
}

private fun nsfwLabel(mode: NsfwMode): String = when (mode) {
    NsfwMode.ALL -> "All"
    NsfwMode.SFW -> "SFW only"
    NsfwMode.NSFW -> "NSFW only"
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}
