package com.scrolller.app.data.model

enum class GallerySort(val apiValue: String, val label: String) {
    HOT("HOT", "Best"),
    NEW("NEW", "Newest"),
    TOP("TOP", "Top"),
    RANDOM("RANDOM", "Random")
}

enum class ContentFilter(val label: String) {
    ALL("All"),
    SFW("SFW"),
    NSFW("NSFW")
}

enum class MediaFilter(val label: String) {
    ALL("All Media"),
    IMAGE("Images"),
    VIDEO("Videos"),
    GIF("GIFs"),
    ALBUM("Albums")
}

enum class SourceOriginFilter(val label: String) {
    ALL("All"),
    SCROLLLER("Scrolller"),
    REDDIT("Reddit"),
    REDGIFS("RedGIFs")
}

enum class SearchContextMode(val label: String) {
    ALL_SCROLLLER("All Scrolller"),
    CURRENT_FEED("This Collection")
}

enum class ViewMode {
    PAGER, GRID
}

data class FeedFilter(
    val sort: GallerySort = GallerySort.HOT,
    val mediaFilter: MediaFilter = MediaFilter.ALL,
    val contentFilter: ContentFilter = ContentFilter.ALL,
    val sourceOrigin: SourceOriginFilter = SourceOriginFilter.ALL,
    val category: String = "ALL",
    val selectedSubreddits: Set<String> = emptySet()
)
