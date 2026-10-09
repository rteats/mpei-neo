package com.rteats.mpeineo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

// Virtual timeline spanning +/- 100 years, loading only weeks near the viewport.
// No network requests are made for the other virtual days.
private const val CENTER_WEEK = 5_200
private const val TOTAL_WEEKS = CENTER_WEEK * 2 + 1
private const val CENTER_DAY_INDEX = CENTER_WEEK * 7
private const val TOTAL_DAYS = TOTAL_WEEKS * 7

/**
 * Calendar-style agenda: a single vertical LazyColumn, not one HorizontalPager
 * for each day. Date headers remain visible in the content flow. Adjacent weeks
 * are loaded on demand and reuse the repository's existing file cache.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScheduleAgenda(
    state: MainUiState,
    onEnsureWeek: (Int) -> Unit,
    onRefreshWeek: (Int) -> Unit,
    onRetryWeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val monday = remember(today) {
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    val todayIndex = CENTER_DAY_INDEX + today.dayOfWeek.value - 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = todayIndex)
    val scope = rememberCoroutineScope()
    val refreshState = rememberPullToRefreshState()
    val selectedId = state.selected?.id
    val selectedType = state.selected?.type

    LaunchedEffect(selectedId, selectedType) {
        listState.scrollToItem(todayIndex)
    }

    val visibleWeekOffset by remember(listState) {
        derivedStateOf {
            (listState.firstVisibleItemIndex / 7) - CENTER_WEEK
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Лента расписания",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { scope.launch { listState.animateScrollToItem(todayIndex) } },
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null)
                    Text("Сегодня")
                }
                IconButton(
                    onClick = { onRefreshWeek(visibleWeekOffset) },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить текущую неделю")
                }
            }
        }

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
                contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    count = TOTAL_DAYS,
                    key = { it },
                    contentType = { "agenda-day" },
                ) { index ->
                    val relativeWeek = index / 7 - CENTER_WEEK
                    val date = monday.plusDays((index - CENTER_DAY_INDEX).toLong())
                    val dayData: ScheduleDay? =
                        state.agendaWeeks[relativeWeek]?.days
                            ?.firstOrNull { it.date == date.toString() }
                            ?: if (relativeWeek == state.weekOffset) {
                                state.week?.days?.firstOrNull { it.date == date.toString() }
                            } else null

                    LaunchedEffect(selectedId, selectedType, relativeWeek) {
                        onEnsureWeek(relativeWeek)
                        // Prefetch only immediate neighbors. Previously visited weeks
                        // are returned by ScheduleRepository's on-disk cache.
                        if (index % 7 == 0 || index == todayIndex) {
                            onEnsureWeek(relativeWeek + 1)
                            onEnsureWeek(relativeWeek - 1)
                        }
                    }

                    AgendaDaySection(
                        date = date,
                        today = today,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgendaDaySection(
    date: LocalDate,
    today: LocalDate,
    day: ScheduleDay?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    val isToday = date == today
    val language = remember { Locale.forLanguageTag("ru") }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", language) }
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy", language) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (date.dayOfMonth == 1 || date == today) {
            Text(
                date.format(monthFormat).replaceFirstChar { it.titlecase(language) },
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (isToday) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ) {
            Text(
                date.format(dateFormat).replaceFirstChar { it.titlecase(language) } +
                    if (isToday) " • Сегодня" else "",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                color = if (isToday) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

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
