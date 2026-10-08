package com.rteats.mpeineo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * Native BARS frontend backed by the official BARS web session.
 *
 * Authentication intentionally stays on the official BARS pages. This makes the app follow
 * BARS' current password + 2FA flow instead of duplicating the private authentication protocol.
 * Once authenticated, the same WebView session is used to extract the current marks into the
 * native Compose UI. The WebView is kept alive so "Открыть БАРС" exposes that exact session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BarsScreen(
    viewModel: BarsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var redirectingToMarks by remember { mutableStateOf(false) }
    var marksExtractionStarted by remember { mutableStateOf(false) }
    var needsFullViewport by remember { mutableStateOf(true) }
    var lastPageStateSignature by remember { mutableStateOf<String?>(null) }
    var marksMissingPolls by remember { mutableStateOf(0) }
    var marksRouteRecoveryAttempted by remember { mutableStateOf(false) }
    var marksWaitLogged by remember { mutableStateOf(false) }
    var marksFailureReported by remember { mutableStateOf(false) }

    val cacheFresh = state.hasFreshCache()
    val shouldHaveWebView =
        state.authStage != BarsAuthStage.AUTHENTICATED ||
            state.browserVisible ||
            state.isLoading ||
            !cacheFresh

    BackHandler(enabled = state.browserVisible) {
        val view = webView
        if (view?.canGoBack() == true && view.url != BARS_MARKS_URL) {
            view.goBack()
        } else {
            needsFullViewport = false
            viewModel.hideBrowser()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("bars-screen"),
    ) {
        when (state.authStage) {
            BarsAuthStage.CHECKING -> BarsCheckingState(
                error = state.error,
                onRetry = {
                    marksRouteRecoveryAttempted = false
                    marksFailureReported = false
                    viewModel.checking()
                    webView?.loadUrl(BARS_LOGIN_URL)
                },
            )

            BarsAuthStage.WEB_AUTH -> {
                // The official BARS page is rendered by the persistent WebView below.
            }

            BarsAuthStage.AUTHENTICATED -> BarsNativeDashboard(
                state = state,
                onRefresh = {
                    redirectingToMarks = false
                    marksRouteRecoveryAttempted = false
                    marksFailureReported = false
                    viewModel.startRefresh()
                    webView?.loadUrl(BARS_LOGIN_URL)
                },
                onOpenBrowser = viewModel::showBrowser,
            )
        }

        if (shouldHaveWebView) {
            AndroidView(
                modifier = when {
                    state.browserVisible -> Modifier
                        .fillMaxSize()
                        .padding(bottom = 72.dp)
                    state.authStage == BarsAuthStage.WEB_AUTH -> Modifier.fillMaxSize()
                    needsFullViewport -> Modifier
                        .fillMaxSize()
                        .alpha(0f)
                    else -> Modifier
                        .size(1.dp)
                        .alpha(0f)
                },
            factory = { context ->
                WebView(context).also { view ->
                    webView = view
                    if (
                        state.authStage == BarsAuthStage.AUTHENTICATED &&
                        !cacheFresh &&
                        !state.isLoading &&
                        !state.browserVisible
                    ) {
                        viewModel.startRefresh()
                    }

                    val hasBarsCookies =
                        !CookieManager.getInstance().getCookie(BARS_BASE_URL).isNullOrBlank()
                    viewModel.logWebEvent(
                        "webview created cookiesPresent=$hasBarsCookies",
                    )

                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        saveFormData = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)

                        // MpeiX used the same workaround for BARS. Some server-side flows
                        // distinguish the Android WebView token from a regular mobile browser.
                        userAgentString = userAgentString.replace("; wv", "")
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(view, false)
                    }

                    view.addJavascriptInterface(
                        BarsJavascriptBridge(
                            onPageState = { json ->
                                view.post {
                                    val page = runCatching {
                                        BarsPayloadParser.parsePageState(json)
                                    }.getOrElse { error ->
                                        viewModel.extractionFailed(
                                            error.message ?: "Не удалось определить состояние БАРС",
                                        )
                                        return@post
                                    }

                                    viewModel.updateSessionUrl(page.url)

                                    val currentLocation = safeBarsLocation(page.url)
                                    val marksLocation = safeBarsLocation(BARS_MARKS_URL)
                                    val signature =
                                        "$currentLocation|${page.isLoginPage}|${page.isAuthFlow}|${page.isStudentList}|${page.isMarksPage}"
                                    if (signature != lastPageStateSignature) {
                                        lastPageStateSignature = signature
                                        viewModel.logWebEvent(
                                            "pageState path=$currentLocation login=${page.isLoginPage} authFlow=${page.isAuthFlow} studentList=${page.isStudentList} marks=${page.isMarksPage}",
                                        )
                                    }

                                    when {
                                        page.isMarksPage -> {
                                            redirectingToMarks = false
                                            marksMissingPolls = 0
                                            marksWaitLogged = false
                                            marksFailureReported = false
                                            if (!marksExtractionStarted) {
                                                marksExtractionStarted = true
                                                viewModel.authenticatedPage(page.url)
                                                view.evaluateJavascript(BARS_EXTRACT_SCRIPT, null)
                                            }
                                        }

                                        page.isLoginPage ||
                                            page.isAuthFlow ||
                                            page.isStudentList -> {
                                            redirectingToMarks = false
                                            marksMissingPolls = 0
                                            marksWaitLogged = false
                                            marksRouteRecoveryAttempted = false
                                            marksFailureReported = false
                                            viewModel.webAuth(page.url)
                                        }

                                        page.url.startsWith(BARS_BASE_URL) &&
                                            currentLocation == marksLocation -> {
                                            redirectingToMarks = false
                                            viewModel.waitingForMarks(page.url)
                                            marksMissingPolls += 1

                                            if (!marksWaitLogged) {
                                                marksWaitLogged = true
                                                viewModel.logWebEvent(
                                                    "marks route loaded; waiting for marks DOM",
                                                )
                                            }

                                            when {
                                                marksMissingPolls >= 5 &&
                                                    !marksRouteRecoveryAttempted -> {
                                                    marksRouteRecoveryAttempted = true
                                                    marksMissingPolls = 0
                                                    marksWaitLogged = false
                                                    viewModel.logWebEvent(
                                                        "marks DOM timeout; bootstrapping through BARS root",
                                                    )
                                                    view.loadUrl(BARS_LOGIN_URL)
                                                }

                                                marksMissingPolls >= 10 &&
                                                    marksRouteRecoveryAttempted &&
                                                    !marksFailureReported -> {
                                                    marksFailureReported = true
                                                    needsFullViewport = false
                                                    view.evaluateJavascript(
                                                        BARS_STOP_STATE_OBSERVER_SCRIPT,
                                                        null,
                                                    )
                                                    viewModel.extractionFailed(
                                                        "БАРС открыл страницу оценок, но таблица не загрузилась.",
                                                    )
                                                }
                                            }
                                        }

                                        page.url.startsWith(BARS_BASE_URL) -> {
                                            marksMissingPolls = 0
                                            marksWaitLogged = false
                                            if (!redirectingToMarks) {
                                                redirectingToMarks = true
                                                viewModel.waitingForMarks(page.url)
                                                viewModel.logWebEvent(
                                                    "authenticated BARS root; opening marks route",
                                                )
                                                view.loadUrl(BARS_MARKS_URL)
                                            }
                                        }

                                        else -> {
                                            needsFullViewport = false
                                            viewModel.extractionFailed(
                                                "БАРС открыл неожиданную страницу",
                                            )
                                        }
                                    }
                                }
                            },
                            onData = { json ->
                                view.post {
                                    needsFullViewport = false
                                    marksRouteRecoveryAttempted = false
                                    marksFailureReported = false
                                    viewModel.logWebEvent("extraction bridge returned data")
                                    CookieManager.getInstance().flush()
                                    viewModel.extractionReceived(json)
                                }
                            },
                            onError = { message ->
                                view.post {
                                    needsFullViewport = false
                                    viewModel.logWebEvent("javascript error=$message")
                                    viewModel.extractionFailed(message)
                                }
                            },
                        ),
                        BARS_JS_INTERFACE,
                    )

                    view.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView,
                            url: String,
                            favicon: Bitmap?,
                        ) {
                            marksExtractionStarted = false
                            marksMissingPolls = 0
                            marksWaitLogged = false
                            needsFullViewport = true
                            viewModel.updateSessionUrl(url)
                            viewModel.logWebEvent(
                                "page started path=${safeBarsLocation(url)}",
                            )
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            viewModel.updateSessionUrl(url)
                            viewModel.logWebEvent(
                                "page finished path=${safeBarsLocation(url)}",
                            )
                            view.evaluateJavascript(BARS_PAGE_STATE_SCRIPT, null)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                needsFullViewport = false
                                viewModel.logWebEvent(
                                    "main-frame error path=${safeBarsLocation(request.url.toString())} code=${error.errorCode}",
                                )
                                viewModel.extractionFailed(
                                    "Не удалось открыть БАРС: ${error.description}",
                                )
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val uri = request.url
                            if (uri.scheme == "https" && uri.host == "bars.mpei.ru") {
                                return false
                            }

                            return runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                true
                            }.getOrDefault(true)
                        }
                    }

                    viewModel.logWebEvent(
                        "initial load path=${safeBarsLocation(BARS_LOGIN_URL)}",
                    )
                    view.loadUrl(BARS_LOGIN_URL)
                }
            },
            update = { view ->
                webView = view
            },
                onRelease = { view ->
                    viewModel.logWebEvent("webview released")
                    view.stopLoading()
                    view.removeJavascriptInterface(BARS_JS_INTERFACE)
                    view.webViewClient = WebViewClient()
                    view.destroy()
                    if (webView === view) webView = null
                },
            )
        }

        if (state.browserVisible) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                FilledTonalButton(
                    onClick = {
                        needsFullViewport = false
                        viewModel.hideBrowser()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("Вернуться к оценкам")
                }
            }
        }
    }
}

