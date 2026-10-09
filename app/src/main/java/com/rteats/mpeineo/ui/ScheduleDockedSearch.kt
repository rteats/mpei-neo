package com.rteats.mpeineo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ContainedLoadingIndicator
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
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarWithGapState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Based on AndroidX DockedSearchBarScaffoldSample:
 * AppBarWithSearch + ExpandedDockedSearchBarWithGap in the SAME Schedule scaffold.
 * Choosing a result never navigates to a separate screen.
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
    scrollBehavior: androidx.compose.material3.SearchBarScrollBehavior,
) {
    val textFieldState = rememberTextFieldState(initialText = state.searchQuery)
    val searchBarState = rememberSearchBarWithGapState()
    val scope = rememberCoroutineScope()
    val colors = SearchBarDefaults.appBarWithSearchColors()

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { onQueryChanged(it) }
    }

    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            modifier = Modifier.testTag("schedule-docked-search"),
            colors = colors.searchBarColors.inputFieldColors,
            onSearch = onSearch,
            placeholder = { Text("Группа, преподаватель или аудитория") },
            leadingIcon = {
                if (searchBarState.currentValue == SearchBarValue.Expanded) {
                    IconButton(onClick = {
                        scope.launch { searchBarState.animateToCollapsed() }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть поиск")
                    }
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                }
            },
        )
    }

    AppBarWithSearch(
        scrollBehavior = scrollBehavior,
        state = searchBarState,
        colors = colors,
        inputField = inputField,
        navigationIcon = {
            Icon(Icons.Default.DateRange, contentDescription = null)
        },
    )

    ExpandedDockedSearchBarWithGap(
        state = searchBarState,
        inputField = inputField,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            if (state.isSearching) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { ContainedLoadingIndicator() }
            }

            state.searchError?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(state.searchResults, key = { "${it.type.apiName}:${it.id}" }) { result ->
                    val favorite = state.favorites.any {
                        it.id == result.id && it.type == result.type
                    }
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(result)
                                textFieldState.setTextAndPlaceCursorAtEnd("")
                                scope.launch { searchBarState.animateToCollapsed() }
                            },
                        headlineContent = {
                            Text(result.name, fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = {
                            Text(
                                listOf(result.type.displayName, result.description)
                                    .filter(String::isNotBlank)
                                    .joinToString(" • "),
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
                                    contentDescription = "Быстрый доступ",
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
