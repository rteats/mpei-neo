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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
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
        )

        WeekNavigation(
            weekOffset = state.weekOffset,
            onPreviousWeek = onPreviousWeek,
            onNextWeek = onNextWeek,
            onCurrentWeek = onCurrentWeek,
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
                isRefreshing = state.isLoading,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun ScheduleHeader(
    target: ScheduleTarget,
    isFavorite: Boolean,
    source: ScheduleSource?,
    onToggleFavorite: () -> Unit,
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
        }
    }
}

@Composable
private fun WeekNavigation(
    weekOffset: Int,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
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
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = weekOffset,
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = slideInHorizontally(
                        animationSpec = tween(180),
                        initialOffsetX = { width -> if (forward) width / 4 else -width / 4 },
                    ) + fadeIn(animationSpec = tween(140))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(160),
                        targetOffsetX = { width -> if (forward) -width / 4 else width / 4 },
                    ) + fadeOut(animationSpec = tween(110))
                    enter togetherWith exit
                },
                label = "week-range",
            ) { offset ->
                Text(
                    formatWeekRange(weekStartForOffset(offset)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (weekOffset != 0) {
                Text(
                    "На текущую неделю",
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable(onClick = onCurrentWeek),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.WeekContent(
    week: ScheduleWeek,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    key(week.weekStart) {
        WeekPager(
            week = week,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.WeekPager(
    week: ScheduleWeek,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
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

    DaySelector(
        week = week,
        selectedDay = pagerState.currentPage,
        onSelect = { index ->
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
private fun DaySelector(
    week: ScheduleWeek,
    selectedDay: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        week.days.forEachIndexed { index, day ->
            val date = LocalDate.parse(day.date)
            val selected = index == selectedDay
            val container = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
            val content = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) },
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
        ) { _, lesson ->
            LessonCard(lesson)
        }
    }
}

@Composable
private fun LessonCard(lesson: Lesson) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = lessonContainerColor(lesson.kind),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${lesson.startTime}–${lesson.endTime}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                lesson.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (lesson.kind.isNotBlank()) {
                Text(
                    lesson.kind,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
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

private fun weekStartForOffset(offset: Int): LocalDate =
    LocalDate.now()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .plusWeeks(offset.toLong())

private fun formatWeekRange(start: LocalDate): String {
    val end = start.plusDays(6)
    val formatter = DateTimeFormatter.ofPattern(
        "d MMM",
        Locale("ru"),
    )
    return start.format(formatter) + " — " + end.format(formatter)
}
