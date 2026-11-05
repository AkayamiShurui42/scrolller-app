package com.scrolller.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.scrolller.app.data.local.CachedSubredditEntity
import com.scrolller.app.data.model.ScrolllerPost
import com.scrolller.app.ui.theme.BlackBackground
import com.scrolller.app.ui.theme.DarkSurface
import com.scrolller.app.ui.theme.DarkSurfaceVariant
import com.scrolller.app.ui.theme.ErrorRed
import com.scrolller.app.ui.theme.GoldAccent
import com.scrolller.app.ui.theme.SfwBadgeGreen
import com.scrolller.app.ui.theme.TextPrimary
import com.scrolller.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedBookmarksScreen(
    favoritePosts: List<ScrolllerPost>,
    recentPosts: List<ScrolllerPost>,
    cachedSubreddits: List<CachedSubredditEntity>,
    totalCachedPostCount: Int,
    onSelectPost: (ScrolllerPost, String) -> Unit,
    onSelectSubreddit: (String) -> Unit,
    onDownloadSubredditOffline: (String) -> Unit,
    onTogglePinSubreddit: (String) -> Unit,
    onRemoveFavorite: (ScrolllerPost) -> Unit,
    onClearRecentHistory: () -> Unit,
    onClearOfflineCache: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler {
        onBack()
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Saved, 1: Cached Subreddits, 2: Recently Viewed

    Scaffold(
        containerColor = BlackBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Offline & Saved Library",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "$totalCachedPostCount feed posts cached in Room DB",
                            color = GoldAccent,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("bookmarks_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    if (selectedTab == 2 && recentPosts.isNotEmpty()) {
                        TextButton(onClick = onClearRecentHistory) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", color = ErrorRed, fontSize = 12.sp)
                        }
                    } else if (selectedTab == 1 && totalCachedPostCount > 0) {
                        TextButton(onClick = onClearOfflineCache) {
                            Text("Clear Cache", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = GoldAccent,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = GoldAccent
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "Saved (${favoritePosts.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Subreddits (${cachedSubreddits.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            text = "Recent (${recentPosts.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                )
            }

            when (selectedTab) {
                0 -> {
                    // Saved Bookmarks Grid
                    if (favoritePosts.isEmpty()) {
                        EmptyOfflineSection(
                            icon = Icons.Default.Favorite,
                            title = "No saved bookmarks yet",
                            subtitle = "Tap the heart button on any post to save it into your local Room database for offline viewing."
                        )
                    } else {
                        PostGridList(
                            posts = favoritePosts,
                            sourceMode = "Saved",
                            showDeleteButton = true,
                            onSelectPost = onSelectPost,
                            onDeletePost = onRemoveFavorite
                        )
                    }
                }

                1 -> {
                    // Cached Subreddits List
                    if (cachedSubreddits.isEmpty()) {
                        EmptyOfflineSection(
                            icon = Icons.Default.Storage,
                            title = "No cached subreddits yet",
                            subtitle = "Browse any subreddit or tap the cloud download button to cache full subreddit feeds for offline access."
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(cachedSubreddits, key = { it.subredditUrl }) { sub ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(DarkSurfaceVariant)
                                        .clickable { onSelectSubreddit(sub.subredditUrl) }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (sub.previewImageUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = sub.previewImageUrl,
                                            contentDescription = sub.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(50.dp)
                                                .clip(CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "r/${sub.subredditUrl}",
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                            if (sub.isFullyCached) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = SfwBadgeGreen.copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = "OFFLINE READY",
                                                        color = SfwBadgeGreen,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "${sub.cachedPostCount} posts cached in local database",
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }

                                    IconButton(
                                        onClick = { onTogglePinSubreddit(sub.subredditUrl) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PushPin,
                                            contentDescription = if (sub.isPinned) "Unpin Subreddit" else "Pin Subreddit",
                                            tint = if (sub.isPinned) GoldAccent else TextSecondary
                                        )
                                    }

                                    IconButton(
                                        onClick = { onDownloadSubredditOffline(sub.subredditUrl) }
                                    ) {
                                        Icon(
                                            imageVector = if (sub.isFullyCached) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                                            contentDescription = "Download Full Subreddit for Offline",
                                            tint = if (sub.isFullyCached) SfwBadgeGreen else GoldAccent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // Recently Viewed Posts Grid
                    if (recentPosts.isEmpty()) {
                        EmptyOfflineSection(
                            icon = Icons.Default.History,
                            title = "No recently viewed posts yet",
                            subtitle = "As you scroll through the feed, items you view are automatically cached here for instant offline access."
                        )
                    } else {
                        PostGridList(
                            posts = recentPosts,
                            sourceMode = "Recent",
                            showDeleteButton = false,
                            onSelectPost = onSelectPost,
                            onDeletePost = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PostGridList(
    posts: List<ScrolllerPost>,
    sourceMode: String,
    showDeleteButton: Boolean,
    onSelectPost: (ScrolllerPost, String) -> Unit,
    onDeletePost: (ScrolllerPost) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(posts, key = { "${sourceMode}_${it.postId}" }) { post ->
            val thumbUrl = post.posterUrl.ifBlank { post.mediaUrl }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSurfaceVariant)
                    .clickable { onSelectPost(post, sourceMode) }
            ) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = post.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (showDeleteButton) {
                    IconButton(
                        onClick = { onDeletePost(post) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = Color.White,
                            modifier = Modifier.height(18.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(8.dp)
                ) {
                    Text(
                        text = post.title,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "r/${post.subredditTitle}",
                        color = GoldAccent,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyOfflineSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}
