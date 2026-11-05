package com.scrolller.app.data.model

import kotlinx.serialization.Serializable

enum class MediaType {
    IMAGE, VIDEO, GIF, ALBUM
}

@Serializable
data class ScrolllerPost(
    val id: String,
    val postId: Long,
    val postUrl: String = "",
    val title: String = "",
    val subredditTitle: String = "",
    val subredditUrl: String = "",
    val mediaType: MediaType = MediaType.IMAGE,
    val mediaUrl: String,
    val posterUrl: String = "",
    val album: List<String> = emptyList(),
    val sourceVariants: List<MediaSource> = emptyList(),
    val isNsfw: Boolean = false,
    val createdAt: Long = 0L,
    val favoriteCount: Long = 0L,
    val isFavorite: Boolean = false,
    val redditPath: String = ""
)
