package com.rteats.mpeineo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SearchScreen(
    state: MainUiState,
    onQueryChanged: (String) -> Unit,
    onTypeChanged: (ScheduleTargetType?) -> Unit,
    onSearch: () -> Unit,
    onSelect: (ScheduleTarget) -> Unit,
    onToggleFavorite: (ScheduleTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(state.searchQuery))
    }

    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery != fieldValue.text) {
            fieldValue = TextFieldValue(
                text = state.searchQuery,
                selection = TextRange(state.searchQuery.length),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { updated ->
                fieldValue = updated
                if (updated.text != state.searchQuery) {
                    onQueryChanged(updated.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused && fieldValue.text.isNotEmpty()) {
                        fieldValue = fieldValue.copy(
                            selection = TextRange(0, fieldValue.text.length),
                        )
                    }
                },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            label = {
                Text("Группа, преподаватель или аудитория")
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = "Искать",
                    )
                }
            },
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
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
            Box(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                ContainedLoadingIndicator()
            }
        }

        state.searchError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(
                items = state.searchResults,
                key = { it.type.apiName + ":" + it.id },
            ) { result ->
                val favorite = state.favorites.any {
                    it.id == result.id && it.type == result.type
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(result) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                                end = 6.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (result.type == ScheduleTargetType.ROOM) {
                                Icons.Default.Place
                            } else {
                                Icons.Default.DateRange
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .weight(1f),
                        ) {
                            Text(
                                result.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                result.type.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (result.description.isNotBlank()) {
                                Text(
                                    result.description,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        FilledIconToggleButton(
                            checked = favorite,
                            onCheckedChange = { onToggleFavorite(result) },
                            shapes = IconButtonDefaults.toggleableShapes(),
                        ) {
                            Icon(
                                if (favorite) {
                                    Icons.Default.Star
                                } else {
                                    Icons.Outlined.Star
                                },
                                contentDescription = "Быстрый доступ",
                            )
                        }
                    }
                }
            }
        }
    }
}
