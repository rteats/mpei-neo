package com.rteats.mpeineo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.rteats.mpeineo.data.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal const val BARS_BASE_URL = "https://bars.mpei.ru"
internal const val BARS_LOGIN_URL = "$BARS_BASE_URL/bars_web/"
internal const val BARS_MARKS_URL = "$BARS_BASE_URL/bars_web/ST_Study/Main/Main"

internal enum class BarsAuthStage {
    CHECKING,
    WEB_AUTH,
    AUTHENTICATED,
}

internal data class BarsUiState(
    val authStage: BarsAuthStage = BarsAuthStage.CHECKING,
    val profileName: String = "",
    val profileGroup: String = "",
    val semester: String = "",
    val disciplines: List<BarsDiscipline> = emptyList(),
    val isLoading: Boolean = true,
    val browserVisible: Boolean = false,
    val sessionUrl: String = BARS_MARKS_URL,
    val lastUpdatedAtMillis: Long = 0L,
    val error: String? = null,
) {
    fun hasFreshCache(nowMillis: Long = System.currentTimeMillis()): Boolean =
        lastUpdatedAtMillis > 0L &&
            nowMillis - lastUpdatedAtMillis in 0 until BARS_CACHE_TTL_MS

    val controlSchedule: List<BarsControlScheduleItem>
        get() = disciplines
            .flatMap { discipline ->
                discipline.activities
                    .filter {
                        it.type == BarsActivityType.CONTROL_ACTIVITY &&
                            !it.weekNum.isNullOrBlank()
                    }
                    .map { activity ->
                        BarsControlScheduleItem(
                            discipline = discipline.disciplineName,
                            activity = activity.name.orEmpty(),
                            weekNum = activity.weekNum.orEmpty(),
                            weight = activity.weight.orEmpty(),
                            markAndDate = activity.markAndDate.orEmpty(),
                        )
                    }
            }
            .sortedWith(
                compareBy<BarsControlScheduleItem> {
                    it.weekNum.extractFirstIntOrNull() ?: Int.MAX_VALUE
                }.thenBy { it.discipline }
            )
}

internal data class BarsDiscipline(
    val disciplineName: String = "",
    val personName: String = "",
    val assessmentType: String = "",
    val activities: List<BarsActivity> = emptyList(),
) {
    val currentMarks: List<String>
        get() = activities
            .filter {
                it.type == BarsActivityType.CONTROL_ACTIVITY &&
                    !it.markAndDate.isNullOrBlank()
            }
            .mapNotNull { activity ->
                activity.markAndDate?.extractFirstNumber()
            }

    val markValues: List<Float>
        get() = currentMarks.mapNotNull { it.toFloatOrNull() }

    val finalMark: String?
        get() = activities
            .lastOrNull {
                it.type == BarsActivityType.FINAL_MARK &&
                    !it.markAndDate.isNullOrBlank()
            }
            ?.markAndDate
            ?.extractFirstNumber()

    val finalMarkValue: Float?
        get() = finalMark?.toFloatOrNull()

    val currentScore: String?
        get() = activities
            .lastOrNull {
                it.type == BarsActivityType.CURRENT_SCORE &&
                    !it.markAndDate.isNullOrBlank()
            }
            ?.markAndDate
            ?.extractFirstNumber()

    val currentScoreValue: Float?
        get() = currentScore?.toFloatOrNull()
}

internal data class BarsActivity(
    val type: BarsActivityType = BarsActivityType.UNDEFINED,
    val name: String? = null,
    val weight: String? = null,
    val weekNum: String? = null,
    val markAndDate: String? = null,
) {
    val markValue: Float?
        get() = markAndDate?.extractFirstNumber()?.toFloatOrNull()
}

internal enum class BarsActivityType {
    UNDEFINED,
    CONTROL_ACTIVITY,
    CURRENT_SCORE,
    CONTROL_WEEK,
    INTERMEDIATE_MARK,
    FINAL_MARK,
}

internal data class BarsControlScheduleItem(
    val discipline: String,
    val activity: String,
    val weekNum: String,
    val weight: String,
    val markAndDate: String,
)

internal data class BarsExtractionPayload(
    val name: String = "",
    val group: String = "",
    val semester: String = "",
    val disciplines: List<BarsDiscipline> = emptyList(),
)

internal data class BarsPageState(
    val url: String = "",
    val path: String = "",
    val isLoginPage: Boolean = false,
    val isAuthFlow: Boolean = false,
    val isStudentList: Boolean = false,
    val isMarksPage: Boolean = false,
)

