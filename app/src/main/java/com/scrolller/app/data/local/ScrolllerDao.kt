package com.scrolller.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScrolllerDao {

    // --- Cached Feed & Subreddit Posts ---

    @Query("SELECT * FROM cached_posts WHERE sourceKey = :sourceKey ORDER BY feedOrder ASC")
    fun observeCachedPostsBySource(sourceKey: String): Flow<List<CachedPostEntity>>

    @Query("SELECT * FROM cached_posts WHERE sourceKey = :sourceKey ORDER BY feedOrder ASC")
    suspend fun getCachedPostsBySource(sourceKey: String): List<CachedPostEntity>

    @Query("SELECT * FROM cached_posts WHERE subredditUrl = :subredditUrl ORDER BY feedOrder ASC, cachedAt DESC")
    suspend fun getCachedPostsBySubreddit(subredditUrl: String): List<CachedPostEntity>

    @Query("SELECT * FROM cached_posts ORDER BY cachedAt DESC LIMIT :limit")
    suspend fun getAllCachedPosts(limit: Int = 200): List<CachedPostEntity>

    @Query("SELECT COUNT(DISTINCT postId) FROM cached_posts")
    fun observeTotalCachedPostCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedPosts(posts: List<CachedPostEntity>)

    @Query("DELETE FROM cached_posts WHERE sourceKey = :sourceKey")
    suspend fun clearCachedPostsForSource(sourceKey: String)

    @Query("DELETE FROM cached_posts")
    suspend fun clearAllCachedPosts()

    @Transaction
    suspend fun replaceCachedPostsForSource(sourceKey: String, posts: List<CachedPostEntity>) {
        clearCachedPostsForSource(sourceKey)
        insertCachedPosts(posts)
    }

    // --- Recently Viewed / Accessed Posts ---

    @Query("SELECT * FROM recent_posts ORDER BY viewedAt DESC LIMIT :limit")
    fun observeRecentPosts(limit: Int = 150): Flow<List<RecentPostEntity>>

    @Query("SELECT * FROM recent_posts ORDER BY viewedAt DESC LIMIT :limit")
    suspend fun getRecentPosts(limit: Int = 150): List<RecentPostEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentPost(post: RecentPostEntity)

    @Query("DELETE FROM recent_posts")
    suspend fun clearRecentPosts()

    // --- Saved / Favorited Posts ---

    @Query("SELECT * FROM saved_posts ORDER BY savedAt DESC")
    fun observeSavedPosts(): Flow<List<SavedPostEntity>>

    @Query("SELECT * FROM saved_posts ORDER BY savedAt DESC")
    suspend fun getSavedPosts(): List<SavedPostEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_posts WHERE postId = :postId)")
    suspend fun isPostSaved(postId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedPost(post: SavedPostEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedPosts(posts: List<SavedPostEntity>)

    @Query("DELETE FROM saved_posts WHERE postId = :postId")
    suspend fun deleteSavedPost(postId: Long)

    // --- Cached & Pinned Subreddits ---

    @Query("SELECT * FROM cached_subreddits ORDER BY isPinned DESC, lastSyncedAt DESC")
    fun observeCachedSubreddits(): Flow<List<CachedSubredditEntity>>

    @Query("SELECT * FROM cached_subreddits WHERE isPinned = 1 ORDER BY subredditUrl ASC")
    fun observePinnedSubreddits(): Flow<List<CachedSubredditEntity>>

    @Query("SELECT * FROM cached_subreddits WHERE subredditUrl = :subredditUrl LIMIT 1")
    suspend fun getCachedSubreddit(subredditUrl: String): CachedSubredditEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedSubreddit(subreddit: CachedSubredditEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCachedSubredditsIgnore(subreddits: List<CachedSubredditEntity>)

    @Query("DELETE FROM cached_subreddits WHERE isPinned = 0")
    suspend fun clearUnpinnedCachedSubreddits()
}
