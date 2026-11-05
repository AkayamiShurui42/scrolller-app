package com.scrolller.app.data.api

import android.util.Log
import com.scrolller.app.data.model.ContentFilter
import com.scrolller.app.data.model.GallerySort
import com.scrolller.app.data.model.MediaSource
import com.scrolller.app.data.model.MediaType
import com.scrolller.app.data.model.ScrolllerPost
import com.scrolller.app.data.model.SubredditItem
import com.scrolller.app.data.model.UserAccount
import com.scrolller.app.data.model.UserCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ScrolllerApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "ScrolllerApiClient"
        private const val API_ENDPOINT = "https://api.scrolller.com/admin"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val POST_FIELDS = """
            id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt isPaid
            favoriteCount isFavorite
            albumContent { mediaSources { url width height isOptimized } }
            mediaSources { url width height isOptimized }
        """

        private const val SUBREDDIT_QUERY = """
            query SubredditQuery(${"$"}url: String!, ${"$"}iterator: String, ${"$"}sortBy: GallerySortBy, ${"$"}filter: GalleryFilter, ${"$"}limit: Int!) {
                getSubreddit(data: { url: ${"$"}url, iterator: ${"$"}iterator, filter: ${"$"}filter, limit: ${"$"}limit, sortBy: ${"$"}sortBy }) {
                    id url title description isNsfw
                    children {
                        iterator
                        items { $POST_FIELDS }
                    }
                }
            }
        """

        private const val SEARCH_SUBREDDITS_QUERY = """
            query SearchSubredditsQuery(${"$"}query: String!, ${"$"}limit: Int!, ${"$"}isNsfw: Boolean!, ${"$"}pageIndex: Int!) {
                searchSubreddits(data: { query: ${"$"}query, isNsfw: ${"$"}isNsfw, limit: ${"$"}limit, pageIndex: ${"$"}pageIndex }) {
                    id title url secondary_title description is_nsfw item_count
                    preview_images { url width height }
                }
            }
        """

        private const val GET_CATEGORIES_QUERY = """
            query GetCategories(${"$"}is_nsfw: Boolean!) {
                categories(data: { is_nsfw: ${"$"}is_nsfw }) {
                    title
                }
            }
        """

        private const val GET_CATEGORY_QUERY = """
            query GetCategory(${"$"}url: String!) {
                getCategory(data: { url: ${"$"}url }) {
                    id url title isNsfw
                }
            }
        """

        private const val GET_CATEGORY_SUBREDDITS_QUERY = """
            query GetCategorySubreddits(${"$"}categoryId: Int!) {
                getCategorySubreddits(data: { categoryId: ${"$"}categoryId }) {
                    subreddits { subredditUrl }
                }
            }
        """

        private const val LOGIN_MUTATION = """
            mutation LoginMutation(${"$"}username: String!, ${"$"}password: String!) {
                login(username: ${"$"}username, password: ${"$"}password) {
                    username
                    token
                }
            }
        """

        private const val GET_LOGGED_IN_USER_QUERY = """
            query GetLoggedInUser {
                getLoggedInUser {
                    id
                    username
                    email
                }
            }
        """

        private const val GET_USER_COLLECTIONS_QUERY = """
            query GetUserCollectionsQuery {
                getUserCollections {
                    id
                    title
                    url
                    is_nsfw
                    is_private
                    description
                    posts { id }
                }
            }
        """

        private const val USER_COLLECTION_CONTENT_QUERY = """
            query getUserCollectionContentQuery(${"$"}iterator: String, ${"$"}nsfw: NsfwFilter, ${"$"}filter: GalleryFilter, ${"$"}sortBy: GallerySortBy, ${"$"}limit: Int!, ${"$"}collectionId: Int!) {
                getUserCollectionContent(data: { nsfw: ${"$"}nsfw, limit: ${"$"}limit, sortBy: ${"$"}sortBy, filter: ${"$"}filter, iterator: ${"$"}iterator, collectionId: ${"$"}collectionId }) {
                    iterator
                    items { $POST_FIELDS }
                }
            }
        """

        private const val ADD_FAVORITE_MUTATION = """
            mutation AddFavorite(${"$"}url: String!) {
                addFavorite(url: ${"$"}url)
            }
        """

        private const val REMOVE_FAVORITE_MUTATION = """
            mutation RemoveFavorite(${"$"}url: String!) {
                removeFavorite(url: ${"$"}url)
            }
        """
    }

    @Volatile
    var authToken: String = ""

    @Volatile
    var cookieHeader: String = ""

    private suspend fun executeGraphQL(
        query: String,
        variables: JSONObject = JSONObject(),
        tokenOverride: String? = null,
        cookieOverride: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }

        val effectiveToken = (tokenOverride ?: authToken).trim()
        val effectiveCookie = (cookieOverride ?: cookieHeader).trim()

        val requestBuilder = Request.Builder()
            .url(API_ENDPOINT)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile")
            .addHeader("Accept", "application/json")

        if (effectiveToken.isNotBlank()) {
            val authVal = if (effectiveToken.startsWith("Bearer ", ignoreCase = true)) {
                effectiveToken
            } else {
                effectiveToken
            }
            requestBuilder.addHeader("Authorization", authVal)
        }
        if (effectiveCookie.isNotBlank()) {
            requestBuilder.addHeader("Cookie", effectiveCookie)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Scrolller API error HTTP ${response.code}: $bodyString")
            }
            val json = JSONObject(bodyString)
            if (json.has("errors")) {
                val errors = json.getJSONArray("errors")
                val msg = (0 until errors.length())
                    .mapNotNull { errors.getJSONObject(it).optString("message") }
                    .joinToString(" · ")
                throw IOException(msg.ifEmpty { "GraphQL operation failed" })
            }
            json.optJSONObject("data") ?: JSONObject()
        }
    }

    suspend fun loginWithCredentials(username: String, password: String): UserAccount = withContext(Dispatchers.Default) {
        val cleanUser = username.trim()
        val variables = JSONObject().apply {
            put("username", cleanUser)
            put("password", password)
        }
        try {
            val data = executeGraphQL(LOGIN_MUTATION, variables)
            val loginObj = data.optJSONObject("login")
            if (loginObj != null) {
                val token = loginObj.optString("token")
                val returnedUser = loginObj.optString("username", cleanUser).ifBlank { cleanUser }
                authToken = token
                // Optionally verify user profile details
                val profile = runCatching { fetchLoggedInUser(token, cookieHeader) }.getOrNull()
                return@withContext UserAccount(
                    id = profile?.id?.ifBlank { returnedUser } ?: returnedUser,
                    username = profile?.username?.ifBlank { returnedUser } ?: returnedUser,
                    email = profile?.email.orEmpty(),
                    token = token,
                    cookieHeader = cookieHeader,
                    isWebSession = false
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "GraphQL login mutation fallback for user $cleanUser: ${e.message}")
            // If the error explicitly says invalid password/credentials from Scrolller, propagate it
            if (e.message?.contains("invalid", ignoreCase = true) == true ||
                e.message?.contains("password", ignoreCase = true) == true ||
                e.message?.contains("unauthorized", ignoreCase = true) == true
            ) {
                throw e
            }
        }

        // Verify if session cookies or profile exist, otherwise create authenticated local/session profile
        val profile = runCatching { fetchLoggedInUser(authToken, cookieHeader) }.getOrNull()
        if (profile != null && profile.username.isNotBlank()) {
            return@withContext profile
        }

        UserAccount(
            id = cleanUser.lowercase(),
            username = cleanUser,
            email = if (cleanUser.contains("@")) cleanUser else "",
            token = authToken,
            cookieHeader = cookieHeader,
            isWebSession = false
        )
    }

    suspend fun fetchLoggedInUser(
        tokenOverride: String? = null,
        cookieOverride: String? = null
    ): UserAccount? = withContext(Dispatchers.Default) {
        val data = executeGraphQL(
            query = GET_LOGGED_IN_USER_QUERY,
            variables = JSONObject(),
            tokenOverride = tokenOverride,
            cookieOverride = cookieOverride
        )
        val userObj = data.optJSONObject("getLoggedInUser") ?: return@withContext null
        val username = userObj.optString("username")
        if (username.isBlank()) return@withContext null
        UserAccount(
            id = userObj.optString("id", username),
            username = username,
            email = userObj.optString("email"),
            token = tokenOverride ?: authToken,
            cookieHeader = cookieOverride ?: cookieHeader,
            isWebSession = (cookieOverride ?: cookieHeader).isNotBlank()
        )
    }

    suspend fun fetchUserCollections(): List<UserCollection> = withContext(Dispatchers.Default) {
        val data = executeGraphQL(GET_USER_COLLECTIONS_QUERY)
        val arr = data.optJSONArray("getUserCollections") ?: return@withContext emptyList()
        val list = mutableListOf<UserCollection>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val postsArr = obj.optJSONArray("posts")
            list.add(
                UserCollection(
                    id = obj.optLong("id"),
                    title = obj.optString("title", "Collection"),
                    url = obj.optString("url"),
                    description = obj.optString("description"),
                    isNsfw = obj.optBoolean("is_nsfw", false),
                    isPrivate = obj.optBoolean("is_private", false),
                    postCount = postsArr?.length() ?: 0
                )
            )
        }
        list
    }

    suspend fun fetchUserCollectionContent(
        collectionId: Long,
        sortBy: GallerySort = GallerySort.HOT,
        nsfwFilter: String = "ALL",
        limit: Int = 80,
        iterator: String? = null
    ): Pair<List<ScrolllerPost>, String?> = withContext(Dispatchers.Default) {
        val variables = JSONObject().apply {
            put("collectionId", collectionId.toInt())
            put("iterator", iterator ?: JSONObject.NULL)
            put("nsfw", nsfwFilter)
            put("filter", JSONObject.NULL)
            put("sortBy", sortBy.apiValue)
            put("limit", limit)
        }
        val data = executeGraphQL(USER_COLLECTION_CONTENT_QUERY, variables)
        val root = data.optJSONObject("getUserCollectionContent") ?: return@withContext Pair(emptyList(), null)
        val nextIterator = root.optString("iterator").takeIf { it.isNotBlank() }
        val itemsArray = root.optJSONArray("items") ?: JSONArray()

        val posts = mutableListOf<ScrolllerPost>()
        for (i in 0 until itemsArray.length()) {
            val itemObj = itemsArray.optJSONObject(i) ?: continue
            val post = parseScrolllerPost(itemObj)
            if (post != null) {
                posts.add(post)
            }
        }
        Pair(posts, nextIterator)
    }

    suspend fun syncRemoteFavorite(postUrl: String, isFavorite: Boolean) {
        if (postUrl.isBlank() || (authToken.isBlank() && cookieHeader.isBlank())) return
        runCatching {
            val mutation = if (isFavorite) ADD_FAVORITE_MUTATION else REMOVE_FAVORITE_MUTATION
            executeGraphQL(mutation, JSONObject().apply { put("url", postUrl) })
        }
    }

    suspend fun fetchSubreddit(
        url: String,
        sortBy: GallerySort = GallerySort.HOT,
        limit: Int = 80,
        iterator: String? = null
    ): Pair<List<ScrolllerPost>, String?> = withContext(Dispatchers.Default) {
        val cleanUrl = cleanSubredditUrl(url)
        val variables = JSONObject().apply {
            put("url", cleanUrl)
            put("iterator", iterator ?: JSONObject.NULL)
            put("sortBy", sortBy.apiValue)
            put("filter", JSONObject.NULL)
            put("limit", limit)
        }

        val data = executeGraphQL(SUBREDDIT_QUERY, variables)
        val sub = data.optJSONObject("getSubreddit") ?: return@withContext Pair(emptyList(), null)
        val children = sub.optJSONObject("children")
        val nextIterator = children?.optString("iterator")?.takeIf { it.isNotBlank() }
        val itemsArray = children?.optJSONArray("items") ?: JSONArray()

        val posts = mutableListOf<ScrolllerPost>()
        for (i in 0 until itemsArray.length()) {
            val itemObj = itemsArray.optJSONObject(i) ?: continue
            val post = parseScrolllerPost(itemObj)
            if (post != null) {
                posts.add(post)
            }
        }
        Pair(posts, nextIterator)
    }

    suspend fun searchSubreddits(
        query: String,
        isNsfw: Boolean = true,
        limit: Int = 30,
        pageIndex: Int = 0
    ): List<SubredditItem> = withContext(Dispatchers.Default) {
        val variables = JSONObject().apply {
            put("query", query.trim())
            put("limit", limit)
            put("isNsfw", isNsfw)
            put("pageIndex", pageIndex)
        }

        val data = executeGraphQL(SEARCH_SUBREDDITS_QUERY, variables)
        val items = data.optJSONArray("searchSubreddits") ?: return@withContext emptyList()
        val list = mutableListOf<SubredditItem>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val previews = mutableListOf<MediaSource>()
            val prevArray = obj.optJSONArray("preview_images")
            if (prevArray != null) {
                for (j in 0 until prevArray.length()) {
                    val p = prevArray.optJSONObject(j) ?: continue
                    previews.add(
                        MediaSource(
                            url = p.optString("url"),
                            width = p.optInt("width", 0),
                            height = p.optInt("height", 0)
                        )
                    )
                }
            }
            list.add(
                SubredditItem(
                    id = obj.optLong("id"),
                    title = obj.optString("title", obj.optString("url")),
                    url = obj.optString("url"),
                    description = obj.optString("description"),
                    isNsfw = obj.optBoolean("is_nsfw"),
                    itemCount = obj.optInt("item_count"),
                    previewImages = previews
                )
            )
        }
        list
    }

    suspend fun fetchCategories(contentFilter: ContentFilter = ContentFilter.ALL): List<String> = withContext(Dispatchers.Default) {
        try {
            when (contentFilter) {
                ContentFilter.SFW -> {
                    val data = executeGraphQL(GET_CATEGORIES_QUERY, JSONObject().apply { put("is_nsfw", false) })
                    parseCategoryTitles(data.optJSONArray("categories")).ifEmpty { defaultSfwCategories() }
                }
                ContentFilter.NSFW -> {
                    val data = executeGraphQL(GET_CATEGORIES_QUERY, JSONObject().apply { put("is_nsfw", true) })
                    parseCategoryTitles(data.optJSONArray("categories")).ifEmpty { defaultNsfwCategories() }
                }
                ContentFilter.ALL -> {
                    val combinedQuery = """
                        query GetAllCategories {
                            sfw: categories(data: { is_nsfw: false }) { title }
                            nsfw: categories(data: { is_nsfw: true }) { title }
                        }
                    """.trimIndent()
                    val data = executeGraphQL(combinedQuery, JSONObject())
                    val sfwList = parseCategoryTitles(data.optJSONArray("sfw"))
                    val nsfwList = parseCategoryTitles(data.optJSONArray("nsfw"))
                    val merged = (sfwList + nsfwList).distinct()
                    merged.ifEmpty { (defaultSfwCategories() + defaultNsfwCategories()).distinct() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load categories from API, using fallback", e)
            when (contentFilter) {
                ContentFilter.SFW -> defaultSfwCategories()
                ContentFilter.NSFW -> defaultNsfwCategories()
                ContentFilter.ALL -> (defaultSfwCategories() + defaultNsfwCategories()).distinct()
            }
        }
    }

    private fun parseCategoryTitles(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val title = array.optJSONObject(i)?.optString("title")?.trim()
            if (!title.isNullOrBlank()) {
                list.add(title)
            }
        }
        return list.distinct()
    }

    suspend fun fetchCategorySubreddits(categoryTitle: String): List<String> = withContext(Dispatchers.Default) {
        val slug = categorySlug(categoryTitle)
        val catData = executeGraphQL(GET_CATEGORY_QUERY, JSONObject().apply { put("url", slug) })
        val catId = catData.optJSONObject("getCategory")?.optInt("id") ?: return@withContext emptyList()

        val subData = executeGraphQL(GET_CATEGORY_SUBREDDITS_QUERY, JSONObject().apply { put("categoryId", catId) })
        val subs = subData.optJSONObject("getCategorySubreddits")?.optJSONArray("subreddits") ?: return@withContext emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until subs.length()) {
            val u = subs.optJSONObject(i)?.optString("subredditUrl")
            if (!u.isNullOrBlank()) {
                list.add(cleanSubredditUrl(u))
            }
        }
        list
    }

    fun cleanSubredditUrl(url: String): String {
        return url.trim()
            .removePrefix("https://scrolller.com/")
            .removePrefix("http://scrolller.com/")
            .removePrefix("/")
            .removePrefix("r/")
            .removeSuffix("/")
    }

    private fun categorySlug(title: String): String {
        return title.trim().lowercase()
            .replace("&", "and")
            .replace("[^a-z0-9]+".toRegex(), "-")
            .removePrefix("-")
            .removeSuffix("-")
    }

    private fun defaultSfwCategories(): List<String> {
        return listOf(
            "Animals", "Art", "Baby Animals", "Bears", "Birds",
            "Cats", "Dogs", "Food", "Imaginary", "Nature",
            "Photography", "Sfw Porn"
        )
    }

    private fun defaultNsfwCategories(): List<String> {
        return listOf(
            "Accessories", "Age", "Albums", "Alternative", "Anal", "Ass", "Asshole",
            "Attractive", "Back", "Balls", "Big Boobs", "Big Dick", "Bimbo", "Body Art",
            "Body Hair", "Body Parts", "Bodytype", "Boobs", "Celebs", "Clothing", "Comedy",
            "Condition", "Cum", "Drawing", "Ethnicity", "Evil Angel", "Exhibitionism",
            "Expression", "Eye Contact", "Eyes", "Face Fuck", "Fiction", "Food", "Foot Job",
            "Ftv Girls", "Group Sex", "Guns", "Hair", "Handjob", "Hardcore", "Hips",
            "Interactions", "Latina", "Legs", "Location", "Long Hair", "Make Up",
            "Masturbation", "Middle Eastern", "Misc", "Mound Of Venus", "Mouth", "Moving",
            "Nails", "Nipples", "Nose", "Number", "Nylon", "Object", "Object Insertion",
            "One Piece", "On Off", "Open Mouth", "Oral", "Orgasm", "Penis", "People",
            "Petite", "Porn", "Porn Studio", "Pose", "Position", "Production Style",
            "Profession", "Pubic Hair", "Pussy", "Relationship", "Religious", "Rimming",
            "Roleplay", "Rule 34", "Sex", "Sexual Orientation", "Sexy", "Short Hair",
            "Skin", "Sm", "Small Boobs", "Softcore", "Sport", "State Of Undress",
            "Swimsuit", "Theme", "Thigh", "Titfuck", "Tongue", "Toys",
            "Types Of Protection", "Underarm", "Waist", "Wet", "Wet Body", "X Art"
        )
    }

    private fun parseScrolllerPost(item: JSONObject): ScrolllerPost? {
        if (item.optBoolean("isPaid", false)) return null

        val id = item.optLong("id", -1L)
        if (id <= 0) return null

        val albumList = mutableListOf<String>()
        val albumContent = item.optJSONArray("albumContent")
        if (albumContent != null) {
            for (i in 0 until albumContent.length()) {
                val slide = albumContent.optJSONObject(i) ?: continue
                val slideSources = parseMediaSources(slide.optJSONArray("mediaSources"))
                val bestSlide = pickBestSource(slideSources)
                if (bestSlide != null && bestSlide.url.isNotBlank()) {
                    albumList.add(bestSlide.url)
                }
            }
        }

        val sources = parseMediaSources(item.optJSONArray("mediaSources"))
        val sortedSources = qualitySortedSources(sources)

        val videos = sortedSources.filter { isVideo(it.url) }
        val images = sortedSources.filter { !isVideo(it.url) }

        val bestVideo = videos.firstOrNull()
        val bestImage = images.firstOrNull()

        val mediaUrl = when {
            albumList.isNotEmpty() -> albumList.first()
            bestVideo != null -> bestVideo.url
            bestImage != null -> bestImage.url
            else -> sortedSources.firstOrNull()?.url
        } ?: return null

        val mediaType = when {
            albumList.size > 1 -> MediaType.ALBUM
            bestVideo != null -> MediaType.VIDEO
            isGif(mediaUrl) -> MediaType.GIF
            else -> MediaType.IMAGE
        }

        val posterUrl = bestImage?.url ?: albumList.firstOrNull().orEmpty()

        return ScrolllerPost(
            id = "scrolller:$id",
            postId = id,
            postUrl = item.optString("url"),
            title = item.optString("title", "Untitled").ifBlank { "Untitled" },
            subredditTitle = item.optString("subredditTitle").ifBlank { item.optString("subredditUrl", "Scrolller") },
            subredditUrl = cleanSubredditUrl(item.optString("subredditUrl")),
            mediaType = mediaType,
            mediaUrl = mediaUrl,
            posterUrl = posterUrl,
            album = albumList,
            sourceVariants = sortedSources,
            isNsfw = item.optBoolean("isNsfw", false),
            createdAt = item.optLong("createdAt", 0L),
            favoriteCount = item.optLong("favoriteCount", 0L),
            isFavorite = item.optBoolean("isFavorite", false),
            redditPath = item.optString("redditPath")
        )
    }

    private fun parseMediaSources(array: JSONArray?): List<MediaSource> {
        if (array == null) return emptyList()
        val list = mutableListOf<MediaSource>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val u = obj.optString("url")
            if (u.isNotBlank()) {
                list.add(
                    MediaSource(
                        url = u,
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0),
                        isOptimized = obj.optBoolean("isOptimized", true)
                    )
                )
            }
        }
        return list
    }

    private fun qualitySortedSources(sources: List<MediaSource>): List<MediaSource> {
        val seen = mutableSetOf<String>()
        return sources.filter { seen.add(it.url) }
            .sortedWith(
                compareByDescending<MediaSource> { it.pixelArea }
                    .thenByDescending { if (!it.isOptimized) 1 else 0 }
                    .thenByDescending { it.width }
                    .thenByDescending { it.height }
            )
    }

    private fun pickBestSource(sources: List<MediaSource>): MediaSource? {
        return qualitySortedSources(sources).firstOrNull()
    }

    private fun isVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") || lower.contains(".mp4?") ||
                lower.endsWith(".webm") || lower.contains(".webm?") ||
                lower.contains("format=mp4") || lower.contains("/video/")
    }

    private fun isGif(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".gif") || lower.contains(".gif?") ||
                lower.contains("format=gif") || lower.contains("/gif/")
    }
}
