package com.rteats.mpeineo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import com.rteats.mpeineo.R
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rteats.mpeineo.data.MailSummary
import com.rteats.mpeineo.data.MailServerCertificate
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailScreen(
    viewModel: MailViewModel,
    webVisible: Boolean,
    onWebVisibleChange: (Boolean) -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var showCertificateDetails by remember { mutableStateOf(false) }

    BackHandler(enabled = active && (webVisible || state.selected != null)) {
        if (webVisible) onWebVisibleChange(false) else viewModel.closeMessage()
    }

    if (webVisible) {
        Column(modifier = modifier.fillMaxSize().testTag("mail-webview")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onWebVisibleChange(false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Вернуться к входящим")
                }
                Text("OWA", style = MaterialTheme.typography.titleMedium)
            }
            WebPortalScreen(
                url = "https://mail.mpei.ru/owa",
                testTag = "portal-mail",
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    state.certificate?.takeIf { showCertificateDetails }?.let { certificate ->
        ModalBottomSheet(
            onDismissRequest = { showCertificateDetails = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f)
                    .verticalScroll(rememberScrollState()),
            ) {
                MailCertificateCard(
                    certificate = certificate,
                    busy = state.loading,
                    onApprove = {
                        showCertificateDetails = false
                        viewModel.approveCertificate()
                    },
                    onDismiss = {
                        showCertificateDetails = false
                        viewModel.dismissCertificateWarning()
                    },
                )
                Spacer(Modifier.size(28.dp))
            }
        }
    }

    Scaffold(
        modifier = modifier.testTag("mail-inbox"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.selected != null) {
                    IconButton(onClick = viewModel::closeMessage) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к письмам")
                    }
                }
                Text(
                    if (state.selected == null) "Входящие" else "Письмо",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (state.selected == null && state.configured) {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить почту")
                    }
                }
                IconButton(onClick = { onWebVisibleChange(true) }) {
                    Icon(painterResource(R.drawable.ic_mail_open_owa), contentDescription = "Открыть OWA")
                }
            }
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (state.error != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (state.inspectingCertificate) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Text("Получаем сведения о TLS-сертификате…")
                }
            }
            if (state.certificate != null) {
                TextButton(
                    onClick = { showCertificateDetails = true },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text("Проверить сертификат и подключиться")
                }
            }
            state.notice?.let { note ->
                Text(
                    note,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                !state.configured -> MailLogin(
                    usernameHint = state.username,
                    busy = state.loading,
                    onLogin = viewModel::login,
                    modifier = Modifier.fillMaxSize(),
                )
                state.selected != null -> MailMessage(
                    state = state,
                    onDownload = viewModel::download,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> {
                    PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (state.items.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (state.loading) CircularProgressIndicator()
                                        else Text(
                                            "Писем нет. Потяните вниз для обновления.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            items(state.items, key = { "${it.uidValidity}-${it.uid}" }) { item ->
                                MailInboxRow(summary = item, onClick = { viewModel.open(item) })
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                            item { Spacer(Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}


/**
 * Explicit certificate exception for mail.mpei.ru only. We show the actual
 * leaf SHA-256, certificate identity and validity before requesting consent.
 */
@Composable
private fun MailCertificateCard(
    certificate: MailServerCertificate,
    busy: Boolean,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember(certificate.sha256) { mutableStateOf(false) }
    val allowed = certificate.currentlyValid && certificate.matchesMailHostname
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                "Сертификат почтового сервера",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "MPEI использует центр сертификации, которому Android не доверяет " +
                    "по умолчанию. Сравните отпечаток SHA-256 ниже с тем, который " +
                    "показывает FairEmail для mail.mpei.ru:993, либо подтвердите его " +
                    "у администраторов МЭИ. Сходство названия недостаточно.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Сервер: mail.mpei.ru:993",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Кому выдан: ${certificate.subject}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Кем выдан: ${certificate.issuer}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Действует до: " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(Date(certificate.validUntil)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "SHA-256 отпечаток сертификата:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(
                    certificate.sha256.chunked(2).joinToString(":"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            certificate.chainIssuerSha256?.let { caFingerprint ->
                Text(
                    "SHA-256 сертификата CA (если сервер его отправил):",
                    style = MaterialTheme.typography.labelMedium,
                )
                SelectionContainer {
                    Text(
                        caFingerprint.chunked(2).joinToString(":"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!allowed) {
                Text(
                    if (!certificate.currentlyValid) {
                        "Сертификат недействителен по сроку. Его нельзя подтвердить."
                    } else {
                        "Имя mail.mpei.ru отсутствует в сертификате. Подключение заблокировано."
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { checked = !checked },
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { checked = it },
                    )
                    Text(
                        "Я сверил SHA-256 по независимому доверенному источнику",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = checked && !busy,
                    onClick = onApprove,
                ) {
                    Text("Доверять этому сертификату и подключиться")
                }
            }
            TextButton(onClick = onDismiss) {
                Text("Не доверять")
            }
            Text(
                "Доверие действует только для точного сертификата сервера " +
                    "mail.mpei.ru и не отключает проверку имени хоста. " +
                    "При замене сертификата потребуется новое подтверждение.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MailLogin(
    usernameHint: String,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by rememberSaveable { mutableStateOf(usernameHint) }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Подключение почты МЭИ",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Используйте те же имя пользователя и пароль IMAP, что и в FairEmail. " +
                "Подключение: mail.mpei.ru · SSL/TLS · 993. SMTP не требуется.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Имя пользователя IMAP") },
            placeholder = { Text("Как в настройках FairEmail") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль IMAP") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onLogin(username, password) },
            enabled = !busy && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else Text("Подключить входящие")
        }
        Text(
            "Пароль шифруется ключом Android Keystore и хранится только на устройстве. " +
                "Приложение не отправляет письма и не изменяет их статус прочтения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MailInboxRow(summary: MailSummary, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    summary.sender.ifBlank { "Неизвестный отправитель" },
                    modifier = Modifier.weight(1f),
                    fontWeight = if (summary.unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(summary.sentAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = {
            Text(
                summary.subject.ifBlank { "Без темы" },
                fontWeight = if (summary.unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(48.dp),
                color = if (summary.unread) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        summary.sender.take(1).uppercase().ifBlank { "@" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        trailingContent = {
            // A full multipart may contain no actual attachments; do not
            // show a misleading paperclip based on MIME alone.
        },
    )
    // The transparent tap layer preserves standard ListItem layout without
    // adding a click target to any individual text.
}

@Composable
private fun MailMessage(
    state: MailUiState,
    onDownload: (com.rteats.mpeineo.data.MailAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.message
    when {
        state.loadingMessage -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        detail == null -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("Письмо не загружено")
        }
        else -> Column(
            modifier = modifier.verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                detail.subject.ifBlank { "Без темы" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(detail.sender, style = MaterialTheme.typography.bodyMedium)
            Text(
                DateFormat.getDateTimeInstance().format(Date(detail.sentAt)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider()
            if (detail.attachments.isNotEmpty()) {
                Text(
                    "Вложения",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                detail.attachments.forEach { attachment ->
                    Card(onClick = { onDownload(attachment) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_mail_attachment), contentDescription = null)
                            Text(
                                attachment.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.savingAttachment == attachment.partPath) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else Icon(
                                painterResource(R.drawable.ic_mail_download),
                                contentDescription = "Сохранить в Загрузки",
                            )
                        }
                    }
                }
                Text(
                    "Нажмите на вложение, чтобы сохранить его в Загрузки/MPEI Neo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
            }
            SelectionContainer {
                Text(
                    annotatedMailLinks(detail.body),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}

/** Compose's URL links use Android's browser handler; no embedded WebView. */
private fun annotatedMailLinks(plain: String) = buildAnnotatedString {
    append(plain)
    val expression = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    expression.findAll(plain).forEach { match ->
        val clean = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
        if (clean.isNotEmpty()) {
            addLink(LinkAnnotation.Url(clean), match.range.first, match.range.first + clean.length)
            addStyle(
                SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF3D77C5), textDecoration = TextDecoration.Underline),
                match.range.first,
                match.range.first + clean.length,
            )
        }
    }
}
