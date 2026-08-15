package com.scrolller.adblock.model

enum class LayoutType { GRID, LIST }
enum class SortMode { RANDOM, HOT, NEW, TOP }
enum class NsfwMode { ALL, SFW, NSFW }
enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class MainTab { HOME, FAVORITES, SEARCH, SETTINGS }

data class MediaSource(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val optimized: Boolean = false
) {
    val area: Long get() = width.toLong() * height.toLong()
    val isVideo: Boolean get() = url.contains(".mp4", true) || url.contains(".webm", true)
}

data class Post(
    val id: String,
    val postUrl: String,
    val title: String,
    val subredditTitle: String,
    val subredditUrl: String,
    val isNsfw: Boolean,
    val hasAudio: Boolean,
    val createdAt: String,
    val mediaSources: List<MediaSource>,
    val albumSources: List<List<MediaSource>> = emptyList()
) {
    val key: String get() = if (id.isNotBlank()) id else postUrl

    fun allSources(): List<MediaSource> = (mediaSources + albumSources.flatten())
        .filter { it.url.isNotBlank() }
        .distinctBy { it.url }

    fun fullCandidates(): List<String> = allSources()
        .sortedWith(compareByDescending<MediaSource> { it.area }.thenBy { it.optimized })
        .map { it.url }

    fun thumbnailCandidates(): List<String> {
        val images = allSources().filterNot { it.isVideo }
        if (images.isEmpty()) return emptyList()

        val preferred = images
            .filter { it.width >= 480 || it.height >= 480 }
            .sortedWith(compareBy<MediaSource> { it.area }.thenByDescending { it.optimized })
        val fallback = images.sortedByDescending { it.area }
        return (preferred + fallback).distinctBy { it.url }.map { it.url }
    }

    fun videoCandidates(): List<String> = allSources()
        .filter { it.isVideo }
        .sortedByDescending { it.area }
        .map { it.url }

    val isVideo: Boolean get() = videoCandidates().isNotEmpty()
}

data class GalleryInfo(
    val title: String,
    val url: String,
    val description: String,
    val posts: List<Post>
)

data class SearchResult(
    val id: String,
    val title: String,
    val url: String,
    val description: String,
    val isNsfw: Boolean,
    val itemCount: Int
)

sealed interface Route {
    data class Gallery(val url: String, val title: String) : Route
    data class Media(val post: Post) : Route
}
