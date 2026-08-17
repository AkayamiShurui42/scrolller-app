from pathlib import Path

path = Path('android/app/src/main/java/com/scrolller/adblock/RedViewV2Activity.kt')
text = path.read_text()


def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, found {count}')
    text = text.replace(old, new, 1)


# Preserve tab navigation history as well as nested route history.
replace_once(
'''    val routes = mutableStateListOf<V2Route>()
    val favorites = mutableStateListOf<Post>().apply { addAll(prefs.loadFavorites()) }''',
'''    val routes = mutableStateListOf<V2Route>()
    private val tabHistory = mutableStateListOf<MainTab>()
    val favorites = mutableStateListOf<Post>().apply { addAll(prefs.loadFavorites()) }''',
'add tab history')

# A gallery/subreddit opened from fullscreen is a new history entry. Do not
# destroy fullscreen underneath it; Back should return to the exact post/pager.
replace_once(
'''    fun openPostGallery(post: Post) {
        val url = post.subredditUrl.trim()
        if (url.isBlank()) return
        if (routes.lastOrNull() is V2Route.Media) routes.removeAt(routes.lastIndex)
        val title = post.subredditTitle.ifBlank { post.subredditUrl }.ifBlank { "Gallery" }
        routes.add(V2Route.Gallery(url, title))
    }''',
'''    fun openPostGallery(post: Post) {
        val url = post.subredditUrl.trim()
        if (url.isBlank()) return
        val title = post.subredditTitle.ifBlank { post.subredditUrl }.ifBlank { "Subreddit" }
        routes.add(V2Route.Gallery(url, title))
    }''',
'preserve fullscreen under source subreddit')

replace_once(
'''    fun openSimilar(post: Post) {
        if (routes.lastOrNull() is V2Route.Media) routes.removeAt(routes.lastIndex)
        routes.add(V2Route.Similar(post))
    }

    fun back(): Boolean {
        if (routes.isEmpty()) return false
        routes.removeAt(routes.lastIndex)
        return true
    }''',
'''    fun openSimilar(post: Post) {
        routes.add(V2Route.Similar(post))
    }

    fun selectTab(tab: MainTab) {
        if (routes.isNotEmpty()) routes.clear()
        if (selectedTab == tab) return
        tabHistory.add(selectedTab)
        if (tabHistory.size > 32) tabHistory.removeAt(0)
        selectedTab = tab
    }

    fun back(): Boolean {
        if (routes.isNotEmpty()) {
            routes.removeAt(routes.lastIndex)
            return true
        }
        if (tabHistory.isNotEmpty()) {
            selectedTab = tabHistory.removeAt(tabHistory.lastIndex)
            return true
        }
        return false
    }''',
'preserve similar and add hierarchical back history')

# Back is always consumed by our history manager. Only a truly empty history
# reaches the exit-confirmation dialog.
replace_once(
'''        val topRoute = controller.routes.lastOrNull()
        val mediaRoute = topRoute as? V2Route.Media
        val backgroundRoute = if (mediaRoute != null) controller.routes.dropLast(1).lastOrNull() else topRoute

        BackHandler(enabled = true) {
            when {
                controller.routes.isNotEmpty() -> controller.back()
                controller.selectedTab != MainTab.HOME -> controller.selectedTab = MainTab.HOME
                else -> confirmExit = true
            }
        }''',
'''        val routeStack = controller.routes.toList()

        BackHandler(enabled = true) {
            if (!controller.back()) confirmExit = true
        }''',
'hierarchical system back handler')

# Keep every route below the current route composed. This is intentional: it
# preserves search results, loaded galleries, LazyColumn position and the
# fullscreen pager exactly as they were instead of recreating/reloading them.
replace_once(
'''        Box(Modifier.fillMaxSize()) {
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
        }''',
'''        Box(Modifier.fillMaxSize()) {
            // Root tab stays mounted beneath the navigation stack.
            Scaffold(bottomBar = { V2BottomBar(controller) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (controller.selectedTab) {
                        MainTab.HOME -> V2GalleryScreen(controller, "funny", "Home", true)
                        MainTab.FAVORITES -> V2FavoritesScreen(controller)
                        MainTab.SEARCH -> V2SearchScreen(controller)
                        MainTab.SETTINGS -> V2SettingsScreen(controller)
                    }
                }
            }

            // Compose the entire route stack in order. Covered routes remain
            // alive, so popping Back reveals their exact prior state instantly.
            routeStack.forEach { route ->
                when (route) {
                    is V2Route.Gallery -> Scaffold(bottomBar = { V2BottomBar(controller) }) { padding ->
                        Surface(Modifier.fillMaxSize().padding(padding)) {
                            V2GalleryScreen(controller, route.url, route.title, false)
                        }
                    }
                    is V2Route.Similar -> Scaffold(bottomBar = { V2BottomBar(controller) }) { padding ->
                        Surface(Modifier.fillMaxSize().padding(padding)) {
                            SimilarScreen(controller, route.seed)
                        }
                    }
                    is V2Route.Media -> V2FullscreenPager(controller, route)
                }
            }
        }''',
'keep full navigation stack composed')

