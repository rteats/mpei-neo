package com.rteats.mpeineo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.BuildConfig
import com.rteats.mpeineo.MpeiNeoApplication
import com.rteats.mpeineo.R

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    updateViewModel: UpdateViewModel,
    onRefreshOnLaunchChanged: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cacheCleared by remember { mutableStateOf(false) }
    var logCopied by remember { mutableStateOf(false) }
    val updateState by updateViewModel.state.collectAsState()
    val context = LocalContext.current
    val application = context.applicationContext as MpeiNeoApplication
    val diagnostics = application.container.diagnostics

    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            updateViewModel.installDownloadedUpdate(context)
        }
    }

    fun installDownloadedUpdate() {
        if (context.packageManager.canRequestPackageInstalls()) {
            updateViewModel.installDownloadedUpdate(context)
        } else {
            installPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
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
                        "Обновлять расписание при запуске",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Если выключено, приложение использует кэш до обновления жестом вниз.",
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

        UpdateCard(
            state = updateState,
            onCheck = updateViewModel::checkForUpdates,
            onDownload = updateViewModel::downloadUpdate,
            onInstall = ::installDownloadedUpdate,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_diagnostics),
                        contentDescription = null,
                    )
                    Text(
                        "Диагностика",
                        modifier = Modifier.padding(start = 14.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "Журнал сохраняется между перезапусками и обновлениями. В него не записываются пароли, 2FA-коды, cookie или токены БАРС.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val text = diagnostics.readText().ifBlank {
                                "Журнал диагностики пока пуст."
                            }
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE,
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("MPEI Neo diagnostics", text),
                            )
                            logCopied = true
                            diagnostics.log("DIAGNOSTICS", "log copied to clipboard")
                        },
                    ) {
                        Text(if (logCopied) "Скопировано" else "Копировать журнал")
                    }
                    OutlinedButton(
                        onClick = {
                            diagnostics.clear()
                            diagnostics.log("DIAGNOSTICS", "log cleared by user")
                            logCopied = false
                        },
                    ) {
                        Text("Очистить")
                    }
                }
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
            "${if (BuildConfig.UPDATE_CHANNEL == "dev") "MPEI Neo Dev" else "MPEI Neo"} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Неофициальный клиент МЭИ. Пароль и 2FA-коды БАРС приложение не сохраняет; аналитики нет.",
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_update),
                    contentDescription = null,
                )
                Column(
                    modifier = Modifier
                        .padding(start = 14.dp)
                        .weight(1f),
                ) {
                    Text(
                        "Обновления приложения",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (BuildConfig.UPDATE_CHANNEL == "dev") {
                            "Канал Dev: автоматические сборки из main."
                        } else {
                            "Канал Stable: проверенные GitHub Releases."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (state.phase) {
                UpdatePhase.IDLE -> {
                    Text(
                        "Текущая версия: ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCheck) {
                        Text("Проверить обновления")
                    }
                }

                UpdatePhase.CHECKING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ContainedLoadingIndicator()
                        Text("Проверяем GitHub Releases…")
                    }
                }

                UpdatePhase.UP_TO_DATE -> {
                    Text(
                        "Установлена актуальная версия.",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.release?.let {
                        Text(
                            "Последний релиз: ${it.versionName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onCheck) {
                        Text("Проверить снова")
                    }
                }

                UpdatePhase.AVAILABLE -> {
                    val release = state.release
                    Text(
                        "Доступно обновление ${release?.versionName.orEmpty()}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    release?.notes
                        ?.takeIf { it.isNotBlank() }
                        ?.let { notes ->
                            Text(
                                text = notes,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    Button(onClick = onDownload) {
                        Text("Скачать обновление")
                    }
                }

                UpdatePhase.DOWNLOADING -> {
                    val progress = state.progressPercent
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Скачивание: ${progress}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Скачивание APK…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                UpdatePhase.READY_TO_INSTALL -> {
                    Text(
                        "APK скачан и проверен. Android откроет системный установщик.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onInstall) {
                        Text("Установить обновление")
                    }
                }

                UpdatePhase.ERROR -> {
                    Text(
                        state.error ?: "Не удалось обновить приложение",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (state.release != null) {
                        OutlinedButton(onClick = onDownload) {
                            Text("Повторить загрузку")
                        }
                    } else {
                        OutlinedButton(onClick = onCheck) {
                            Text("Повторить проверку")
                        }
                    }
                }
            }
        }
    }
}
