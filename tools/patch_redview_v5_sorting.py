from pathlib import Path

activity_path = Path('android/app/src/main/java/com/scrolller/adblock/RedViewV2Activity.kt')
api_path = Path('android/app/src/main/java/com/scrolller/adblock/network/ScrolllerApi.kt')
legacy_activity_paths = [
    Path('android/app/src/main/java/com/scrolller/adblock/MainActivity.kt'),
    Path('android/app/src/main/java/com/scrolller/adblock/RedViewActivity.kt'),
]
text = activity_path.read_text()
api = api_path.read_text()


def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, found {count}')
    text = text.replace(old, new, 1)


def replace_api_once(old: str, new: str, label: str):
    global api
    count = api.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, found {count}')
    api = api.replace(old, new, 1)


def patch_legacy_sort_label(path: Path):
    legacy = path.read_text()
    old = '''private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.RANDOM -> "Random"
    SortMode.HOT -> "Hot"
    SortMode.NEW -> "New"
    SortMode.TOP -> "Top"
}'''
    new = '''private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.RANDOM -> "Random"
    SortMode.HOT -> "Hot"
    SortMode.NEW -> "Newest → Oldest"
    SortMode.OLDEST -> "Oldest → Newest"
    SortMode.TOP -> "Top"
}'''
    count = legacy.count(old)
    if count != 1:
        raise SystemExit(f'{path.name} sortLabel: expected exactly 1 match, found {count}')
    path.write_text(legacy.replace(old, new, 1))


replace_api_once(
    '.put("sortBy", sort.name)',
    '.put("sortBy", if (sort == SortMode.OLDEST) SortMode.NEW.name else sort.name)',
    'map oldest to supported server sort'
)

replace_once(
    'private enum class FavoriteSort { SAVED, NEWEST, OLDEST, RANDOM }',
    'private enum class FavoriteSort { SAVED, SAVED_OLDEST, NEWEST, OLDEST, RANDOM }',
    'favorites sort options'
)

replace_once(
'''    var sortMode by mutableStateOf(SortMode.RANDOM)
    var favoriteSort by mutableStateOf(FavoriteSort.SAVED)''',
'''    var sortMode by mutableStateOf(SortMode.RANDOM)
    var favoriteSort by mutableStateOf(FavoriteSort.RANDOM)
    var favoriteRandomSeed by mutableStateOf(System.nanoTime())''',
    'random defaults'
)

replace_once(
'''    fun sortedFavorites(): List<Post> {
        val base = filtered(favorites)
        return when (favoriteSort) {
            FavoriteSort.SAVED -> base
            FavoriteSort.NEWEST -> base.sortedByDescending { createdMillis(it.createdAt) }
            FavoriteSort.OLDEST -> base.sortedBy { createdMillis(it.createdAt) }
            FavoriteSort.RANDOM -> base.sortedBy { stableRandomKey(it.key) }
        }
    }

    fun isFavorite(post: Post): Boolean''',
'''    fun sortedFavorites(): List<Post> {
        val base = filtered(favorites)
        return when (favoriteSort) {
            FavoriteSort.SAVED -> base
            FavoriteSort.SAVED_OLDEST -> base.reversed()
            FavoriteSort.NEWEST -> base.sortedByDescending { createdMillis(it.createdAt) }
            FavoriteSort.OLDEST -> base.sortedBy { createdMillis(it.createdAt) }
            FavoriteSort.RANDOM -> base.sortedBy { stableRandomKey("$favoriteRandomSeed:${it.key}") }
        }
    }

    fun updateFavoriteSort(value: FavoriteSort) {
        favoriteSort = value
        if (value == FavoriteSort.RANDOM) favoriteRandomSeed = System.nanoTime()
    }

    fun ordered(posts: List<Post>): List<Post> = when (sortMode) {
        SortMode.NEW -> posts.sortedByDescending { createdMillis(it.createdAt) }
        SortMode.OLDEST -> posts.sortedBy { createdMillis(it.createdAt) }
        SortMode.RANDOM, SortMode.HOT, SortMode.TOP -> posts
    }

    fun isFavorite(post: Post): Boolean''',
    'favorites and global ordering'
)

replace_once(
'''    suspend fun loadGalleryCached(url: String, sort: SortMode): GalleryInfo {
        val key = "${url.trim().lowercase()}|${sort.name}"
        galleryCache[key]?.let { return it }
        val loaded = ScrolllerApi.loadGallery(url, sort)
        galleryCache[key] = loaded
        return loaded
    }''',
'''    suspend fun loadGalleryCached(url: String, sort: SortMode): GalleryInfo {
        val key = "${url.trim().lowercase()}|${sort.name}"
        if (sort != SortMode.RANDOM) galleryCache[key]?.let { return it }
        val loaded = ScrolllerApi.loadGallery(url, sort)
        if (sort != SortMode.RANDOM) galleryCache[key] = loaded
        return loaded
    }''',
    'fresh random gallery corpus'
)

replace_once(
    'gallery != null -> V2MediaFeed(controller, controller.filtered(gallery!!.posts))',
    'gallery != null -> V2MediaFeed(controller, controller.ordered(controller.filtered(gallery!!.posts)))',
    'gallery display ordering'
)

replace_once(
    'val visible = controller.filtered(postResults)',
    'val visible = controller.ordered(controller.filtered(postResults))',
    'content search ordering'
)

replace_once(
    'controller.favoriteSort = it\n            showSort = false',
    'controller.updateFavoriteSort(it)\n            showSort = false',
    'favorites randomizer action'
)

replace_once(
    'val visible = controller.filtered(results)',
    'val visible = controller.ordered(controller.filtered(results))',
    'similar content ordering'
)

replace_once(
'''private fun v2SortLabel(mode: SortMode): String = when (mode) {
    SortMode.RANDOM -> "Random"
    SortMode.HOT -> "Hot"
    SortMode.NEW -> "New"
    SortMode.TOP -> "Top"
}''',
'''private fun v2SortLabel(mode: SortMode): String = when (mode) {
    SortMode.RANDOM -> "Random"
    SortMode.HOT -> "Hot"
    SortMode.NEW -> "Newest → Oldest"
    SortMode.OLDEST -> "Oldest → Newest"
    SortMode.TOP -> "Top"
}''',
    'global sort labels'
)

replace_once(
'''private fun favoriteSortLabel(mode: FavoriteSort): String = when (mode) {
    FavoriteSort.SAVED -> "Recently saved"
    FavoriteSort.NEWEST -> "Newest post"
    FavoriteSort.OLDEST -> "Oldest post"
    FavoriteSort.RANDOM -> "Random"
}''',
'''private fun favoriteSortLabel(mode: FavoriteSort): String = when (mode) {
    FavoriteSort.SAVED -> "Newest saved → Oldest saved"
    FavoriteSort.SAVED_OLDEST -> "Oldest saved → Newest saved"
    FavoriteSort.NEWEST -> "Newest post → Oldest post"
    FavoriteSort.OLDEST -> "Oldest post → Newest post"
    FavoriteSort.RANDOM -> "Randomize"
}''',
    'favorites sort labels'
)

activity_path.write_text(text)
api_path.write_text(api)
for legacy_path in legacy_activity_paths:
    patch_legacy_sort_label(legacy_path)
print('RedView V5 global sorting/random-default patch applied successfully')
