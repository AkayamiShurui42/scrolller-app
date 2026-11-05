package com.scrolller.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SubredditItem(
    val id: Long = 0L,
    val title: String = "",
    val url: String = "",
    val description: String = "",
    val isNsfw: Boolean = false,
    val itemCount: Int = 0,
    val previewImages: List<MediaSource> = emptyList()
) {
    val cleanUrl: String
        get() = url.trim()
            .removePrefix("https://scrolller.com/")
            .removePrefix("http://scrolller.com/")
            .removePrefix("/")
            .removePrefix("r/")
            .removeSuffix("/")
}
