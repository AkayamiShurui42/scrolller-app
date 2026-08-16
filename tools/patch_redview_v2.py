from pathlib import Path

path = Path('android/app/src/main/java/com/scrolller/adblock/RedViewV2Activity.kt')
text = path.read_text()


def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, found {count}')
    text = text.replace(old, new, 1)

# Controller: jumping to a post's source gallery should close fullscreen first.
replace_once(
'''    fun openGallery(url: String, title: String) {
        routes.add(V2Route.Gallery(url, title))
    }

    fun openMedia(post: Post, posts: List<Post>) {''',
'''    fun openGallery(url: String, title: String) {
        routes.add(V2Route.Gallery(url, title))
    }

    fun openPostGallery(post: Post) {
        val url = post.subredditUrl.trim()
        if (url.isBlank()) return
        if (routes.lastOrNull() is V2Route.Media) routes.removeAt(routes.lastIndex)
        val title = post.subredditTitle.ifBlank { post.subredditUrl }.ifBlank { "Gallery" }
        routes.add(V2Route.Gallery(url, title))
    }

    fun openMedia(post: Post, posts: List<Post>) {''',
'openPostGallery controller method')

# Feed autoplay: keep nearby video rows instantiated/prepared and play a small viewport window.
replace_once(
'''    val listState = rememberLazyListState()
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
private fun V2NaturalMediaPreview(controller: V2Controller, post: Post, posts: List<Post>, active: Boolean) {''',
'''    val listState = rememberLazyListState()
    val autoplayIndices by remember(listState, posts) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (visible.isEmpty()) {
                emptySet()
            } else {
                val first = visible.minOrNull() ?: 0
                val last = visible.maxOrNull() ?: first
                val center = (first + last) / 2.0
                val start = maxOf(0, first - 6)
                val end = minOf(posts.lastIndex, last + 6)
                (start..end)
                    .filter { posts[it].isVideo }
                    .sortedBy { kotlin.math.abs(it - center) }
                    .take(4)
                    .toSet()
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        itemsIndexed(posts, key = { _, post -> post.key }) { index, post ->
            V2NaturalMediaPreview(
                controller = controller,
                post = post,
                posts = posts,
                autoplayActive = index in autoplayIndices
            )
        }
    }
}

@Composable
private fun V2NaturalMediaPreview(controller: V2Controller, post: Post, posts: List<Post>, autoplayActive: Boolean) {''',
'feed autoplay window')

replace_once(
'''        if (video != null && active && controller.autoplay) {
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

        V2FavoriteButton(''',
'''        if (video != null && controller.autoplay) {
            // Instantiate VideoView for every composed video row so Android can prepare
            // upcoming media before it becomes the dominant item. Only the nearby
            // autoplay window actually plays, which keeps resource use bounded.
            V2NativeVideo(video, true, controller.muted, autoplayActive) {
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

        V2GalleryButton(
            post = post,
            controller = controller,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
        )
        V2FavoriteButton(''',
'prepared inline video and gallery button')

# Gallery hyperlink/pill. Keep the media-first UI; only the source-gallery pill is visible.
replace_once(
'''@Composable
private fun V2FavoriteButton(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    V2ActionButton(if (favorite) "♥" else "♡", onClick, modifier, favorite)
}

@Composable
private fun V2ActionButton''',
'''@Composable
private fun V2GalleryButton(post: Post, controller: V2Controller, modifier: Modifier = Modifier) {
    if (post.subredditUrl.isBlank()) return
    val label = post.subredditTitle.ifBlank { post.subredditUrl }.removePrefix("r/")
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.62f)
    ) {
        TextButton(
            onClick = { controller.openPostGallery(post) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Text(
                text = label,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun V2FavoriteButton(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    V2ActionButton(if (favorite) "♥" else "♡", onClick, modifier, favorite)
}

@Composable
private fun V2ActionButton''',
'gallery link composable')

