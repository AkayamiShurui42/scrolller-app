package com.scrolller.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.scrolller.app.data.model.ContentFilter
import com.scrolller.app.data.model.FeedFilter
import com.scrolller.app.data.model.MediaFilter
import com.scrolller.app.data.model.SearchContextMode
import com.scrolller.app.data.model.SubredditItem
import com.scrolller.app.ui.theme.BlackBackground
import com.scrolller.app.ui.theme.DarkSurface
import com.scrolller.app.ui.theme.DarkSurfaceVariant
import com.scrolller.app.ui.theme.GoldAccent
import com.scrolller.app.ui.theme.NsfwBadgeRed
import com.scrolller.app.ui.theme.SfwBadgeGreen
import com.scrolller.app.ui.theme.TextPrimary
import com.scrolller.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchSheet(
    sheetState: SheetState,
    currentSourceTitle: String,
    currentFilter: FeedFilter,
    searchContextMode: SearchContextMode,
    isSearching: Boolean,
    subredditResults: List<SubredditItem>,
    onSearchContextChange: (SearchContextMode) -> Unit,
    onUpdateMediaFilter: (MediaFilter) -> Unit,
    onUpdateContentFilter: (ContentFilter) -> Unit,
    onToggleHeldSubredditFilter: (String) -> Unit,
    onSearchPosts: (String, SearchContextMode) -> Unit,
    onSearchSubreddits: (String) -> Unit,
    onSelectSubreddit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Subreddits & Posts, 1: Subreddits Only
    val keyboardController = LocalSoftwareKeyboardController.current

    // Always load subreddit list immediately when opened and debounce as user types!
    LaunchedEffect(query, currentFilter.contentFilter) {
        if (query.isBlank()) {
            onSearchSubreddits("")
        } else {
            delay(220)
            onSearchSubreddits(query)
        }
    }

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
                .padding(horizontal = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Search & Subreddit Directory",
                    color = TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Scope Switch: Posts + Subreddits vs Subreddit Directory
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurfaceVariant,
                contentColor = GoldAccent,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = GoldAccent
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Posts & Subreddits", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Subreddit List (${subredditResults.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Context Row ("This Collection" vs "All Scrolller") + Content Context
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchContextMode.entries.forEach { mode ->
                    val label = if (mode == SearchContextMode.CURRENT_FEED) {
                        "In: $currentSourceTitle"
                    } else {
                        "All Scrolller"
                    }
                    FilterChip(
                        selected = searchContextMode == mode,
                        onClick = { onSearchContextChange(mode) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldAccent,
                            selectedLabelColor = BlackBackground,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextPrimary
                        )
                    )
                }

                ContentFilter.entries.forEach { cf ->
                    FilterChip(
                        selected = currentFilter.contentFilter == cf,
                        onClick = { onUpdateContentFilter(cf) },
                        label = { Text(cf.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldAccent.copy(alpha = 0.25f),
                            selectedLabelColor = GoldAccent,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextSecondary
                        )
                    )
                }
            }

            // Media Type Context Row inside Search
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MediaFilter.entries.forEach { mf ->
                    FilterChip(
                        selected = currentFilter.mediaFilter == mf,
                        onClick = { onUpdateMediaFilter(mf) },
                        label = { Text(mf.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldAccent,
                            selectedLabelColor = BlackBackground,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search Input + Go Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("search_input"),
                    placeholder = {
                        Text(
                            "Search posts or subreddits (e.g. pics, art)…",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = GoldAccent)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GoldAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboardController?.hide()
                            if (query.isNotBlank()) {
                                if (selectedTab == 0) {
                                    onSearchPosts(query, searchContextMode)
                                    onDismiss()
                                } else {
                                    onSearchSubreddits(query)
                                }
                            }
                        }
                    )
                )

                Button(
                    onClick = {
                        keyboardController?.hide()
                        if (query.isNotBlank()) {
                            onSearchPosts(query, searchContextMode)
                            onDismiss()
                        }
                    },
                    enabled = query.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldAccent,
                        contentColor = BlackBackground
                    ),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("search_submit_button")
                ) {
                    Text("Search", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Held Subreddit Filters Strip (if any subreddits are held down)
            if (currentFilter.selectedSubreddits.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Held Filters:",
                        color = GoldAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    currentFilter.selectedSubreddits.forEach { heldSub ->
                        Surface(
                            onClick = { onToggleHeldSubredditFilter(heldSub) },
                            shape = RoundedCornerShape(10.dp),
                            color = GoldAccent.copy(alpha = 0.2f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "r/$heldSub ×",
                                    color = GoldAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Subreddit List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (query.isBlank()) "SUBREDDIT LIST (TAP TO OPEN • HOLD/FILTER TO LOCK)" else "MATCHING SUBREDDITS (${subredditResults.size})",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isSearching) {
                    CircularProgressIndicator(
                        color = GoldAccent,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live Subreddit List (Always visible on both tabs!)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .testTag("search_subreddit_list")
            ) {
                if (subredditResults.isEmpty() && !isSearching) {
                    item {
                        Text(
                            text = "No subreddits matched \"$query\". Tap Search to search posts directly.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                items(subredditResults, key = { "${it.id}_${it.cleanUrl}" }) { sub ->
                    val cleanSub = sub.cleanUrl.lowercase()
                    val isHeldInFilter = currentFilter.selectedSubreddits.any { it.equals(cleanSub, ignoreCase = true) }
                    val previewUrl = sub.previewImages.firstOrNull()?.url

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isHeldInFilter) GoldAccent.copy(alpha = 0.16f) else DarkSurfaceVariant
                            )
                            .combinedClickable(
                                onClick = {
                                    onSelectSubreddit(sub.cleanUrl)
                                    onDismiss()
                                },
                                onLongClick = {
                                    onToggleHeldSubredditFilter(sub.cleanUrl)
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!previewUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = previewUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(GoldAccent.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "r/",
                                    color = GoldAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "r/${sub.cleanUrl}",
                                    color = if (isHeldInFilter) GoldAccent else TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                if (sub.isNsfw) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = NsfwBadgeRed.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "18+",
                                            color = NsfwBadgeRed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            val subtitleText = buildString {
                                if (sub.title.isNotBlank() && !sub.title.equals("r/${sub.cleanUrl}", ignoreCase = true)) {
                                    append(sub.title)
                                }
                                if (sub.itemCount > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append("${sub.itemCount} items")
                                }
                                if (sub.description.isNotBlank() && isEmpty()) {
                                    append(sub.description)
                                }
                            }
                            if (subtitleText.isNotBlank()) {
                                Text(
                                    text = subtitleText,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        // Hold / Lock Subreddit Filter Button
                        IconButton(
                            onClick = { onToggleHeldSubredditFilter(sub.cleanUrl) },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = if (isHeldInFilter) Icons.Default.Check else Icons.Default.FilterAlt,
                                contentDescription = if (isHeldInFilter) "Held in Subreddit Filter" else "Hold in Subreddit Filter",
                                tint = if (isHeldInFilter) SfwBadgeGreen else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}
