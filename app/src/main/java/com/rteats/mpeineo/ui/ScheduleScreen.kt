package com.rteats.mpeineo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScheduleScreen(
    state: MainUiState,
    onSelect: (ScheduleTarget) -> Unit,
    onToggleFavorite: (ScheduleTarget) -> Unit,
    onQueryChanged: (String) -> Unit,
    onTypeChanged: (com.rteats.mpeineo.model.ScheduleTargetType?) -> Unit,
    onSearch: () -> Unit,
    onEnsureAgendaWeek: (Int) -> Unit,
    onRefreshAgendaWeek: (Int) -> Unit,
    onRetryAgendaWeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchScrollBehavior =
        androidx.compose.material3.SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

    androidx.compose.material3.Scaffold(
        modifier = modifier.nestedScroll(searchScrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScheduleDockedSearch(
                state = state,
                onQueryChanged = onQueryChanged,
                onTypeChanged = onTypeChanged,
                onSearch = onSearch,
                onSelect = onSelect,
                onToggleFavorite = onToggleFavorite,
                scrollBehavior = searchScrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
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
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                "Выберите расписание",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Найдите группу, преподавателя или аудиторию в поиске сверху.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                ScheduleTargetSelector(
                    target = selected,
                    favorites = state.favorites,
                    isFavorite = state.favorites.any {
                        it.id == selected.id && it.type == selected.type
                    },
                    source = state.source,
                    onSelect = onSelect,
                    onToggleFavorite = { onToggleFavorite(selected) },
                )
                ScheduleAgenda(
                    state = state,
                    onEnsureWeek = onEnsureAgendaWeek,
                    onRefreshWeek = onRefreshAgendaWeek,
                    onRetryWeek = onRetryAgendaWeek,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScheduleTargetSelector(
    target: ScheduleTarget,
    favorites: List<ScheduleTarget>,
    isFavorite: Boolean,
    source: ScheduleSource?,
    onSelect: (ScheduleTarget) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var expanded by remember(target.id, target.type) { mutableStateOf(false) }
    val density = LocalDensity.current
    var menuWidth by remember { mutableStateOf(0.dp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
            .onSizeChanged { size ->
                menuWidth = with(density) { size.width.toDp() }
            },
    ) {
        // Clickable Card owns its ripple and clips it to the same large shape.
        // A Modifier.clickable outside the Card would produce a rectangular ripple.
        Card(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
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
                    Text(
                        target.type.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Text(
                    "▾",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                FilledIconToggleButton(
                    checked = isFavorite,
                    onCheckedChange = { onToggleFavorite() },
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    Icon(
                        if (isFavorite) Icons.Default.Star else Icons.Outlined.Star,
                        contentDescription =
                            if (isFavorite) "Удалить из избранного" else "Добавить в избранное",
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth),
            offset = DpOffset(0.dp, (-4).dp),
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            if (favorites.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Нет избранных расписаний") },
                    onClick = {},
                    shape = MenuDefaults.standaloneItemShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    enabled = false,
                )
            } else {
                favorites.forEachIndexed { index, favorite ->
                    val current = favorite.id == target.id && favorite.type == target.type
                    val itemShape = when {
                        favorites.size == 1 -> MenuDefaults.standaloneItemShape
                        index == 0 -> MenuDefaults.leadingItemShape
                        index == favorites.lastIndex -> MenuDefaults.trailingItemShape
                        else -> MenuDefaults.middleItemShape
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                favorite.name,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingText = {
                            Text(favorite.type.displayName)
                        },
                        onClick = {
                            expanded = false
                            onSelect(favorite)
                        },
                        shape = if (current) MenuDefaults.selectedItemShape else itemShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        trailingContent = {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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

    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = refreshState,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = refreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
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
        contentPadding = PaddingValues(top = 10.dp, bottom = 112.dp),
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

internal fun isOneHourBreak(previous: Lesson, next: Lesson): Boolean =
    runCatching {
        val end = LocalTime.parse(previous.endTime)
        val start = LocalTime.parse(next.startTime)
        Duration.between(end, start).toMinutes() == 60L
    }.getOrDefault(false)

/**
 * Three-line Material 3 ListItem experiment:
 * overline = time + type, headline = lesson, supporting = room / teacher / group.
 */
@Composable
internal fun LessonCard(lesson: Lesson) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = lessonContainerColor(lesson.kind),
    ) {
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            overlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = lessonPeriodNumber(lesson.startTime)?.let { number ->
                            "${number}-я пара · ${lesson.startTime}–${lesson.endTime}"
                        } ?: "${lesson.startTime}–${lesson.endTime}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (lesson.kind.isNotBlank()) {
                        LessonTypeChip(lesson.kind)
                    }
                }
            },
            headlineContent = {
                Text(
                    lesson.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (lesson.place.isNotBlank()) {
                        Text(
                            lesson.place,
                            style = MaterialTheme.typography.bodyMedium,
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
            },
        )
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
