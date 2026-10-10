package com.rteats.mpeineo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
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
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var retryNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var redirectingToMarks by remember { mutableStateOf(false) }
    var marksExtractionStarted by remember { mutableStateOf(false) }
    var needsFullViewport by remember { mutableStateOf(true) }
    var lastPageStateSignature by remember { mutableStateOf<String?>(null) }
    var marksMissingPolls by remember { mutableStateOf(0) }
    var marksRouteRecoveryAttempted by remember { mutableStateOf(false) }
    var marksWaitLogged by remember { mutableStateOf(false) }
    var marksFailureReported by remember { mutableStateOf(false) }
    var mainFrameFailed by remember { mutableStateOf(false) }
    var mainFrameRetryCount by remember { mutableStateOf(0) }

    val cacheFresh = state.hasFreshCache()
    // A pager may precompose and immediately discard an off-screen BARS page.
    // Only establish a connection while its tab is settled/visible. Once loaded,
    // retain the same WebView for the entire visible BARS session, even when the
    // extracted grades are cached, instead of destroying it after extraction.
    val shouldHaveWebView = webView != null || (active && (
        state.authStage != BarsAuthStage.AUTHENTICATED ||
            state.browserVisible ||
            state.isLoading ||
            !cacheFresh
        ))

    // The WebView remains mounted when switching to Mail/Settings. Suspend
    // page activity while hidden, but do not destroy its session or JS bridge.
    androidx.compose.runtime.LaunchedEffect(active, webView) {
        webView?.let { view ->
            if (active) view.onResume() else view.onPause()
        }
    }

    BackHandler(enabled = active && state.browserVisible) {
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
                    mainFrameRetryCount = 0
                    mainFrameFailed = false
                    needsFullViewport = true
                    viewModel.checking()
                    retryNavigation?.invoke()
                },
            )

            BarsAuthStage.WEB_AUTH -> {
                // The official BARS page is rendered by the persistent WebView below.
            }

            BarsAuthStage.AUTHENTICATED -> BarsNativeDashboard(
                state = state,
                active = active,
                onRefresh = {
                    redirectingToMarks = false
                    marksRouteRecoveryAttempted = false
                    marksFailureReported = false
                    mainFrameRetryCount = 0
                    mainFrameFailed = false
                    needsFullViewport = true
                    viewModel.startRefresh()
                    retryNavigation?.invoke()
                },
                onOpenBrowser = viewModel::showBrowser,
            )
        }

        if (shouldHaveWebView) {
            AndroidView(
                modifier = when {
                    state.browserVisible -> Modifier.fillMaxSize()
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
                    val provider = WebView.getCurrentWebViewPackage()
                    viewModel.logWebEvent(
                        "webview created cookiesPresent=$hasBarsCookies " +
                            "provider=${provider?.packageName ?: "unknown"} " +
                            "providerVersion=${provider?.versionName ?: "unknown"} " +
                            "browser=${state.browserVisible} stage=${state.authStage}",
                    )
                    viewModel.networkSnapshot("webview-created")

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
                    var programmaticRequestToken = 0
                    var programmaticStartedToken = -1
                    var programmaticFinishedToken = -1
                    var lastLoadStartedAt = SystemClock.elapsedRealtime()
                    var lastNavigationProgress = -1
                    var subresourceErrorCount = 0

                    fun loadBarsWithWatchdog(
                        targetUrl: String,
                        reason: String,
                        fallbackUrl: String? = null,
                    ) {
                        programmaticRequestToken += 1
                        val token = programmaticRequestToken
                        // An aborted navigation can report onPageFinished after the next
                        // loadUrl. Its callback must not mark the new request completed.
                        programmaticStartedToken = -1
                        programmaticFinishedToken = -1
                        lastLoadStartedAt = SystemClock.elapsedRealtime()
                        lastNavigationProgress = -1
                        viewModel.logWebEvent(
                            "load requested id=$token reason=$reason " +
                                "path=${safeBarsLocation(targetUrl)} " +
                                "priorPath=${safeBarsLocation(view.url.orEmpty())} " +
                                "browser=${state.browserVisible} loading=${state.isLoading} " +
                                "viewProgress=${view.progress}",
                        )
                        view.stopLoading()
                        view.loadUrl(targetUrl)

                        view.postDelayed(
                            {
                                if (
                                    webView !== view ||
                                    programmaticRequestToken != token ||
                                    programmaticFinishedToken == token
                                ) {
                                    return@postDelayed
                                }

                                val started = programmaticStartedToken == token
                                viewModel.logWebEvent(
                                    "load watchdog fired id=$token " +
                                        "path=${safeBarsLocation(targetUrl)} " +
                                        "started=$started elapsedMs=${SystemClock.elapsedRealtime() - lastLoadStartedAt} " +
                                        "currentPath=${safeBarsLocation(view.url.orEmpty())} " +
                                        "progress=${view.progress} lastProgressBucket=$lastNavigationProgress " +
                                        "failed=$mainFrameFailed browser=${state.browserVisible}",
                                )
                                viewModel.probeNetwork("watchdog-id-$token")
                                view.stopLoading()

                                if (fallbackUrl != null) {
                                    loadBarsWithWatchdog(
                                        targetUrl = fallbackUrl,
                                        reason = "watchdog fallback",
                                        fallbackUrl = null,
                                    )
                                } else if (!state.browserVisible) {
                                    needsFullViewport = false
                                    viewModel.extractionFailed(
                                        "Тайм-аут подключения к БАРС. Сессия не сброшена. " +
                                            "Проверьте bars.mpei.ru в браузере или попробуйте другую сеть.",
                                    )
                                }
                            },
                            12_000L,
                        )
                    }

                    // The Retry action must use this same timeout-protected
                    // navigation path rather than calling WebView.loadUrl directly.
                    retryNavigation = {
                        if (webView === view) {
                            loadBarsWithWatchdog(
                                targetUrl = BARS_LOGIN_URL,
                                reason = "manual retry",
                                fallbackUrl = null,
                            )
                        }
                    }

                    view.addJavascriptInterface(
                        BarsJavascriptBridge(
                            onPageState = { json ->
                                view.post {
                                    if (webView !== view) return@post
                                    val page = runCatching {
                                        BarsPayloadParser.parsePageState(json)
                                    }.getOrElse { error ->
                                        viewModel.extractionFailed(
                                            error.message ?: "Не удалось определить состояние БАРС",
                                        )
                                        return@post
                                    }

                                    // stopLoading() can leave Chromium displaying a blank
                                    // internal document. Its JS bridge must not transition
                                    // native BARS state or overwrite the original failure.
                                    if (!isTrustedBarsDocument(page.url)) {
                                        val location = safeBarsLocation(page.url)
                                        val ignoredState = "ignored invalid document $location"
                                        if (lastPageStateSignature != ignoredState) {
                                            lastPageStateSignature = ignoredState
                                            viewModel.logWebEvent(ignoredState)
                                        }
                                        // An old callback may arrive after the fallback
                                        // has navigated to a legitimate new document.
                                        // Do not stop the new document's observer.
                                        if (!isTrustedBarsDocument(view.url.orEmpty())) {
                                            view.evaluateJavascript(
                                                BARS_STOP_STATE_OBSERVER_SCRIPT,
                                                null,
                                            )
                                        }
                                        return@post
                                    }

                                    // Discard callbacks sent by an old page after a redirect.
                                    val currentWebLocation = view.url.orEmpty()
                                    if (
                                        !isTrustedBarsDocument(currentWebLocation) ||
                                        safeBarsLocation(page.url) !=
                                            safeBarsLocation(currentWebLocation)
                                    ) {
                                        viewModel.logWebEvent(
                                            "ignored stale page state path=${safeBarsLocation(page.url)}",
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

                                    if (state.browserVisible) {
                                        viewModel.logWebEvent(
                                            "browser mode; leaving navigation untouched path=$currentLocation",
                                        )
                                        return@post
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
                                                    loadBarsWithWatchdog(
                                                        targetUrl = BARS_LOGIN_URL,
                                                        reason = "marks DOM recovery",
                                                        fallbackUrl = null,
                                                    )
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
                                                loadBarsWithWatchdog(
                                                    targetUrl = BARS_MARKS_URL,
                                                    reason = "open marks route",
                                                    fallbackUrl = BARS_LOGIN_URL,
                                                )
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
                                    if (webView !== view) return@post
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
                                    if (webView !== view) return@post
                                    needsFullViewport = false
                                    viewModel.logWebEvent("javascript error=$message")
                                    viewModel.extractionFailed(message)
                                }
                            },
                        ),
                        BARS_JS_INTERFACE,
                    )

                    view.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            val bucket = when {
                                newProgress >= 100 -> 100
                                newProgress >= 75 -> 75
                                newProgress >= 50 -> 50
                                newProgress >= 25 -> 25
                                else -> 0
                            }
                            if (bucket != lastNavigationProgress) {
                                lastNavigationProgress = bucket
                                viewModel.logWebEvent(
                                    "progress=$newProgress bucket=$bucket " +
                                        "path=${safeBarsLocation(view.url.orEmpty())} " +
                                        "elapsedMs=${SystemClock.elapsedRealtime() - lastLoadStartedAt}",
                                )
                            }
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                                consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.WARNING
                            ) {
                                // Never write the console text: it can include private page data.
                                viewModel.logWebEvent(
                                    "console level=${consoleMessage.messageLevel()} " +
                                        "source=${safeBarsLocation(consoleMessage.sourceId())} " +
                                        "line=${consoleMessage.lineNumber()} message=redacted",
                                )
                            }
                            return true
                        }
                    }

                    view.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView,
                            url: String,
                            favicon: Bitmap?,
                        ) {
                            if (webView !== view) return
                            marksExtractionStarted = false
                            marksMissingPolls = 0
                            marksWaitLogged = false
                            if (url.startsWith(BARS_BASE_URL)) {
                                mainFrameFailed = false
                                programmaticStartedToken = programmaticRequestToken
                            }
                            needsFullViewport = true
                            viewModel.updateSessionUrl(url)
                            lastLoadStartedAt = SystemClock.elapsedRealtime()
                            viewModel.logWebEvent(
                                "page started id=$programmaticRequestToken path=${safeBarsLocation(url)} " +
                                    "browser=${state.browserVisible} progress=${view.progress}",
                            )
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (webView !== view) return
                            viewModel.updateSessionUrl(url)
                            viewModel.logWebEvent(
                                "page finished id=$programmaticRequestToken " +
                                    "path=${safeBarsLocation(url)} " +
                                    "elapsedMs=${SystemClock.elapsedRealtime() - lastLoadStartedAt} " +
                                    "progress=${view.progress} currentPath=${safeBarsLocation(view.url.orEmpty())}",
                            )

                            if (
                                mainFrameFailed ||
                                !isTrustedBarsDocument(url)
                            ) {
                                viewModel.logWebEvent(
                                    "ignoring failed or internal WebView document",
                                )
                                return
                            }

                            // A cancelled navigation can finish after the watchdog starts
                            // a fallback. Do not let it complete the fallback's token.
                            if (programmaticStartedToken == programmaticRequestToken) {
                                programmaticFinishedToken = programmaticRequestToken
                            }
                            mainFrameRetryCount = 0
                            view.evaluateJavascript(BARS_PAGE_STATE_SCRIPT, null)
                        }

                        override fun onPageCommitVisible(view: WebView, url: String) {
                            viewModel.logWebEvent(
                                "page committed path=${safeBarsLocation(url)} progress=${view.progress}",
                            )
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String,
                            isReload: Boolean,
                        ) {
                            viewModel.logWebEvent(
                                "navigation history path=${safeBarsLocation(url)} reload=$isReload",
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            if (request.isForMainFrame) {
                                viewModel.logWebEvent(
                                    "main-frame HTTP error status=${errorResponse.statusCode} " +
                                        "path=${safeBarsLocation(request.url.toString())}",
                                )
                                viewModel.probeNetwork("main-http-${errorResponse.statusCode}")
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: SslError,
                        ) {
                            viewModel.logWebEvent(
                                "SSL error primary=${error.primaryError} " +
                                    "path=${safeBarsLocation(error.url.orEmpty())}",
                            )
                            viewModel.probeNetwork("webview-ssl-error")
                            handler.cancel()
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (webView !== view) return
                            if (!request.isForMainFrame) {
                                if (subresourceErrorCount++ < 5) {
                                    viewModel.logWebEvent(
                                        "subresource error code=${error.errorCode} " +
                                            "path=${safeBarsLocation(request.url.toString())}",
                                    )
                                }
                                return
                            }

                            mainFrameFailed = true
                            view.evaluateJavascript(
                                BARS_STOP_STATE_OBSERVER_SCRIPT,
                                null,
                            )

                            val failedUrl = request.url.toString()
                            val canRetry =
                                isRetryableBarsWebError(error.errorCode) &&
                                    failedUrl.startsWith(BARS_BASE_URL) &&
                                    mainFrameRetryCount < 1

                            viewModel.logWebEvent(
                                "main-frame error path=${safeBarsLocation(failedUrl)} " +
                                    "code=${error.errorCode} retry=$canRetry " +
                                    "progress=${view.progress} elapsedMs=${SystemClock.elapsedRealtime() - lastLoadStartedAt}",
                            )
                            viewModel.probeNetwork("webview-error-${error.errorCode}")

                            if (canRetry) {
                                mainFrameRetryCount += 1
                                // Network timeouts are not evidence of an expired
                                // cookie. Retry the same page, never jump to the
                                // marks route based on cookie presence alone.
                                val retryUrl = failedUrl

                                viewModel.logWebEvent(
                                    "retrying BARS main frame in 1500ms target=${safeBarsLocation(retryUrl)}",
                                )
                                view.postDelayed(
                                    {
                                        if (webView === view) {
                                            mainFrameFailed = false
                                            viewModel.logWebEvent(
                                                "retry fired target=${safeBarsLocation(retryUrl)}",
                                            )
                                            loadBarsWithWatchdog(
                                                targetUrl = retryUrl,
                                                reason = "main-frame retry",
                                                fallbackUrl = null,
                                            )
                                        }
                                    },
                                    1_500L,
                                )
                                return
                            }

                            if (!state.browserVisible) {
                                needsFullViewport = false
                                viewModel.extractionFailed(
                                    if (error.errorCode == WebViewClient.ERROR_TIMEOUT) {
                                        "Сервер БАРС не ответил (тайм-аут сети). " +
                                            "Сессия сохранена. Попробуйте Wi-Fi или откройте сайт в браузере."
                                    } else {
                                        "Не удалось открыть БАРС: ${error.description}. Нажмите «Повторить»."
                                    },
                                )
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val uri = request.url
                            if (request.isForMainFrame) {
                                viewModel.logWebEvent(
                                    "navigation requested path=${safeBarsLocation(uri.toString())} " +
                                        "gesture=${request.hasGesture()} main=true",
                                )
                            }
                            if (uri.scheme == "https" && uri.host == "bars.mpei.ru") {
                                return false
                            }

                            return runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                true
                            }.getOrDefault(true)
                        }
                    }

                    // A cookie may be stale, and a timed-out root URL
                    // cannot be repaired by requesting a second protected route.
                    viewModel.logWebEvent(
                        "initial load path=${safeBarsLocation(BARS_LOGIN_URL)}",
                    )
                    loadBarsWithWatchdog(
                        targetUrl = BARS_LOGIN_URL,
                        reason = "initial session check",
                        fallbackUrl = null,
                    )
                }
            },
            update = { view ->
                webView = view
            },
                onRelease = { view ->
                    viewModel.logWebEvent(
                        "webview released stage=${state.authStage} browser=${state.browserVisible} " +
                            "cacheFresh=${state.hasFreshCache()} loading=${state.isLoading} " +
                            "path=${safeBarsLocation(view.url.orEmpty())} progress=${view.progress}",
                    )
                    if (webView === view) retryNavigation = null
                    view.stopLoading()
                    view.removeJavascriptInterface(BARS_JS_INTERFACE)
                    view.webViewClient = WebViewClient()
                    view.destroy()
                    if (webView === view) webView = null
                },
            )
        }

    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
                ContainedLoadingIndicator()
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BarsNativeDashboard(
    state: BarsUiState,
    active: Boolean,
    onRefresh: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var selectedDisciplineName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedDiscipline = state.disciplines.firstOrNull {
        it.disciplineName == selectedDisciplineName
    }

    // Expansion happens inside the BARS tab, rather than navigating to
    // a separate screen. The pinned discipline header is the only collapse
    // control; app-level horizontal paging continues to work.
    val dashboardListState = rememberLazyListState()
    if (selectedDiscipline != null) {
        DisciplineExpandedCard(
            discipline = selectedDiscipline,
            onCollapse = { selectedDisciplineName = null },
            onOpenBrowser = onOpenBrowser,
        )
        return
    }

    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = onRefresh,
        state = refreshState,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = refreshState,
                isRefreshing = state.isLoading,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = dashboardListState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                BarsUserHeader(
                    state = state,
                    onOpenBrowser = onOpenBrowser,
                )
            }

            if (state.disciplines.isNotEmpty()) {
                item { BarsSemesterSummary(state) }
            }

            state.error?.let { message ->
                item {
                    val hasCachedGrades = state.disciplines.isNotEmpty()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (hasCachedGrades) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (hasCachedGrades) {
                                Text(
                                    text = "Не удалось обновить оценки. Показаны сохранённые данные.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = message,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            TextButton(onClick = onRefresh) {
                                Text("Повторить")
                            }
                        }
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
                        onClick = { selectedDisciplineName = discipline.disciplineName },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .padding(bottom = 8.dp),
                    )
                }
            }

            item {
                // Scrollable trailing space lets the last discipline move above
                // the overlaid floating toolbar without an opaque bottom footer.
                Spacer(Modifier.size(112.dp))
            }
        }
    }
}

