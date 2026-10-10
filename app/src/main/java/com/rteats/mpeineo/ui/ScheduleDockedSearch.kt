package com.rteats.mpeineo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import com.rteats.mpeineo.model.displayName
import com.rteats.mpeineo.model.favoriteKey
import com.rteats.mpeineo.model.matchingFavorites

/**
 * Overlay search docked immediately above the bottom navigation bar.
 * Results and favorites expand UP, not in a full-screen route or below input.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScheduleDockedSearch(
    state: MainUiState,
    onQueryChanged: (String) -> Unit,
    onTypeChanged: (ScheduleTargetType?) -> Unit,
    onSearch: () -> Unit,
    onSelect: (ScheduleTarget) -> Unit,
    onToggleFavorite: (ScheduleTarget) -> Unit,
    onRenameFavorite: (ScheduleTarget, String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ScheduleTarget?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun closeSearch() {
        expanded = false
        onExpandedChange(false)
        onQueryChanged("")
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    fun select(target: ScheduleTarget) {
        onSelect(target)
        closeSearch()
    }

    androidx.compose.runtime.LaunchedEffect(active) {
        if (!active && expanded) closeSearch()
    }
    BackHandler(enabled = active && expanded && renameTarget == null) { closeSearch() }

    val favorites = matchingFavorites(
        state.favorites, state.favoriteNames, state.searchQuery, state.searchType,
    )
    val favoriteKeys = favorites.map { it.favoriteKey() }.toSet()
    val otherResults = state.searchResults.filterNot { it.favoriteKey() in favoriteKeys }
    val selected = state.selected
    val alreadyFavorite = selected != null && state.favorites.any {
        it.favoriteKey() == selected.favoriteKey()
    }

    BoxWithConstraints(modifier = modifier) {
        val panelMaxHeight = (maxHeight - 86.dp).coerceAtLeast(0.dp)
        val rowCount = favorites.size + otherResults.size
        val suggestedHeight = (118 + rowCount.coerceAtMost(6) * 74 +
            (if (state.isSearching || state.searchError != null) 48 else 0)).dp
        val panelHeight = suggestedHeight.coerceAtMost(panelMaxHeight)

        if (expanded) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.10f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = ::closeSearch,
                    ),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            if (expanded && panelHeight > 0.dp) {
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .height(panelHeight),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.searchType == null,
                        onClick = { onTypeChanged(null) },
                        label = { Text("Все") },
                    )
                }
                items(ScheduleTargetType.entries) { type ->
                    FilterChip(
                        selected = state.searchType == type,
                        onClick = { onTypeChanged(type) },
                        label = { Text(type.displayName) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                if (favorites.isNotEmpty()) {
                    item(key = "favorites-label") {
                        Text(
                            "Избранное",
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(favorites, key = { "favorite-${it.favoriteKey()}" }) { favorite ->
                        var menuExpanded by remember(favorite.favoriteKey()) {
                            mutableStateOf(false)
                        }
                        val current = state.selected?.favoriteKey() == favorite.favoriteKey()
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { select(favorite) },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "Избранное",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = {
                                Text(
                                    favorite.displayName(state.favoriteNames),
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    listOf(
                                        favorite.type.displayName,
                                        favorite.name.takeIf {
                                            it != favorite.displayName(state.favoriteNames)
                                        }.orEmpty(),
                                    ).filter(String::isNotBlank).joinToString(" • "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Изменить избранное")
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        shape = MaterialTheme.shapes.large,
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Переименовать") },
                                            leadingIcon = {
                                                Icon(Icons.Default.Edit, contentDescription = null)
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                renameDraft = favorite.displayName(state.favoriteNames)
                                                renameTarget = favorite
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Удалить из избранного") },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Star, contentDescription = null)
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                onToggleFavorite(favorite)
                                            },
                                        )
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }

                if (state.searchQuery.isNotBlank() && (otherResults.isNotEmpty() ||
                            state.isSearching || state.searchError != null)
                ) {
                    item(key = "results-label") {
                        Text(
                            "Результаты поиска",
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (state.isSearching) {
                    item(key = "loading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { ContainedLoadingIndicator() }
                    }
                }
                state.searchError?.let { error ->
                    item(key = "search-error") {
                        Text(
                            error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(otherResults, key = { "result-${it.favoriteKey()}" }) { result ->
                    val favorite = state.favorites.any {
                        it.favoriteKey() == result.favoriteKey()
                    }
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable { select(result) },
                        headlineContent = {
                            Text(result.name, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(
                                listOf(result.type.displayName, result.description)
                                    .filter(String::isNotBlank)
                                    .joinToString(" • "),
                                maxLines = 2,
                            )
                        },
                        trailingContent = {
                            FilledIconToggleButton(
                                checked = favorite,
                                onCheckedChange = { onToggleFavorite(result) },
                                shapes = IconButtonDefaults.toggleableShapes(),
                            ) {
                                Icon(
                                    if (favorite) Icons.Default.Star else Icons.Outlined.Star,
                                    contentDescription = if (favorite) {
                                        "Удалить из избранного"
                                    } else "В избранное",
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (favorites.isEmpty() && otherResults.isEmpty() &&
                    !state.isSearching && state.searchQuery.isBlank()
                ) {
                    item(key = "empty-favorites") {
                        Text(
                            "Здесь появятся избранные расписания. Начните вводить название для поиска.",
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
                    }
                }
            }

            // Solid dock matches the navigation bar below it. Only the upper
            // corners are rounded; no shadow/gradient in light or dark theme.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
                        ),
                    ) {
                TextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.fillMaxWidth()
                        .testTag("schedule-docked-search")
                        .onFocusChanged {
                            if (it.isFocused && !expanded) {
                                expanded = true
                                onExpandedChange(true)
                            }
                        },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSearch()
                        keyboard?.hide()
                    }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    placeholder = {
                        Text(
                            if (expanded) "Группа, преподаватель или аудитория" else
                                selected?.displayName(state.favoriteNames) ?: "Найти расписание",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        if (expanded) {
                            IconButton(onClick = ::closeSearch) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть поиск")
                            }
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
                    },
                    trailingIcon = {
                        if (!expanded && selected != null && !alreadyFavorite) {
                            IconButton(onClick = { onToggleFavorite(selected) }) {
                                Icon(Icons.Outlined.Star, contentDescription = "Добавить в избранное")
                            }
                        }
                    },
                )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Название в избранном") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(80) },
                    label = { Text("Название") },
                    placeholder = { Text(target.name) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameFavorite(target, renameDraft)
                    renameTarget = null
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Отмена") }
            },
        )
    }
}
