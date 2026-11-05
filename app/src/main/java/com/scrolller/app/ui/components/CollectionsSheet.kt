package com.scrolller.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scrolller.app.data.local.CachedSubredditEntity
import com.scrolller.app.data.model.UserCollection
import com.scrolller.app.ui.theme.BlackBackground
import com.scrolller.app.ui.theme.DarkSurface
import com.scrolller.app.ui.theme.DarkSurfaceVariant
import com.scrolller.app.ui.theme.GoldAccent
import com.scrolller.app.ui.theme.SfwBadgeGreen
import com.scrolller.app.ui.theme.TextPrimary
import com.scrolller.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionsSheet(
    sheetState: SheetState,
    categories: List<String>,
    savedSubreddits: Set<String>,
    cachedSubreddits: List<CachedSubredditEntity>,
    userCollections: List<UserCollection>,
    totalCachedPostCount: Int,
    recentPostCount: Int,
    savedPostCount: Int,
    isOfflineModeEnabled: Boolean,
    onToggleOfflineMode: () -> Unit,
    onSelectSource: (String) -> Unit,
    onDownloadSubredditOffline: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        dragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Collections & Offline Cache",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Offline Mode Switch Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isOfflineModeEnabled) Icons.Default.CloudOff else Icons.Default.Storage,
                        contentDescription = null,
                        tint = GoldAccent
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Offline Cache Mode",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "$totalCachedPostCount cached items in local Room DB",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                Switch(
                    checked = isOfflineModeEnabled,
                    onCheckedChange = { onToggleOfflineMode() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BlackBackground,
                        checkedTrackColor = GoldAccent
                    ),
                    modifier = Modifier.testTag("offline_mode_switch")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Default Main Feed Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GoldAccent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .clickable {
                        onSelectSource("Feed")
                        onDismiss()
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.Folder, contentDescription = null, tint = GoldAccent)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "All Feed (Popular Aggregated)",
                        color = GoldAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Curated top subreddits mixed into one feed (auto-cached)",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Offline Feeds Row: Recently Viewed, Saved Posts, All Offline Cache
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .clickable {
                            onSelectSource("Recent")
                            onDismiss()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = GoldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Recent",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "$recentPostCount viewed",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .clickable {
                            onSelectSource("Saved")
                            onDismiss()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = GoldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Saved",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "$savedPostCount items",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // User Account Collections (if signed in)
            if (userCollections.isNotEmpty()) {
                Text(
                    text = "MY SCROLLLER COLLECTIONS",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    userCollections.forEach { col ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onSelectSource("Collection:${col.id}:${col.title}")
                                onDismiss()
                            },
                            label = { Text(col.title) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = DarkSurfaceVariant,
                                labelColor = GoldAccent
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }

            // Saved & Offline Cached Subreddits
            val displaySubreddits = if (cachedSubreddits.isNotEmpty()) {
                cachedSubreddits
            } else {
                savedSubreddits.map { CachedSubredditEntity(subredditUrl = it, title = it, isPinned = true) }
            }

            if (displaySubreddits.isNotEmpty()) {
                Text(
                    text = "SAVED & CACHED SUBREDDITS (TAP CLOUD TO CACHE FULL SUBREDDIT)",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    displaySubreddits.take(15).forEach { subEntity ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                                .clickable {
                                    onSelectSource(subEntity.subredditUrl)
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "r/${subEntity.subredditUrl}",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    if (subEntity.isFullyCached) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "OFFLINE READY",
                                            color = SfwBadgeGreen,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = if (subEntity.cachedPostCount > 0) {
                                        "${subEntity.cachedPostCount} posts cached in Room DB"
                                    } else {
                                        "Tap to browse or download for offline"
                                    },
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            IconButton(
                                onClick = { onDownloadSubredditOffline(subEntity.subredditUrl) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (subEntity.isFullyCached) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                                    contentDescription = "Cache Full Subreddit Offline",
                                    tint = if (subEntity.isFullyCached) SfwBadgeGreen else GoldAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Categories Section
            Text(
                text = "WEBSITE CATEGORIES",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            onSelectSource("Category:$cat")
                            onDismiss()
                        },
                        label = { Text(cat) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
