package com.scrolller.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scrolller.app.ui.screens.FeedScreen
import com.scrolller.app.ui.screens.SavedBookmarksScreen
import com.scrolller.app.ui.theme.BlackBackground
import com.scrolller.app.ui.theme.ScrolllerTheme
import com.scrolller.app.ui.viewmodel.FeedViewModel

enum class AppDestination {
    FEED, BOOKMARKS
}

class MainActivity : ComponentActivity() {

    private val feedViewModel: FeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ScrolllerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BlackBackground
                ) {
                    val uiState by feedViewModel.uiState.collectAsStateWithLifecycle()
                    val favoritePosts by feedViewModel.favoritePosts.collectAsStateWithLifecycle()
                    val recentPosts by feedViewModel.recentPosts.collectAsStateWithLifecycle()
                    val cachedSubreddits by feedViewModel.cachedSubreddits.collectAsStateWithLifecycle()
                    val totalCachedPostCount by feedViewModel.totalCachedPostCount.collectAsStateWithLifecycle()

                    var currentDestination by remember { mutableStateOf(AppDestination.FEED) }

                    when (currentDestination) {
                        AppDestination.FEED -> {
                            FeedScreen(
                                viewModel = feedViewModel,
                                uiState = uiState,
                                onOpenBookmarks = {
                                    currentDestination = AppDestination.BOOKMARKS
                                }
                            )
                        }

                        AppDestination.BOOKMARKS -> {
                            SavedBookmarksScreen(
                                favoritePosts = favoritePosts,
                                recentPosts = recentPosts,
                                cachedSubreddits = cachedSubreddits,
                                totalCachedPostCount = totalCachedPostCount,
                                onSelectPost = { _, sourceMode ->
                                    feedViewModel.loadFeed(sourceMode)
                                    currentDestination = AppDestination.FEED
                                },
                                onSelectSubreddit = { sub ->
                                    feedViewModel.loadFeed(sub)
                                    currentDestination = AppDestination.FEED
                                },
                                onDownloadSubredditOffline = { sub ->
                                    feedViewModel.downloadSubredditForOffline(sub)
                                },
                                onTogglePinSubreddit = { sub ->
                                    feedViewModel.toggleSaveSubreddit(sub)
                                },
                                onRemoveFavorite = { post ->
                                    feedViewModel.toggleFavorite(post)
                                },
                                onClearRecentHistory = {
                                    feedViewModel.clearRecentHistory()
                                },
                                onClearOfflineCache = {
                                    feedViewModel.clearOfflineFeedCache()
                                },
                                onBack = {
                                    currentDestination = AppDestination.FEED
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
