package com.rteats.mpeineo.ui

import androidx.lifecycle.ViewModel
import android.net.Uri
import android.provider.OpenableColumns
import com.rteats.mpeineo.data.OutgoingMail
import com.rteats.mpeineo.data.OutgoingFile
import com.rteats.mpeineo.data.OwaComposeProtocol
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rteats.mpeineo.data.MailAttachment
import com.rteats.mpeineo.data.MailCredentials
import com.rteats.mpeineo.data.MailDetail
import com.rteats.mpeineo.data.MailRepository
import com.rteats.mpeineo.data.OwaAuthProbe
import com.rteats.mpeineo.data.OwaMailRepository
import com.rteats.mpeineo.data.OwaMailFolder
import com.rteats.mpeineo.data.OwaRecipientSuggestion
import com.rteats.mpeineo.data.OwaRecipientDirectory
import com.rteats.mpeineo.data.MailNotificationScheduler
import com.rteats.mpeineo.data.MailInboxSyncEvents
import com.rteats.mpeineo.data.MailSummary
import com.rteats.mpeineo.data.MailServerCertificate
import com.rteats.mpeineo.data.markMailRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class RecipientField { TO, CC, BCC }

internal data class MailUiState(
    val configured: Boolean = false,
    val loginRequired: Boolean = false,
    val certificate: MailServerCertificate? = null,
    val inspectingCertificate: Boolean = false,
    val username: String = "",
    val loading: Boolean = false,
    val items: List<MailSummary> = emptyList(),
    val mailbox: OwaMailFolder = OwaMailFolder.INBOX,
    val sentItems: List<MailSummary> = emptyList(),
    val sentLoaded: Boolean = false,
    val sentLoading: Boolean = false,
    val sentLoadingMore: Boolean = false,
    val sentHasMore: Boolean = false,
    val sentNextPageOffset: Int = 0,
    val sentMoreError: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextPageOffset: Int = 0,
    val moreError: Boolean = false,
    val selected: MailSummary? = null,
    val message: MailDetail? = null,
    val loadingMessage: Boolean = false,
    val savingAttachment: List<Int>? = null,
    val notice: String? = null,
    val error: String? = null,
    val composing: Boolean = false,
    val draft: OutgoingMail = OutgoingMail(),
    val outgoingFiles: List<OutgoingFile> = emptyList(),
    val sending: Boolean = false,
    val sendStage: String? = null,
    val sendError: String? = null,
    val activeRecipientField: RecipientField? = null,
    val recipientSuggestions: List<OwaRecipientSuggestion> = emptyList(),
    val recipientSearching: Boolean = false,
)

/** Preserve OWA's server-provided newest-first order and avoid duplicate rows
 *  when a conversation shifts across page boundaries during a new delivery. */
internal fun mergeOwaInboxPages(
    existing: List<MailSummary>,
    older: List<MailSummary>,
): List<MailSummary> = (existing + older).distinctBy { it.uidValidity to it.uid }

/** Preserve previously fetched recipients when a refreshed sent page omits To. */
internal fun mergeOwaSentPages(
    incoming: List<MailSummary>,
    cached: List<MailSummary>,
): List<MailSummary> {
    val byId = cached.associateBy { it.uidValidity to it.uid }
    return (incoming + cached).distinctBy { it.uidValidity to it.uid }.map { message ->
        if (!message.recipients.isNullOrEmpty()) message
        else byId[message.uidValidity to message.uid]?.recipients
            ?.takeIf { it.isNotEmpty() }?.let { message.copy(recipients = it) } ?: message
    }
}

private const val MAIL_PAGE_SIZE = 60

