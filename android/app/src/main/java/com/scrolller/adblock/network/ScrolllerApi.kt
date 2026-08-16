package com.scrolller.adblock.network

import com.scrolller.adblock.model.GalleryInfo
import com.scrolller.adblock.model.MediaSource
import com.scrolller.adblock.model.Post
import com.scrolller.adblock.model.SearchResult
import com.scrolller.adblock.model.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object ScrolllerApi {
    private const val ENDPOINT = "https://api.scrolller.com/admin"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
    const val PRELOAD_LIMIT = 5000

    private const val POST_FIELDS = """
        id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt
        mediaSources{url width height isOptimized}
        albumContent{mediaSources{url width height isOptimized}}
    """

    private val galleryQuery = """
        query SubredditQuery(${"$"}url:String!,${"$"}iterator:String,${"$"}sortBy:GallerySortBy,${"$"}filter:GalleryFilter,${"$"}limit:Int!){
          getSubreddit(data:{url:${"$"}url,iterator:${"$"}iterator,filter:${"$"}filter,limit:${"$"}limit,sortBy:${"$"}sortBy}){
            id url title description isNsfw
            children{iterator items{$POST_FIELDS}}
          }
        }
    """.trimIndent()

    private val searchQuery = """
        query SearchSubredditsQuery(${"$"}query:String!,${"$"}limit:Int!,${"$"}isNsfw:Boolean!,${"$"}pageIndex:Int!){
          searchSubreddits(data:{query:${"$"}query,isNsfw:${"$"}isNsfw,limit:${"$"}limit,pageIndex:${"$"}pageIndex}){
            id title url description is_nsfw item_count
          }
        }
    """.trimIndent()

    suspend fun loadGallery(url: String, sort: SortMode, limit: Int = PRELOAD_LIMIT): GalleryInfo = withContext(Dispatchers.IO) {
        val vars = JSONObject()
            .put("url", normalizeGalleryUrl(url))
            .put("iterator", JSONObject.NULL)
            .put("sortBy", sort.name)
            .put("filter", JSONObject.NULL)
            .put("limit", limit)

        val data = postGraphQl(galleryQuery, vars)
        val root = data.optJSONObject("getSubreddit") ?: JSONObject()
        val children = root.optJSONObject("children") ?: JSONObject()
        val raw = children.optJSONArray("items") ?: JSONArray()
        val posts = ArrayList<Post>(raw.length())
        val seen = HashSet<String>()
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val post = parsePost(item) ?: continue
            if (seen.add(post.key)) posts.add(post)
        }

        GalleryInfo(
            title = root.optString("title").ifBlank { normalizeGalleryUrl(url) },
            url = root.optString("url").ifBlank { normalizeGalleryUrl(url) },
            description = root.optString("description"),
            posts = posts
        )
    }

    suspend fun searchGalleries(query: String, includeNsfw: Boolean = true): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val vars = JSONObject()
            .put("query", query.trim())
            .put("limit", 50)
            .put("isNsfw", includeNsfw)
            .put("pageIndex", 0)
        val data = postGraphQl(searchQuery, vars)
        val raw = data.optJSONArray("searchSubreddits") ?: JSONArray()
        buildList {
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val galleryUrl = item.optString("url")
                if (galleryUrl.isBlank()) continue
                add(
                    SearchResult(
                        id = item.optString("id"),
                        title = item.optString("title").ifBlank { galleryUrl },
                        url = galleryUrl,
                        description = item.optString("description"),
                        isNsfw = item.optBoolean("is_nsfw", false),
                        itemCount = item.optInt("item_count", 0)
                    )
                )
            }
        }
    }

    suspend fun searchGalleriesFuzzy(query: String, includeNsfw: Boolean = true): List<SearchResult> = coroutineScope {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return@coroutineScope emptyList()

        val variants = fuzzyQueryVariants(clean)
        val batches = variants.map { variant ->
            async(Dispatchers.IO) {
                runCatching { searchGalleries(variant, includeNsfw) }.getOrDefault(emptyList())
            }
        }.awaitAll()

        val merged = LinkedHashMap<String, SearchResult>()
        batches.flatten().forEach { result ->
            val key = result.url.trim().lowercase().ifBlank { result.id }
            val previous = merged[key]
            if (previous == null || result.itemCount > previous.itemCount) merged[key] = result
        }

        merged.values
            .map { it to fuzzyGalleryScore(it, clean) }
            .sortedWith(
                compareByDescending<Pair<SearchResult, Int>> { it.second }
                    .thenByDescending { it.first.itemCount }
                    .thenBy { it.first.title.lowercase() }
            )
            .map { it.first }
    }

    private fun fuzzyQueryVariants(query: String): List<String> {
        val words = query.split(' ').filter { it.isNotBlank() }
        val meaningful = words.filter { it.length >= 2 }
        val out = LinkedHashSet<String>()

        fun add(value: String) {
            val clean = value.trim().replace(Regex("\\s+"), " ")
            if (clean.isNotBlank()) out.add(clean)
        }

        add(query)
        add(query.lowercase())
        add(query.uppercase())
        add(query.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } })

        meaningful.forEach { word ->
            add(word)
            add(word.lowercase())
            add(word.replaceFirstChar { c -> c.uppercase() })
            if (word.length > 3) {
                if (word.endsWith("s", ignoreCase = true)) add(word.dropLast(1)) else add("${word}s")
            }
        }

        for (i in 0 until max(0, meaningful.size - 1)) {
            add("${meaningful[i]} ${meaningful[i + 1]}")
        }

        return out.take(14)
    }

    private fun fuzzyGalleryScore(result: SearchResult, query: String): Int {
        val q = normalizeForFuzzy(query)
        val title = normalizeForFuzzy(result.title)
        val url = normalizeForFuzzy(result.url.replace('-', ' ').replace('_', ' '))
        val description = normalizeForFuzzy(result.description)
        val haystack = "$title $url $description".trim()
        if (q.isBlank()) return 0

        var score = 0
        if (title == q) score += 1000
        if (url == q) score += 900
        if (title.startsWith(q)) score += 600
        if (url.startsWith(q)) score += 500
        if (title.contains(q)) score += 450
        if (url.contains(q)) score += 400
        if (haystack.contains(q)) score += 250

        val qTokens = fuzzyTokens(q)
        val hTokens = fuzzyTokens(haystack)
        qTokens.forEach { token ->
            if (token in hTokens) {
                score += 120
            } else {
                val best = hTokens.maxOfOrNull { fuzzyWordSimilarity(token, it) } ?: 0.0
                score += (best * 85.0).toInt()
            }
        }
        return score
    }

    private fun normalizeForFuzzy(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun fuzzyTokens(value: String): Set<String> = normalizeForFuzzy(value)
        .split(' ')
        .filter { it.length >= 2 }
        .toSet()

    private fun fuzzyWordSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a.startsWith(b) || b.startsWith(a)) return 0.9
        if (a.contains(b) || b.contains(a)) return 0.82
        if (a.length == 1 || b.length == 1) return 0.0

        val aPairs = a.windowed(2).toSet()
        val bPairs = b.windowed(2).toSet()
        if (aPairs.isEmpty() || bPairs.isEmpty()) return 0.0
        val overlap = aPairs.intersect(bPairs).size.toDouble()
        return (2.0 * overlap) / (aPairs.size + bPairs.size).toDouble()
    }

    private fun normalizeGalleryUrl(value: String): String = value
        .trim()
        .removePrefix("https://scrolller.com/")
        .removePrefix("http://scrolller.com/")
        .removePrefix("r/")
        .trim('/')

    private fun parsePost(item: JSONObject): Post? {
        val direct = parseSources(item.optJSONArray("mediaSources"))
        val albums = mutableListOf<List<MediaSource>>()
        val albumArray = item.optJSONArray("albumContent")
        if (albumArray != null) {
            for (i in 0 until albumArray.length()) {
                val slide = albumArray.optJSONObject(i) ?: continue
                val sources = parseSources(slide.optJSONArray("mediaSources"))
                if (sources.isNotEmpty()) albums.add(sources)
            }
        }
        if (direct.isEmpty() && albums.isEmpty()) return null

        return Post(
            id = item.optString("id"),
            postUrl = item.optString("url"),
            title = item.optString("title").ifBlank { "Untitled" },
            subredditTitle = item.optString("subredditTitle").ifBlank { item.optString("subredditUrl") },
            subredditUrl = item.optString("subredditUrl"),
            isNsfw = item.optBoolean("isNsfw", false),
            hasAudio = item.optBoolean("hasAudio", false),
            createdAt = item.optString("createdAt"),
            mediaSources = direct,
            albumSources = albums
        )
    }

    private fun parseSources(array: JSONArray?): List<MediaSource> {
        if (array == null) return emptyList()
        val out = ArrayList<MediaSource>(array.length())
        for (i in 0 until array.length()) {
            val source = array.optJSONObject(i) ?: continue
            val url = source.optString("url")
            if (url.isBlank()) continue
            out.add(
                MediaSource(
                    url = url,
                    width = source.optInt("width", 0),
                    height = source.optInt("height", 0),
                    optimized = source.optBoolean("isOptimized", false)
                )
            )
        }
        return out.distinctBy { it.url }
    }

    private fun postGraphQl(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: throw IllegalStateException("Scrolller returned ${connection.responseCode}"))).use { reader ->
                reader.readText()
            }
            val json = JSONObject(text.ifBlank { "{}" })
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val messages = buildList {
                    for (i in 0 until errors.length()) {
                        add(errors.optJSONObject(i)?.optString("message") ?: "Scrolller API error")
                    }
                }
                throw IllegalStateException(messages.joinToString(" · "))
            }
            return json.optJSONObject("data") ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }
}