replace_once(
'''                selected = controller.routes.isEmpty() && controller.selectedTab == tab,
                onClick = {
                    controller.routes.clear()
                    controller.selectedTab = tab
                },''',
'''                selected = controller.selectedTab == tab,
                onClick = { controller.selectTab(tab) },''',
'bottom navigation history')

# Match Scrolller's terminology. The website exposes one generic Search UI;
# its content search resolves subreddit matches and then loads their posts.
replace_once(
'''            label = { Text(if (mode == V2SearchMode.POSTS) "Search posts and media" else "Search galleries") }''',
'''            label = { Text("Search") }''',
'generic Scrolller search field')

replace_once(
'''            item { FilterChip(mode == V2SearchMode.POSTS, { mode = V2SearchMode.POSTS }, label = { Text("Posts") }) }
            item { FilterChip(mode == V2SearchMode.GALLERIES, { mode = V2SearchMode.GALLERIES }, label = { Text("Galleries") }) }''',
'''            item { FilterChip(mode == V2SearchMode.POSTS, { mode = V2SearchMode.POSTS }, label = { Text("Content") }) }
            item { FilterChip(mode == V2SearchMode.GALLERIES, { mode = V2SearchMode.GALLERIES }, label = { Text("Subreddits") }) }''',
'content and subreddit search labels')

replace_once(
'''            loading -> V2LoadingPanel(if (mode == V2SearchMode.POSTS) "Searching media…" else "Searching galleries…")''',
'''            loading -> V2LoadingPanel(if (mode == V2SearchMode.POSTS) "Searching content…" else "Searching subreddits…")''',
'search loading terminology')

replace_once(
'''                if (visible.isEmpty()) V2EmptyPanel("No matching media") else V2MediaFeed(controller, visible)
            }
            galleryResults.isEmpty() -> V2EmptyPanel("No galleries found")''',
'''                if (visible.isEmpty()) V2EmptyPanel("No matching content") else V2MediaFeed(controller, visible)
            }
            galleryResults.isEmpty() -> V2EmptyPanel("No subreddits found")''',
'search empty terminology')

# Scrolller's own current search resolves subreddits first and then flattens
# content from those subreddits. Do the same. The source subreddit relevance
# determines the group ordering; post/title/source metadata only ranks within
# that group. Critically, do NOT discard a post just because its individual
# title does not repeat the query -- membership in a matched subreddit is
# itself a valid search match.
replace_once(
'''private suspend fun v2SearchPosts(query: String, sort: SortMode, includeNsfw: Boolean): List<Post> = coroutineScope {
    val groups = ScrolllerApi.searchGalleriesFuzzy(query, includeNsfw)
        .filter { it.itemCount != 0 && it.url.isNotBlank() }
        .take(12)
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
}''',
'''private suspend fun v2SearchPosts(query: String, sort: SortMode, includeNsfw: Boolean): List<Post> = coroutineScope {
    val groups = ScrolllerApi.searchGalleriesFuzzy(query, includeNsfw)
        .filter { it.itemCount != 0 && it.url.isNotBlank() }
        .take(8)

    if (groups.isEmpty()) {
        return@coroutineScope runCatching { ScrolllerApi.loadGallery(query, sort, limit = 600) }
            .getOrNull()
            ?.posts
            ?.sortedByDescending { v2PostSearchScore(it, query) }
            .orEmpty()
    }

    val galleries = groups.map { result ->
        async(Dispatchers.IO) {
            runCatching { ScrolllerApi.loadGallery(result.url, sort, limit = 600) }.getOrNull()
        }
    }.awaitAll()

    val seen = HashSet<String>()
    buildList {
        galleries.forEach { gallery ->
            if (gallery == null) return@forEach
            gallery.posts
                .sortedWith(
                    compareByDescending<Post> { v2PostSearchScore(it, query) }
                        .thenByDescending { createdMillis(it.createdAt) }
                )
                .forEach { post ->
                    if (seen.add(post.key)) add(post)
                }
        }
    }
}''',
'Scrolller-style content search')

path.write_text(text)
print('RedView V3 search/navigation patch applied successfully')
