package com.scrolller.app.data.repository

import com.scrolller.app.data.api.ScrolllerApiClient
import com.scrolller.app.data.local.CachedPostEntity
import com.scrolller.app.data.local.CachedSubredditEntity
import com.scrolller.app.data.local.ScrolllerDao
import com.scrolller.app.data.model.ContentFilter
import com.scrolller.app.data.model.FeedFilter
import com.scrolller.app.data.model.GallerySort
import com.scrolller.app.data.model.MediaFilter
import com.scrolller.app.data.model.MediaType
import com.scrolller.app.data.model.ScrolllerPost
import com.scrolller.app.data.model.SearchContextMode
import com.scrolller.app.data.model.SourceOriginFilter
import com.scrolller.app.data.model.SubredditItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class FeedLoadResult(
    val posts: List<ScrolllerPost>,
    val nextIterator: String?,
    val isFromOfflineCache: Boolean = false
)

class ScrolllerRepository(
    private val apiClient: ScrolllerApiClient,
    private val dao: ScrolllerDao
) {
    companion object {
        val DEFAULT_SUBREDDITS = listOf("funny", "pics", "videos", "aww", "art", "gifs", "earthporn", "memes")
    }

    suspend fun getFeed(
        source: String,
        filter: FeedFilter,
        iterator: String? = null,
        forceOffline: Boolean = false
    ): FeedLoadResult {
        val effectiveSource = when {
            filter.selectedSubreddits.size == 1 && (source.equals("Feed", ignoreCase = true) || source.isBlank()) -> {
                filter.selectedSubreddits.first()
            }
            filter.category != "ALL" && (source.equals("Feed", ignoreCase = true) || source.isBlank()) -> {
                "Category:${filter.category}"
            }
            else -> source
        }
        val normalizedSourceKey = normalizeSourceKey(effectiveSource, filter.selectedSubreddits)

        // Built-in local sources that always read from Room
        if (effectiveSource.equals("Recent", ignoreCase = true)) {
            val recent = dao.getRecentPosts(200).map { it.toDomain() }
            return FeedLoadResult(
                posts = filterAndDeduplicate(recent, filter),
                nextIterator = null,
                isFromOfflineCache = true
            )
        }
        if (effectiveSource.equals("Saved", ignoreCase = true)) {
            val saved = dao.getSavedPosts().map { it.toDomain() }
            return FeedLoadResult(
                posts = filterAndDeduplicate(saved, filter),
                nextIterator = null,
                isFromOfflineCache = true
            )
        }
        if (effectiveSource.equals("OfflineAll", ignoreCase = true)) {
            val allCached = loadAllOfflineContent()
            return FeedLoadResult(
                posts = filterAndDeduplicate(allCached, filter),
                nextIterator = null,
                isFromOfflineCache = true
            )
        }

        if (forceOffline) {
            val cached = loadFromLocalCache(effectiveSource, normalizedSourceKey, filter)
            return FeedLoadResult(
                posts = cached,
                nextIterator = null,
                isFromOfflineCache = true
            )
        }

        return try {
            val rawPosts: List<ScrolllerPost>
            var nextIterator: String? = null

            when {
                // Multi-subreddit held filter
                filter.selectedSubreddits.isNotEmpty() && (source.equals("Feed", ignoreCase = true) || source.isBlank()) -> {
                    val targetSubs = filter.selectedSubreddits.map { apiClient.cleanSubredditUrl(it) }.filter { it.isNotBlank() }
                    if (targetSubs.size == 1) {
                        val (items, iter) = apiClient.fetchSubreddit(
                            url = targetSubs.first(),
                            sortBy = filter.sort,
                            limit = 80,
                            iterator = iterator
                        )
                        rawPosts = items
                        nextIterator = iter
                    } else {
                        rawPosts = aggregateSubreddits(targetSubs.take(8), filter.sort)
                    }
                }

                effectiveSource.startsWith("Category:", ignoreCase = true) -> {
                    val categoryName = effectiveSource.removePrefix("Category:").trim()
                    val subreddits = apiClient.fetchCategorySubreddits(categoryName)
                    rawPosts = if (subreddits.isNotEmpty()) {
                        aggregateSubreddits(subreddits.take(8), filter.sort)
                    } else {
                        val (items, _) = apiClient.fetchSubreddit(categoryName, filter.sort)
                        items
                    }
                }

                effectiveSource.equals("Feed", ignoreCase = true) || effectiveSource.isBlank() -> {
                    rawPosts = aggregateSubreddits(DEFAULT_SUBREDDITS.take(6), filter.sort)
                }

                effectiveSource.startsWith("Collection:", ignoreCase = true) -> {
                    val parts = effectiveSource.split(":", limit = 3)
                    val colId = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    val nsfwStr = when (filter.contentFilter) {
                        ContentFilter.SFW -> "SFW"
                        ContentFilter.NSFW -> "NSFW"
                        ContentFilter.ALL -> "ALL"
                    }
                    val (items, iter) = apiClient.fetchUserCollectionContent(
                        collectionId = colId,
                        sortBy = filter.sort,
                        nsfwFilter = nsfwStr,
                        limit = 80,
                        iterator = iterator
                    )
                    rawPosts = items
                    nextIterator = iter
                }

                else -> {
                    val (items, iter) = apiClient.fetchSubreddit(
                        url = effectiveSource,
                        sortBy = filter.sort,
                        limit = 80,
                        iterator = iterator
                    )
                    rawPosts = items
                    nextIterator = iter
                }
            }

            if (rawPosts.isNotEmpty()) {
                cachePostsIntoRoom(
                    source = effectiveSource,
                    normalizedSourceKey = normalizedSourceKey,
                    posts = rawPosts,
                    isAppend = iterator != null
                )
            }

            val filtered = filterAndDeduplicate(rawPosts, filter)
            if (filtered.isEmpty() && rawPosts.isEmpty()) {
                val fallback = loadFromLocalCache(effectiveSource, normalizedSourceKey, filter)
                if (fallback.isNotEmpty()) {
                    return FeedLoadResult(
                        posts = fallback,
                        nextIterator = null,
                        isFromOfflineCache = true
                    )
                }
            }

            FeedLoadResult(
                posts = filtered,
                nextIterator = nextIterator,
                isFromOfflineCache = false
            )
        } catch (e: Exception) {
            val cached = loadFromLocalCache(effectiveSource, normalizedSourceKey, filter)
            if (cached.isNotEmpty()) {
                FeedLoadResult(
                    posts = cached,
                    nextIterator = null,
                    isFromOfflineCache = true
                )
            } else {
                throw e
            }
        }
    }

    suspend fun downloadFullSubredditForOffline(
        subredditUrl: String,
        sort: GallerySort = GallerySort.HOT
    ): Int {
        val cleanSub = apiClient.cleanSubredditUrl(subredditUrl).lowercase()
        if (cleanSub.isBlank()) return 0

        val allCollected = mutableListOf<ScrolllerPost>()
        var currentIterator: String? = null

        for (page in 0 until 2) {
            val (items, nextIter) = apiClient.fetchSubreddit(
                url = cleanSub,
                sortBy = sort,
                limit = 100,
                iterator = currentIterator
            )
            allCollected.addAll(items)
            if (nextIter.isNullOrBlank() || items.isEmpty()) break
            currentIterator = nextIter
        }

        val deduplicated = allCollected.distinctBy { it.postId }
        if (deduplicated.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val entities = deduplicated.mapIndexed { idx, post ->
                CachedPostEntity.fromDomain(
                    post = post,
                    sourceKey = "sub:$cleanSub",
                    feedOrder = idx,
                    cachedAt = now
                )
            }
            dao.replaceCachedPostsForSource("sub:$cleanSub", entities)

            val existingSub = dao.getCachedSubreddit(cleanSub)
            val preview = deduplicated.firstOrNull { it.posterUrl.isNotBlank() }?.posterUrl
                ?: deduplicated.firstOrNull()?.mediaUrl.orEmpty()
            val title = deduplicated.firstOrNull()?.subredditTitle?.ifBlank { cleanSub } ?: cleanSub

            dao.upsertCachedSubreddit(
                CachedSubredditEntity(
                    subredditUrl = cleanSub,
                    title = title,
                    isPinned = existingSub?.isPinned ?: true,
                    cachedPostCount = deduplicated.size,
                    previewImageUrl = preview,
                    isFullyCached = true,
                    lastSyncedAt = now
                )
            )
        }
        return deduplicated.size
    }

    suspend fun searchPosts(
        query: String,
        filter: FeedFilter,
        searchContext: SearchContextMode = SearchContextMode.ALL_SCROLLLER,
        currentCorpus: List<ScrolllerPost> = emptyList(),
        forceOffline: Boolean = false
    ): FeedLoadResult {
        val q = query.trim()
        if (q.isBlank()) return FeedLoadResult(emptyList(), null, false)

        // If searching within the current collection/subreddit corpus
        if (searchContext == SearchContextMode.CURRENT_FEED && currentCorpus.isNotEmpty()) {
            val lowerQ = q.lowercase()
            val words = lowerQ.split("\\s+".toRegex()).filter { it.isNotBlank() }
            val matched = currentCorpus.mapNotNull { post ->
                val titleLower = post.title.lowercase()
                val subLower = post.subredditTitle.lowercase()
                var score = 0
                if (titleLower.contains(lowerQ)) score += 100
                if (subLower.contains(lowerQ)) score += 40
                for (w in words) {
                    if (titleLower.contains(w)) score += 15
                    if (subLower.contains(w)) score += 6
                }
                if (score > 0) Pair(post, score) else null
            }
                .sortedByDescending { it.second }
                .map { it.first }
            return FeedLoadResult(
                posts = filterAndDeduplicate(matched, filter),
                nextIterator = null,
                isFromOfflineCache = false
            )
        }

        if (forceOffline) {
            val localMatches = searchLocalCache(q, filter)
            return FeedLoadResult(localMatches, null, isFromOfflineCache = true)
        }

        return try {
            val subreddits = apiClient.searchSubreddits(
                query = q,
                isNsfw = filter.contentFilter != ContentFilter.SFW,
                limit = 8
            )
            val urls = if (subreddits.isNotEmpty()) {
                subreddits.map { it.cleanUrl }
            } else {
                listOf(q)
            }

            val raw = aggregateSubreddits(urls.take(5), filter.sort)
            if (raw.isNotEmpty()) {
                cachePostsIntoRoom(
                    source = "Search:$q",
                    normalizedSourceKey = "search:${q.lowercase()}",
                    posts = raw,
                    isAppend = false
                )
            }

            val filtered = filterAndDeduplicate(raw, filter)
            val lowerQ = q.lowercase()
            val ranked = filtered.sortedByDescending { post ->
                var score = 0
                if (post.title.contains(lowerQ, ignoreCase = true)) score += 50
                if (post.subredditTitle.contains(lowerQ, ignoreCase = true)) score += 30
                score
            }
            FeedLoadResult(ranked, null, isFromOfflineCache = false)
        } catch (e: Exception) {
            val localMatches = searchLocalCache(q, filter)
            if (localMatches.isNotEmpty()) {
                FeedLoadResult(localMatches, null, isFromOfflineCache = true)
            } else {
                throw e
            }
        }
    }

    suspend fun searchSubreddits(query: String, isNsfw: Boolean): List<SubredditItem> {
        val q = query.trim()
        return try {
            if (q.isBlank()) {
                // Return default/popular subreddits + API search for popular galleries so the Search subreddit list is always populated!
                val remotePopular = runCatching {
                    apiClient.searchSubreddits("a", isNsfw = isNsfw, limit = 25)
                }.getOrDefault(emptyList())
                val defaultItems = DEFAULT_SUBREDDITS.mapIndexed { idx, name ->
                    SubredditItem(
                        id = (idx + 1).toLong(),
                        title = "r/$name",
                        url = name,
                        description = "Popular Scrolller Gallery",
                        isNsfw = false,
                        itemCount = 500
                    )
                }
                (defaultItems + remotePopular).distinctBy { it.cleanUrl.lowercase() }
            } else {
                val remote = apiClient.searchSubreddits(q, isNsfw = isNsfw, limit = 40)
                if (remote.isNotEmpty()) {
                    remote
                } else {
                    // Include direct subreddit entry so user can always jump to r/<query>
                    listOf(
                        SubredditItem(
                            id = q.hashCode().toLong(),
                            title = "r/${apiClient.cleanSubredditUrl(q)}",
                            url = apiClient.cleanSubredditUrl(q),
                            description = "Open subreddit r/${apiClient.cleanSubredditUrl(q)} directly",
                            isNsfw = isNsfw,
                            itemCount = 0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val lowerQ = q.lowercase()
            val cached = dao.getAllCachedPosts(300)
                .groupBy { it.subredditUrl }
                .map { (subUrl, posts) ->
                    SubredditItem(
                        id = subUrl.hashCode().toLong(),
                        title = posts.firstOrNull()?.subredditTitle?.ifBlank { subUrl } ?: subUrl,
                        url = subUrl,
                        description = "Cached offline (${posts.size} posts)",
                        isNsfw = posts.any { it.isNsfw },
                        itemCount = posts.size
                    )
                }
                .filter { lowerQ.isBlank() || it.url.contains(lowerQ) || it.title.lowercase().contains(lowerQ) }
            if (cached.isNotEmpty()) cached else DEFAULT_SUBREDDITS.mapIndexed { idx, name ->
                SubredditItem(
                    id = (idx + 1).toLong(),
                    title = "r/$name",
                    url = name,
                    description = "Default Scrolller Gallery",
                    isNsfw = false
                )
            }
        }
    }

    suspend fun getCategories(contentFilter: ContentFilter = ContentFilter.ALL): List<String> {
        return apiClient.fetchCategories(contentFilter)
    }

    private suspend fun cachePostsIntoRoom(
        source: String,
        normalizedSourceKey: String,
        posts: List<ScrolllerPost>,
        isAppend: Boolean
    ) {
        val now = System.currentTimeMillis()
        val startOrder = if (isAppend) {
            dao.getCachedPostsBySource(normalizedSourceKey).size
        } else {
            0
        }

        val sourceEntities = posts.mapIndexed { index, post ->
            CachedPostEntity.fromDomain(
                post = post,
                sourceKey = normalizedSourceKey,
                feedOrder = startOrder + index,
                cachedAt = now
            )
        }

        if (isAppend) {
            dao.insertCachedPosts(sourceEntities)
        } else {
            dao.replaceCachedPostsForSource(normalizedSourceKey, sourceEntities)
        }

        val groupedBySub = posts.groupBy { it.subredditUrl.lowercase().trim() }
        for ((subUrl, subPosts) in groupedBySub) {
            if (subUrl.isBlank()) continue
            val existing = dao.getCachedSubreddit(subUrl)
            val totalSubPostsInDb = dao.getCachedPostsBySubreddit(subUrl).size
            val preview = subPosts.firstOrNull { it.posterUrl.isNotBlank() }?.posterUrl
                ?: subPosts.firstOrNull()?.mediaUrl
                ?: existing?.previewImageUrl.orEmpty()
            val title = subPosts.firstOrNull()?.subredditTitle?.ifBlank { subUrl }
                ?: existing?.title
                ?: subUrl

            dao.upsertCachedSubreddit(
                CachedSubredditEntity(
                    subredditUrl = subUrl,
                    title = title,
                    isPinned = existing?.isPinned ?: false,
                    cachedPostCount = maxOf(totalSubPostsInDb, subPosts.size),
                    previewImageUrl = preview,
                    isFullyCached = existing?.isFullyCached ?: (!source.equals("Feed", ignoreCase = true) && subPosts.size >= 30),
                    lastSyncedAt = now
                )
            )
        }
    }

    private suspend fun loadFromLocalCache(
        source: String,
        normalizedSourceKey: String,
        filter: FeedFilter
    ): List<ScrolllerPost> {
        val bySource = dao.getCachedPostsBySource(normalizedSourceKey).map { it.toDomain() }
        if (bySource.isNotEmpty()) {
            return filterAndDeduplicate(bySource, filter)
        }

        if (filter.selectedSubreddits.isNotEmpty()) {
            val combinedSubs = filter.selectedSubreddits.flatMap { sub ->
                val clean = apiClient.cleanSubredditUrl(sub).lowercase()
                dao.getCachedPostsBySubreddit(clean).map { it.toDomain() }
            }
            if (combinedSubs.isNotEmpty()) {
                return filterAndDeduplicate(combinedSubs, filter)
            }
        }

        val cleanSub = apiClient.cleanSubredditUrl(source).lowercase()
        if (cleanSub.isNotBlank() && !source.equals("Feed", ignoreCase = true)) {
            val bySub = dao.getCachedPostsBySubreddit(cleanSub).map { it.toDomain() }
            if (bySub.isNotEmpty()) {
                return filterAndDeduplicate(bySub, filter)
            }
        }

        val combined = loadAllOfflineContent()
        if (combined.isNotEmpty()) {
            return filterAndDeduplicate(combined, filter)
        }

        return emptyList()
    }

    private suspend fun loadAllOfflineContent(): List<ScrolllerPost> {
        val recent = dao.getRecentPosts(100).map { it.toDomain() }
        val cached = dao.getAllCachedPosts(200).map { it.toDomain() }
        val saved = dao.getSavedPosts().map { it.toDomain() }
        return (recent + cached + saved).distinctBy { it.postId }
    }

    private suspend fun searchLocalCache(query: String, filter: FeedFilter): List<ScrolllerPost> {
        val lowerQ = query.lowercase().trim()
        val allOffline = loadAllOfflineContent()
        val matching = allOffline.filter { post ->
            post.title.lowercase().contains(lowerQ) ||
                    post.subredditTitle.lowercase().contains(lowerQ) ||
                    post.subredditUrl.lowercase().contains(lowerQ)
        }
        return filterAndDeduplicate(matching, filter)
    }

    private fun normalizeSourceKey(source: String, selectedSubreddits: Set<String> = emptySet()): String {
        val trimmed = source.trim()
        if (selectedSubreddits.isNotEmpty() && (trimmed.isBlank() || trimmed.equals("Feed", ignoreCase = true))) {
            return "subs:${selectedSubreddits.map { it.lowercase() }.sorted().joinToString(",")}"
        }
        return when {
            trimmed.isBlank() || trimmed.equals("Feed", ignoreCase = true) -> "feed:main"
            trimmed.startsWith("Category:", ignoreCase = true) -> "cat:${trimmed.removePrefix("Category:").trim().lowercase()}"
            trimmed.startsWith("Collection:", ignoreCase = true) -> {
                val parts = trimmed.split(":")
                "collection:${parts.getOrNull(1) ?: "0"}"
            }
            trimmed.startsWith("Search:", ignoreCase = true) -> "search:${trimmed.removePrefix("Search:").trim().lowercase()}"
            else -> "sub:${apiClient.cleanSubredditUrl(trimmed).lowercase()}"
        }
    }

    private suspend fun aggregateSubreddits(
        urls: List<String>,
        sort: GallerySort
    ): List<ScrolllerPost> = coroutineScope {
        val deferred = urls.map { url ->
            async {
                try {
                    apiClient.fetchSubreddit(url, sortBy = sort, limit = 40).first
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        deferred.awaitAll().flatten()
    }

    private fun filterAndDeduplicate(
        posts: List<ScrolllerPost>,
        filter: FeedFilter
    ): List<ScrolllerPost> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<ScrolllerPost>()
        val activeSubFilter = filter.selectedSubreddits.map { apiClient.cleanSubredditUrl(it).lowercase() }.toSet()

        for (post in posts) {
            val key = post.postId.toString().takeIf { post.postId > 0 } ?: post.mediaUrl
            if (!seen.add(key)) continue

            // Subreddit Filter (if held/selected and browsing a mixed feed)
            if (activeSubFilter.isNotEmpty()) {
                val postSub = post.subredditUrl.lowercase().trim()
                val postSubTitle = post.subredditTitle.removePrefix("r/").lowercase().trim()
                if (!activeSubFilter.contains(postSub) && !activeSubFilter.contains(postSubTitle)) {
                    continue
                }
            }

            // Source Origin Filter (All / Scrolller / Reddit / RedGIFs)
            val matchesOrigin = when (filter.sourceOrigin) {
                SourceOriginFilter.ALL -> true
                SourceOriginFilter.SCROLLLER -> post.mediaUrl.contains("scrolller", ignoreCase = true) || post.postUrl.isNotBlank()
                SourceOriginFilter.REDDIT -> post.redditPath.isNotBlank() || post.mediaUrl.contains("redd.it", ignoreCase = true) || post.mediaUrl.contains("reddit", ignoreCase = true)
                SourceOriginFilter.REDGIFS -> post.mediaUrl.contains("redgifs", ignoreCase = true) || post.mediaType == MediaType.VIDEO || post.mediaType == MediaType.GIF
            }
            if (!matchesOrigin) continue

            // Media Filter
            val matchesMedia = when (filter.mediaFilter) {
                MediaFilter.ALL -> true
                MediaFilter.IMAGE -> post.mediaType == MediaType.IMAGE
                MediaFilter.VIDEO -> post.mediaType == MediaType.VIDEO
                MediaFilter.GIF -> post.mediaType == MediaType.GIF
                MediaFilter.ALBUM -> post.mediaType == MediaType.ALBUM
            }
            if (!matchesMedia) continue

            // Content Filter
            val matchesContent = when (filter.contentFilter) {
                ContentFilter.ALL -> true
                ContentFilter.SFW -> !post.isNsfw
                ContentFilter.NSFW -> post.isNsfw
            }
            if (!matchesContent) continue

            result.add(post)
        }

        return when (filter.sort) {
            GallerySort.NEW -> result.sortedByDescending { it.createdAt }
            GallerySort.RANDOM -> result.shuffled()
            GallerySort.TOP, GallerySort.HOT -> result
        }
    }
}