@Composable
private fun BarsCheckingState(
    error: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error == null) {
                CircularProgressIndicator()
                Text(
                    "Проверяем сессию БАРС…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry) {
                    Text("Повторить")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarsNativeDashboard(
    state: BarsUiState,
    onRefresh: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var selectedDiscipline by remember { mutableStateOf<BarsDiscipline?>(null) }

    selectedDiscipline?.let { discipline ->
        ModalBottomSheet(
            onDismissRequest = { selectedDiscipline = null },
        ) {
            DisciplineDetailsSheet(discipline)
        }
    }

    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                BarsUserHeader(
                    state = state,
                    onOpenBrowser = onOpenBrowser,
                )
            }

            state.error?.let { message ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (state.isLoading && state.disciplines.isNotEmpty()) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            if (state.disciplines.isEmpty() && !state.isLoading) {
                item {
                    EmptyBarsCard(
                        text = "В текущем семестре дисциплины не найдены.",
                    )
                }
            } else {
                items(state.disciplines) { discipline ->
                    DisciplineCard(
                        discipline = discipline,
                        onClick = { selectedDiscipline = discipline },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .padding(bottom = 8.dp),
                    )
                }
            }

            item {
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun BarsUserHeader(
    state: BarsUiState,
    onOpenBrowser: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = state.profileName.ifBlank { "БАРС МЭИ" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (state.profileGroup.isNotBlank()) {
                Text(
                    text = state.profileGroup,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.semester.isNotBlank()) {
                Text(
                    text = state.semester,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FilledTonalButton(
            onClick = onOpenBrowser,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text("Открыть БАРС")
        }
    }
}

@Composable
private fun DisciplineCard(
    discipline: BarsDiscipline,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = discipline.disciplineName,
                style = MaterialTheme.typography.titleMedium,
            )

            if (discipline.assessmentType.isNotBlank()) {
                AssessmentTypeChip(
                    text = discipline.assessmentType,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (discipline.personName.isNotBlank()) {
                Text(
                    text = discipline.personName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        top = if (discipline.assessmentType.isNotBlank()) 4.dp else 6.dp,
                        bottom = 6.dp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val finalMark = discipline.finalMarkValue
            when {
                finalMark != null -> {
                    GradeChip(
                        text = "Итог: ${finalMark.roundToInt()}",
                        mark = finalMark,
                    )
                }

                discipline.markValues.isNotEmpty() -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        discipline.markValues.take(8).forEach { mark ->
                            GradeChip(
                                text = mark.roundToInt().toString(),
                                mark = mark,
                            )
                        }
                        if (discipline.markValues.size > 8) {
                            NeutralChip("+${discipline.markValues.size - 8}")
                        }
                    }
                }

                else -> NeutralChip("Нет оценок")
            }
        }
    }
}

@Composable
private fun DisciplineDetailsSheet(
    discipline: BarsDiscipline,
) {
    val controls = discipline.activities.filter {
        it.type == BarsActivityType.CONTROL_ACTIVITY
    }
    val finalGrades = discipline.activities.filter {
        it.type != BarsActivityType.CONTROL_ACTIVITY &&
            it.type != BarsActivityType.UNDEFINED &&
            it.markValue != null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
    ) {
        Text(
            text = discipline.disciplineName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (discipline.assessmentType.isNotBlank()) {
            AssessmentTypeChip(
                text = discipline.assessmentType,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (discipline.personName.isNotBlank()) {
            Text(
                text = discipline.personName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    top = 6.dp,
                    bottom = 18.dp,
                ),
            )
        } else {
            Spacer(Modifier.size(16.dp))
        }

        if (controls.isNotEmpty()) {
            Text(
                text = "Контрольные мероприятия",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            controls.forEachIndexed { index, activity ->
                ActivityDetailRow(activity)
                if (index != controls.lastIndex) {
                    HorizontalDivider()
                }
            }
        } else {
            Text(
                text = "Контрольных мероприятий нет",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (finalGrades.isNotEmpty()) {
            Text(
                text = "Итоговые оценки",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
            )

            finalGrades.forEachIndexed { index, activity ->
                FinalGradeRow(activity)
                if (index != finalGrades.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ActivityDetailRow(
    activity: BarsActivity,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = activity.name?.takeIf { it.isNotBlank() }
                    ?: "Контрольное мероприятие",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            val details = buildList {
                activity.weight?.takeIf { it.isNotBlank() }?.let {
                    add("Вес: $it")
                }
                activity.weekNum?.takeIf { it.isNotBlank() }?.let {
                    add("Неделя: $it")
                }
            }

            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        activity.markValue?.let { mark ->
            GradeChip(
                text = mark.roundToInt().toString(),
                mark = mark,
            )
        }
    }
}

@Composable
private fun FinalGradeRow(
    activity: BarsActivity,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = activity.name?.takeIf { it.isNotBlank() }
                ?: activity.type.displayName(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        activity.markValue?.let { mark ->
            GradeChip(
                text = mark.roundToInt().toString(),
                mark = mark,
            )
        }
    }
}

@Composable
private fun AssessmentTypeChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun GradeChip(
    text: String,
    mark: Float,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val (container, content) = when (mark.roundToInt()) {
        4, 5 -> if (dark) {
            Color(0xFF1B5E20) to Color(0xFFC8E6C9)
        } else {
            Color(0xFFC8E6C9) to Color(0xFF145A20)
        }

        3 -> if (dark) {
            Color(0xFF6B5500) to Color(0xFFFFE082)
        } else {
            Color(0xFFFFE082) to Color(0xFF5A4600)
        }

        0, 1, 2 -> if (dark) {
            Color(0xFF7F1D1D) to Color(0xFFFFDAD6)
        } else {
            Color(0xFFFFCDD2) to Color(0xFF7A1420)
        }

        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NeutralChip(
    text: String,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun BarsActivityType.displayName(): String =
    when (this) {
        BarsActivityType.CURRENT_SCORE -> "Балл текущего контроля"
        BarsActivityType.CONTROL_WEEK -> "Контрольная неделя"
        BarsActivityType.INTERMEDIATE_MARK -> "Промежуточная оценка"
        BarsActivityType.FINAL_MARK -> "Итоговая оценка"
        BarsActivityType.CONTROL_ACTIVITY -> "Контрольное мероприятие"
        BarsActivityType.UNDEFINED -> "Оценка"
    }

@Composable
private fun EmptyBarsCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun safeBarsLocation(rawUrl: String): String =
    runCatching {
        val uri = Uri.parse(rawUrl)
        "${uri.host.orEmpty()}${uri.path.orEmpty()}"
    }.getOrDefault("unparseable")

private const val BARS_JS_INTERFACE = "MpeiNeoBars"

private const val BARS_STOP_STATE_OBSERVER_SCRIPT =
    "if (window.__mpeiNeoBarsStateTimer) { clearInterval(window.__mpeiNeoBarsStateTimer); window.__mpeiNeoBarsStateTimer = null; }"

private class BarsJavascriptBridge(
    private val onPageState: (String) -> Unit,
    private val onData: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    @JavascriptInterface
    fun onPageState(json: String) = onPageState.invoke(json)

    @JavascriptInterface
    fun onData(json: String) = onData.invoke(json)

    @JavascriptInterface
    fun onError(message: String) = onError.invoke(message)
}

private val BARS_PAGE_STATE_SCRIPT = """
    (function() {
        function reportState() {
            try {
                const path = (window.location.pathname || "").toLowerCase();
                const hasPassword = !!document.querySelector('input[type="password"]');
                const marks = !!document.getElementById("div-Student_SemesterSheet__Mark");
                const studentList = path.indexOf("/student/liststudent") >= 0;
                const bodyText = ((document.body && document.body.textContent) || "").toLowerCase();
                const hasOneTimeCodeInput = !!document.querySelector(
                    'input[autocomplete="one-time-code"], ' +
                    'input[name*="code" i], input[id*="code" i]'
                );
                const hasTwoFactorUi =
                    hasOneTimeCodeInput ||
                    (
                        (bodyText.indexOf("двухфактор") >= 0 ||
                         bodyText.indexOf("код подтверждения") >= 0 ||
                         bodyText.indexOf("получить код") >= 0 ||
                         bodyText.indexOf("одноразов") >= 0) &&
                        !!document.querySelector("form, .modal, [role='dialog']")
                    );
                const authFlow =
                    path.indexOf("/auth") >= 0 ||
                    hasTwoFactorUi;

                MpeiNeoBars.onPageState(JSON.stringify({
                    url: window.location.href,
                    path: path,
                    isLoginPage: hasPassword,
                    isAuthFlow: authFlow,
                    isStudentList: studentList,
                    isMarksPage: marks
                }));

                if (marks && window.__mpeiNeoBarsStateTimer) {
                    window.clearInterval(window.__mpeiNeoBarsStateTimer);
                    window.__mpeiNeoBarsStateTimer = null;
                }
            } catch (error) {
                MpeiNeoBars.onError("Не удалось определить страницу БАРС");
            }
        }

        reportState();

        if (!window.__mpeiNeoBarsStateTimer) {
            window.__mpeiNeoBarsStateTimer =
                window.setInterval(reportState, 1000);
        }
    })();
""".trimIndent()

/*
 * DOM extraction is based on the same public BARS markup used by MpeiX's former
 * statics/bars/extract.js implementation, but is intentionally self-contained here.
 */
private val BARS_EXTRACT_SCRIPT = """
    (function() {
        function clean(value) {
            return (value || "").replace(/\s+/g, " ").trim();
        }

        function parseRow(row) {
            const columns = Array.from(row.querySelectorAll("td")).map(function(cell) {
                return clean(cell.textContent);
            });

            if (columns.length === 0) return { type: "UNDEFINED" };

            if (columns.length === 4) {
                return {
                    type: "CONTROL_ACTIVITY",
                    name: clean(columns[0].replace(/[(]критерии[)]\s*$/i, "")),
                    weight: columns[1] || null,
                    weekNum: columns[2] || null,
                    markAndDate: columns[3] || null
                };
            }

            const title = columns[0] || "";
            if (title.indexOf("Балл текущего") === 0) {
                return {
                    type: "CURRENT_SCORE",
                    name: title,
                    markAndDate: columns[1] || null
                };
            }
            if (title.indexOf("Контрольная неделя №") === 0) {
                return {
                    type: "CONTROL_WEEK",
                    name: title,
                    weekNum: columns[1] || null,
                    markAndDate: columns[2] || null
                };
            }
            if (title.indexOf("Промежуточная") === 0) {
                return {
                    type: "INTERMEDIATE_MARK",
                    name: title,
                    markAndDate: columns[1] || null
                };
            }
            if (title.indexOf("Итоговая") === 0) {
                return {
                    type: "FINAL_MARK",
                    name: title,
                    markAndDate: columns[1] || null
                };
            }
            return { type: "UNDEFINED", name: title };
        }

        function extract(attempt) {
            try {
                const marksContainer =
                    document.getElementById("div-Student_SemesterSheet__Mark");

                if (!marksContainer) {
                    if (attempt < 8) {
                        window.setTimeout(function() { extract(attempt + 1); }, 400);
                        return;
                    }
                    MpeiNeoBars.onError(
                        "Страница БАРС загрузилась, но таблица оценок не найдена."
                    );
                    return;
                }

                const blocks = Array.from(marksContainer.children || []);
                const disciplines = [];

                for (let i = 0; i < blocks.length; i++) {
                    const block = blocks[i];
                    if (!block.classList || !block.classList.contains("collapse") || i === 0) {
                        continue;
                    }

                    const headerParts = clean(blocks[i - 1].textContent)
                        .split(",")
                        .map(clean)
                        .filter(Boolean);

                    const assessmentIndex = headerParts.findLastIndex(function(part) {
                        return /(экзамен|зач[её]т|аттестаци|дифференц)/i.test(part);
                    });

                    let disciplineName = "";
                    let personName = "";
                    let assessmentType = "";

                    if (assessmentIndex > 0) {
                        personName = headerParts[assessmentIndex - 1] || "";
                        assessmentType = headerParts[assessmentIndex] || "";
                        disciplineName = headerParts
                            .slice(0, Math.max(assessmentIndex - 1, 1))
                            .join(", ");
                    } else if (headerParts.length >= 4) {
                        disciplineName = headerParts.slice(0, -3).join(", ");
                        personName = headerParts[headerParts.length - 3] || "";
                        assessmentType = headerParts[headerParts.length - 2] || "";
                    } else {
                        disciplineName = headerParts[0] || "";
                        personName = headerParts[1] || "";
                        assessmentType = headerParts[2] || "";
                    }

                    const activities = Array.from(block.querySelectorAll("tr"))
                        .filter(function(row) {
                            return !row.classList.contains("collapse");
                        })
                        .map(parseRow);

                    disciplines.push({
                        disciplineName: clean(disciplineName),
                        personName: clean(personName),
                        assessmentType: clean(assessmentType),
                        activities: activities
                    });
                }

                const metadata =
                    document.querySelector("#div-FormHeader .row .col-sm") ||
                    document.querySelector("#div-FormHeader .form-row .col-sm");

                let name = "";
                let group = "";

                if (metadata) {
                    const lines = Array.from(metadata.children || [])
                        .map(function(node) { return clean(node.textContent); })
                        .filter(Boolean);

                    if (lines.length > 0) {
                        const combined = lines[0].match(/^(.*?)\s*\((.*?)\)\s*$/);
                        if (combined) {
                            name = clean(combined[1]);
                            group = clean(combined[2]);
                        } else {
                            name = clean(lines[0].replace(/\(.*\)$/g, ""));
                            if (lines.length > 1) group = clean(lines[1]);
                        }
                    }
                }

                const semesterNode =
                    document.querySelector(".filter-option-inner-inner");
                const semester = semesterNode ? clean(semesterNode.textContent) : "";

                MpeiNeoBars.onData(JSON.stringify({
                    name: name,
                    group: group,
                    semester: semester,
                    disciplines: disciplines
                }));
            } catch (error) {
                MpeiNeoBars.onError(
                    "Не удалось прочитать оценки из текущей сессии БАРС."
                );
            }
        }

        extract(0);
    })();
""".trimIndent()
