package com.scrolller.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scrolller.app.data.api.ScrolllerApiClient
import com.scrolller.app.data.local.CachedSubredditEntity
import com.scrolller.app.data.local.ScrolllerDatabase
import com.scrolller.app.data.model.ContentFilter
import com.scrolller.app.data.model.FeedFilter
import com.scrolller.app.data.model.GallerySort
import com.scrolller.app.data.model.MediaFilter
import com.scrolller.app.data.model.ScrolllerPost
import com.scrolller.app.data.model.SearchContextMode
import com.scrolller.app.data.model.SourceOriginFilter
import com.scrolller.app.data.model.SubredditItem
import com.scrolller.app.data.model.UserAccount
import com.scrolller.app.data.model.UserCollection
import com.scrolller.app.data.model.ViewMode
import com.scrolller.app.data.repository.AuthRepository
import com.scrolller.app.data.repository.BookmarkRepository
import com.scrolller.app.data.repository.ScrolllerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedUiState(
    val source: String = "Feed",
    val title: String = "Scrolller",
    val filter: FeedFilter = FeedFilter(),
    val searchContextMode: SearchContextMode = SearchContextMode.ALL_SCROLLLER,
    val posts: List<ScrolllerPost> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val viewMode: ViewMode = ViewMode.PAGER,
    val gridColumns: Int = 2,
    val isMuted: Boolean = true,
    val availableCategories: List<String> = emptyList(),
    val isSearchingSubreddits: Boolean = false,
    val subredditSearchResults: List<SubredditItem> = emptyList(),
    val mediaUrlOverrides: Map<Long, String> = emptyMap(),
    val activePageIndex: Int = 0,
    val isFromOfflineCache: Boolean = false,
    val isOfflineModeEnabled: Boolean = false,
    val isDownloadingSubreddit: Boolean = false,
    val statusMessage: String? = null,
    val isLoggingIn: Boolean = false,
    val loginError: String? = null
)

class FeedViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ScrolllerDatabase.getInstance(application)
    private val dao = database.scrolllerDao()
    private val apiClient = ScrolllerApiClient()
    private val repository = ScrolllerRepository(apiClient, dao)
    private val bookmarkRepository = BookmarkRepository(application, dao)
    private val authRepository = AuthRepository(application, apiClient)
    private val prefs = application.getSharedPreferences("scrolller_feed_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    val favoritePosts: StateFlow<List<ScrolllerPost>> = bookmarkRepository.favoritePosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedSubreddits: StateFlow<Set<String>> = bookmarkRepository.savedSubreddits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val cachedSubreddits: StateFlow<List<CachedSubredditEntity>> = bookmarkRepository.cachedSubreddits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentPosts: StateFlow<List<ScrolllerPost>> = bookmarkRepository.recentPosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalCachedPostCount: StateFlow<Int> = bookmarkRepository.totalCachedPostCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val currentUser: StateFlow<UserAccount?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val userCollections: StateFlow<List<UserCollection>> = authRepository.userCollections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val historyStack = mutableListOf<Pair<String, FeedFilter>>()
    private var nextIterator: String? = null
    private var currentRawCorpus: List<ScrolllerPost> = emptyList()

    init {
        val lastSource = prefs.getString("last_viewed_source", "Feed") ?: "Feed"
        val savedSort = runCatching {
            GallerySort.valueOf(prefs.getString("filter_sort", "HOT") ?: "HOT")
        }.getOrDefault(GallerySort.HOT)
        val savedMedia = runCatching {
            MediaFilter.valueOf(prefs.getString("filter_media", "ALL") ?: "ALL")
        }.getOrDefault(MediaFilter.ALL)
        val savedContent = runCatching {
            ContentFilter.valueOf(prefs.getString("filter_content", "ALL") ?: "ALL")
        }.getOrDefault(ContentFilter.ALL)
        val savedOrigin = runCatching {
            SourceOriginFilter.valueOf(prefs.getString("filter_origin", "ALL") ?: "ALL")
        }.getOrDefault(SourceOriginFilter.ALL)
        val savedCategory = prefs.getString("filter_category", "ALL") ?: "ALL"
        val savedHeldSubs = prefs.getStringSet("filter_held_subreddits", emptySet()) ?: emptySet()
        val savedCols = prefs.getInt("grid_columns", 2).coerceIn(1, 5)

        val initialFilter = FeedFilter(
            sort = savedSort,
            mediaFilter = savedMedia,
            contentFilter = savedContent,
            sourceOrigin = savedOrigin,
            category = savedCategory,
            selectedSubreddits = savedHeldSubs
        )

        _uiState.value = _uiState.value.copy(
            filter = initialFilter,
            gridColumns = savedCols
        )

        loadCategoriesForContentFilter(initialFilter.contentFilter)
        searchSubreddits("")
        loadFeed(lastSource, initialFilter, recordHistory = false)

        viewModelScope.launch {
            authRepository.verifyAndRefreshSession()
        }
    }

    private fun saveFilterPrefs(filter: FeedFilter) {
        prefs.edit()
            .putString("filter_sort", filter.sort.name)
            .putString("filter_media", filter.mediaFilter.name)
            .putString("filter_content", filter.contentFilter.name)
            .putString("filter_origin", filter.sourceOrigin.name)
            .putString("filter_category", filter.category)
            .putStringSet("filter_held_subreddits", filter.selectedSubreddits)
            .apply()
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun loadCategoriesForContentFilter(contentFilter: ContentFilter = _uiState.value.filter.contentFilter) {
        viewModelScope.launch {
            try {
                val cats = repository.getCategories(contentFilter)
                _uiState.value = _uiState.value.copy(availableCategories = cats)
            } catch (e: Exception) {
                // Fallback handled in API client
            }
        }
    }

    private fun formatTitleForSource(source: String, filter: FeedFilter): String {
        return when {
            source.equals("Recent", ignoreCase = true) -> "Recently Viewed"
            source.equals("Saved", ignoreCase = true) -> "Saved Posts"
            source.equals("OfflineAll", ignoreCase = true) -> "Offline Cache"
            source.startsWith("Category:", ignoreCase = true) -> source.removePrefix("Category:").trim()
            source.startsWith("Collection:", ignoreCase = true) -> {
                val parts = source.split(":", limit = 3)
                parts.getOrNull(2)?.ifBlank { "My Collection" } ?: "My Collection"
            }
            source.startsWith("Search:", ignoreCase = true) -> source
            filter.selectedSubreddits.isNotEmpty() && (source.equals("Feed", ignoreCase = true) || source.isBlank()) -> {
                if (filter.selectedSubreddits.size == 1) {
                    "r/${filter.selectedSubreddits.first()}"
                } else {
                    "${filter.selectedSubreddits.size} Subreddits Held"
                }
            }
            filter.category != "ALL" && (source.equals("Feed", ignoreCase = true) || source.isBlank()) -> {
                filter.category
            }
            source.equals("Feed", ignoreCase = true) || source.isBlank() -> "Scrolller"
            else -> "r/${source.removePrefix("r/").removePrefix("/")}"
        }
    }

    fun loadFeed(
        source: String,
        filter: FeedFilter = _uiState.value.filter,
        recordHistory: Boolean = true
    ) {
        viewModelScope.launch {
            if (recordHistory && (source != _uiState.value.source || filter.category != _uiState.value.filter.category)) {
                historyStack.add(Pair(_uiState.value.source, _uiState.value.filter))
                if (historyStack.size > 25) historyStack.removeAt(0)
            }

            // If user explicitly navigates to a specific Category:<cat>, sync filter.category
            val syncedFilter = when {
                source.startsWith("Category:", ignoreCase = true) -> {
                    val cat = source.removePrefix("Category:").trim()
                    filter.copy(category = cat)
                }
                !source.equals("Feed", ignoreCase = true) && !source.startsWith("Search:", ignoreCase = true) -> {
                    // When opening a specific subreddit directly, keep held subreddits if the new sub is in them, otherwise clear category override
                    filter.copy(category = "ALL")
                }
                else -> filter
            }

            saveFilterPrefs(syncedFilter)

            if (!source.startsWith("Search:", ignoreCase = true)) {
                prefs.edit().putString("last_viewed_source", source).apply()
            }

            val title = formatTitleForSource(source, syncedFilter)
            val forceOffline = _uiState.value.isOfflineModeEnabled || !isNetworkConnected()

            _uiState.value = _uiState.value.copy(
                source = source,
                title = title,
                filter = syncedFilter,
                isLoading = true,
                errorMessage = null,
                activePageIndex = 0
            )

            try {
                val result = repository.getFeed(
                    source = source,
                    filter = syncedFilter,
                    iterator = null,
                    forceOffline = forceOffline
                )
                nextIterator = result.nextIterator
                val savedIds = bookmarkRepository.getSavedPostIds()
                val updatedItems = result.posts.map { post ->
                    post.copy(isFavorite = savedIds.contains(post.postId))
                }
                currentRawCorpus = updatedItems

                updatedItems.firstOrNull()?.let { firstPost ->
                    bookmarkRepository.recordPostViewed(firstPost)
                }

                _uiState.value = _uiState.value.copy(
                    posts = updatedItems,
                    isLoading = false,
                    isFromOfflineCache = result.isFromOfflineCache
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Failed to load posts"
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val forceOffline = _uiState.value.isOfflineModeEnabled || !isNetworkConnected()
            try {
                val result = repository.getFeed(
                    source = _uiState.value.source,
                    filter = _uiState.value.filter,
                    iterator = null,
                    forceOffline = forceOffline
                )
                nextIterator = result.nextIterator
                val savedIds = bookmarkRepository.getSavedPostIds()
                val updatedItems = result.posts.map { post ->
                    post.copy(isFavorite = savedIds.contains(post.postId))
                }
                currentRawCorpus = updatedItems
                _uiState.value = _uiState.value.copy(
                    posts = updatedItems,
                    isRefreshing = false,
                    isFromOfflineCache = result.isFromOfflineCache,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoading || nextIterator == null || _uiState.value.isFromOfflineCache) return
        viewModelScope.launch {
            try {
                val result = repository.getFeed(
                    source = _uiState.value.source,
                    filter = _uiState.value.filter,
                    iterator = nextIterator,
                    forceOffline = false
                )
                nextIterator = result.nextIterator
                val savedIds = bookmarkRepository.getSavedPostIds()
                val updated = result.posts.map { post ->
                    post.copy(isFavorite = savedIds.contains(post.postId))
                }
                val combined = (_uiState.value.posts + updated).distinctBy { it.postId }
                currentRawCorpus = combined
                _uiState.value = _uiState.value.copy(posts = combined)
            } catch (e: Exception) {
                // Ignore pagination failure seamlessly
            }
        }
    }

    fun setSearchContextMode(mode: SearchContextMode) {
        _uiState.value = _uiState.value.copy(searchContextMode = mode)
    }

    fun searchPosts(query: String, contextMode: SearchContextMode = _uiState.value.searchContextMode) {
        viewModelScope.launch {
            historyStack.add(Pair(_uiState.value.source, _uiState.value.filter))
            val forceOffline = _uiState.value.isOfflineModeEnabled || !isNetworkConnected()
            val labelPrefix = if (contextMode == SearchContextMode.CURRENT_FEED) {
                "${_uiState.value.title}: $query"
            } else {
                "Search: $query"
            }
            _uiState.value = _uiState.value.copy(
                source = "Search: $query",
                title = labelPrefix,
                searchContextMode = contextMode,
                isLoading = true,
                errorMessage = null,
                activePageIndex = 0
            )

            try {
                val result = repository.searchPosts(
                    query = query,
                    filter = _uiState.value.filter,
                    searchContext = contextMode,
                    currentCorpus = currentRawCorpus,
                    forceOffline = forceOffline
                )
                val savedIds = bookmarkRepository.getSavedPostIds()
                val updated = result.posts.map { post ->
                    post.copy(isFavorite = savedIds.contains(post.postId))
                }
                _uiState.value = _uiState.value.copy(
                    posts = updated,
                    isLoading = false,
                    isFromOfflineCache = result.isFromOfflineCache
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Search failed"
                )
            }
        }
    }

    fun searchSubreddits(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearchingSubreddits = true)
            try {
                val isNsfw = _uiState.value.filter.contentFilter != ContentFilter.SFW
                val results = repository.searchSubreddits(query, isNsfw)
                _uiState.value = _uiState.value.copy(
                    subredditSearchResults = results,
                    isSearchingSubreddits = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    subredditSearchResults = emptyList(),
                    isSearchingSubreddits = false
                )
            }
        }
    }

    // --- Quick Media & Subreddit Context Switchers ---

    fun toggleHeldSubredditFilter(subreddit: String) {
        val clean = apiClient.cleanSubredditUrl(subreddit).lowercase()
        if (clean.isBlank()) return
        val currentSet = _uiState.value.filter.selectedSubreddits
        val nextSet = if (currentSet.contains(clean)) {
            currentSet - clean
        } else {
            currentSet + clean
        }
        val newFilter = _uiState.value.filter.copy(selectedSubreddits = nextSet)
        val msg = if (nextSet.contains(clean)) {
            "Held r/$clean in Subreddit Filter (${nextSet.size} active)"
        } else {
            "Released r/$clean from Subreddit Filter"
        }
        _uiState.value = _uiState.value.copy(statusMessage = msg)
        // Also pin to saved subreddits when held
        if (nextSet.contains(clean) && !savedSubreddits.value.contains(clean)) {
            viewModelScope.launch { bookmarkRepository.toggleSubredditSaved(clean) }
        }
        loadFeed(
            source = if (nextSet.isNotEmpty()) "Feed" else _uiState.value.source,
            filter = newFilter,
            recordHistory = false
        )
    }

    fun clearHeldSubredditFilters() {
        val newFilter = _uiState.value.filter.copy(selectedSubreddits = emptySet())
        _uiState.value = _uiState.value.copy(statusMessage = "Cleared held subreddit filters")
        loadFeed("Feed", newFilter, recordHistory = false)
    }

    fun updateMediaFilter(mediaFilter: MediaFilter) {
        val newFilter = _uiState.value.filter.copy(mediaFilter = mediaFilter)
        loadFeed(_uiState.value.source, newFilter, recordHistory = false)
    }

    fun updateContentFilter(contentFilter: ContentFilter) {
        val newFilter = _uiState.value.filter.copy(contentFilter = contentFilter)
        loadCategoriesForContentFilter(contentFilter)
        loadFeed(_uiState.value.source, newFilter, recordHistory = false)
    }

    fun updateSourceOrigin(origin: SourceOriginFilter) {
        val newFilter = _uiState.value.filter.copy(sourceOrigin = origin)
        loadFeed(_uiState.value.source, newFilter, recordHistory = false)
    }

    fun setGridColumns(columns: Int) {
        val clamped = columns.coerceIn(1, 5)
        prefs.edit().putInt("grid_columns", clamped).apply()
        _uiState.value = _uiState.value.copy(gridColumns = clamped)
    }

    fun downloadSubredditForOffline(subredditUrl: String) {
        val targetSub = subredditUrl.removePrefix("r/").removePrefix("/").trim()
        if (targetSub.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloadingSubreddit = true,
                statusMessage = "Caching r/$targetSub for offline viewing…"
            )
            try {
                val count = repository.downloadFullSubredditForOffline(targetSub, _uiState.value.filter.sort)
                _uiState.value = _uiState.value.copy(
                    isDownloadingSubreddit = false,
                    statusMessage = if (count > 0) "Cached $count posts from r/$targetSub for offline use" else "No posts found to cache"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDownloadingSubreddit = false,
                    statusMessage = "Offline cache failed: ${e.message ?: "Check connection"}"
                )
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    fun toggleOfflineMode() {
        val next = !_uiState.value.isOfflineModeEnabled
        _uiState.value = _uiState.value.copy(
            isOfflineModeEnabled = next,
            statusMessage = if (next) "Offline Mode enabled (browsing local Room cache)" else "Online Mode enabled"
        )
        loadFeed(_uiState.value.source, _uiState.value.filter, recordHistory = false)
    }

    fun toggleFavorite(post: ScrolllerPost) {
        viewModelScope.launch {
            val isNowFavorite = bookmarkRepository.togglePostFavorite(post)
            val currentPosts = _uiState.value.posts.map {
                if (it.postId == post.postId) it.copy(isFavorite = isNowFavorite) else it
            }
            _uiState.value = _uiState.value.copy(posts = currentPosts)
            apiClient.syncRemoteFavorite(post.postUrl, isNowFavorite)
        }
    }

    fun toggleSaveSubreddit(subName: String) {
        viewModelScope.launch {
            val isSaved = bookmarkRepository.toggleSubredditSaved(subName)
            val clean = subName.removePrefix("r/").trim()
            _uiState.value = _uiState.value.copy(
                statusMessage = if (isSaved) "Pinned r/$clean to saved subreddits" else "Unpinned r/$clean"
            )
        }
    }

    fun clearRecentHistory() {
        viewModelScope.launch {
            bookmarkRepository.clearRecentHistory()
            _uiState.value = _uiState.value.copy(statusMessage = "Cleared recently viewed history")
            if (_uiState.value.source.equals("Recent", ignoreCase = true)) {
                loadFeed("Recent", recordHistory = false)
            }
        }
    }

    fun clearOfflineFeedCache() {
        viewModelScope.launch {
            bookmarkRepository.clearFeedCache()
            _uiState.value = _uiState.value.copy(statusMessage = "Cleared cached feed items")
        }
    }

    // --- Authentication Actions ---

    fun loginWithCredentials(usernameOrEmail: String, password: String, onSuccess: () -> Unit = {}) {
        if (usernameOrEmail.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(loginError = "Please enter both username/email and password")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingIn = true, loginError = null)
            try {
                val account = authRepository.loginWithCredentials(usernameOrEmail, password)
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    loginError = null,
                    statusMessage = "Signed in as ${account.username}"
                )
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    loginError = e.message ?: "Sign-in failed"
                )
            }
        }
    }

    fun completeWebSignIn(extractedToken: String?, fallbackUsername: String?, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingIn = true, loginError = null)
            try {
                val account = authRepository.completeWebSignIn(extractedToken, fallbackUsername)
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    loginError = null,
                    statusMessage = "Signed in as ${account.username}"
                )
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    loginError = e.message ?: "Web sign-in verification failed"
                )
            }
        }
    }

    fun refreshUserCollections() {
        viewModelScope.launch {
            authRepository.refreshUserCollections()
        }
    }

    fun signOut() {
        authRepository.signOut()
        _uiState.value = _uiState.value.copy(
            statusMessage = "Signed out of Scrolller",
            loginError = null
        )
    }

    fun clearLoginError() {
        _uiState.value = _uiState.value.copy(loginError = null)
    }

    // --- UI Controls ---

    fun toggleMute() {
        _uiState.value = _uiState.value.copy(isMuted = !_uiState.value.isMuted)
    }

    fun toggleViewMode() {
        val next = if (_uiState.value.viewMode == ViewMode.PAGER) ViewMode.GRID else ViewMode.PAGER
        _uiState.value = _uiState.value.copy(viewMode = next)
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    fun selectSourceVariant(postId: Long, newUrl: String) {
        val map = _uiState.value.mediaUrlOverrides.toMutableMap()
        map[postId] = newUrl
        _uiState.value = _uiState.value.copy(mediaUrlOverrides = map)
    }

    fun setActivePageIndex(index: Int) {
        _uiState.value = _uiState.value.copy(activePageIndex = index)
        val viewedPost = _uiState.value.posts.getOrNull(index)
        if (viewedPost != null) {
            viewModelScope.launch {
                bookmarkRepository.recordPostViewed(viewedPost)
            }
        }
    }

    fun applyFilter(newFilter: FeedFilter) {
        if (newFilter.contentFilter != _uiState.value.filter.contentFilter) {
            loadCategoriesForContentFilter(newFilter.contentFilter)
        }
        val nextSource = if (newFilter.category != "ALL" && _uiState.value.source.startsWith("Category:", ignoreCase = true)) {
            "Category:${newFilter.category}"
        } else if (newFilter.category == "ALL" && _uiState.value.source.startsWith("Category:", ignoreCase = true)) {
            "Feed"
        } else {
            _uiState.value.source
        }
        loadFeed(nextSource, newFilter, recordHistory = false)
    }

    fun canNavigateBack(): Boolean = historyStack.isNotEmpty()

    fun navigateBack(): Boolean {
        if (historyStack.isEmpty()) return false
        val previous = historyStack.removeAt(historyStack.lastIndex)
        loadFeed(previous.first, previous.second, recordHistory = false)
        return true
    }
}
