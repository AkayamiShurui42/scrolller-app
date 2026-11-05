package com.scrolller.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.scrolller.app.data.model.MediaSource
import com.scrolller.app.data.model.MediaType
import com.scrolller.app.data.model.ScrolllerPost
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val entityJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Entity(
    tableName = "cached_posts",
    primaryKeys = ["postId", "sourceKey"],
    indices = [
        Index(value = ["sourceKey"]),
        Index(value = ["subredditUrl"]),
        Index(value = ["cachedAt"])
    ]
)
data class CachedPostEntity(
    val postId: Long,
    val sourceKey: String,
    val id: String,
    val postUrl: String,
    val title: String,
    val subredditTitle: String,
    val subredditUrl: String,
    val mediaType: String,
    val mediaUrl: String,
    val posterUrl: String,
    val albumJson: String,
    val sourceVariantsJson: String,
    val isNsfw: Boolean,
    val createdAt: Long,
    val favoriteCount: Long,
    val isFavorite: Boolean,
    val redditPath: String,
    val feedOrder: Int,
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(isFavOverride: Boolean? = null): ScrolllerPost {
        val albumList = runCatching {
            entityJson.decodeFromString<List<String>>(albumJson)
        }.getOrDefault(emptyList())
        val variantsList = runCatching {
            entityJson.decodeFromString<List<MediaSource>>(sourceVariantsJson)
        }.getOrDefault(emptyList())
        val parsedType = runCatching {
            MediaType.valueOf(mediaType)
        }.getOrDefault(MediaType.IMAGE)

        return ScrolllerPost(
            id = id,
            postId = postId,
            postUrl = postUrl,
            title = title,
            subredditTitle = subredditTitle,
            subredditUrl = subredditUrl,
            mediaType = parsedType,
            mediaUrl = mediaUrl,
            posterUrl = posterUrl,
            album = albumList,
            sourceVariants = variantsList,
            isNsfw = isNsfw,
            createdAt = createdAt,
            favoriteCount = favoriteCount,
            isFavorite = isFavOverride ?: isFavorite,
            redditPath = redditPath
        )
    }

    companion object {
        fun fromDomain(
            post: ScrolllerPost,
            sourceKey: String,
            feedOrder: Int,
            cachedAt: Long = System.currentTimeMillis()
        ): CachedPostEntity {
            return CachedPostEntity(
                postId = post.postId,
                sourceKey = sourceKey.lowercase().trim(),
                id = post.id,
                postUrl = post.postUrl,
                title = post.title,
                subredditTitle = post.subredditTitle,
                subredditUrl = post.subredditUrl.lowercase().trim(),
                mediaType = post.mediaType.name,
                mediaUrl = post.mediaUrl,
                posterUrl = post.posterUrl,
                albumJson = runCatching { entityJson.encodeToString(post.album) }.getOrDefault("[]"),
                sourceVariantsJson = runCatching { entityJson.encodeToString(post.sourceVariants) }.getOrDefault("[]"),
                isNsfw = post.isNsfw,
                createdAt = post.createdAt,
                favoriteCount = post.favoriteCount,
                isFavorite = post.isFavorite,
                redditPath = post.redditPath,
                feedOrder = feedOrder,
                cachedAt = cachedAt
            )
        }
    }
}

