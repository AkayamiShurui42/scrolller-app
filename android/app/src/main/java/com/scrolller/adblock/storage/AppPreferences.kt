package com.scrolller.adblock.storage

import android.content.Context
import com.scrolller.adblock.model.LayoutType
import com.scrolller.adblock.model.MediaSource
import com.scrolller.adblock.model.NsfwMode
import com.scrolller.adblock.model.Post
import com.scrolller.adblock.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("scrolller_native_preferences", Context.MODE_PRIVATE)

    var layoutType: LayoutType
        get() = runCatching { LayoutType.valueOf(prefs.getString("layout", LayoutType.GRID.name)!!) }.getOrDefault(LayoutType.GRID)
        set(value) { prefs.edit().putString("layout", value.name).apply() }

    var nsfwMode: NsfwMode
        get() = runCatching { NsfwMode.valueOf(prefs.getString("nsfw", NsfwMode.ALL.name)!!) }.getOrDefault(NsfwMode.ALL)
        set(value) { prefs.edit().putString("nsfw", value.name).apply() }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", ThemeMode.DARK.name)!!) }.getOrDefault(ThemeMode.DARK)
        set(value) { prefs.edit().putString("theme", value.name).apply() }

    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(value) { prefs.edit().putBoolean("autoplay", value).apply() }

    var muted: Boolean
        get() = prefs.getBoolean("muted", true)
        set(value) { prefs.edit().putBoolean("muted", value).apply() }

    fun loadFavorites(): List<Post> {
        val raw = prefs.getString("favorites_json", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val post = postFromJson(array.optJSONObject(i) ?: continue)
                    if (post != null) add(post)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveFavorites(posts: Collection<Post>) {
        val array = JSONArray()
        posts.forEach { array.put(postToJson(it)) }
        prefs.edit().putString("favorites_json", array.toString()).apply()
    }

    private fun postToJson(post: Post): JSONObject = JSONObject()
        .put("id", post.id)
        .put("postUrl", post.postUrl)
        .put("title", post.title)
        .put("subredditTitle", post.subredditTitle)
        .put("subredditUrl", post.subredditUrl)
        .put("isNsfw", post.isNsfw)
        .put("hasAudio", post.hasAudio)
        .put("createdAt", post.createdAt)
        .put("mediaSources", JSONArray().apply { post.mediaSources.forEach { put(sourceToJson(it)) } })
        .put("albumSources", JSONArray().apply {
            post.albumSources.forEach { group ->
                put(JSONArray().apply { group.forEach { put(sourceToJson(it)) } })
            }
        })

    private fun postFromJson(obj: JSONObject): Post? {
        val sources = sourcesFromJson(obj.optJSONArray("mediaSources"))
        val albums = mutableListOf<List<MediaSource>>()
        val albumArray = obj.optJSONArray("albumSources")
        if (albumArray != null) {
            for (i in 0 until albumArray.length()) {
                val group = sourcesFromJson(albumArray.optJSONArray(i))
                if (group.isNotEmpty()) albums.add(group)
            }
        }
        if (sources.isEmpty() && albums.isEmpty()) return null
        return Post(
            id = obj.optString("id"),
            postUrl = obj.optString("postUrl"),
            title = obj.optString("title"),
            subredditTitle = obj.optString("subredditTitle"),
            subredditUrl = obj.optString("subredditUrl"),
            isNsfw = obj.optBoolean("isNsfw", false),
            hasAudio = obj.optBoolean("hasAudio", false),
            createdAt = obj.optString("createdAt"),
            mediaSources = sources,
            albumSources = albums
        )
    }

    private fun sourceToJson(source: MediaSource): JSONObject = JSONObject()
        .put("url", source.url)
        .put("width", source.width)
        .put("height", source.height)
        .put("optimized", source.optimized)

    private fun sourcesFromJson(array: JSONArray?): List<MediaSource> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isBlank()) continue
                add(
                    MediaSource(
                        url = url,
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0),
                        optimized = obj.optBoolean("optimized", false)
                    )
                )
            }
        }
    }
}
