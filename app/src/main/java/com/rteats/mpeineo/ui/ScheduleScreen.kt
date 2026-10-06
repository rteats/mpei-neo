package com.rteats.mpeineo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.Lesson
import com.rteats.mpeineo.model.ScheduleSource
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun ScheduleScreen(
    state: MainUiState,
    onSelect: (ScheduleTarget) -> Unit,
    onToggleFavorite: (ScheduleTarget) -> Unit,
    onRefresh: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        if (state.favorites.isNotEmpty()) {
            Text(
                text = "Быстрый доступ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    items = state.favorites,
                    key = { it.type.apiName + ":" + it.id },
                ) { target ->
                    AssistChip(
                        onClick = { onSelect(target) },
                        label = {
                            Text(
                                target.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }

        val selected = state.selected
        if (selected == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Card {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Выберите расписание",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Найдите группу, преподавателя или аудиторию и закрепите нужные варианты.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                        )
                        Button(onClick = onOpenSearch) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Открыть поиск")
                        }
                    }
                }
            }
            return
        }

        ScheduleHeader(
            target = selected,
            isFavorite = state.favorites.any {
                it.id == selected.id && it.type == selected.type
            },
            source = state.source,
            onToggleFavorite = { onToggleFavorite(selected) },
            onRefresh = onRefresh,
        )

        WeekNavigation(
            week = state.week,
            weekOffset = state.weekOffset,
            onPreviousWeek = onPreviousWeek,
            onNextWeek = onNextWeek,
            onCurrentWeek = onCurrentWeek,
        )

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.error?.let { message ->
            ErrorCard(message = message, onRetry = onRefresh)
        }

        state.week?.let { week ->
            WeekContent(week)
        }
    }
}

@Composable
private fun ScheduleHeader(
    target: ScheduleTarget,
    isFavorite: Boolean,
    source: ScheduleSource?,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    target.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        target.type.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    source?.let {
                        Text(
                            if (it == ScheduleSource.CACHE) "из кэша" else "обновлено",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Default.Star else Icons.Outlined.Star,
                    contentDescription = "Быстрый доступ",
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }
    }
}

@Composable
private fun WeekNavigation(
    week: ScheduleWeek?,
    weekOffset: Int,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPreviousWeek) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Предыдущая неделя")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                week?.let { formatWeekRange(it.weekStart) } ?: "Неделя",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (weekOffset != 0) {
                Text(
                    "На текущую неделю",
                    modifier = Modifier.clickable(onClick = onCurrentWeek),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onNextWeek) {
            Icon(Icons.Default.ArrowForward, contentDescription = "Следующая неделя")
        }
    }
}

@Composable
private fun WeekContent(week: ScheduleWeek) {
    var selectedDay by rememberSaveable(week.weekStart) {
        mutableIntStateOf(
            week.days.indexOfFirst {
                it.date == LocalDate.now().toString()
            }.takeIf { it >= 0 } ?: 0,
        )
    }

    LaunchedEffect(week.weekStart) {
        selectedDay = week.days.indexOfFirst {
            it.date == LocalDate.now().toString()
        }.takeIf { it >= 0 } ?: 0
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(week.days) { index, day ->
            val date = LocalDate.parse(day.date)
            FilterChip(
                selected = selectedDay == index,
                onClick = { selectedDay = index },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            date.dayOfWeek.getDisplayName(
                                TextStyle.SHORT,
                                Locale("ru"),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            date.dayOfMonth.toString(),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
            )
        }
    }

    val day = week.days.getOrNull(selectedDay) ?: return
    if (day.lessons.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Пар нет",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp),
        ) {
            items(day.lessons) { lesson ->
                LessonCard(lesson)
            }
        }
    }
}

@Composable
private fun LessonCard(lesson: Lesson) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 8.dp,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        lesson.startTime,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        lesson.endTime,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Text(
                    lesson.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (lesson.kind.isNotBlank()) {
                    Text(
                        lesson.kind,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (lesson.place.isNotBlank()) {
                    Text(
                        lesson.place,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (lesson.lecturer.isNotBlank()) {
                    Text(
                        lesson.lecturer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (lesson.groups.isNotBlank()) {
                    Text(
                        lesson.groups,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Повторить")
            }
        }
    }
}

private fun formatWeekRange(weekStartRaw: String): String {
    val start = LocalDate.parse(weekStartRaw)
    val end = start.plusDays(6)
    val formatter = DateTimeFormatter.ofPattern(
        "d MMM",
        Locale("ru"),
    )
    return start.format(formatter) + " — " + end.format(formatter)
}