internal object BarsPayloadParser {
    private val gson = Gson()

    fun parseExtraction(json: String): BarsExtractionPayload =
        gson.fromJson(json, BarsExtractionPayload::class.java)

    fun parsePageState(json: String): BarsPageState =
        gson.fromJson(json, BarsPageState::class.java)
}

internal class BarsViewModel(
    private val diagnostics: DiagnosticLog,
) : ViewModel() {

    private val _state = MutableStateFlow(BarsUiState())
    val state: StateFlow<BarsUiState> = _state.asStateFlow()

    private var lastWebEvent: String? = null

    fun checking(url: String? = null) {
        val current = _state.value
        val targetUrl = url ?: current.sessionUrl
        if (
            current.authStage == BarsAuthStage.CHECKING &&
            current.sessionUrl == targetUrl &&
            current.error == null
        ) {
            return
        }

        diagnostics.log("BARS", "state=CHECKING")
        _state.update {
            it.copy(
                authStage = BarsAuthStage.CHECKING,
                isLoading = true,
                sessionUrl = targetUrl,
                error = null,
            )
        }
    }

    fun webAuth(url: String) {
        val current = _state.value
        if (
            current.authStage == BarsAuthStage.WEB_AUTH &&
            current.sessionUrl == url &&
            current.error == null
        ) {
            return
        }

        diagnostics.log("BARS", "state=WEB_AUTH")
        _state.update {
            it.copy(
                authStage = BarsAuthStage.WEB_AUTH,
                isLoading = false,
                browserVisible = false,
                sessionUrl = url,
                error = null,
            )
        }
    }

    fun authenticatedPage(url: String) {
        diagnostics.log("BARS", "state=AUTHENTICATED extracting=true")
        _state.update {
            it.copy(
                authStage = BarsAuthStage.AUTHENTICATED,
                isLoading = true,
                sessionUrl = url,
                error = null,
            )
        }
    }

    fun updateSessionUrl(url: String) {
        if (url.isBlank()) return
        _state.update { it.copy(sessionUrl = url) }
    }

    fun extractionReceived(json: String) {
        runCatching { BarsPayloadParser.parseExtraction(json) }
            .onSuccess { payload ->
                diagnostics.log(
                    "BARS",
                    "extraction success disciplines=${payload.disciplines.size}",
                )
                _state.update {
                    it.copy(
                        authStage = BarsAuthStage.AUTHENTICATED,
                        profileName = payload.name,
                        profileGroup = payload.group,
                        semester = payload.semester,
                        disciplines = payload.disciplines,
                        isLoading = false,
                        lastUpdatedAtMillis = System.currentTimeMillis(),
                        error = null,
                    )
                }
            }
            .onFailure { error ->
                extractionFailed(error.message ?: "Не удалось разобрать данные БАРС")
            }
    }

    fun extractionFailed(message: String) {
        diagnostics.log("BARS", "error=$message")
        _state.update {
            it.copy(
                isLoading = false,
                error = message,
            )
        }
    }

    fun startRefresh() {
        diagnostics.log("BARS", "refresh requested")
        _state.update { it.copy(isLoading = true, error = null) }
    }

    fun waitingForMarks(url: String) {
        val current = _state.value
        if (current.lastUpdatedAtMillis > 0L) {
            _state.update {
                it.copy(
                    isLoading = true,
                    sessionUrl = url,
                    error = null,
                )
            }
        } else {
            checking(url)
        }
    }

    fun logWebEvent(message: String) {
        if (message == lastWebEvent) return
        lastWebEvent = message
        diagnostics.log("BARS_WEB", message)
    }

    fun showBrowser() {
        diagnostics.log("BARS", "session WebView opened")
        _state.update { it.copy(browserVisible = true) }
    }

    fun hideBrowser() {
        diagnostics.log("BARS", "session WebView hidden")
        _state.update { it.copy(browserVisible = false) }
    }

    companion object {
        fun factory(diagnostics: DiagnosticLog): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BarsViewModel(diagnostics) as T
            }
    }
}

private fun String.extractFirstNumber(): String? =
    replace(',', '.')
        .split(Regex("[\\s(/]+"))
        .firstOrNull { token -> token.toFloatOrNull() != null }

private fun String.extractFirstIntOrNull(): Int? =
    split(Regex("[^0-9]+"))
        .firstOrNull { it.isNotBlank() }
        ?.toIntOrNull()

private const val BARS_CACHE_TTL_MS = 5L * 60L * 1_000L
