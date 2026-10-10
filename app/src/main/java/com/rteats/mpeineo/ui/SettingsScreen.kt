package com.rteats.mpeineo.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rteats.mpeineo.BuildConfig
import com.rteats.mpeineo.MpeiNeoApplication
import com.rteats.mpeineo.R
import com.rteats.mpeineo.data.MailNotificationPreferences
import com.rteats.mpeineo.data.MailNotificationScheduler

/**
 * EasyNotes-inspired surface rows, circular accent badges and trailing
 * switches/actions. Original Compose implementation using Material You colors.
 */
@Composable
private fun SettingsBadge(iconRes: Int, inverted: Boolean = false) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = if (inverted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (inverted) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(iconRes), contentDescription = null,
                modifier = Modifier.size(23.dp))
        }
    }
}

@Composable
private fun SettingsExpansion(
    title: String,
    subtitle: String,
    iconRes: Int,
    expanded: Boolean,
    onExpand: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                SettingsBadge(iconRes, inverted = expanded)
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(165, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(100)),
                exit = shrinkVertically(animationSpec = tween(125, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(90)),
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SettingsAction(
    title: String,
    detail: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsBadge(iconRes)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    iconRes: Int,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsBadge(iconRes)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
internal fun SettingsScreen(
    updateViewModel: UpdateViewModel,
    mailViewModel: MailViewModel,
    onClearCache: () -> Unit,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val diagnostics = (context.applicationContext as MpeiNeoApplication).container.diagnostics
    val mailState by mailViewModel.state.collectAsState()
    val updateState by updateViewModel.state.collectAsState()

    // Stored per installed app, surviving process death and package updates.
    // RememberSaveable alone only survives saved Activity state.
    val sectionPrefs = remember(context) {
        context.getSharedPreferences("settings_sections", Context.MODE_PRIVATE)
    }
    var mailExpanded by remember { mutableStateOf(sectionPrefs.getBoolean("mail", true)) }
    var updatesExpanded by remember { mutableStateOf(sectionPrefs.getBoolean("updates", false)) }
    var storageExpanded by remember { mutableStateOf(sectionPrefs.getBoolean("storage", false)) }
    var logsExpanded by remember { mutableStateOf(sectionPrefs.getBoolean("logs", false)) }
    var aboutExpanded by remember { mutableStateOf(sectionPrefs.getBoolean("about", false)) }
    fun changeSection(key: String, next: Boolean) {
        sectionPrefs.edit().putBoolean(key, next).apply()
        when (key) {
            "mail" -> mailExpanded = next
            "updates" -> updatesExpanded = next
            "storage" -> storageExpanded = next
            "logs" -> logsExpanded = next
            "about" -> aboutExpanded = next
        }
    }
    var copied by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    var notificationError by remember { mutableStateOf<String?>(null) }
    val notificationPrefs = remember(context) { MailNotificationPreferences(context) }
    var notificationsEnabled by remember { mutableStateOf(notificationPrefs.enabled) }
    var pollMinutes by remember { mutableStateOf(notificationPrefs.intervalMinutes) }

    LaunchedEffect(mailState.configured) {
        if (!mailState.configured) notificationsEnabled = false
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && mailState.configured) {
            MailNotificationScheduler.setEnabled(context, true)
            notificationsEnabled = true
            notificationError = null
        } else {
            notificationError = "Разрешите уведомления для MPEI Neo в настройках Android."
        }
    }

    fun toggleNotifications(on: Boolean) {
        if (!on) {
            MailNotificationScheduler.setEnabled(context, false)
            notificationsEnabled = false
            notificationError = null
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MailNotificationScheduler.setEnabled(context, true)
            notificationsEnabled = true
            notificationError = null
        }
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
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
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName)),
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("settings-overview"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold)
        }
        item {
            SettingsExpansion(
                "Почта МЭИ",
                if (mailState.configured) mailState.username else "Аккаунт не подключён",
                R.drawable.ic_nav_mail,
                mailExpanded, { changeSection("mail", !mailExpanded) },
            ) {
                SettingsSwitchRow(
                    "Уведомления о новых письмах",
                    "Фоновая проверка IMAP, без постоянного подключения",
                    R.drawable.ic_nav_mail,
                    notificationsEnabled, mailState.configured,
                    ::toggleNotifications,
                )
                if (notificationsEnabled && mailState.configured) {
                    Text("Интервал проверки",
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                        style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 10.dp)) {
                        listOf(15, 30, 60).forEach { minutes ->
                            FilterChip(
                                selected = pollMinutes == minutes,
                                onClick = {
                                    pollMinutes = minutes
                                    MailNotificationScheduler.changeInterval(context, minutes)
                                },
                                label = { Text(minutes.toString() + " мин") },
                            )
                        }
                    }
                    Text(
                        "Android может откладывать проверки ради экономии батареи.",
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                notificationError?.let { error ->
                    Text(error, modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (mailState.configured) {
                    SettingsAction(
                        "Выйти из почты",
                        "Отключить почту и остановить проверку новых писем",
                        R.drawable.ic_mail_logout,
                        onClick = {
                            mailViewModel.logout()
                            notificationsEnabled = false
                        },
                    )
                } else {
                    Text("Для настройки уведомлений войдите в почту на вкладке «Почта».",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            SettingsExpansion(
                "Обновления приложения",
                "Версия " + BuildConfig.VERSION_NAME + " · канал " + BuildConfig.UPDATE_CHANNEL,
                R.drawable.ic_settings_update,
                updatesExpanded, { changeSection("updates", !updatesExpanded) },
            ) {
                UpdateCard(updateState, updateViewModel::checkForUpdates,
                    updateViewModel::downloadUpdate, ::installDownloadedUpdate)
            }
        }
        item {
            SettingsExpansion(
                "Локальный кэш",
                "Расписание доступно без сети",
                R.drawable.ic_settings_update,
                storageExpanded, { changeSection("storage", !storageExpanded) },
            ) {
                SettingsAction(
                    if (cacheCleared) "Кэш очищен" else "Очистить кэш",
                    "Удалить сохранённые недели расписания",
                    R.drawable.ic_settings_update,
                    onClick = { onClearCache(); cacheCleared = true },
                )
            }
        }
        item {
            SettingsExpansion(
                "Диагностика",
                "Копирование и очистка журнала",
                R.drawable.ic_settings_diagnostics,
                logsExpanded, { changeSection("logs", !logsExpanded) },
            ) {
                SettingsAction(
                    if (copied) "Скопировано" else "Копировать журнал",
                    "Скопировать диагностические события",
                    R.drawable.ic_settings_diagnostics,
                    onClick = {
                        val content = diagnostics.readText().ifBlank { "Журнал пока пуст." }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("MPEI Neo diagnostics", content))
                        copied = true
                        diagnostics.log("DIAGNOSTICS", "log copied to clipboard")
                    },
                )
                SettingsAction("Очистить журнал", "Удалить диагностические события",
                    R.drawable.ic_settings_diagnostics,
                    onClick = {
                        diagnostics.clear()
                        diagnostics.log("DIAGNOSTICS", "log cleared by user")
                        copied = false
                    },
                )
            }
        }
        item {
            SettingsExpansion(
                "О приложении",
                "MPEI Neo · " + BuildConfig.VERSION_NAME,
                R.drawable.ic_nav_bars,
                aboutExpanded, { changeSection("about", !aboutExpanded) },
            ) {
                Text(
                    "MPEI Neo " + BuildConfig.VERSION_NAME + " (" +
                        BuildConfig.VERSION_CODE + ")",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Неофициальный клиент МЭИ. Пароли и коды 2FA БАРС не сохраняются. " +
                        "Цвета интерфейса берутся из палитры Material You.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}


@Composable
private fun SettingsActionButton(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
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
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsBadge(R.drawable.ic_settings_update, inverted = true)
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
                    SettingsActionButton(onClick = onCheck) {
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
                    SettingsActionButton(onClick = onCheck) {
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
                    SettingsActionButton(onClick = onDownload) {
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
                    SettingsActionButton(onClick = onInstall) {
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
                        SettingsActionButton(onClick = onDownload) {
                            Text("Повторить загрузку")
                        }
                    } else {
                        SettingsActionButton(onClick = onCheck) {
                            Text("Повторить проверку")
                        }
                    }
                }
            }
    }
}
