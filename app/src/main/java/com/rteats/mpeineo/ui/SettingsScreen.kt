package com.rteats.mpeineo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    onRefreshOnLaunchChanged: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cacheCleared by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                )
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .weight(1f),
                ) {
                    Text(
                        "Обновлять при запуске",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Если выключено, приложение использует кэш до ручного обновления.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.refreshOnLaunch,
                    onCheckedChange = onRefreshOnLaunchChanged,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                    )
                    Text(
                        "Локальный кэш",
                        modifier = Modifier.padding(start = 14.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "Сохранённые недели позволяют смотреть расписание без повторной загрузки и при проблемах с сетью.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        top = 8.dp,
                        bottom = 12.dp,
                    ),
                )
                OutlinedButton(
                    onClick = {
                        onClearCache()
                        cacheCleared = true
                    },
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (cacheCleared) {
                            "Кэш очищен"
                        } else {
                            "Очистить кэш"
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        Text(
            "MPEI Neo",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Неофициальный лёгкий клиент расписания МЭИ. Не хранит логины БАРС, не содержит аналитики и не требует аккаунта.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "На Android 12+ цвета интерфейса берутся из системной палитры Material You (Monet).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