@Entity(
    tableName = "recent_posts",
    indices = [Index(value = ["viewedAt"])]
)
data class RecentPostEntity(
    @PrimaryKey val postId: Long,
    val id: String,
    val postUrl: String,
    val title: String,
    val subredditTitle: String,
    val subredditUrl: String,
    val mediaType: String,
    val mediaUrl: String,
    val posterUrl: String,
    val albumJson: String,
    val sourceVariantsJson: String,
    val isNsfw: Boolean,
    val createdAt: Long,
    val favoriteCount: Long,
    val isFavorite: Boolean,
    val redditPath: String,
    val viewedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(isFavOverride: Boolean? = null): ScrolllerPost {
        val albumList = runCatching {
            entityJson.decodeFromString<List<String>>(albumJson)
        }.getOrDefault(emptyList())
        val variantsList = runCatching {
            entityJson.decodeFromString<List<MediaSource>>(sourceVariantsJson)
        }.getOrDefault(emptyList())
        val parsedType = runCatching {
            MediaType.valueOf(mediaType)
        }.getOrDefault(MediaType.IMAGE)

        return ScrolllerPost(
            id = id,
            postId = postId,
            postUrl = postUrl,
            title = title,
            subredditTitle = subredditTitle,
            subredditUrl = subredditUrl,
            mediaType = parsedType,
            mediaUrl = mediaUrl,
            posterUrl = posterUrl,
            album = albumList,
            sourceVariants = variantsList,
            isNsfw = isNsfw,
            createdAt = createdAt,
            favoriteCount = favoriteCount,
            isFavorite = isFavOverride ?: isFavorite,
            redditPath = redditPath
        )
    }

    companion object {
        fun fromDomain(post: ScrolllerPost, viewedAt: Long = System.currentTimeMillis()): RecentPostEntity {
            return RecentPostEntity(
                postId = post.postId,
                id = post.id,
                postUrl = post.postUrl,
                title = post.title,
                subredditTitle = post.subredditTitle,
                subredditUrl = post.subredditUrl.lowercase().trim(),
                mediaType = post.mediaType.name,
                mediaUrl = post.mediaUrl,
                posterUrl = post.posterUrl,
                albumJson = runCatching { entityJson.encodeToString(post.album) }.getOrDefault("[]"),
                sourceVariantsJson = runCatching { entityJson.encodeToString(post.sourceVariants) }.getOrDefault("[]"),
                isNsfw = post.isNsfw,
                createdAt = post.createdAt,
                favoriteCount = post.favoriteCount,
                isFavorite = post.isFavorite,
                redditPath = post.redditPath,
                viewedAt = viewedAt
            )
        }
    }
}

@Entity(
    tableName = "saved_posts",
    indices = [Index(value = ["savedAt"])]
)
data class SavedPostEntity(
    @PrimaryKey val postId: Long,
    val id: String,
    val postUrl: String,
    val title: String,
    val subredditTitle: String,
    val subredditUrl: String,
    val mediaType: String,
    val mediaUrl: String,
    val posterUrl: String,
    val albumJson: String,
    val sourceVariantsJson: String,
    val isNsfw: Boolean,
    val createdAt: Long,
    val favoriteCount: Long,
    val redditPath: String,
    val savedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): ScrolllerPost {
        val albumList = runCatching {
            entityJson.decodeFromString<List<String>>(albumJson)
        }.getOrDefault(emptyList())
        val variantsList = runCatching {
            entityJson.decodeFromString<List<MediaSource>>(sourceVariantsJson)
        }.getOrDefault(emptyList())
        val parsedType = runCatching {
            MediaType.valueOf(mediaType)
        }.getOrDefault(MediaType.IMAGE)

        return ScrolllerPost(
            id = id,
            postId = postId,
            postUrl = postUrl,
            title = title,
            subredditTitle = subredditTitle,
            subredditUrl = subredditUrl,
            mediaType = parsedType,
            mediaUrl = mediaUrl,
            posterUrl = posterUrl,
            album = albumList,
            sourceVariants = variantsList,
            isNsfw = isNsfw,
            createdAt = createdAt,
            favoriteCount = favoriteCount,
            isFavorite = true,
            redditPath = redditPath
        )
    }

    companion object {
        fun fromDomain(post: ScrolllerPost, savedAt: Long = System.currentTimeMillis()): SavedPostEntity {
            return SavedPostEntity(
                postId = post.postId,
                id = post.id,
                postUrl = post.postUrl,
                title = post.title,
                subredditTitle = post.subredditTitle,
                subredditUrl = post.subredditUrl.lowercase().trim(),
                mediaType = post.mediaType.name,
                mediaUrl = post.mediaUrl,
                posterUrl = post.posterUrl,
                albumJson = runCatching { entityJson.encodeToString(post.album) }.getOrDefault("[]"),
                sourceVariantsJson = runCatching { entityJson.encodeToString(post.sourceVariants) }.getOrDefault("[]"),
                isNsfw = post.isNsfw,
                createdAt = post.createdAt,
                favoriteCount = post.favoriteCount,
                redditPath = post.redditPath,
                savedAt = savedAt
            )
        }
    }
}

@Entity(tableName = "cached_subreddits")
data class CachedSubredditEntity(
    @PrimaryKey val subredditUrl: String,
    val title: String,
    val isPinned: Boolean = false,
    val cachedPostCount: Int = 0,
    val previewImageUrl: String = "",
    val isFullyCached: Boolean = false,
    val lastSyncedAt: Long = System.currentTimeMillis()
)
