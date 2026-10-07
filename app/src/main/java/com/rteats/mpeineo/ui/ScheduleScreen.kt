package com.rteats.mpeineo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.Lesson
import com.rteats.mpeineo.model.ScheduleDay
import com.rteats.mpeineo.model.ScheduleSource
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

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
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Поиск расписания",
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Открыть поиск")
                        }
                    }
                }
            }
            return
        }

        ScheduleTargetSelector(
            target = selected,
            favorites = state.favorites,
            isFavorite = state.favorites.any {
                it.id == selected.id && it.type == selected.type
            },
            source = state.source,
            onSelect = onSelect,
            onToggleFavorite = { onToggleFavorite(selected) },
            onOpenSearch = onOpenSearch,
        )

        if (state.isLoading && state.week == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.error?.let { message ->
            ErrorCard(message = message, onRetry = onRefresh)
        }

        state.week?.let { week ->
            WeekContent(
                week = week,
                weekOffset = state.weekOffset,
                isRefreshing = state.isLoading,
                onRefresh = onRefresh,
                onPreviousWeek = onPreviousWeek,
                onNextWeek = onNextWeek,
                onCurrentWeek = onCurrentWeek,
            )
        }
    }
}

@Composable
private fun ScheduleTargetSelector(
    target: ScheduleTarget,
    favorites: List<ScheduleTarget>,
    isFavorite: Boolean,
    source: ScheduleSource?,
    onSelect: (ScheduleTarget) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    var expanded by remember(target.id, target.type) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            target.type.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        source?.let {
                            Text(
                                if (it == ScheduleSource.CACHE) "из кэша" else "обновлено",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        if (favorites.isEmpty()) {
                            "Нет избранных • нажмите, чтобы открыть список"
                        } else {
                            "Избранное: ${favorites.size} • нажмите, чтобы выбрать"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Поиск расписания",
                    )
                }
                Text(
                    "▾",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Default.Star else Icons.Outlined.Star,
                        contentDescription = "Быстрый доступ",
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (favorites.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Нет избранных расписаний") },
                    onClick = {},
                    enabled = false,
                )
            } else {
                favorites.forEach { favorite ->
                    val current = favorite.id == target.id && favorite.type == target.type
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    favorite.name,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                )
                                Text(
                                    favorite.type.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(favorite)
                        },
                        trailingIcon = {
                            if (current) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WeekContent(
    week: ScheduleWeek,
    weekOffset: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
) {
    WeekPager(
        week = week,
        weekOffset = weekOffset,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        onPreviousWeek = onPreviousWeek,
        onNextWeek = onNextWeek,
        onCurrentWeek = onCurrentWeek,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.WeekPager(
    week: ScheduleWeek,
    weekOffset: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
) {
    val initialPage = remember(week.weekStart) {
        week.days.indexOfFirst { it.date == LocalDate.now().toString() }
            .takeIf { it >= 0 } ?: 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { week.days.size },
    )
    val scope = rememberCoroutineScope()

    WeekSelectorPanel(
        week = week,
        weekOffset = weekOffset,
        selectedDay = pagerState.currentPage,
        onPreviousWeek = onPreviousWeek,
        onNextWeek = onNextWeek,
        onCurrentWeek = onCurrentWeek,
        onSelectDay = { index ->
            scope.launch {
                val delta = abs(index - pagerState.currentPage)
                if (delta <= 1) {
                    pagerState.animateScrollToPage(index)
                } else {
                    pagerState.scrollToPage(index)
                }
            }
        },
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            DayPage(
                day = week.days[page],
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WeekSelectorPanel(
    week: ScheduleWeek,
    weekOffset: Int,
    selectedDay: Int,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onSelectDay: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .pointerInput(weekOffset) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragCancel = { totalDrag = 0f },
                    onDragEnd = {
                        val threshold = 48.dp.toPx()
                        when {
                            totalDrag > threshold -> onPreviousWeek()
                            totalDrag < -threshold -> onNextWeek()
                        }
                        totalDrag = 0f
                    },
                ) { change, dragAmount ->
                    change.consume()
                    totalDrag += dragAmount
                }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AnimatedContent(
            targetState = week,
            transitionSpec = {
                val targetStart = LocalDate.parse(targetState.weekStart)
                val initialStart = LocalDate.parse(initialState.weekStart)
                val forward = targetStart.isAfter(initialStart)
                val enter = slideInHorizontally(
                    animationSpec = tween(220),
                    initialOffsetX = { width -> if (forward) width else -width },
                ) + fadeIn(animationSpec = tween(150))
                val exit = slideOutHorizontally(
                    animationSpec = tween(220),
                    targetOffsetX = { width -> if (forward) -width else width },
                ) + fadeOut(animationSpec = tween(150))
                enter togetherWith exit
            },
            contentKey = { it.weekStart },
            label = "week-panel-content",
        ) { displayedWeek ->
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatWeekRange(LocalDate.parse(displayedWeek.weekStart)),
                    modifier = if (weekOffset != 0) {
                        Modifier.clickable(onClick = onCurrentWeek)
                    } else {
                        Modifier
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (weekOffset != 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    displayedWeek.days.forEachIndexed { index, day ->
                        val date = LocalDate.parse(day.date)
                        val selected = index == selectedDay
                        val container = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                        val content = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectDay(index) },
                            shape = RoundedCornerShape(10.dp),
                            color = container,
                            contentColor = content,
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = date.dayOfWeek
                                        .getDisplayName(TextStyle.SHORT, Locale("ru"))
                                        .take(2)
                                        .uppercase(Locale("ru")),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayPage(
    day: ScheduleDay,
    modifier: Modifier = Modifier,
) {
    if (day.lessons.isEmpty()) {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
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
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp),
    ) {
        itemsIndexed(
            items = day.lessons,
            key = { index, lesson ->
                "${index}:${lesson.startTime}:${lesson.name}:${lesson.place}"
            },
        ) { index, lesson ->
            Column {
                if (
                    index > 0 &&
                    isOneHourBreak(day.lessons[index - 1], lesson)
                ) {
                    Spacer(Modifier.height(48.dp))
                }
                LessonCard(lesson)
            }
        }
    }
}

private fun isOneHourBreak(previous: Lesson, next: Lesson): Boolean =
    runCatching {
        val end = LocalTime.parse(previous.endTime)
        val start = LocalTime.parse(next.startTime)
        Duration.between(end, start).toMinutes() == 60L
    }.getOrDefault(false)

@Composable
private fun LessonCard(lesson: Lesson) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = lessonContainerColor(lesson.kind),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${lesson.startTime}–${lesson.endTime}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (lesson.kind.isNotBlank()) {
                    LessonTypeChip(lesson.kind)
                }
            }
            Text(
                lesson.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (lesson.place.isNotBlank()) {
                Text(
                    lesson.place,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
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

@Composable
private fun LessonTypeChip(kind: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = kind,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun lessonContainerColor(kind: String): Color {
    val scheme = MaterialTheme.colorScheme
    val normalized = kind.lowercase(Locale("ru"))

    val semantic = when {
        "лаб" in normalized || "lab" in normalized ->
            Color(0xFFEF5350)
        "лек" in normalized || "lecture" in normalized ->
            Color(0xFF66BB6A)
        "сем" in normalized || "прак" in normalized ||
            "seminar" in normalized || "practice" in normalized ->
            Color(0xFFFFCA28)
        "конс" in normalized || "экзам" in normalized ||
            "зач" in normalized || "exam" in normalized ->
            scheme.onSurfaceVariant
        else ->
            return scheme.surfaceContainer
    }

    val monetAdjusted = if (
        "конс" in normalized || "экзам" in normalized ||
        "зач" in normalized || "exam" in normalized
    ) {
        semantic
    } else {
        lerp(semantic, scheme.primary, 0.12f)
    }

    val tintStrength = if (monetAdjusted == scheme.onSurfaceVariant) 0.08f else 0.13f
    return lerp(scheme.surfaceContainer, monetAdjusted, tintStrength)
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

private fun formatWeekRange(start: LocalDate): String {
    val end = start.plusDays(6)
    val formatter = DateTimeFormatter.ofPattern(
        "d MMM",
        Locale("ru"),
    )
    return start.format(formatter) + " — " + end.format(formatter)
}
