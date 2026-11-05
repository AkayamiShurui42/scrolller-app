package com.scrolller.app.data.repository

import android.content.Context
import com.scrolller.app.data.local.CachedSubredditEntity
import com.scrolller.app.data.local.RecentPostEntity
import com.scrolller.app.data.local.SavedPostEntity
import com.scrolller.app.data.local.ScrolllerDao
import com.scrolller.app.data.model.ScrolllerPost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class BookmarkRepository(
    context: Context,
    private val dao: ScrolllerDao
) {
    private val prefs = context.getSharedPreferences("scrolller_bookmarks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val favoritePosts: Flow<List<ScrolllerPost>> = dao.observeSavedPosts().map { entities ->
        entities.map { it.toDomain() }
    }

    val savedSubreddits: Flow<Set<String>> = dao.observePinnedSubreddits().map { entities ->
        entities.map { it.subredditUrl }.toSet()
    }

    val cachedSubreddits: Flow<List<CachedSubredditEntity>> = dao.observeCachedSubreddits()

    val recentPosts: Flow<List<ScrolllerPost>> = dao.observeRecentPosts().map { entities ->
        entities.map { it.toDomain() }
    }

    val totalCachedPostCount: Flow<Int> = dao.observeTotalCachedPostCount()

    init {
        repoScope.launch {
            migrateAndSeedDefaultsIfNeeded()
        }
    }

    private suspend fun migrateAndSeedDefaultsIfNeeded() {
        val migrated = prefs.getBoolean("room_migrated_v1", false)
        if (!migrated) {
            // Migrate any legacy favorite posts from SharedPreferences into Room
            val legacyPostsJson = prefs.getString("favorite_posts_json", null)
            if (!legacyPostsJson.isNullOrBlank()) {
                runCatching {
                    val legacyPosts = json.decodeFromString<List<ScrolllerPost>>(legacyPostsJson)
                    if (legacyPosts.isNotEmpty()) {
                        dao.insertSavedPosts(legacyPosts.map { SavedPostEntity.fromDomain(it) })
                    }
                }
            }

            // Seed default pinned subreddits
            val legacySubs = prefs.getStringSet("saved_subreddits", null)
                ?: setOf("funny", "pics", "videos", "aww", "art")
            val defaultEntities = legacySubs.map { sub ->
                val clean = sub.lowercase().trim()
                CachedSubredditEntity(
                    subredditUrl = clean,
                    title = clean.replaceFirstChar { it.uppercase() },
                    isPinned = true
                )
            }
            dao.insertCachedSubredditsIgnore(defaultEntities)

            prefs.edit().putBoolean("room_migrated_v1", true).apply()
        }
    }

    suspend fun isPostFavorited(postId: Long): Boolean {
        return dao.isPostSaved(postId)
    }

    suspend fun getSavedPostIds(): Set<Long> {
        return dao.getSavedPosts().map { it.postId }.toSet()
    }

    suspend fun togglePostFavorite(post: ScrolllerPost): Boolean {
        val currentlySaved = dao.isPostSaved(post.postId)
        return if (currentlySaved) {
            dao.deleteSavedPost(post.postId)
            false
        } else {
            dao.insertSavedPost(SavedPostEntity.fromDomain(post.copy(isFavorite = true)))
            true
        }
    }

    suspend fun toggleSubredditSaved(name: String): Boolean {
        val clean = name.lowercase().trim()
            .removePrefix("r/")
            .removePrefix("/")
            .removeSuffix("/")
        if (clean.isBlank()) return false

        val existing = dao.getCachedSubreddit(clean)
        val isNowPinned = !(existing?.isPinned ?: false)
        val updated = existing?.copy(isPinned = isNowPinned)
            ?: CachedSubredditEntity(
                subredditUrl = clean,
                title = clean.replaceFirstChar { it.uppercase() },
                isPinned = isNowPinned
            )
        dao.upsertCachedSubreddit(updated)
        return isNowPinned
    }

    suspend fun recordPostViewed(post: ScrolllerPost) {
        if (post.postId <= 0) return
        dao.insertRecentPost(RecentPostEntity.fromDomain(post))
    }

    suspend fun clearRecentHistory() {
        dao.clearRecentPosts()
    }

    suspend fun clearFeedCache() {
        dao.clearAllCachedPosts()
        dao.clearUnpinnedCachedSubreddits()
    }
}
