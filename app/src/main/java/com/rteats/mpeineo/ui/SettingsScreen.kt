package com.rteats.mpeineo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.testTag
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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


private enum class SettingsPage { HOME, UPDATE, MAIL, LOGS, STORAGE, ABOUT }

private data class SettingsLink(
    val title: String,
    val subtitle: String,
    val icon: Int,
    val page: SettingsPage,
)

/** Tonal grouped rows with accent-circle icons, inspired by EasyNotes' layout. */
@Composable
private fun SettingsGroup(
    links: List<SettingsLink>,
    onOpen: (SettingsPage) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column {
            links.forEachIndexed { index, link ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onOpen(link.page) }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(link.title, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(link.subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(link.icon), contentDescription = null,
                                modifier = Modifier.size(24.dp))
                        }
                    }
                }
                if (index != links.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        title, color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SettingsScreen(
    updateViewModel: UpdateViewModel,
    mailViewModel: MailViewModel,
    onClearCache: () -> Unit,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(SettingsPage.HOME) }
    var cacheCleared by remember { mutableStateOf(false) }
    var logCopied by remember { mutableStateOf(false) }
    val updateState by updateViewModel.state.collectAsState()
    val mailState by mailViewModel.state.collectAsState()
    val context = LocalContext.current
    val diagnostics = (context.applicationContext as MpeiNeoApplication).container.diagnostics
    val appName = if (BuildConfig.UPDATE_CHANNEL == "dev") "MPEI Neo Dev" else "MPEI Neo"

    BackHandler(enabled = active && page != SettingsPage.HOME) {
        page = SettingsPage.HOME
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            updateViewModel.installDownloadedUpdate(context)
        }
    }
    fun installUpdate() {
        if (context.packageManager.canRequestPackageInstalls()) {
            updateViewModel.installDownloadedUpdate(context)
        } else {
            permissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    if (page == SettingsPage.HOME) {
        LazyColumn(
            modifier = modifier.fillMaxSize().testTag("settings-overview"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 24.dp, bottom = 36.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Настройки", style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold)
                Text("Приложение, подключения и данные",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Surface(
                    onClick = { page = SettingsPage.UPDATE },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(appName, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Text("Версия ${BuildConfig.VERSION_NAME} · обновления",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                    }
                }
            }
            item { SettingsGroupTitle("АККАУНТЫ") }
            item {
                SettingsGroup(
                    listOf(SettingsLink(
                        "Почта МЭИ",
                        if (mailState.configured) mailState.username else "Аккаунт не подключён",
                        R.drawable.ic_nav_mail, SettingsPage.MAIL,
                    )),
                    onOpen = { page = it },
                )
            }
            item { SettingsGroupTitle("ПРИЛОЖЕНИЕ") }
            item {
                SettingsGroup(
                    listOf(
                        SettingsLink("Локальный кэш", "Сохранённые недели расписания",
                            R.drawable.ic_settings_update, SettingsPage.STORAGE),
                        SettingsLink("Диагностика", "Журнал событий и ошибок",
                            R.drawable.ic_settings_diagnostics, SettingsPage.LOGS),
                    ),
                    onOpen = { page = it },
                )
            }
            item { SettingsGroupTitle("ИНФОРМАЦИЯ") }
            item {
                SettingsGroup(
                    listOf(SettingsLink("О приложении", "Версия и конфиденциальность",
                        R.drawable.ic_nav_bars, SettingsPage.ABOUT)),
                    onOpen = { page = it },
                )
            }
        }
    } else {
        val title = when (page) {
            SettingsPage.UPDATE -> "Обновления"
            SettingsPage.MAIL -> "Почта МЭИ"
            SettingsPage.LOGS -> "Диагностика"
            SettingsPage.STORAGE -> "Локальный кэш"
            SettingsPage.ABOUT -> "О приложении"
            SettingsPage.HOME -> "Настройки"
        }
        Column(modifier = modifier.fillMaxSize().testTag("settings-detail")) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { page = SettingsPage.HOME }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Вернуться к настройкам")
                }
                Text(title, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    SettingsPage.UPDATE -> UpdateCard(
                        state = updateState,
                        onCheck = updateViewModel::checkForUpdates,
                        onDownload = updateViewModel::downloadUpdate,
                        onInstall = ::installUpdate,
                    )
                    SettingsPage.MAIL -> Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (mailState.configured) "Подключённый аккаунт" else "Почта не подключена",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(if (mailState.configured) mailState.username
                                else "Подключите аккаунт на вкладке «Почта».",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (mailState.configured) {
                                OutlinedButton(onClick = mailViewModel::logout) {
                                    Icon(painterResource(R.drawable.ic_mail_logout),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("Выйти из почты")
                                }
                            }
                        }
                    }
                    SettingsPage.LOGS -> Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Журнал диагностики",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text("Журнал сохраняется после обновлений. В него не записываются пароли, 2FA-коды, cookie или токены.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {
                                val data = diagnostics.readText().ifBlank {
                                    "Журнал диагностики пока пуст."
                                }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("MPEI Neo diagnostics", data))
                                logCopied = true
                                diagnostics.log("DIAGNOSTICS", "log copied to clipboard")
                            }) {
                                Text(if (logCopied) "Скопировано" else "Копировать журнал")
                            }
                            OutlinedButton(onClick = {
                                diagnostics.clear()
                                diagnostics.log("DIAGNOSTICS", "log cleared by user")
                                logCopied = false
                            }) { Text("Очистить журнал") }
                        }
                    }
                    SettingsPage.STORAGE -> Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Сохранённые расписания",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text("Сохранённые недели доступны без повторной загрузки и при проблемах с сетью.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {
                                onClearCache()
                                cacheCleared = true
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(if (cacheCleared) "Кэш очищен" else "Очистить кэш")
                            }
                        }
                    }
                    SettingsPage.ABOUT -> Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("$appName ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text("Неофициальный клиент МЭИ. Пароль и 2FA-коды БАРС приложение не сохраняет; аналитики нет.")
                            Text("На Android 12+ цвета интерфейса берутся из системной палитры Material You (Monet).",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    SettingsPage.HOME -> Unit
                }
                Spacer(Modifier.size(24.dp))
            }
        }
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
