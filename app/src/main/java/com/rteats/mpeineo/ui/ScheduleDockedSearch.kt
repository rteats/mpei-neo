package com.rteats.mpeineo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExpandedDockedSearchBarWithGap
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarWithGapState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import com.rteats.mpeineo.model.displayName
import com.rteats.mpeineo.model.favoriteKey
import com.rteats.mpeineo.model.matchingFavorites
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * One search surface for the active schedule, favorites and remote results.
 * Favorites are local and can match custom names even before the API responds.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScheduleDockedSearch(
    state: MainUiState,
    onQueryChanged: (String) -> Unit,
    onTypeChanged: (ScheduleTargetType?) -> Unit,
    onSearch: () -> Unit,
    onSelect: (ScheduleTarget) -> Unit,
    onToggleFavorite: (ScheduleTarget) -> Unit,
    onRenameFavorite: (ScheduleTarget, String) -> Unit,
    scrollBehavior: androidx.compose.material3.SearchBarScrollBehavior,
) {
    val textFieldState = rememberTextFieldState(initialText = state.searchQuery)
    val searchBarState = rememberSearchBarWithGapState()
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<ScheduleTarget?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    val searchContainer = MaterialTheme.colorScheme.surfaceContainerHigh
    val background = MaterialTheme.colorScheme.background
    val colors = SearchBarDefaults.appBarWithSearchColors(
        searchBarColors = SearchBarDefaults.colors(containerColor = searchContainer),
        scrolledSearchBarContainerColor = searchContainer,
        // Remove the opaque app-bar surface: a fading scrim is drawn behind
        // the field instead of a permanent rectangular header.
        appBarContainerColor = Color.Transparent,
        scrolledAppBarContainerColor = Color.Transparent,
    )

    val favorites = matchingFavorites(
        state.favorites,
        state.favoriteNames,
        state.searchQuery,
        state.searchType,
    )
    val shownFavoriteKeys = favorites.map { it.favoriteKey() }.toSet()
    val otherResults = state.searchResults.filterNot {
        it.favoriteKey() in shownFavoriteKeys
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { onQueryChanged(it) }
    }

    fun select(target: ScheduleTarget) {
        onSelect(target)
        textFieldState.setTextAndPlaceCursorAtEnd("")
        scope.launch { searchBarState.animateToCollapsed() }
    }

    // Dismissing search (including Android's Back gesture) restores the active
    // schedule name instead of leaving a stale query in the collapsed field.
    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue == SearchBarValue.Collapsed &&
            textFieldState.text.isNotEmpty()
        ) {
            textFieldState.setTextAndPlaceCursorAtEnd("")
        }
    }

    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            modifier = Modifier.testTag("schedule-docked-search"),
            colors = colors.searchBarColors.inputFieldColors,
            onSearch = { onSearch() },
            placeholder = {
                Text(
                    if (searchBarState.currentValue == SearchBarValue.Collapsed) {
                        state.selected?.displayName(state.favoriteNames)
                            ?: "Найти расписание"
                    } else {
                        "Группа, преподаватель или аудитория"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                if (searchBarState.currentValue == SearchBarValue.Expanded) {
                    IconButton(
                        onClick = {
                            textFieldState.setTextAndPlaceCursorAtEnd("")
                            scope.launch { searchBarState.animateToCollapsed() }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть поиск")
                    }
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                val selected = state.selected
                if (searchBarState.currentValue == SearchBarValue.Collapsed &&
                    selected != null
                ) {
                    val isFavorite = state.favorites.any {
                        it.favoriteKey() == selected.favoriteKey()
                    }
                    FilledIconToggleButton(
                        checked = isFavorite,
                        onCheckedChange = { onToggleFavorite(selected) },
                        shapes = IconButtonDefaults.toggleableShapes(),
                    ) {
                        Icon(
                            if (isFavorite) Icons.Default.Star else Icons.Outlined.Star,
                            contentDescription = if (isFavorite) {
                                "Удалить выбранное расписание из избранного"
                            } else "Добавить выбранное расписание в избранное",
                        )
                    }
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        background,
                        background.copy(alpha = 0.94f),
                        background.copy(alpha = 0.68f),
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                ),
            ),
    ) {
        AppBarWithSearch(
            scrollBehavior = scrollBehavior,
            state = searchBarState,
            colors = colors,
            inputField = inputField,
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
    }

    ExpandedDockedSearchBarWithGap(
        state = searchBarState,
        inputField = inputField,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                contentPadding = PaddingValues(bottom = 20.dp),
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
