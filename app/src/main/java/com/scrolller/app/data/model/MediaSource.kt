package com.scrolller.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaSource(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val isOptimized: Boolean = true
) {
    val pixelArea: Long get() = width.toLong() * height.toLong()
}
