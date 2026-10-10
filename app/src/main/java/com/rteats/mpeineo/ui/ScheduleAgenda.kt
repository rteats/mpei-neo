package com.rteats.mpeineo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.ScheduleDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// Bounded native-sticky timeline. Shift this window before reaching an edge.
// Keys remain stable across shifts, so old weeks stay reachable indefinitely
// without allocating thousands of stickyHeader intervals at composition time.
private const val WINDOW_DAYS = 126
private const val WINDOW_SHIFT_DAYS = 35
private const val WINDOW_EDGE_DAYS = 21
// An indicator does not assert that new lessons exist; it only suggests checking.
private const val SCHEDULE_STALE_AFTER_MS = 30 * 60 * 1000L

/**
 * Calendar-style agenda with native LazyColumn stickyHeader per date.
 * Header and lessons belong to the same lazy list; a next date pushes the prior
 * sticky date away. A sliding window bounds the number of composed intervals,
 * while date-stable keys and requestScrollToItem preserve scroll position.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val todayDayOffset = today.dayOfWeek.value - 1
    val initialWindowStart = todayDayOffset - WINDOW_DAYS / 2
    var windowStartDay by remember { mutableIntStateOf(initialWindowStart) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (todayDayOffset - initialWindowStart) * 2,
    )
    val refreshState = rememberPullToRefreshState()
    val selectedId = state.selected?.id
    val selectedType = state.selected?.type

    LaunchedEffect(selectedId, selectedType) {
        windowStartDay = initialWindowStart
        listState.requestScrollToItem((todayDayOffset - initialWindowStart) * 2)
    }

    // Never create the whole multi-year timeline as LazyListScope intervals.
    // Move 35 days at a time, well before the currently visible day reaches an
    // edge. requestScrollToItem compensates for the inserted/removed items
    // synchronously with the next remeasure (each date uses two list items).
    LaunchedEffect(listState.firstVisibleItemIndex, windowStartDay) {
        val firstIndex = listState.firstVisibleItemIndex
        val visibleWindowDay = firstIndex / 2
        val shift = when {
            visibleWindowDay < WINDOW_EDGE_DAYS -> -WINDOW_SHIFT_DAYS
            visibleWindowDay >= WINDOW_DAYS - WINDOW_EDGE_DAYS -> WINDOW_SHIFT_DAYS
            else -> 0
        }
        if (shift != 0) {
            val offset = listState.firstVisibleItemScrollOffset
            windowStartDay += shift
            listState.requestScrollToItem(firstIndex - shift * 2, offset)
        }
    }

    val visibleDayOffset by remember(listState, windowStartDay) {
        derivedStateOf {
            windowStartDay + listState.firstVisibleItemIndex / 2
        }
    }
    val visibleWeekOffset = Math.floorDiv(visibleDayOffset, 7)

    // The active Schedule destination doubles as a jump-to-today action.
    // When invoked, recenter the bounded timeline even after months of scrolling.
    LaunchedEffect(todayJumpRequest) {
        windowStartDay = initialWindowStart
        listState.requestScrollToItem((todayDayOffset - initialWindowStart) * 2)
    }

    Column(modifier = modifier) {
        PullToRefreshBox(
            isRefreshing = visibleWeekOffset in state.agendaLoadingOffsets,
            onRefresh = { onRefreshWeek(visibleWeekOffset) },
            state = refreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = refreshState,
                    isRefreshing = visibleWeekOffset in state.agendaLoadingOffsets,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (dayInWindow in 0 until WINDOW_DAYS) {
                    val dayOffset = windowStartDay + dayInWindow
                    val date = monday.plusDays(dayOffset.toLong())
                    val relativeWeek = Math.floorDiv(dayOffset, 7)

                    // One actual header per date: not an overlay or a mirrored
                    // copy. Compose's native stickyHeader handles the push-off
                    // animation when the next date arrives.
                    stickyHeader(
                        key = "date-header-$dayOffset",
                        contentType = "date-header",
                    ) {
                        AgendaDayHeader(date = date, today = today)
                    }

                    item(
                        key = "date-content-$dayOffset",
                        contentType = "date-content",
                    ) {
                        val dayData: ScheduleDay? =
                            state.agendaWeeks[relativeWeek]?.days
                                ?.firstOrNull { it.date == date.toString() }
                                ?: if (relativeWeek == state.weekOffset) {
                                    state.week?.days?.firstOrNull { it.date == date.toString() }
                                } else null

                        LaunchedEffect(selectedId, selectedType, relativeWeek) {
                            onEnsureWeek(relativeWeek)
                            if (dayOffset % 7 == 0 || dayOffset == todayDayOffset) {
                                onEnsureWeek(relativeWeek + 1)
                                onEnsureWeek(relativeWeek - 1)
                            }
                        }

                        AgendaDaySection(
                            day = dayData,
                            loading = relativeWeek in state.agendaLoadingOffsets ||
                                (relativeWeek == state.weekOffset && state.isLoading),
                            failed = relativeWeek in state.agendaFailedOffsets,
                            onRetry = { onRetryWeek(relativeWeek) },
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
