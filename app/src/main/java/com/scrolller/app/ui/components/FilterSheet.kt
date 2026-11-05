package com.scrolller.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scrolller.app.data.model.ContentFilter
import com.scrolller.app.data.model.FeedFilter
import com.scrolller.app.data.model.GallerySort
import com.scrolller.app.data.model.MediaFilter
import com.scrolller.app.data.model.SourceOriginFilter
import com.scrolller.app.ui.theme.BlackBackground
import com.scrolller.app.ui.theme.DarkSurface
import com.scrolller.app.ui.theme.DarkSurfaceVariant
import com.scrolller.app.ui.theme.GoldAccent
import com.scrolller.app.ui.theme.GoldAccentLight
import com.scrolller.app.ui.theme.TextPrimary
import com.scrolller.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    currentFilter: FeedFilter,
    availableCategories: List<String>,
    knownSubreddits: List<String>,
    currentUsername: String? = null,
    sheetState: SheetState,
    onContentFilterChanged: (ContentFilter) -> Unit = {},
    onApplyFilter: (FeedFilter) -> Unit,
    onOpenLogin: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var sort by remember(currentFilter) { mutableStateOf(currentFilter.sort) }
    var media by remember(currentFilter) { mutableStateOf(currentFilter.mediaFilter) }
    var content by remember(currentFilter) { mutableStateOf(currentFilter.contentFilter) }
    var sourceOrigin by remember(currentFilter) { mutableStateOf(currentFilter.sourceOrigin) }
    var category by remember(currentFilter) { mutableStateOf(currentFilter.category) }
    var selectedSubreddits by remember(currentFilter) { mutableStateOf(currentFilter.selectedSubreddits) }

    var customSubredditInput by remember { mutableStateOf("") }
    var categorySearchQuery by remember { mutableStateOf("") }

    val filteredCategories = remember(availableCategories, categorySearchQuery) {
        if (categorySearchQuery.isBlank()) {
            availableCategories
        } else {
            availableCategories.filter { it.contains(categorySearchQuery.trim(), ignoreCase = true) }
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
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Feed Options & Filters",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sort Section
            Text(text = "SORT BY", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GallerySort.entries.forEach { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { sort = s },
                        label = { Text(s.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldAccent,
                            selectedLabelColor = BlackBackground,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Media Context Section
            Text(text = "MEDIA CONTEXT", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MediaFilter.entries.forEach { m ->
                    FilterChip(
                        selected = media == m,
                        onClick = { media = m },
                        label = { Text(m.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldAccent,
                            selectedLabelColor = BlackBackground,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Source Origin Context Section
            Text(text = "SOURCE ORIGIN", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourceOriginFilter.entries.forEach { origin ->
                    FilterChip(
                        selected = sourceOrigin == origin,
                        onClick = { sourceOrigin = origin },
                        label = { Text(origin.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldAccent,
                            selectedLabelColor = BlackBackground,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Content Rating Section
            Text(text = "CONTENT RATING", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ContentFilter.entries.forEach { c ->
                    FilterChip(
                        selected = content == c,
                        onClick = {
                            content = c
                            onContentFilterChanged(c)
                        },
                        label = { Text(c.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldAccent,
                            selectedLabelColor = BlackBackground,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Subreddit Filter (Hold-Down Multi-Subreddit Filter)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUBREDDIT FILTER (${selectedSubreddits.size} HELD)",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (selectedSubreddits.isNotEmpty()) {
                    TextButton(onClick = { selectedSubreddits = emptySet() }) {
                        Text("Clear Held Subreddits", color = GoldAccent, fontSize = 11.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customSubredditInput,
                    onValueChange = { customSubredditInput = it },
                    placeholder = { Text("Add subreddit to hold (e.g. pics)…", color = TextSecondary, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GoldAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val clean = customSubredditInput.trim().removePrefix("r/").removePrefix("/").lowercase()
                            if (clean.isNotBlank()) {
                                selectedSubreddits = selectedSubreddits + clean
                                customSubredditInput = ""
                            }
                        }
                    )
                )
                IconButton(
                    onClick = {
                        val clean = customSubredditInput.trim().removePrefix("r/").removePrefix("/").lowercase()
                        if (clean.isNotBlank()) {
                            selectedSubreddits = selectedSubreddits + clean
                            customSubredditInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .background(GoldAccent, RoundedCornerShape(12.dp))
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Subreddit Filter", tint = BlackBackground)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val allSubChips = remember(knownSubreddits, selectedSubreddits) {
                (selectedSubreddits.toList() + knownSubreddits).map { it.lowercase().trim() }.distinct()
            }
            if (allSubChips.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    allSubChips.take(18).forEach { sub ->
                        val isSelected = selectedSubreddits.contains(sub)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedSubreddits = if (isSelected) {
                                    selectedSubreddits - sub
                                } else {
                                    selectedSubreddits + sub
                                }
                            },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            label = { Text("r/$sub", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GoldAccent,
                                selectedLabelColor = BlackBackground,
                                selectedLeadingIconColor = BlackBackground,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextPrimary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Categories Section
            Text(
                text = "SCROLLLER CATEGORIES (${availableCategories.size})",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = categorySearchQuery,
                onValueChange = { categorySearchQuery = it },
                placeholder = { Text("Filter categories…", color = TextSecondary, fontSize = 12.sp) },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (categorySearchQuery.isNotEmpty()) {
                        IconButton(onClick = { categorySearchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldAccent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = category == "ALL",
                    onClick = { category = "ALL" },
                    label = { Text("All Categories") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GoldAccent,
                        selectedLabelColor = BlackBackground,
                        containerColor = DarkSurfaceVariant,
                        labelColor = TextPrimary
                    )
                )

                filteredCategories.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = {
                            category = cat
                            // Clear single-subreddit override when switching category
                        },
                        label = { Text(cat) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldAccent,
                            selectedLabelColor = BlackBackground,
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Apply Button
            Button(
                onClick = {
                    onApplyFilter(
                        FeedFilter(
                            sort = sort,
                            mediaFilter = media,
                            contentFilter = content,
                            sourceOrigin = sourceOrigin,
                            category = category,
                            selectedSubreddits = selectedSubreddits
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldAccent,
                    contentColor = BlackBackground
                )
            ) {
                Text(
                    text = "Apply Filters",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    onDismiss()
                    onOpenLogin()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkSurfaceVariant,
                    contentColor = GoldAccentLight
                )
            ) {
                Text(
                    text = if (!currentUsername.isNullOrBlank()) "Account: $currentUsername" else "Sign in to Scrolller",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
