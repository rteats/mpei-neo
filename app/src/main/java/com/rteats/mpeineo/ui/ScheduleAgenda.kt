package com.rteats.mpeineo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.ScheduleDay
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The vertical agenda is an infinite sequence of bounded, seven-day pages.
 * Normal scrolling remains native LazyColumn scrolling. Only a deliberate
 * overscroll at Monday/Sunday changes week; no horizontal day pager exists.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScheduleAgenda(
    state: MainUiState,
    onEnsureWeek: (Int) -> Unit,
    onRefreshWeek: (Int) -> Unit,
    onRetryWeek: (Int) -> Unit,
    todayJumpRequest: Int,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val monday = remember(today) {
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    val currentDayIndex = today.dayOfWeek.value - 1
    val selectedId = state.selected?.id
    val selectedType = state.selected?.type
    var visibleWeek by rememberSaveable(selectedId, selectedType) { mutableIntStateOf(0) }
    var entryDay by rememberSaveable(selectedId, selectedType) {
        mutableIntStateOf(currentDayIndex)
    }
    val locale = remember { Locale.forLanguageTag("ru") }
    val weekDateFormat = remember { DateTimeFormatter.ofPattern("d MMM", locale) }
    val overscrollThreshold = with(LocalDensity.current) { 84.dp.toPx() }
    val overscrollMax = with(LocalDensity.current) { 180.dp.toPx() }

    LaunchedEffect(selectedId, selectedType, visibleWeek) {
        onEnsureWeek(visibleWeek)
        // Preload adjacent weeks without displaying their days.
        onEnsureWeek(visibleWeek - 1)
        onEnsureWeek(visibleWeek + 1)
    }
    LaunchedEffect(todayJumpRequest) {
        visibleWeek = 0
        entryDay = currentDayIndex
    }

    AnimatedContent(
        targetState = visibleWeek,
        modifier = modifier.fillMaxSize(),
        label = "schedule-week",
        transitionSpec = {
            val forward = targetState > initialState
            (slideInVertically(
                initialOffsetY = { if (forward) it / 4 else -it / 4 },
                animationSpec = tween(230, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(160))) togetherWith
                (slideOutVertically(
                    targetOffsetY = { if (forward) -it / 4 else it / 4 },
                    animationSpec = tween(230, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(160)))
        },
    ) { displayedWeek ->
        val weekStart = monday.plusWeeks(displayedWeek.toLong())
        val weekEnd = weekStart.plusDays(6)
        val listState = remember(displayedWeek, selectedId, selectedType, todayJumpRequest) {
            LazyListState(initialFirstVisibleItemIndex = entryDay.coerceIn(0, 6) * 2)
        }
        var overscroll by remember(displayedWeek) { mutableFloatStateOf(0f) }

        val overscrollConnection = remember(
            displayedWeek, listState, overscrollThreshold, overscrollMax,
        ) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (displayedWeek != visibleWeek ||
                        source != NestedScrollSource.UserInput || available.y == 0f
                    ) return Offset.Zero

                    // Check the LazyColumn's *actual* scroll boundaries before
                    // overscroll effects consume the remaining pointer movement.
                    // In-week vertical scrolling is never intercepted.
                    val atPreviousEdge = available.y > 0f && !listState.canScrollBackward
                    val atNextEdge = available.y < 0f && !listState.canScrollForward
                    if (atPreviousEdge || atNextEdge) {
                        overscroll = (overscroll + available.y)
                            .coerceIn(-overscrollMax, overscrollMax)
                    } else {
                        overscroll = 0f
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (displayedWeek == visibleWeek) {
                        when {
                            overscroll >= overscrollThreshold -> {
                                entryDay = 6 // previous week lands on Sunday
                                visibleWeek = displayedWeek - 1
                            }
                            overscroll <= -overscrollThreshold -> {
                                entryDay = 0 // next week begins on Monday
                                visibleWeek = displayedWeek + 1
                            }
                        }
                    }
                    overscroll = 0f
                    return Velocity.Zero
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    weekStart.format(weekDateFormat) + " – " + weekEnd.format(weekDateFormat),
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { onRefreshWeek(displayedWeek) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить эту неделю")
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()
                .nestedScroll(overscrollConnection)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer { translationY = overscroll * 0.22f },
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (dayIndex in 0..6) {
                        val date = weekStart.plusDays(dayIndex.toLong())
                        stickyHeader(
                            key = "day-header-$date",
                            contentType = "date-header",
                        ) {
                            AgendaDayHeader(date, today)
                        }
                        item(key = "day-content-$date", contentType = "date-content") {
                            val dayData: ScheduleDay? =
                                state.agendaWeeks[displayedWeek]?.days
                                    ?.firstOrNull { it.date == date.toString() }
                                    ?: if (displayedWeek == state.weekOffset) {
                                        state.week?.days?.firstOrNull {
                                            it.date == date.toString()
                                        }
                                    } else null
                            AgendaDaySection(
                                day = dayData,
                                loading = displayedWeek in state.agendaLoadingOffsets ||
                                    (displayedWeek == state.weekOffset && state.isLoading),
                                failed = displayedWeek in state.agendaFailedOffsets ||
                                    (displayedWeek == state.weekOffset &&
                                        state.error != null && dayData == null),
                                onRetry = { onRetryWeek(displayedWeek) },
                            )
                        }
                    }
                }
                if (overscroll != 0f) {
                    val previous = overscroll > 0f
                    Surface(
                        modifier = Modifier.align(
                            if (previous) Alignment.TopCenter else Alignment.BottomCenter,
                        ).padding(8.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            if (previous) "Предыдущая неделя" else "Следующая неделя",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgendaDaySection(
    day: ScheduleDay?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            day != null -> {
                if (day.lessons.isEmpty()) {
                    Text(
                        "Пар нет",
                        modifier = Modifier.padding(start = 14.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                day.lessons.forEachIndexed { index, lesson ->
                    if (
                        index > 0 &&
                        isOneHourBreak(day.lessons[index - 1], lesson)
                    ) {
                        Spacer(Modifier.height(36.dp))
                    }
                    LessonCard(lesson)
                }
            }
            failed -> {
                Text(
                    "Не удалось загрузить эту неделю",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRetry) { Text("Повторить") }
            }
            loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) { ContainedLoadingIndicator() }
            }
            else -> {
                Text(
                    "Загружаем расписание…",
                    modifier = Modifier.padding(start = 14.dp, bottom = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Native sticky weekday label, rendered exactly once per date by LazyColumn.
 */
@Composable
private fun AgendaDayHeader(
    date: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val language = remember { Locale.forLanguageTag("ru") }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", language) }
    val isToday = date == today
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isToday) {
            MaterialTheme.colorScheme.primaryContainer
        } else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            date.format(dateFormat).replaceFirstChar { it.titlecase(language) } +
                if (isToday) " • Сегодня" else "",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isToday) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else MaterialTheme.colorScheme.onSurface,
        )
    }
}