/** Native mail reads and refreshes exclusively through OWA. */
internal class MailViewModel(
    private val repository: MailRepository,
    private val owaAuthProbe: OwaAuthProbe,
    private val owaMailRepository: OwaMailRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MailUiState())
    val state: StateFlow<MailUiState> = _state.asStateFlow()
    private var sessionVersion = 0L
    private var refreshSequence = 0L
    private var pendingNotification: Pair<Long, Long>? = null
    private var recipientJob: Job? = null
    private var sentRecipientJob: Job? = null

    private fun hydrateSentRecipients(items: List<MailSummary>) {
        sentRecipientJob?.cancel()
        val version = sessionVersion
        sentRecipientJob = viewModelScope.launch {
            for (batch in items.filter { it.recipients.isNullOrEmpty() }.chunked(10)) {
                val resolved = try {
                    owaMailRepository.sentRecipientNames(batch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    repository.logState("OWA sent recipients unavailable type=" +
                        failure.javaClass.simpleName)
                    emptyMap()
                }
                if (version != sessionVersion) return@launch
                if (resolved.isNotEmpty()) {
                    _state.update { current ->
                        current.copy(sentItems = current.sentItems.map { item ->
                            resolved[item.uid]?.let { item.copy(recipients = it) } ?: item
                        })
                    }
                }
            }
        }
    }

    init {
        val cached = owaMailRepository.cachedInbox()
        if (cached.isNotEmpty()) {
            _state.update {
                it.copy(configured = true, items = cached,
                    hasMore = cached.size >= MAIL_PAGE_SIZE, nextPageOffset = cached.size)
            }
        }
        val savedSent = owaMailRepository.cachedSent()
        if (savedSent.isNotEmpty()) {
            _state.update {
                it.copy(sentItems = savedSent, sentHasMore = savedSent.size >= MAIL_PAGE_SIZE,
                    sentNextPageOffset = savedSent.size)
            }
        }
        // WorkManager uses a separate repository instance. Its successful OWA
        // poll already writes the shared encrypted cache; reflect the new first
        // page in memory rather than re-fetching the same 60 conversations.
        viewModelScope.launch {
            MailInboxSyncEvents.updates.collect { update ->
                if (update.account != owaMailRepository.cachedAccountKey()) return@collect
                val version = sessionVersion
                _state.update { old ->
                    if (version != sessionVersion) return@update old
                    val previousKeys = old.items.mapTo(HashSet()) {
                        it.uidValidity to it.uid
                    }
                    val newAtHead = update.firstPage.count {
                        (it.uidValidity to it.uid) !in previousKeys
                    }
                    old.copy(
                        configured = true,
                        items = mergeOwaInboxPages(update.firstPage, old.items),
                        nextPageOffset = if (old.nextPageOffset > 0)
                            maxOf(old.nextPageOffset + newAtHead, update.firstPage.size)
                        else update.firstPage.size,
                        hasMore = old.hasMore || (old.items.isEmpty() &&
                            update.firstPage.size == MAIL_PAGE_SIZE),
                    )
                }
                repository.logState("OWA worker cache observed count=${update.firstPage.size}")
            }
        }
        refresh()
    }

    fun refresh() {
        if (_state.value.mailbox == OwaMailFolder.SENT) {
            refreshSent()
            return
        }
        if (_state.value.loading || _state.value.loadingMore) return
        val version = sessionVersion
        val id = ++refreshSequence
        repository.logState("refresh requested id=$id protocol=OWA cachedItems=${_state.value.items.size}")
        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, error = null, notice = null, loginRequired = false)
            }
            try {
                val items = owaMailRepository.inbox(maxConversations = MAIL_PAGE_SIZE, refreshId = id)
                if (version != sessionVersion) return@launch
                repository.credentials.clear()
                repository.logState("refresh success id=$id protocol=OWA items=${items.size}")
                _state.update { old ->
                    val preserved = mergeOwaInboxPages(items, old.items)
                    val seenBefore = old.items.mapTo(mutableSetOf()) {
                        it.uidValidity to it.uid
                    }
                    val addedAtHead = items.count {
                        (it.uidValidity to it.uid) !in seenBefore
                    }
                    old.copy(
                        configured = true,
                        loginRequired = false,
                        loading = false,
                        items = preserved,
                        nextPageOffset = if (old.nextPageOffset > 0) {
                            maxOf(old.nextPageOffset + addedAtHead, items.size)
                        } else items.size,
                        hasMore = if (old.items.size >= MAIL_PAGE_SIZE && !old.hasMore) {
                            false
                        } else items.size == MAIL_PAGE_SIZE,
                        loadingMore = false,
                        moreError = false,
                    )
                }
                pendingNotification?.let { target ->
                    pendingNotification = null
                    items.firstOrNull { it.uid == target.first && it.uidValidity == target.second }
                        ?.let(::open)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version != sessionVersion) return@launch
                repository.logState("refresh failure id=$id protocol=OWA type=${failure.javaClass.simpleName}")
                val needsLogin = failure is OwaMailRepository.LoginRequired
                _state.update {
                    it.copy(
                        configured = it.items.isNotEmpty() || (!needsLogin && it.configured),
                        loginRequired = needsLogin,
                        loading = false,
                        error = if (needsLogin) "Требуется повторный вход в ОСЭП."
                            else "Не удалось обновить почту через ОСЭП.",
                        // A failed refresh has one recoverable status, not a
                        // second cached-data notice below the error.
                        notice = null,
                    )
                }
            }
        }
    }

    /** Demand-driven older conversation pages. Only first page is persisted
     *  in the existing encrypted inbox cache, keeping it small and recent. */
    fun loadMore() {
        if (_state.value.mailbox == OwaMailFolder.SENT) {
            loadMoreSent()
            return
        }
        val old = _state.value
        if (old.loading || old.loadingMore || !old.hasMore || old.items.isEmpty()) return
        val offset = old.nextPageOffset
        val version = sessionVersion
        _state.update { it.copy(loadingMore = true, moreError = false) }
        viewModelScope.launch {
            try {
                val older = owaMailRepository.inbox(
                    maxConversations = MAIL_PAGE_SIZE, offset = offset,
                )
                if (version != sessionVersion ||
                    _state.value.nextPageOffset != offset) return@launch
                _state.update {
                    it.copy(
                        loadingMore = false,
                        items = mergeOwaInboxPages(it.items, older),
                        hasMore = older.size == MAIL_PAGE_SIZE,
                        nextPageOffset = offset + older.size,
                        moreError = false,
                    )
                }
                repository.logState("OWA page success offset=$offset returned=${older.size}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version != sessionVersion) return@launch
                repository.logState("OWA page failure offset=$offset" +
                    " type=${failure.javaClass.simpleName}")
                _state.update { it.copy(loadingMore = false, moreError = true) }
            }
        }
    }


    /** Sent Items is lazy: loading it never interferes with inbox polling or notifications. */
    fun switchMailbox(folder: OwaMailFolder) {
        if (_state.value.mailbox == folder) return
        _state.update { it.copy(mailbox = folder, selected = null, message = null,
            loadingMessage = false, error = null, notice = null) }
        if (folder == OwaMailFolder.SENT && !_state.value.sentLoaded) refreshSent()
    }

    private fun refreshSent() {
        if (_state.value.sentLoading || _state.value.sentLoadingMore) return
        val version = sessionVersion
        _state.update { it.copy(sentLoading = true, sentMoreError = false, error = null) }
        viewModelScope.launch {
            try {
                val page = owaMailRepository.inbox(
                    maxConversations = MAIL_PAGE_SIZE, folder = OwaMailFolder.SENT,
                )
                if (version != sessionVersion) return@launch
                _state.update { old ->
                    val seen = old.sentItems.mapTo(HashSet()) { it.uidValidity to it.uid }
                    val newItems = page.count { (it.uidValidity to it.uid) !in seen }
                    old.copy(
                        sentItems = mergeOwaSentPages(page, old.sentItems),
                        sentLoaded = true, sentLoading = false,
                        sentHasMore = if (old.sentItems.size >= MAIL_PAGE_SIZE && !old.sentHasMore)
                            false else page.size == MAIL_PAGE_SIZE,
                        sentNextPageOffset = if (old.sentNextPageOffset > 0)
                            maxOf(old.sentNextPageOffset + newItems, page.size) else page.size,
                        sentMoreError = false, loginRequired = false,
                    )
                }
                hydrateSentRecipients(_state.value.sentItems)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version != sessionVersion) return@launch
                repository.logState("OWA sent refresh failed type=${failure.javaClass.simpleName}")
                _state.update { it.copy(
                    sentLoading = false,
                    loginRequired = failure is OwaMailRepository.LoginRequired,
                    error = "Не удалось обновить папку «Отправленные».",
                ) }
            }
        }
    }

    private fun loadMoreSent() {
        val old = _state.value
        if (old.sentLoading || old.sentLoadingMore || !old.sentHasMore) return
        val version = sessionVersion
        val offset = old.sentNextPageOffset
        _state.update { it.copy(sentLoadingMore = true, sentMoreError = false) }
        viewModelScope.launch {
            try {
                val page = owaMailRepository.inbox(
                    maxConversations = MAIL_PAGE_SIZE, offset = offset,
                    folder = OwaMailFolder.SENT,
                )
                if (version != sessionVersion ||
                    _state.value.sentNextPageOffset != offset) return@launch
                _state.update { it.copy(
                    sentItems = mergeOwaSentPages(page, it.sentItems),
                    sentNextPageOffset = offset + page.size,
                    sentHasMore = page.size == MAIL_PAGE_SIZE,
                    sentLoadingMore = false, sentMoreError = false,
                ) }
                hydrateSentRecipients(_state.value.sentItems)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version != sessionVersion) return@launch
                repository.logState("OWA sent page failed type=${failure.javaClass.simpleName}")
                _state.update { it.copy(sentLoadingMore = false, sentMoreError = true) }
            }
        }
    }

    fun openInboxMessage(summary: MailSummary) {
        _state.update { it.copy(mailbox = OwaMailFolder.INBOX) }
        open(summary)
    }

    fun open(summary: MailSummary) {
        val version = sessionVersion
        val cached = owaMailRepository.cachedMessage(summary)
        _state.update {
            it.copy(selected = summary, message = cached,
                loadingMessage = cached == null, error = null, notice = null)
        }
        viewModelScope.launch {
            try {
                val detail = owaMailRepository.message(summary)
                if (version != sessionVersion) return@launch
                _state.update { current ->
                    if (current.selected?.uid != summary.uid ||
                        current.selected?.uidValidity != summary.uidValidity) current
                    else current.copy(message = detail, loadingMessage = false,
                        sentItems = if (current.mailbox == OwaMailFolder.SENT &&
                            !detail.recipients.isNullOrEmpty()) current.sentItems.map { row ->
                                if (row.uid == summary.uid) row.copy(recipients = detail.recipients)
                                else row
                            } else current.sentItems,
                        notice = null)
                }
                if (summary.unread && _state.value.mailbox == OwaMailFolder.INBOX &&
                    _state.value.selected?.uid == summary.uid) {
                    try {
                        owaMailRepository.markRead(summary)
                        if (version == sessionVersion) {
                            _state.update { state ->
                                state.copy(
                                    items = com.rteats.mpeineo.data.markMailRead(
                                        state.items, summary.uid, summary.uidValidity),
                                    selected = state.selected?.let {
                                        if (it.uid == summary.uid && it.uidValidity == summary.uidValidity)
                                            it.copy(unread = false) else it
                                    },
                                )
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        repository.logState("OWA read_state failure type=${failure.javaClass.simpleName}" +
                            " category=" + (failure as? OwaMailRepository.ServiceFailure)?.category.orEmpty())
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version != sessionVersion) return@launch
                repository.logState("OWA message failure type=${failure.javaClass.simpleName}")
                _state.update { current ->
                    if (current.selected?.uid != summary.uid) current
                    else current.copy(loadingMessage = false,
                        error = if (cached == null) "Не удалось открыть переписку через ОСЭП." else null,
                        notice = if (cached != null) "Показана сохранённая переписка" else null)
                }
            }
        }
    }

    fun openFromNotification(uid: Long, validity: Long) {
        if (uid <= 0 || validity <= 0) return
        _state.update { it.copy(mailbox = OwaMailFolder.INBOX) }
        _state.value.items.firstOrNull { it.uid == uid && it.uidValidity == validity }
            ?.let { open(it); return }
        pendingNotification = uid to validity
        refresh()
    }

    fun closeMessage() {
        _state.update { it.copy(selected = null, loadingMessage = false, notice = null, error = null) }
    }

    fun download(attachment: MailAttachment) {
        if (_state.value.savingAttachment != null) return
        val version = sessionVersion
        viewModelScope.launch {
            _state.update { it.copy(savingAttachment = attachment.partPath, error = null) }
            try {
                val file = owaMailRepository.downloadAttachment(attachment)
                if (version != sessionVersion) return@launch
                _state.update { it.copy(savingAttachment = null, notice = "Сохранено: " + file) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version != sessionVersion) return@launch
                repository.logState("OWA attachment failure type=${failure.javaClass.simpleName}" +
                    " category=" + (failure as? OwaMailRepository.ServiceFailure)?.category.orEmpty())
                _state.update { it.copy(savingAttachment = null,
                    error = "Не удалось загрузить вложение через ОСЭП.") }
            }
        }
    }

    fun startCompose() {
        _state.update { it.copy(composing = true, sendError = null,
            activeRecipientField = null, recipientSuggestions = emptyList()) }
    }

    /** Closing retains the unsent in-memory draft and selected file URIs. */
    fun closeCompose() {
        if (_state.value.sending) return
        recipientJob?.cancel()
        _state.update { it.copy(composing = false, sendError = null,
            activeRecipientField = null, recipientSuggestions = emptyList(),
            recipientSearching = false) }
    }

    fun editDraft(change: (OutgoingMail) -> OutgoingMail) {
        _state.update {
            if (it.sending) it else it.copy(draft = change(it.draft), sendError = null)
        }
    }

    /** Debounced and cancelable: search only the unfinished recipient token. */
    fun editRecipient(field: RecipientField, value: String) {
        editDraft { draft -> when (field) {
            RecipientField.TO -> draft.copy(to = value)
            RecipientField.CC -> draft.copy(cc = value)
            RecipientField.BCC -> draft.copy(bcc = value)
        } }
        recipientJob?.cancel()
        val query = OwaRecipientDirectory.token(value)
        if (query.length < 2 || !_state.value.composing) {
            _state.update { it.copy(activeRecipientField = null,
                recipientSuggestions = emptyList(), recipientSearching = false) }
            return
        }
        val version = sessionVersion
        _state.update { it.copy(activeRecipientField = field,
            recipientSearching = true, recipientSuggestions = emptyList()) }
        recipientJob = viewModelScope.launch {
            delay(260)
            val cached = (_state.value.items + _state.value.sentItems).map { it.sender }
            val fallback = OwaRecipientDirectory.cachedAddresses(query, cached)
            val found = try {
                owaMailRepository.findRecipients(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                repository.logState("OWA recipient lookup unavailable type=${failure.javaClass.simpleName}")
                emptyList()
            }
            if (sessionVersion != version) return@launch
            _state.update { current ->
                val actual = when (field) {
                    RecipientField.TO -> current.draft.to
                    RecipientField.CC -> current.draft.cc
                    RecipientField.BCC -> current.draft.bcc
                }
                if (!current.composing || current.activeRecipientField != field ||
                    OwaRecipientDirectory.token(actual) != query) current
                else current.copy(recipientSuggestions = (found + fallback)
                    .distinctBy { it.email.lowercase() }.take(8), recipientSearching = false)
            }
        }
    }

    fun acceptRecipient(field: RecipientField, suggestion: OwaRecipientSuggestion) {
        recipientJob?.cancel()
        editDraft { draft -> when (field) {
            RecipientField.TO -> draft.copy(to =
                OwaRecipientDirectory.replaceToken(draft.to, suggestion.email))
            RecipientField.CC -> draft.copy(cc =
                OwaRecipientDirectory.replaceToken(draft.cc, suggestion.email))
            RecipientField.BCC -> draft.copy(bcc =
                OwaRecipientDirectory.replaceToken(draft.bcc, suggestion.email))
        } }
        _state.update { it.copy(activeRecipientField = null,
            recipientSuggestions = emptyList(), recipientSearching = false) }
    }

    fun addOutgoingFiles(uris: List<Uri>) {
        if (_state.value.sending) return
        val resolver = repository.contextForNotifications.contentResolver
        val files = uris.distinct().mapNotNull { uri ->
            try {
                var name = "attachment"
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn))
                            size = cursor.getLong(sizeColumn)
                    }
                }
                OutgoingFile(
                    uri = uri,
                    name = name.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[\\u0000-\\u001f]"), "").take(160)
                        .ifBlank { "attachment" },
                    mimeType = resolver.getType(uri) ?: "application/octet-stream",
                    size = size,
                )
            } catch (_: Exception) { null }
        }
        _state.update { old ->
            if (old.sending) old else {
                val combined = (old.outgoingFiles + files).distinctBy { it.uri }
                old.copy(
                    outgoingFiles = if (combined.size <= 8) combined else old.outgoingFiles,
                    sendError = when {
                        files.size != uris.distinct().size -> "Не удалось прочитать часть вложений."
                        combined.size > 8 -> "Можно прикрепить не более 8 файлов."
                        combined.any { it.size > 10L * 1024 * 1024 } ->
                            "Размер одного вложения не должен превышать 10 МБ."
                        else -> null
                    },
                )
            }
        }
    }

    fun removeOutgoingFile(uri: Uri) {
        _state.update { old ->
            if (old.sending) old else old.copy(
                outgoingFiles = old.outgoingFiles.filterNot { it.uri == uri },
                sendError = null,
            )
        }
    }

    fun sendDraft() {
        val current = _state.value
        if (!current.composing || current.sending) return
        if (!OwaComposeProtocol.validRecipients(current.draft)) {
            _state.update { it.copy(sendError = "Проверьте адреса получателей.") }
            return
        }
        val version = sessionVersion
        _state.update { it.copy(sending = true, sendError = null, sendStage = "Подготовка…") }
        viewModelScope.launch {
            try {
                owaMailRepository.sendMail(current.draft, current.outgoingFiles) { stage ->
                    _state.update { old -> old.copy(sendStage = stage) }
                }
                if (version != sessionVersion) return@launch
                _state.update {
                    it.copy(
                        composing = false, draft = OutgoingMail(), outgoingFiles = emptyList(),
                        sentLoaded = false, activeRecipientField = null,
                        recipientSuggestions = emptyList(), recipientSearching = false,
                        sending = false, sendStage = null, sendError = null,
                        notice = "Письмо отправлено",
                    )
                }
                // If the user composed from Sent, show the new saved copy
                // without requiring another manual folder switch.
                if (_state.value.mailbox == OwaMailFolder.SENT) refreshSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version != sessionVersion) return@launch
                repository.logState("OWA send failed type=${failure.javaClass.simpleName}")
                _state.update {
                    it.copy(
                        sending = false, sendStage = null,
                        sendError = if (failure is OwaMailRepository.LoginRequired) {
                            "Сессия ОСЭП истекла. Войдите повторно; черновик сохранён."
                        } else if (failure is OwaMailRepository.ServiceFailure &&
                            failure.category in setOf("invalid_recipients", "message_too_long",
                                "attachment_limit", "attachment_too_large", "attachment_unavailable")) {
                            "Проверьте получателей и вложения. Черновик сохранён."
                        } else {
                            "Не удалось подтвердить отправку. Проверьте папку «Отправленные» " +
                                "в ОСЭП перед повторной попыткой."
                        },
                    )
                }
            }
        }
    }

    fun logout() {
        sessionVersion++
        pendingNotification = null
        recipientJob?.cancel()
        sentRecipientJob?.cancel()
        viewModelScope.coroutineContext.cancelChildren()
        MailNotificationScheduler.stopAndClear(repository.contextForNotifications)
        repository.credentials.clear()
        repository.forgetTrustedCertificate()
        _state.value = MailUiState()
        viewModelScope.launch {
            try { owaMailRepository.signOut() }
            catch (failure: Exception) {
                repository.logState("OWA local logout failed type=${failure.javaClass.simpleName}")
            }
        }
    }

    companion object {
        fun factory(
            repo: MailRepository,
            owaAuthProbe: OwaAuthProbe,
            owaMailRepository: OwaMailRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MailViewModel(repo, owaAuthProbe, owaMailRepository) as T
        }
    }
}
