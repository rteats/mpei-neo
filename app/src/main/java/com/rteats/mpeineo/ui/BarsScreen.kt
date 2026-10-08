package com.rteats.mpeineo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

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

    val webIsVisible =
        state.authStage == BarsAuthStage.WEB_AUTH || state.browserVisible

    BackHandler(enabled = state.browserVisible) {
        val view = webView
        if (view?.canGoBack() == true && view.url != BARS_MARKS_URL) {
            view.goBack()
        } else {
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
                    viewModel.checking()
                    webView?.loadUrl(BARS_MARKS_URL)
                },
            )

            BarsAuthStage.WEB_AUTH -> {
                // The official BARS page is rendered by the persistent WebView below.
            }

            BarsAuthStage.AUTHENTICATED -> BarsNativeDashboard(
                state = state,
                onRefresh = {
                    redirectingToMarks = false
                    viewModel.startRefresh()
                    webView?.loadUrl(BARS_MARKS_URL)
                },
                onOpenBrowser = viewModel::showBrowser,
            )
        }

        AndroidView(
            modifier = if (webIsVisible) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .size(1.dp)
                    .alpha(0f)
            },
            factory = { context ->
                WebView(context).also { view ->
                    webView = view

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

                                    when {
                                        page.isMarksPage -> {
                                            redirectingToMarks = false
                                            viewModel.authenticatedPage(page.url)
                                            view.evaluateJavascript(BARS_EXTRACT_SCRIPT, null)
                                        }

                                        page.isLoginPage ||
                                            page.isAuthFlow ||
                                            page.isStudentList -> {
                                            redirectingToMarks = false
                                            viewModel.webAuth(page.url)
                                        }

                                        page.url.startsWith(BARS_BASE_URL) -> {
                                            // A successful password/2FA flow may land on the
                                            // BARS main menu. From there move to the student's
                                            // current marks page once.
                                            if (!redirectingToMarks) {
                                                redirectingToMarks = true
                                                viewModel.checking(page.url)
                                                view.loadUrl(BARS_MARKS_URL)
                                            }
                                        }

                                        else -> {
                                            viewModel.extractionFailed(
                                                "БАРС открыл неожиданную страницу",
                                            )
                                        }
                                    }
                                }
                            },
                            onData = { json ->
                                view.post {
                                    CookieManager.getInstance().flush()
                                    viewModel.extractionReceived(json)
                                }
                            },
                            onError = { message ->
                                view.post {
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
                            viewModel.updateSessionUrl(url)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            viewModel.updateSessionUrl(url)
                            view.evaluateJavascript(BARS_PAGE_STATE_SCRIPT, null)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
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

                    view.loadUrl(BARS_MARKS_URL)
                }
            },
            update = { view ->
                webView = view
            },
            onRelease = { view ->
                view.stopLoading()
                view.removeJavascriptInterface(BARS_JS_INTERFACE)
                view.webViewClient = WebViewClient()
                view.destroy()
                if (webView === view) webView = null
            },
        )

        if (state.browserVisible) {
            FilledTonalButton(
                onClick = viewModel::hideBrowser,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            ) {
                Text("Вернуться в приложение")
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
    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                BarsProfileCard(
                    state = state,
                    onRefresh = onRefresh,
                    onOpenBrowser = onOpenBrowser,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }

            state.error?.let { message ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
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
                            .padding(horizontal = 16.dp),
                    )
                }
            }

            item {
                SectionTitle(
                    title = "Текущие оценки",
                    subtitle = state.semester.takeIf { it.isNotBlank() },
                )
            }

            if (state.disciplines.isEmpty() && !state.isLoading) {
                item {
                    EmptyBarsCard(
                        text = "В текущем семестре оценки не найдены.",
                    )
                }
            } else {
                items(
                    items = state.disciplines,
                ) { discipline ->
                    DisciplineCard(
                        discipline = discipline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            }

            if (state.controlSchedule.isNotEmpty()) {
                item {
                    SectionTitle(
                        title = "Контрольные мероприятия",
                        subtitle = "План КМ по учебным неделям",
                    )
                }

                items(
                    items = state.controlSchedule,
                ) { control ->
                    ControlScheduleCard(
                        item = control,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            }

            item {
                Spacer(Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun BarsProfileCard(
    state: BarsUiState,
    onRefresh: () -> Unit,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = state.profileName.ifBlank { "БАРС МЭИ" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (state.profileGroup.isNotBlank()) {
                Text(
                    text = state.profileGroup,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenBrowser,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Открыть БАРС")
                }
                FilledTonalButton(
                    onClick = onRefresh,
                ) {
                    Text("Обновить")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DisciplineCard(
    discipline: BarsDiscipline,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = discipline.disciplineName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                val headlineMark = discipline.finalMark ?: discipline.currentScore
                if (!headlineMark.isNullOrBlank()) {
                    ScoreChip(headlineMark)
                }
            }

            if (discipline.assessmentType.isNotBlank()) {
                Text(
                    text = discipline.assessmentType,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (discipline.personName.isNotBlank()) {
                Text(
                    text = discipline.personName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (discipline.currentMarks.isNotEmpty()) {
                Text(
                    text = discipline.currentMarks.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (discipline.finalMark == null && discipline.currentScore == null) {
                Text(
                    text = "Оценок пока нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScoreChip(mark: String) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = mark,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ControlScheduleCard(
    item: BarsControlScheduleItem,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.activity.ifBlank { "Контрольное мероприятие" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${item.weekNum} нед.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = item.discipline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val details = buildList {
                if (item.weight.isNotBlank()) add("вес ${item.weight}")
                if (item.markAndDate.isNotBlank()) add(item.markAndDate)
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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

private const val BARS_JS_INTERFACE = "MpeiNeoBars"

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

                    const header = clean(blocks[i - 1].textContent).split(",");
                    const activities = Array.from(block.querySelectorAll("tr"))
                        .filter(function(row) {
                            return !row.classList.contains("collapse");
                        })
                        .map(parseRow);

                    disciplines.push({
                        disciplineName: clean(header[0]),
                        personName: clean(header[1]),
                        assessmentType: clean(header[2]),
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