# Both search tabs use fuzzy gallery retrieval.
replace_once(
'''                galleryResults = ScrolllerApi.searchGalleries(clean, controller.nsfwMode != NsfwMode.SFW)''',
'''                galleryResults = ScrolllerApi.searchGalleriesFuzzy(clean, controller.nsfwMode != NsfwMode.SFW)''',
'gallery tab fuzzy search')

replace_once(
'''    val groups = ScrolllerApi.searchGalleries(query, includeNsfw)
        .filter { it.itemCount != 0 && it.url.isNotBlank() }
        .take(8)''',
'''    val groups = ScrolllerApi.searchGalleriesFuzzy(query, includeNsfw)
        .filter { it.itemCount != 0 && it.url.isNotBlank() }
        .take(12)''',
'post fuzzy gallery retrieval')

# Post ranking: gallery membership is a first-class search term, with fuzzy token matching.
replace_once(
'''private fun v2PostSearchScore(post: Post, query: String): Int {
    val q = query.lowercase().trim()
    if (q.isBlank()) return 1
    val title = post.title.lowercase()
    val gallery = (post.subredditTitle + " " + post.subredditUrl).lowercase()
    val words = q.split(Regex("\\\\s+")).filter { it.isNotBlank() }
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
}''',
'''private fun v2PostSearchScore(post: Post, query: String): Int {
    fun norm(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\\\s+"), " ")

    fun tokens(value: String): Set<String> = norm(value)
        .split(' ')
        .filter { it.length >= 2 }
        .toSet()

    fun wordSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a.startsWith(b) || b.startsWith(a)) return 0.92
        if (a.contains(b) || b.contains(a)) return 0.84
        val ap = if (a.length > 1) a.windowed(2).toSet() else emptySet()
        val bp = if (b.length > 1) b.windowed(2).toSet() else emptySet()
        if (ap.isEmpty() || bp.isEmpty()) return 0.0
        return (2.0 * ap.intersect(bp).size) / (ap.size + bp.size).toDouble()
    }

    val q = norm(query)
    if (q.isBlank()) return 1
    val title = norm(post.title)
    val galleryTitle = norm(post.subredditTitle)
    val galleryUrl = norm(post.subredditUrl.removePrefix("r/").replace('-', ' ').replace('_', ' '))
    val gallery = "$galleryTitle $galleryUrl".trim()
    val qTokens = tokens(q)
    val titleTokens = tokens(title)
    val galleryTokens = tokens(gallery)

    var score = 0
    if (title == q) score += 1000
    if (galleryTitle == q || galleryUrl == q) score += 950
    if (title.startsWith(q)) score += 700
    if (galleryTitle.startsWith(q) || galleryUrl.startsWith(q)) score += 650
    if (title.contains(q)) score += 520
    if (gallery.contains(q)) score += 500

    qTokens.forEach { token ->
        if (token in titleTokens) score += 150
        else score += ((titleTokens.maxOfOrNull { wordSimilarity(token, it) } ?: 0.0) * 90.0).toInt()

        // A post inherits the searchable vocabulary of its source gallery.
        if (token in galleryTokens) score += 170
        else score += ((galleryTokens.maxOfOrNull { wordSimilarity(token, it) } ?: 0.0) * 120.0).toInt()
    }
    return score
}''',
'post gallery metadata fuzzy ranking')

# Fullscreen gets the same source-gallery jump.
replace_once(
'''            V2ActionButton(
                "≈",
                { controller.openSimilar(post) },
                Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = 10.dp)
            )
        }
    }
}''',
'''            V2ActionButton(
                "≈",
                { controller.openSimilar(post) },
                Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = 10.dp)
            )
            V2GalleryButton(
                post = post,
                controller = controller,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 28.dp)
            )
        }
    }
}''',
'fullscreen gallery link')

path.write_text(text)
print('RedView V2 behavior patch applied successfully')
