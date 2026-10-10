package com.rteats.mpeineo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.model.Lesson
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import java.time.Duration
import java.time.LocalTime
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScheduleScreen(
    state: MainUiState,
    onSelect: (ScheduleTarget) -> Unit,
    onToggleFavorite: (ScheduleTarget) -> Unit,
    onRenameFavorite: (ScheduleTarget, String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onTypeChanged: (ScheduleTargetType?) -> Unit,
    onSearch: () -> Unit,
    onEnsureAgendaWeek: (Int) -> Unit,
    onRefreshAgendaWeek: (Int) -> Unit,
    onRetryAgendaWeek: (Int) -> Unit,
    todayJumpRequest: Int,
    modifier: Modifier = Modifier,
) {
    val searchScrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

    androidx.compose.material3.Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScheduleDockedSearch(
                state = state,
                onQueryChanged = onQueryChanged,
                onTypeChanged = onTypeChanged,
                onSearch = onSearch,
                onSelect = onSelect,
                onToggleFavorite = onToggleFavorite,
                onRenameFavorite = onRenameFavorite,
                scrollBehavior = searchScrollBehavior,
            )
        },
    ) { innerPadding ->
        val selected = state.selected
        if (selected == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
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
                            "Найдите группу, преподавателя или аудиторию в поиске.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            ScheduleAgenda(
                state = state,
                onEnsureWeek = onEnsureAgendaWeek,
                onRefreshWeek = onRefreshAgendaWeek,
                onRetryWeek = onRetryAgendaWeek,
                todayJumpRequest = todayJumpRequest,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            )
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
 * Compact, tinted lesson card: period and time, title, then icon-led metadata.
 * Period numbers come from official start times, not the visible row index.
 */
@Composable
internal fun LessonCard(lesson: Lesson) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = lessonContainerColor(lesson.kind),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                lessonPeriodNumber(lesson.startTime)?.let { period ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
                    ) {
                        Text(
                            period.toString(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    "${lesson.startTime}–${lesson.endTime}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (lesson.kind.isNotBlank()) LessonTypeChip(lesson.kind)
            }

            Text(
                lesson.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (lesson.place.isNotBlank() || lesson.lecturer.isNotBlank() ||
                lesson.groups.isNotBlank()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (lesson.place.isNotBlank()) {
                        LessonDetail(
                            icon = { Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            text = lesson.place,
                        )
                    }
                    if (lesson.lecturer.isNotBlank()) {
                        LessonDetail(
                            icon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            text = lesson.lecturer,
                        )
                    }
                    if (lesson.groups.isNotBlank()) {
                        LessonDetail(
                            icon = { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            text = lesson.groups,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonDetail(
    icon: @Composable () -> Unit,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LessonTypeChip(kind: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Text(
            kind,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun lessonContainerColor(kind: String): Color {
    val scheme = MaterialTheme.colorScheme
    val normalized = kind.lowercase(Locale.forLanguageTag("ru"))
    val semantic = when {
        "лаб" in normalized || "lab" in normalized -> Color(0xFFEF5350)
        "лек" in normalized || "lecture" in normalized -> Color(0xFF66BB6A)
        "сем" in normalized || "прак" in normalized ||
            "seminar" in normalized || "practice" in normalized -> Color(0xFFFFCA28)
        "конс" in normalized || "экзам" in normalized ||
            "зач" in normalized || "exam" in normalized -> scheme.onSurfaceVariant
        else -> return scheme.surfaceContainer
    }
    val monetAdjusted = if (
        "конс" in normalized || "экзам" in normalized ||
        "зач" in normalized || "exam" in normalized
    ) semantic else lerp(semantic, scheme.primary, 0.12f)

    return lerp(
        scheme.surfaceContainer,
        monetAdjusted,
        if (monetAdjusted == scheme.onSurfaceVariant) 0.08f else 0.13f,
    )
}
