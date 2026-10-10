package com.rteats.mpeineo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rteats.mpeineo.data.MailAttachment
import com.rteats.mpeineo.data.MailCredentials
import com.rteats.mpeineo.data.MailDetail
import com.rteats.mpeineo.data.MailRepository
import com.rteats.mpeineo.data.MailNotificationScheduler
import com.rteats.mpeineo.data.MailSummary
import com.rteats.mpeineo.data.MailServerCertificate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class MailUiState(
    val configured: Boolean = false,
    val certificate: MailServerCertificate? = null,
    val inspectingCertificate: Boolean = false,
    val username: String = "",
    val loading: Boolean = false,
    val items: List<MailSummary> = emptyList(),
    val selected: MailSummary? = null,
    val message: MailDetail? = null,
    val loadingMessage: Boolean = false,
    val savingAttachment: List<Int>? = null,
    val notice: String? = null,
    val error: String? = null,
)

/** Only the username and message summaries enter Compose state. Never the password. */
internal class MailViewModel(private val repository: MailRepository) : ViewModel() {
    private val initialAuth = repository.credentials.load()
    private val _state = MutableStateFlow(
        MailUiState(configured = initialAuth != null, username = initialAuth?.username.orEmpty()),
    )
    val state: StateFlow<MailUiState> = _state.asStateFlow()
    private var inMemoryCredentials: MailCredentials? = initialAuth
    private var attemptedCredentials: MailCredentials? = initialAuth

    init { if (initialAuth != null) refresh() }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Введите имя пользователя IMAP и пароль.") }
            return
        }
        val auth = MailCredentials(username.trim(), password)
        attemptedCredentials = auth
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notice = null, certificate = null) }
            runCatching {
                val messages = repository.listInbox(auth)
                repository.credentials.save(auth)
                messages
            }.onSuccess { items ->
                inMemoryCredentials = auth
                _state.update {
                    it.copy(configured = true, username = auth.username, items = items, loading = false, certificate = null)
                }
            }.onFailure { cause ->
                handleConnectionFailure(cause)
            }
        }
    }

    fun refresh() {
        val auth = inMemoryCredentials ?: return
        attemptedCredentials = auth
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.listInbox(auth) }
                .onSuccess { items -> _state.update { it.copy(items = items, loading = false) } }
                .onFailure { cause -> handleConnectionFailure(cause) }
        }
    }

    fun open(summary: MailSummary) {
        val auth = inMemoryCredentials ?: return
        _state.update {
            it.copy(selected = summary, message = null, loadingMessage = true, error = null, notice = null)
        }
        viewModelScope.launch {
            runCatching { repository.readMessage(auth, summary.uid, summary.uidValidity) }
                .onSuccess { body ->
                    _state.update { current ->
                        if (current.selected?.uid != summary.uid) current
                        else current.copy(message = body, loadingMessage = false)
                    }
                }
                .onFailure { cause ->
                    _state.update {
                        it.copy(loadingMessage = false, error = readableError(cause))
                    }
                }
        }
    }

    fun closeMessage() {
        _state.update { it.copy(selected = null, message = null, notice = null, error = null) }
    }

    fun download(attachment: MailAttachment) {
        val auth = inMemoryCredentials ?: return
        val summary = _state.value.selected ?: return
        if (_state.value.savingAttachment != null) return
        viewModelScope.launch {
            _state.update { it.copy(savingAttachment = attachment.partPath, notice = null, error = null) }
            runCatching {
                repository.saveAttachment(auth, summary.uid, summary.uidValidity, attachment)
            }.onSuccess { destination ->
                _state.update { it.copy(savingAttachment = null, notice = "Сохранено: $destination") }
            }.onFailure { cause ->
                _state.update { it.copy(savingAttachment = null, error = readableError(cause)) }
            }
        }
    }

    fun logout() {
        MailNotificationScheduler.stopAndClear(repository.contextForNotifications)
        repository.credentials.clear()
        repository.forgetTrustedCertificate()
        attemptedCredentials = null
        inMemoryCredentials = null
        _state.value = MailUiState()
    }

    /**
     * Don't accept trust automatically. The user must independently compare
     * the SHA-256 fingerprint and explicitly approve the exact certificate.
     */
    fun approveCertificate() {
        val candidate = _state.value.certificate ?: return
        val auth = attemptedCredentials ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val live = repository.inspectServerCertificate()
                require(live.sha256 == candidate.sha256) {
                    "Сертификат сервера изменился. Проверьте новый отпечаток."
                }
                require(live.currentlyValid && live.matchesMailHostname) {
                    "Сертификат просрочен либо не принадлежит mail.mpei.ru."
                }
                repository.trustCertificate(live)
            }.onSuccess {
                _state.update { it.copy(loading = false, certificate = null) }
                if (_state.value.configured) refresh() else login(auth.username, auth.password)
            }.onFailure { problem ->
                _state.update {
                    it.copy(
                        loading = false,
                        certificate = null,
                        error = readableError(problem),
                    )
                }
            }
        }
    }

    fun dismissCertificateWarning() {
        _state.update { it.copy(certificate = null, inspectingCertificate = false) }
    }

    private suspend fun handleConnectionFailure(cause: Throwable) {
        val descriptions = generateSequence(cause) { it.cause }
            .take(8).mapNotNull { it.message }.joinToString(" ")
        val likelyTlsIssue = descriptions.contains("certificate", ignoreCase = true) ||
            descriptions.contains("PKIX", ignoreCase = true) ||
            descriptions.contains("SSLHandshake", ignoreCase = true)

        if (!likelyTlsIssue) {
            _state.update {
                it.copy(loading = false, inspectingCertificate = false, error = readableError(cause))
            }
            return
        }

        _state.update {
            it.copy(
                loading = false,
                inspectingCertificate = true,
                certificate = null,
                error = "Сертификат mail.mpei.ru не доверен. Для подключения проверьте его SHA-256 отпечаток.",
            )
        }
        runCatching { repository.inspectServerCertificate() }
            .onSuccess { cert ->
                _state.update {
                    it.copy(certificate = cert, inspectingCertificate = false)
                }
            }
            .onFailure { failure ->
                _state.update {
                    it.copy(
                        inspectingCertificate = false,
                        error = "Не удалось получить сертификат: " + readableError(failure),
                    )
                }
            }
    }

    private fun readableError(t: Throwable): String {
        val message = t.message.orEmpty()
        return when {
            message.contains("authentication", true) || message.contains("login failed", true) ->
                "Не удалось войти в IMAP. Проверьте имя пользователя и пароль FairEmail."
            message.contains("timeout", true) -> "Сервер почты не ответил. Повторите позже."
            else -> message.take(240).ifBlank { "Не удалось получить почту через IMAP." }
        }
    }

    companion object {
        fun factory(repo: MailRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MailViewModel(repo) as T
            }
    }
}
