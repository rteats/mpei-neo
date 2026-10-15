package com.rteats.mpeineo.ui

import com.rteats.mpeineo.AppIcon
import com.rteats.mpeineo.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
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
import com.rteats.mpeineo.data.OwaMailFolder
import com.rteats.mpeineo.data.MailServerCertificate
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MailScreen(
    viewModel: MailViewModel,
    active: Boolean,
    onOpenPortal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val inboxScrollState = rememberLazyListState()
    val sentScrollState = rememberLazyListState()
    val scrollState = if (state.mailbox == OwaMailFolder.SENT) sentScrollState else inboxScrollState
    var inboxQuery by rememberSaveable { mutableStateOf("") }
    val folderItems = if (state.mailbox == OwaMailFolder.SENT) state.sentItems else state.items
    val folderLoading = if (state.mailbox == OwaMailFolder.SENT) state.sentLoading else state.loading
    val folderLoadingMore = if (state.mailbox == OwaMailFolder.SENT)
        state.sentLoadingMore else state.loadingMore
    val folderHasMore = if (state.mailbox == OwaMailFolder.SENT)
        state.sentHasMore else state.hasMore
    val folderMoreError = if (state.mailbox == OwaMailFolder.SENT)
        state.sentMoreError else state.moreError
    val visibleMessages = if (inboxQuery.isBlank()) folderItems else folderItems.filter {
        it.sender.contains(inboxQuery.trim(), ignoreCase = true) ||
            it.recipients.orEmpty().any { recipient ->
                recipient.contains(inboxQuery.trim(), ignoreCase = true)
            } ||
            it.subject.contains(inboxQuery.trim(), ignoreCase = true)
    }
    // Search is explicitly local to downloaded pages, not a server-wide search.
    LaunchedEffect(scrollState, folderItems.size, folderHasMore,
        folderLoadingMore, folderLoading, folderMoreError, inboxQuery,
        state.selected, active, state.mailbox) {
        if (active && state.selected == null && inboxQuery.isBlank() &&
            folderHasMore && !folderLoadingMore &&
            !folderLoading && !folderMoreError) {
            snapshotFlow {
                scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            }.distinctUntilChanged().collect { last ->
                if (last >= folderItems.size - 4) viewModel.loadMore()
            }
        }
    }
    val motion = MaterialTheme.motionScheme

    BackHandler(enabled = active && !state.composing && state.selected != null) {
        viewModel.closeMessage()
    }


    Scaffold(
        modifier = modifier.testTag("mail-inbox"),
        containerColor = MaterialTheme.colorScheme.background,
        // The app scaffold already handles safe-area insets; no second blank header.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { insets ->
        if (state.composing) {
            MailComposeScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize().padding(insets),
            )
        } else {
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (state.configured) {
                if (state.error != null) {
                    val hasCachedContent = folderItems.isNotEmpty() || state.message != null
                    val canRetryMessage = state.selected != null && state.message == null
                    ServiceStatus(
                        title = when {
                            state.loginRequired -> "Сессия ОСЭП истекла"
                            state.selected != null -> "Не удалось выполнить действие"
                            else -> "Не удалось обновить почту"
                        },
                        detail = if (hasCachedContent && state.selected == null) {
                            "Показаны сохранённые письма."
                        } else state.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        tone = if (hasCachedContent) ServiceStatusTone.RECOVERY
                            else ServiceStatusTone.ERROR,
                        actionLabel = when {
                            state.loginRequired -> "Войти"
                            state.selected == null || canRetryMessage -> "Повторить"
                            else -> null // Re-tap an attachment to retry its download.
                        },
                        onAction = when {
                            state.loginRequired -> onOpenPortal
                            state.selected == null -> viewModel::refresh
                            canRetryMessage -> {
                                { state.selected?.let(viewModel::open); Unit }
                            }
                            else -> null
                        },
                    )
                } else {
                    state.notice?.let { note ->
                        ServiceStatus(
                            title = note,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            if (!state.configured) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    if (state.loading) {
                        ContainedLoadingIndicator()
                        Text(
                            "Проверяем подключение к ОСЭП…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ServiceStatus(
                            title = when {
                                state.loginRequired -> "Войдите в ОСЭП"
                                state.error != null -> "Не удалось получить почту"
                                else -> "Почта ОСЭП не подключена"
                            },
                            detail = when {
                                state.loginRequired -> "Откройте ОСЭП, войдите и вернитесь к почте."
                                state.error != null -> state.error
                                else -> "Войдите через кнопку ОСЭП и вернитесь к входящим."
                            },
                            tone = if (state.error != null) ServiceStatusTone.ERROR
                                else ServiceStatusTone.INFO,
                            actionLabel = if (state.error != null && !state.loginRequired) {
                                "Повторить"
                            } else "Открыть ОСЭП",
                            onAction = if (state.error != null && !state.loginRequired) {
                                viewModel::refresh
                            } else onOpenPortal,
                        )
                    }
                }
            } else {
                // Preserve the inbox LazyListState while smoothly navigating
                // into and out of a message; all mail operations remain native.
                AnimatedContent(
                    targetState = state.selected != null,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val opening = targetState
                        (slideInHorizontally(
                            initialOffsetX = { if (opening) it / 5 else -it / 5 },
                            animationSpec = motion.defaultSpatialSpec(),
                        ) + fadeIn(animationSpec = motion.defaultEffectsSpec())) togetherWith
                            (slideOutHorizontally(
                                targetOffsetX = { if (opening) -it / 5 else it / 5 },
                                animationSpec = motion.defaultSpatialSpec(),
                            ) + fadeOut(animationSpec = motion.defaultEffectsSpec()))
                    },
                    label = "mail-inbox-message",
                ) { reading ->
                    if (reading) {
                        MailMessage(
                            state = state,
                            isSent = state.mailbox == OwaMailFolder.SENT,
                            onDownload = viewModel::download,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val refreshState = rememberPullToRefreshState()
                        PullToRefreshBox(
                            isRefreshing = folderLoading,
                            onRefresh = viewModel::refresh,
                            state = refreshState,
                            modifier = Modifier.fillMaxSize(),
                            indicator = {
                                PullToRefreshDefaults.LoadingIndicator(
                                    state = refreshState,
                                    isRefreshing = folderLoading,
                                    modifier = Modifier.align(Alignment.TopCenter),
                                )
                            },
                        ) {
                            LazyColumn(
                                state = scrollState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 12.dp, end = 12.dp,
                                    top = 12.dp, bottom = 100.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                item(key = "mail-view-header") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            "Почта",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        PortalHeaderAction(label = "ОСЭП", onClick = onOpenPortal)
                                    }
                                }
                                stickyHeader(key = "inbox-search") {
                                    Surface(color = MaterialTheme.colorScheme.background) {
                                    Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        FilterChip(
                                            selected = state.mailbox == OwaMailFolder.INBOX,
                                            onClick = { inboxQuery = ""; viewModel.switchMailbox(OwaMailFolder.INBOX) },
                                            label = { Text("Входящие") },
                                            modifier = Modifier.testTag("mail-folder-inbox"),
                                        )
                                        FilterChip(
                                            selected = state.mailbox == OwaMailFolder.SENT,
                                            onClick = { inboxQuery = ""; viewModel.switchMailbox(OwaMailFolder.SENT) },
                                            label = { Text("Отправленные") },
                                            modifier = Modifier.testTag("mail-folder-sent"),
                                        )
                                    }
                                    OutlinedTextField(
                                        value = inboxQuery,
                                        onValueChange = { inboxQuery = it },
                                        placeholder = { Text("Поиск по загруженным письмам") },
                                        leadingIcon = {
                                            AppIcon(AppIcons.Search, contentDescription = null)
                                        },
                                        trailingIcon = if (inboxQuery.isNotBlank()) {
                                            {
                                                TextButton(onClick = { inboxQuery = "" }) {
                                                    Text("Сброс")
                                                }
                                            }
                                        } else null,
                                        singleLine = true,
                                        shape = MaterialTheme.shapes.extraLarge,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    )
                                    }
                                    }
                                }
                                if (visibleMessages.isEmpty()) {
                                    item(key = "empty-inbox") {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (folderLoading) ContainedLoadingIndicator()
                                            else Text(
                                                if (inboxQuery.isNotBlank()) "Среди загруженных писем совпадений нет"
                                                else if (state.mailbox == OwaMailFolder.SENT)
                                                    "Отправленных писем нет."
                                                else "Писем нет. Потяните вниз для обновления.",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                items(
                                    items = visibleMessages,
                                    key = { "${state.mailbox.name}-${it.uidValidity}-${it.uid}" },
                                ) { item ->
                                    MailInboxRow(
                                        summary = item,
                                        isSent = state.mailbox == OwaMailFolder.SENT,
                                        onClick = { viewModel.open(item) },
                                        modifier = Modifier,
                                    )
                                }
                                if (folderHasMore || folderLoadingMore || folderMoreError) {
                                    item(key = "inbox-older") {
                                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center) {
                                            if (folderLoadingMore) {
                                                ContainedLoadingIndicator()
                                            } else {
                                                TextButton(
                                                    onClick = viewModel::loadMore,
                                                    enabled = !folderLoading,
                                                ) {
                                                    Text(if (folderMoreError) "Повторить загрузку старых писем"
                                                        else "Загрузить предыдущие письма")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}


/**
 * Compact inbox surfaces have separate shapes and muted tonal emphasis.
 * Unread state remains explicit in typography, without badges or extra labels.
 */
@Composable
private fun MailInboxRow(
    summary: MailSummary,
    isSent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val correspondent = if (isSent) {
        summary.recipients.orEmpty().joinToString(", ").ifBlank { "Получатель не определён" }
    } else summary.sender.ifBlank { "Неизвестный отправитель" }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (summary.unread) {
            lerp(
                MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.colorScheme.primaryContainer,
                0.20f,
            )
        } else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MailSenderAvatar(correspondent, summary.unread)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (isSent) "Кому: $correspondent" else correspondent,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (summary.unread) {
                            FontWeight.Bold
                        } else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        DateFormat.getDateInstance(DateFormat.SHORT)
                            .format(Date(summary.sentAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    summary.subject.ifBlank { "<Без темы>" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (summary.unread) {
                        FontWeight.SemiBold
                    } else FontWeight.Normal,
                    color = if (summary.unread) {
                        MaterialTheme.colorScheme.onSurface
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MailSenderAvatar(sender: String, unread: Boolean) {
    Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = if (unread) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (unread) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                sender.trim().take(1).uppercase().ifBlank { "@" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MailMessage(
    state: MailUiState,
    isSent: Boolean,
    onDownload: (com.rteats.mpeineo.data.MailAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.message
    val toRecipients = detail?.recipients?.takeIf { it.isNotEmpty() }
        ?: state.selected?.recipients.orEmpty()
    val correspondent = if (isSent) {
        toRecipients.joinToString(", ").ifBlank { "Получатель не определён" }
    } else detail?.sender.orEmpty()
    when {
        state.loadingMessage -> Box(modifier, contentAlignment = Alignment.Center) {
            ContainedLoadingIndicator()
        }
        detail == null -> Box(modifier, contentAlignment = Alignment.Center) {
            if (state.error == null) {
                Text("Письмо не загружено")
            }
        }
        else -> Column(
            modifier = modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                detail.subject.ifBlank { "<Без темы>" },
                style = MaterialTheme.typography.titleLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MailSenderAvatar(correspondent, unread = false)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        if (isSent) "Кому: $correspondent" else detail.sender,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(detail.sentAt)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )
            if (detail.attachments.isNotEmpty()) {
                Text(
                    "Вложения",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                detail.attachments.forEach { attachment ->
                    Surface(
                        onClick = { onDownload(attachment) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 14.dp,
                                vertical = 13.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(
                                AppIcons.Attachment,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                attachment.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (state.savingAttachment == attachment.partPath) {
                                ContainedLoadingIndicator(
                                    modifier = Modifier.size(26.dp),
                                )
                            } else AppIcon(
                                AppIcons.Download,
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
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
            }
            if (detail.threadMessages.size > 1) {
                Text("Сообщений в переписке: ${detail.threadMessages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                detail.threadMessages.forEachIndexed { index, message ->
                    if (index > 0) HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    Text(message.sender.ifBlank { "Неизвестный отправитель" },
                        style = MaterialTheme.typography.titleSmall)
                    Text(DateFormat.getDateTimeInstance().format(Date(message.sentAt)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(annotatedMailLinks(message.body,
                            linkColor = MaterialTheme.colorScheme.primary),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                SelectionContainer {
                    Text(annotatedMailLinks(detail.body,
                        linkColor = MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.size(100.dp))
        }
    }
}

/** Compose's URL links use Android's browser handler; no embedded WebView. */
private fun annotatedMailLinks(plain: String, linkColor: Color) = buildAnnotatedString {
    append(plain)
    val expression = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    expression.findAll(plain).forEach { match ->
        val clean = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
        if (clean.isNotEmpty()) {
            addLink(LinkAnnotation.Url(clean), match.range.first, match.range.first + clean.length)
            addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                match.range.first,
                match.range.first + clean.length,
            )
        }
    }
}