/**
 * Expressive BARS dashboard: dominant profile surface + factual semester
 * summary + approachable, interactive discipline cards.
 */
@Composable
private fun BarsUserHeader(
    state: BarsUiState,
    onOpenBrowser: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "МОЙ БАРС",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.profileName.ifBlank { "БАРС МЭИ" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.profileGroup.isNotBlank()) {
                Text(
                    text = state.profileGroup,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (state.semester.isNotBlank()) {
                Text(
                    text = state.semester,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilledTonalButton(
                onClick = onOpenBrowser,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Открыть БАРС")
                Spacer(Modifier.size(8.dp))
                androidx.compose.material3.Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun BarsSemesterSummary(state: BarsUiState) {
    val gradedCount = state.disciplines.count {
        it.finalMarkValue != null || it.markValues.isNotEmpty()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BarsSummaryStat(
            value = state.disciplines.size,
            label = "Дисциплин",
            modifier = Modifier.weight(1f),
        )
        BarsSummaryStat(
            value = gradedCount,
            label = "С оценками",
            modifier = Modifier.weight(1f),
        )
        BarsSummaryStat(
            value = state.controlSchedule.size,
            label = "Контрольных",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BarsSummaryStat(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
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
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = discipline.disciplineName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                androidx.compose.material3.Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "Подробности",
                    modifier = Modifier.padding(top = 3.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            if (discipline.assessmentType.isNotBlank()) {
                AssessmentTypeChip(text = discipline.assessmentType)
            }

            if (discipline.personName.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "ПРЕПОДАВАТЕЛЬ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = discipline.personName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )
            Text(
                text = "ОЦЕНКИ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val finalMark = discipline.finalMarkValue
            when {
                finalMark != null -> {
                    GradeChip(
                        text = "Итог: ${finalMark.roundToInt()}",
                        mark = finalMark,
                    )
                }

                discipline.markValues.isNotEmpty() -> {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        discipline.markValues.take(12).forEach { mark ->
                            GradeChip(
                                text = mark.roundToInt().toString(),
                                mark = mark,
                            )
                        }
                        if (discipline.markValues.size > 12) {
                            NeutralChip("+${discipline.markValues.size - 12}")
                        }
                    }
                }

                else -> NeutralChip("Нет оценок")
            }
        }
    }
}

/**
 * Expanded discipline remains an in-place BARS card, not a navigation
 * destination. Its title stays pinned and collapses the card on tap.
 * Content can scroll within the card, without reaching other disciplines.
 */
@Composable
private fun DisciplineExpandedCard(
    discipline: BarsDiscipline,
    onCollapse: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val controls = discipline.activities.filter {
        it.type == BarsActivityType.CONTROL_ACTIVITY
    }
    val otherActivities = discipline.activities.filter {
        it.type != BarsActivityType.CONTROL_ACTIVITY
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp)
            .testTag("bars-discipline-expanded"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Same card header expands and collapses the discipline in-place.
        Surface(
            onClick = onCollapse,
            modifier = Modifier.fillMaxWidth()
                .padding(top = 8.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    discipline.disciplineName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Свернуть дисциплину",
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (discipline.assessmentType.isNotBlank()) {
                        AssessmentTypeChip(discipline.assessmentType)
                    }
                    if (discipline.personName.isNotBlank()) {
                        Text(
                            discipline.personName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        discipline.finalMarkValue?.let {
                            GradeChip("Итог: ${formatBarsGrade(it.toDouble())}", it)
                        }
                        discipline.currentScoreValue?.let {
                            GradeChip("Текущий: ${formatBarsGrade(it.toDouble())}", it)
                        }
                    }
                    Text(
                        "Оценено: ${controls.count { it.markValue != null }} / ${controls.size}" +
                            if (controls.any { it.weekNum?.isNotBlank() == true }) {
                                "  ·  Сроки указаны в контрольных мероприятиях"
                            } else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GradeForecastSection(discipline)
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "Контрольные мероприятия",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (controls.isEmpty()) {
                        Text(
                            "Контрольных мероприятий нет",
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        controls.forEachIndexed { index, activity ->
                            ActivityDetailRow(activity)
                            if (index < controls.lastIndex) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }

            if (otherActivities.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            "Другие данные БАРС",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        otherActivities.forEachIndexed { index, activity ->
                            ActivityDetailRow(activity)
                            if (index < otherActivities.lastIndex) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onOpenBrowser,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Открыть в БАРС")
                Spacer(Modifier.size(6.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Compact automatic-grade preview: existing grades, missing slots, minimal combinations. */
@Composable
private fun GradeForecastSection(
    discipline: BarsDiscipline,
    modifier: Modifier = Modifier,
) {
    val forecast = remember(discipline) { calculateGradeForecast(discipline) }
    val controls = discipline.activities.filter {
        it.type == BarsActivityType.CONTROL_ACTIVITY
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Автомат",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (controls.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                controls.forEach { activity ->
                    val grade = activity.markValue
                    if (grade != null) {
                        GradeChip(formatBarsGrade(grade.toDouble()), grade)
                    } else {
                        NeutralChip("—")
                    }
                }
            }
        }
        when (forecast) {
            is GradeForecast.Unavailable -> {
                Text(
                    "Недостаточно данных",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is GradeForecast.Available -> {
                if (forecast.combinations.isEmpty()) {
                    Text(
                        "Недостижимо",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (forecast.remaining.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    forecast.combinations.forEach { combination ->
                        var upcomingIndex = 0
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            controls.forEach { activity ->
                                val existing = activity.markValue
                                val value = when {
                                    existing != null -> existing
                                    parseBarsWeight(activity.weight)?.let { it > 0.0 } == true &&
                                        upcomingIndex < combination.marks.size ->
                                        combination.marks[upcomingIndex++].toFloat()
                                    else -> null
                                }
                                if (value != null) {
                                    GradeChip(formatBarsGrade(value.toDouble()), value)
                                } else {
                                    NeutralChip("—")
                                }
                            }
                        }
                    }
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
                activity.markAndDate?.takeIf { it.isNotBlank() }?.let {
                    add("Запись БАРС: $it")
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
        shape = RoundedCornerShape(14.dp),
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

private fun formatBarsGrade(mark: Double): String =
    if (mark % 1.0 == 0.0) mark.toInt().toString() else formatBarsWeightedSum(mark)

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
        shape = RoundedCornerShape(10.dp),
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
        shape = RoundedCornerShape(10.dp),
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

private fun isTrustedBarsDocument(url: String): Boolean =
    runCatching {
        val uri = Uri.parse(url)
        uri.scheme == "https" &&
            uri.host.equals("bars.mpei.ru", ignoreCase = true)
    }.getOrDefault(false)

private fun isRetryableBarsWebError(errorCode: Int): Boolean =
    errorCode == WebViewClient.ERROR_TIMEOUT ||
        errorCode == WebViewClient.ERROR_CONNECT ||
        errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
        errorCode == WebViewClient.ERROR_UNKNOWN

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
