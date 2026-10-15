package com.rteats.mpeineo.ui

import com.rteats.mpeineo.AppIcon
import com.rteats.mpeineo.AppIcons

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date

/**
* Native BARS frontend backed by the official BARS web session.
*
* Authentication intentionally stays on the official BARS pages. This makes the app follow
* BARS' current password + 2FA flow instead of duplicating the private authentication protocol.
* Once authenticated, the same WebView session is used to extract the current marks into the
* native Compose UI. The WebView is kept alive so "Открыть БАРС" exposes that exact session.
*/
/** Pure guard: a disabled automatic update must not create a Home WebView. */
internal fun shouldInitializeBarsWebView(
  connectionRequired: Boolean,
  hasWebView: Boolean,
  active: Boolean,
  backgroundAllowed: Boolean,
): Boolean = connectionRequired && (hasWebView || active || backgroundAllowed)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BarsScreen(
  viewModel: BarsViewModel,
  autoRefreshEnabled: Boolean = true,
  active: Boolean = true,
  /** Keep the single WebView alive for automatic Home data refresh. */
  allowBackgroundSync: Boolean = false,
  onBrowserExit: () -> Unit = {},
  onDetailOpenChange: (Boolean) -> Unit = {},
  openDisciplineName: String? = null,
  openDisciplineRequest: Int = 0,
  onOpenDisciplineConsumed: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsState()
  val browserCanInteract by rememberUpdatedState(
    active && (state.browserVisible || state.authStage == BarsAuthStage.WEB_AUTH)
  )
  var webView by remember { mutableStateOf<WebView?>(null) }
  var navigationController by remember { mutableStateOf<BarsNavigationController?>(null) }
  var marksReadiness by remember { mutableStateOf<BarsMarksReadiness?>(null) }
  var retryNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
  var redirectingToMarks by remember { mutableStateOf(false) }
  var marksExtractionStarted by remember { mutableStateOf(false) }
  var summaryFetchAttempted by remember { mutableStateOf(false) }
  var summaryExtractionStarted by remember { mutableStateOf(false) }
  var officialMainUrl by remember { mutableStateOf<String?>(null) }
  var summaryRouteDiscoveryGeneration by remember { mutableStateOf(0) }
  var needsFullViewport by remember { mutableStateOf(true) }
  var lastPageStateSignature by remember { mutableStateOf<String?>(null) }
  var mainFrameFailed by remember { mutableStateOf(false) }
  val extractionGate = remember { BarsExtractionGate() }
  val autoRefreshGate = remember { BarsAutoRefreshGate() }
  var manuallyRequestedConnection by remember { mutableStateOf(false) }

  androidx.compose.runtime.LaunchedEffect(autoRefreshEnabled) {
    if (!autoRefreshEnabled && !manuallyRequestedConnection &&
      state.disciplines.isNotEmpty()
    ) {
      viewModel.showCachedWithoutRefresh()
    }
  }

  val cacheFresh = state.hasFreshCache()
  // The super-app keeps one BARS composition mounted even at Home. Auto-refresh
  // may establish its authenticated WebView without entering the BARS service.
  // Explicitly disabling automatic updates still suppresses that background work.
  val connectionRequired = autoRefreshEnabled || state.disciplines.isEmpty() ||
    manuallyRequestedConnection || state.browserVisible
  val shouldHaveWebView = shouldInitializeBarsWebView(
    connectionRequired = connectionRequired,
    hasWebView = webView != null,
    active = active,
    backgroundAllowed = allowBackgroundSync,
  )

  // A background Home sync must not pause JS while Main/Summary are loading.
  // The same WebView survives entering/leaving BARS and retains its cookies.
  androidx.compose.runtime.LaunchedEffect(active, allowBackgroundSync, webView, autoRefreshEnabled) {
    webView?.let { view ->
      if (active || allowBackgroundSync) view.onResume() else view.onPause()
      if ((active || allowBackgroundSync) && state.authStage == BarsAuthStage.AUTHENTICATED &&
        !state.loginRequired &&
        autoRefreshGate.shouldRefresh(
          enabled = autoRefreshEnabled,
          nowElapsedMs = SystemClock.elapsedRealtime(),
          sessionReady = navigationController?.hasCompletedDocument == true &&
            retryNavigation != null,
          hasCachedGrades = state.lastUpdatedAtMillis > 0L &&
            state.disciplines.isNotEmpty(),
          cacheFresh = state.hasFreshCache(),
          isLoading = state.isLoading,
          browserVisible = state.browserVisible,
        )
      ) {
        viewModel.logWebEvent("cached grades expired; refreshing on tab activation")
        redirectingToMarks = false
        summaryFetchAttempted = false
        summaryExtractionStarted = false
        mainFrameFailed = false
        needsFullViewport = true
        marksReadiness?.reset()
        viewModel.startRefresh()
        retryNavigation?.invoke()
      }
    }
  }

  var browserWasVisible by remember { mutableStateOf(false) }
  androidx.compose.runtime.LaunchedEffect(state.browserVisible, webView) {
    if (state.browserVisible) marksReadiness?.navigationStarted()
    if (browserWasVisible && !state.browserVisible) {
      webView?.let { view ->
        if (BarsDocumentPolicy.isTrusted(view.url.orEmpty())) {
          // Login/2FA may have finished while showing the official
          // browser. Recheck its final page when returning to grades.
          view.evaluateJavascript(BARS_PAGE_STATE_SCRIPT, null)
        }
      }
    }
    browserWasVisible = state.browserVisible
  }

  BackHandler(enabled = active && state.browserVisible) {
    val view = webView
    if (view?.canGoBack() == true && view.url != BARS_MARKS_URL) {
      view.goBack()
    } else {
      // Arm the pager exit guard before WebView loses focus or is destroyed.
      onBrowserExit()
      view?.clearFocus()
      needsFullViewport = false
      viewModel.hideBrowser()
    }
  }

  fun restoreOfficialMarks(reason: String) {
    // A bare Main route can be rejected without BARS' selected student.
    val target = officialMainUrl?.takeIf {
      BarsAuthenticatedRoutePolicy.isStudentRoute(it, "Main")
    } ?: BARS_LOGIN_URL
    viewModel.logWebEvent(
      "restore marks officialLink=" + (target != BARS_LOGIN_URL),
    )
    navigationController?.load(target, reason)
  }

  fun requestOfficialSummary(view: WebView, reason: String) {
    val controller = navigationController ?: return
    if (webView !== view || state.browserVisible ||
      !BarsDocumentPolicy.isCurrent(view.url.orEmpty(), BARS_MARKS_URL)
    ) return

    // Route discovery is asynchronous. Keep the authenticated document laid
    // out at its normal size until discovery/navigation is finished: reducing
    // Chromium to 1dp can remove or postpone responsive menu markup.
    summaryFetchAttempted = true
    summaryExtractionStarted = false
    needsFullViewport = true
    val generation = ++summaryRouteDiscoveryGeneration

    fun discover(attempt: Int) {
      if (webView !== view || state.browserVisible ||
        navigationController !== controller ||
        !summaryFetchAttempted || generation != summaryRouteDiscoveryGeneration ||
        !BarsDocumentPolicy.isCurrent(view.url.orEmpty(), BARS_MARKS_URL)
      ) return

      view.evaluateJavascript(BARS_AUTHENTICATED_ROUTES_SCRIPT) { result ->
        if (webView !== view || state.browserVisible ||
          navigationController !== controller ||
          !summaryFetchAttempted || generation != summaryRouteDiscoveryGeneration ||
          !BarsDocumentPolicy.isCurrent(view.url.orEmpty(), BARS_MARKS_URL)
        ) return@evaluateJavascript

        val discovery = BarsAuthenticatedRoutePolicy.parseJsResult(result)
        val routes = BarsAuthenticatedRoutePolicy.validatedRoutes(discovery)
        viewModel.logWebEvent(
          "Summary route discovery attempt=${attempt + 1} " +
            (discovery?.safeDiagnostics() ?: "result=unreadable") +
            " valid=${routes != null}",
        )
        if (routes == null) {
          if (attempt < 3) {
            // BARS renders parts of its navigation asynchronously on some
            // devices. Probe again without reloading or disturbing marks.
            val delayMs = when (attempt) {
              0 -> 450L
              1 -> 1_200L
              else -> 2_400L
            }
            view.postDelayed({ discover(attempt + 1) }, delayMs)
          } else {
            viewModel.logWebEvent("Summary route unavailable after DOM retries")
            needsFullViewport = false
            viewModel.summaryCaptureFailed(
              "БАРС не предоставил ссылку на Сводку для текущего студента. " +
                "Откройте БАРС или повторите обновление.",
            )
          }
          return@evaluateJavascript
        }

        officialMainUrl = routes.main
        viewModel.summaryCaptureStarted()
        viewModel.logWebEvent(
          "Summary official navigation resolved=true; opening student-scoped page",
        )
        controller.load(routes.summary, reason)
      }
    }
    discover(0)
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
          mainFrameFailed = false
          summaryFetchAttempted = false
          summaryExtractionStarted = false
          needsFullViewport = true
          marksReadiness?.reset()
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
        onDetailOpenChange = onDetailOpenChange,
        openDisciplineName = openDisciplineName,
        openDisciplineRequest = openDisciplineRequest,
        onOpenDisciplineConsumed = onOpenDisciplineConsumed,
        showCachedRefreshErrors = autoRefreshEnabled || manuallyRequestedConnection,
        onAcknowledgeGrades = viewModel::acknowledgeGradeChanges,
        onRefresh = {
          manuallyRequestedConnection = true
          redirectingToMarks = false
          summaryFetchAttempted = false
          summaryExtractionStarted = false
          mainFrameFailed = false
          if (state.loginRequired) {
            viewModel.showBrowser()
          } else {
            needsFullViewport = true
            marksReadiness?.reset()
            viewModel.startRefresh()
            retryNavigation?.invoke()
          }
        },
        onOpenBrowser = {
          marksReadiness?.navigationStarted()
          viewModel.showBrowser()
        },
        onRefreshSummary = {
          manuallyRequestedConnection = true
          if (state.loginRequired) {
            viewModel.showBrowser()
          } else if (!state.isSummaryLoading) {
            val view = webView
            summaryFetchAttempted = false
            summaryExtractionStarted = false
            mainFrameFailed = false
            if (view != null &&
              BarsDocumentPolicy.isCurrent(view.url.orEmpty(), BARS_MARKS_URL) &&
              !state.isLoading && navigationController != null
            ) {
              requestOfficialSummary(view, "manual Summary refresh")
            } else {
              // The live document is not Main yet. Revalidate marks first;
              // their extraction callback will chain into Summary.
              needsFullViewport = true
              marksReadiness?.reset()
              viewModel.startRefresh()
              retryNavigation?.invoke()
            }
          }
        },
      )
    }

    if (shouldHaveWebView) {
      AndroidView(
        // On refresh BARS needs a full-size WebView to lay out its DOM.
        // Transparent AndroidViews still intercept input when layered on top.
        // Only the explicit session browser or login should cover native UI.
        modifier = (when {
          state.browserVisible -> Modifier.fillMaxSize()
          state.authStage == BarsAuthStage.WEB_AUTH -> Modifier.fillMaxSize()
          needsFullViewport -> Modifier
            .fillMaxSize()
            .alpha(0f)
          else -> Modifier
            .size(1.dp)
            .alpha(0f)
        }).zIndex(
          if (!state.browserVisible &&
            state.authStage != BarsAuthStage.WEB_AUTH
          ) -1f else 1f,
        ),
      factory = { context ->
        WebView(context).also { view ->
          // Let Chromium own pointer events when the official browser is visible.
          // This listener never consumes events, including nested HTML scrolling.
          val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
          var touchDownX = 0f
          var touchDownY = 0f
          var verticalGestureLogged = false
          view.setOnTouchListener { touched, event ->
            if (!browserCanInteract) return@setOnTouchListener false
            when (event.actionMasked) {
              MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                verticalGestureLogged = false
                touched.parent?.requestDisallowInterceptTouchEvent(true)
              }
              MotionEvent.ACTION_MOVE -> {
                if (!verticalGestureLogged &&
                    kotlin.math.abs(event.y - touchDownY) > touchSlop &&
                    kotlin.math.abs(event.y - touchDownY) >
                      kotlin.math.abs(event.x - touchDownX)) {
                  verticalGestureLogged = true
                  viewModel.logWebEvent(
                    "vertical browser gesture enabled=${touched.isEnabled} " +
                      "width=${touched.width} height=${touched.height} " +
                      "scrollY=${view.scrollY} contentHeight=${view.contentHeight}"
                  )
                }
              }
              MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                touched.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
          }
          view.setOnFocusChangeListener { _, focused ->
            viewModel.logWebEvent(
              "browser focus changed focused=$focused browser=${state.browserVisible} " +
                "stage=${state.authStage}"
            )
          }
          var previousWidth = -1
          var previousHeight = -1
          view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width != previousWidth || height != previousHeight) {
              previousWidth = width
              previousHeight = height
              viewModel.logWebEvent(
                "browser layout width=$width height=$height " +
                  "browser=${state.browserVisible} stage=${state.authStage}"
              )
            }
          }
          webView = view
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
          var subresourceErrorCount = 0
          val controller = BarsNavigationController(
            view = view,
            log = viewModel::logWebEvent,
            probe = viewModel::probeNetwork,
            onTimeout = {
              if (!state.browserVisible &&
                summaryFetchAttempted && state.isSummaryLoading
              ) {
                summaryExtractionStarted = false
                viewModel.summaryCaptureFailed(
                  "Не удалось загрузить Сводку: сервер БАРС не ответил.",
                )
                // A Summary failure should not erase successfully cached marks.
                restoreOfficialMarks("restore marks after Summary timeout")
              } else if (!state.browserVisible) {
                needsFullViewport = false
                viewModel.extractionFailed(
                  "Тайм-аут подключения к БАРС. Сессия не сброшена. " +
                    "Проверьте bars.mpei.ru в браузере или попробуйте другую сеть.",
                )
              }
            },
          )
          navigationController = controller

          fun loadBarsWithWatchdog(
            targetUrl: String,
            reason: String,
            fallbackUrl: String? = null,
          ) {
            extractionGate.invalidate()
            controller.load(targetUrl, reason, fallbackUrl)
          }

          val readiness = BarsMarksReadiness(
            schedule = { callback, delayMs -> view.postDelayed(callback, delayMs) },
            cancel = { callback -> view.removeCallbacks(callback) },
            onRecover = {
              viewModel.logWebEvent(
                "marks DOM timeout; bootstrapping through BARS root",
              )
              loadBarsWithWatchdog(
                targetUrl = BARS_LOGIN_URL,
                reason = "marks DOM recovery",
              )
            },
            onFailure = {
              needsFullViewport = false
              view.evaluateJavascript(BARS_STOP_STATE_OBSERVER_SCRIPT, null)
              viewModel.extractionFailed(
                "БАРС открыл страницу оценок, но таблица не загрузилась.",
              )
            },
          )
          marksReadiness = readiness

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
              onSummaryDiagnostic = { safeMessage ->
                viewModel.logWebEvent("Summary DOM " + safeMessage.take(180))
              },
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
                    "$currentLocation|${page.isLoginPage}|${page.isAuthFlow}|${page.isStudentList}|${page.isMarksPage}|${page.isSummaryReady}"
                  if (signature != lastPageStateSignature) {
                    lastPageStateSignature = signature
                    viewModel.logWebEvent(
                      "pageState path=$currentLocation login=${page.isLoginPage} authFlow=${page.isAuthFlow} studentList=${page.isStudentList} marks=${page.isMarksPage} summary=${page.isSummaryPage} ready=${page.isSummaryReady}",
                    )
                  }

                  if (state.browserVisible) {
                    readiness.navigationStarted()
                    viewModel.logWebEvent(
                      "browser mode; leaving navigation untouched path=$currentLocation",
                    )
                    return@post
                  }

                  // Summary has independent AJAX-populated grade and attendance tables.
                  if (page.isSummaryPage && summaryFetchAttempted) {
                    if (page.isSummaryReady && !summaryExtractionStarted) {
                      summaryExtractionStarted = true
                      viewModel.logWebEvent("summary tables ready")
                      view.evaluateJavascript(BARS_SUMMARY_RAW_SCRIPT, null)
                    }
                    return@post
                  }

                  when (classifyBarsPage(page)) {
                    BarsPageKind.MARKS_READY -> {
                      redirectingToMarks = false
                      readiness.reset()
                      if (summaryFetchAttempted && state.isSummaryLoading) {
                        // Redirected back to Main without a Summary payload.
                        viewModel.summaryCaptureFailed(
                          "Сводка не вернула данные. Нажмите «Обновить», чтобы повторить.",
                        )
                      }
                      if (!marksExtractionStarted) {
                        marksExtractionStarted = true
                        viewModel.authenticatedPage(page.url)
                        val extractionId = extractionGate.begin()
                        view.evaluateJavascript(
                          BARS_EXTRACT_SCRIPT.replace(
                            BARS_EXTRACT_TOKEN_PLACEHOLDER,
                            extractionId.toString(),
                          ),
                          null,
                        )
                      }
                    }
                    BarsPageKind.AUTH_FLOW -> {
                      redirectingToMarks = false
                      readiness.reset()
                      if (summaryFetchAttempted && state.isSummaryLoading) {
                        viewModel.summaryCaptureFailed(
                          "Для загрузки Сводки требуется повторный вход в БАРС.",
                        )
                      }
                      officialMainUrl = null
                      viewModel.webAuth(page.url)
                    }
                    BarsPageKind.MARKS_LOADING -> {
                      redirectingToMarks = false
                      viewModel.waitingForMarks(page.url)
                      if (readiness.waitForMarks()) {
                        viewModel.logWebEvent(
                          "marks route loaded; waiting for marks DOM",
                        )
                      }
                    }
                    BarsPageKind.OTHER_BARS_PAGE -> {
                      readiness.navigationStarted()
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
                    BarsPageKind.UNTRUSTED -> {
                      viewModel.logWebEvent("ignored untrusted page state")
                    }
                  }
                }
              },
              onData = { token, json ->
                view.post {
                  if (webView !== view) return@post
                  if (!extractionGate.consume(token) ||
                    !BarsDocumentPolicy.isCurrent(
                      view.url.orEmpty(), BARS_MARKS_URL,
                    ) || state.browserVisible || mainFrameFailed
                  ) {
                    viewModel.logWebEvent("ignored stale extraction result")
                    return@post
                  }
                  // Keep the offscreen WebView at full layout size until the
                  // Main -> Summary route has been resolved. On the return
                  // trip, marks are already cached and it can shrink again.
                  if (summaryFetchAttempted && !state.isSummaryLoading) {
                    needsFullViewport = false
                  }
                  readiness.reset()
                  viewModel.logWebEvent("extraction bridge returned data")
                  CookieManager.getInstance().flush()
                  viewModel.extractionReceived(json)
                  // Extract Main before visiting Summary. The flag prevents a
                  // second trip when the WebView returns to Main.
                  view.evaluateJavascript(BARS_MAIN_RAW_SCRIPT, null)
                }
              },
              onRawSnapshot = { source, raw ->
                view.post {
                  if (webView !== view || state.browserVisible || mainFrameFailed ||
                    !BarsDocumentPolicy.isTrusted(view.url.orEmpty())) return@post
                  val valid = when (source) {
                    "MAIN" -> BarsDocumentPolicy.isCurrent(view.url.orEmpty(), BARS_MARKS_URL)
                    "SUMMARY" -> BarsDocumentPolicy.isCurrent(view.url.orEmpty(), BARS_SUMMARY_URL)
                    else -> false
                  }
                  if (!valid) return@post
                  viewModel.rawSnapshotReceived(source, raw)
                  if (source == "MAIN" && !summaryFetchAttempted &&
                    !state.browserVisible
                  ) {
                    viewModel.logWebEvent("automatic Summary capture: resolving official link")
                    requestOfficialSummary(view, "automatic Summary capture")
                  }
                  if (source == "SUMMARY" && summaryFetchAttempted) {
                    viewModel.logWebEvent("summary captured; restoring marks")
                    restoreOfficialMarks("restore marks after Summary")
                  }
                }
              },
              onExtractionError = { token, message ->
                view.post {
                  if (webView !== view || !extractionGate.consume(token)) {
                    return@post
                  }
                  if (!BarsDocumentPolicy.isCurrent(
                      view.url.orEmpty(), BARS_MARKS_URL,
                    ) || state.browserVisible || mainFrameFailed
                  ) return@post
                  needsFullViewport = false
                  readiness.navigationStarted()
                  viewModel.logWebEvent("extraction error=$message")
                  viewModel.extractionFailed(message)
                }
              },
              onError = { message ->
                view.post {
                  if (webView !== view) return@post
                  // Errors from an obsolete page observer must not
                  // overwrite successfully cached marks.
                  viewModel.logWebEvent("javascript page observer error")
                  if (state.isLoading) {
                    needsFullViewport = false
                    readiness.navigationStarted()
                    viewModel.extractionFailed(message)
                  }
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
              if (controller.recordProgress(bucket)) {
                viewModel.logWebEvent(
                  "progress=$newProgress bucket=$bucket " +
                    "path=${safeBarsLocation(view.url.orEmpty())} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - controller.lastLoadStartedAt}",
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
              extractionGate.invalidate()
              readiness.navigationStarted()
              if (isTrustedBarsDocument(url)) mainFrameFailed = false
              controller.onStarted(url)
              needsFullViewport = true
              viewModel.updateSessionUrl(url)
              viewModel.logWebEvent(
                "page started id=${controller.requestId} path=${safeBarsLocation(url)} " +
                  "browser=${state.browserVisible} progress=${view.progress}",
              )
            }

            override fun onPageFinished(view: WebView, url: String) {
              if (webView !== view) return
              viewModel.updateSessionUrl(url)
              viewModel.logWebEvent(
                "page finished id=${controller.requestId} " +
                  "path=${safeBarsLocation(url)} " +
                  "elapsedMs=${SystemClock.elapsedRealtime() - controller.lastLoadStartedAt} " +
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

              if (!controller.onFinished(url)) {
                viewModel.logWebEvent("ignoring stale page completion")
                return
              }
              view.evaluateJavascript(BARS_PAGE_STATE_SCRIPT, null)
              if (BarsDocumentPolicy.isCurrent(url, BARS_SUMMARY_URL) &&
                summaryFetchAttempted
              ) {
                view.evaluateJavascript(BARS_SUMMARY_DIAGNOSTICS_SCRIPT, null)
                val deadlineNavigationId = controller.requestId
                // Starting the extraction script is not proof of success.
                // If its bridge never returns, restore the native screen too.
                view.postDelayed({
                  if (webView === view &&
                    BarsDocumentPolicy.isCurrent(view.url.orEmpty(), BARS_SUMMARY_URL) &&
                    summaryFetchAttempted && state.isSummaryLoading &&
                    controller.requestId == deadlineNavigationId && !state.browserVisible
                  ) {
                    view.evaluateJavascript(BARS_SUMMARY_DIAGNOSTICS_SCRIPT, null)
                    summaryExtractionStarted = false
                    viewModel.summaryCaptureFailed(
                      "Таблицы Сводки не загрузились. Можно повторить.",
                    )
                    viewModel.logWebEvent("Summary AJAX deadline; restoring marks")
                    restoreOfficialMarks("restore marks after Summary AJAX timeout")
                  }
                }, 20_000L)
              }
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
                val code = errorResponse.statusCode
                viewModel.logWebEvent(
                  "main-frame HTTP error status=$code " +
                    "path=${safeBarsLocation(request.url.toString())}",
                )
                viewModel.probeNetwork("main-http-$code")
                if (code >= 400 && !mainFrameFailed) {
                  readiness.navigationStarted()
                  mainFrameFailed = true
                  val retryScheduled = controller.onMainFrameFailure(
                    url = request.url.toString(),
                    retryable = code in 500..599,
                  )
                  if (!retryScheduled && !state.browserVisible) {
                    if (summaryFetchAttempted &&
                      BarsDocumentPolicy.isCurrent(request.url.toString(), BARS_SUMMARY_URL)
                    ) {
                      viewModel.summaryCaptureFailed(
                        "Сводка БАРС вернула ошибку HTTP $code.",
                      )
                      view.post {
                        if (webView === view) {
                          restoreOfficialMarks("restore marks after Summary HTTP failure")
                        }
                      }
                    } else {
                      needsFullViewport = false
                      viewModel.extractionFailed(
                        "Сервер БАРС вернул ошибку HTTP $code. Повторите позже.",
                      )
                    }
                  }
                }
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

              if (mainFrameFailed) return
              readiness.navigationStarted()
              mainFrameFailed = true
              view.evaluateJavascript(
                BARS_STOP_STATE_OBSERVER_SCRIPT,
                null,
              )

              val failedUrl = request.url.toString()
              val canRetry = controller.onMainFrameFailure(
                url = failedUrl,
                retryable = isRetryableBarsWebError(error.errorCode),
              )
              viewModel.logWebEvent(
                "main-frame error path=" + safeBarsLocation(failedUrl) +
                  " code=" + error.errorCode + " retry=" + canRetry +
                  " progress=" + view.progress,
              )
              viewModel.probeNetwork("webview-error-" + error.errorCode)
              if (canRetry) return

              if (!state.browserVisible) {
                if (summaryFetchAttempted &&
                  BarsDocumentPolicy.isCurrent(failedUrl, BARS_SUMMARY_URL)
                ) {
                  viewModel.summaryCaptureFailed(
                    "Не удалось загрузить Сводку (ошибка сети ${error.errorCode}).",
                  )
                  view.post {
                    if (webView === view) {
                      restoreOfficialMarks("restore marks after Summary network failure")
                    }
                  }
                } else {
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
        // Keep Chromium enabled when moving between background sync and
        // interactive browsing. Toggling View.isEnabled can leave scroll
        // gestures unresponsive. Hidden WebViews stay behind native content
        // or offscreen at Home, so they cannot intercept native input.
      },
        onRelease = { view ->
          viewModel.logWebEvent(
            "webview released stage=${state.authStage} browser=${state.browserVisible} " +
              "cacheFresh=${state.hasFreshCache()} loading=${state.isLoading} " +
              "path=${safeBarsLocation(view.url.orEmpty())} progress=${view.progress}",
          )
          if (webView === view) retryNavigation = null
          if (webView === view) {
            extractionGate.invalidate()
            marksReadiness?.dispose()
            marksReadiness = null
          }
          if (navigationController?.belongsTo(view) == true) {
            navigationController?.close()
            navigationController = null
          }
          // A WebView with an editable element can retain native input
          // focus while AndroidView is detached, reopening the IME.
          // Drop focus and make it ineligible for focus restoration.
          view.clearFocus()
          view.isFocusableInTouchMode = false
          view.isFocusable = false
          view.onFocusChangeListener = null
          view.setOnTouchListener(null)
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
        ServiceStatus(
          title = "Не удалось подключиться к БАРС",
          detail = error,
          tone = ServiceStatusTone.ERROR,
          actionLabel = "Повторить",
          onAction = onRetry,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BarsNativeDashboard(
  state: BarsUiState,
  active: Boolean,
  onDetailOpenChange: (Boolean) -> Unit,
  openDisciplineName: String?,
  openDisciplineRequest: Int,
  onOpenDisciplineConsumed: () -> Unit,
  showCachedRefreshErrors: Boolean,
  onRefresh: () -> Unit,
  onOpenBrowser: () -> Unit,
  onAcknowledgeGrades: (String) -> Unit,
  onRefreshSummary: () -> Unit,
) {
  var selectedDisciplineName by rememberSaveable { mutableStateOf<String?>(null) }
  var selectedSection by rememberSaveable { mutableStateOf("subjects") }
  val report = remember(state.rawSummary) { BarsReportParser.parseSummary(state.rawSummary) }
  val controls = remember(state.disciplines) { state.controlSchedule }
  val gradeReportScroll = rememberScrollState()
  val attendanceReportScroll = rememberScrollState()
  val dashboardListState = rememberLazyListState()
  val selectedDiscipline = state.disciplines.firstOrNull {
    it.disciplineName == selectedDisciplineName
  }
  val motion = MaterialTheme.motionScheme

  androidx.compose.runtime.LaunchedEffect(openDisciplineRequest, openDisciplineName,
      state.disciplines, active) {
    if (active && openDisciplineName != null &&
        state.disciplines.any { it.disciplineName == openDisciplineName }) {
      selectedDisciplineName = openDisciplineName
      onOpenDisciplineConsumed()
    }
  }

  androidx.compose.runtime.SideEffect {
    onDetailOpenChange(selectedDiscipline != null)
  }

  // Android Back returns to the overview before leaving the tab.
  // The official WebView keeps priority when the browser is visible.
  BackHandler(
    enabled = active && selectedDiscipline != null && !state.browserVisible,
  ) {
    selectedDisciplineName?.let(onAcknowledgeGrades)
    selectedDisciplineName = null
  }

  AnimatedContent(
    targetState = selectedDiscipline?.disciplineName,
    modifier = Modifier.fillMaxSize(),
    contentKey = { it ?: "bars-overview" },
    transitionSpec = {
      val opening = targetState != null
      (slideInHorizontally(
        initialOffsetX = { if (opening) it / 7 else -it / 7 },
        animationSpec = motion.defaultSpatialSpec(),
      ) + fadeIn(animationSpec = motion.defaultEffectsSpec())) togetherWith
        (slideOutHorizontally(
          targetOffsetX = { if (opening) -it / 7 else it / 7 },
          animationSpec = motion.defaultSpatialSpec(),
        ) + fadeOut(animationSpec = motion.defaultEffectsSpec()))
    },
    label = "bars-discipline",
  ) { expandedName ->
    val expandedDiscipline = state.disciplines.firstOrNull {
      it.disciplineName == expandedName
    }
    if (expandedDiscipline != null) {
      DisciplineExpandedCard(
        discipline = expandedDiscipline,
        changes = state.gradeChanges[expandedDiscipline.disciplineName].orEmpty(),
        onCollapse = {
          onAcknowledgeGrades(expandedDiscipline.disciplineName)
          selectedDisciplineName = null
        },
      )
    } else {
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
          contentPadding = androidx.compose.foundation.layout.PaddingValues(
            bottom = 112.dp,
          ),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (state.loginRequired) {
            item(key = "session-expired") {
              ServiceStatus(
                title = "Сессия БАРС истекла",
                detail = "Сохранённые оценки доступны.",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                tone = ServiceStatusTone.RECOVERY,
                actionLabel = "Войти",
                onAction = onOpenBrowser,
              )
            }
          }
          item(key = "profile") {
            BarsUserHeader(state = state, onOpenBrowser = onOpenBrowser)
          }

          state.error?.takeIf {
            state.disciplines.isEmpty() || showCachedRefreshErrors
          }?.let { message ->
            item(key = "error") {
              val hasCachedGrades = state.disciplines.isNotEmpty()
              ServiceStatus(
                title = if (hasCachedGrades) {
                  "Не удалось обновить БАРС"
                } else "Не удалось загрузить БАРС",
                detail = if (hasCachedGrades) "Показаны сохранённые оценки." else message,
                modifier = Modifier.padding(horizontal = 8.dp),
                tone = if (hasCachedGrades) {
                  ServiceStatusTone.RECOVERY
                } else ServiceStatusTone.ERROR,
                actionLabel = "Повторить",
                onAction = onRefresh,
              )
            }
          }

          if (state.isLoading && state.disciplines.isNotEmpty() &&
            showCachedRefreshErrors
          ) {
            item(key = "refreshing") {
              LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
                  .padding(horizontal = 10.dp),
              )
            }
          }

          item(key = "sections") {
            BarsReportChooser(
              selected = selectedSection,
              onSelected = { selectedSection = it },
            )
          }

          if (selectedSection != "subjects") {
            barsReportEntries(
              section = selectedSection,
              report = report,
              controls = controls,
              gradeScroll = gradeReportScroll,
              attendanceScroll = attendanceReportScroll,
              onOpenDiscipline = { selectedDisciplineName = it },
              isSummaryLoading = state.isSummaryLoading,
              summaryError = state.summaryError,
              onRetrySummary = onRefreshSummary,
            )
          } else if (state.disciplines.isEmpty() && !state.isLoading) {
            item(key = "empty") {
              EmptyBarsCard("В текущем семестре дисциплины не найдены.")
            }
          } else {
            items(
              items = state.disciplines,
              key = { it.disciplineName },
            ) { discipline ->
              DisciplineCard(
                discipline = discipline,
                changes = state.gradeChanges[discipline.disciplineName].orEmpty(),
                onClick = {
                  selectedDisciplineName = discipline.disciplineName
                },
                modifier = Modifier.padding(horizontal = 8.dp)
                  .animateItem(),
              )
            }
          }
        }
      }
    }
  }
}

/**
* Expressive BARS dashboard: dominant profile surface + factual semester
* summary + approachable, interactive discipline cards.
*/
/** A quiet data-first profile summary, rather than unparsed BARS text chips. */
@Composable
private fun BarsUserHeader(
  state: BarsUiState,
  onOpenBrowser: () -> Unit,
) {
  var showMore by rememberSaveable { mutableStateOf(false) }
  val extras = remember(state.rawMain) { BarsReportParser.parseMain(state.rawMain) }
  val facts = remember(extras, state.studentDetails) {
    barsProfileFacts(extras, state.studentDetails)
  }
  val filtered = facts.filterNot {
    (it.kind == BarsProfileKind.GROUP && state.profileGroup.isNotBlank()) ||
      (it.kind == BarsProfileKind.SEMESTER && state.semester.isNotBlank())
  }
  val primary = filtered.filter { it.kind.featurePriority() < 100 }
    .sortedBy { it.kind.featurePriority() }.take(4)
  val secondary = filtered.filterNot { it in primary }
  val colors = MaterialTheme.colorScheme

  Column(
    modifier = Modifier.fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          state.profileName.ifBlank { "Студент МЭИ" },
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val identity = listOf(state.profileGroup, state.semester)
          .filter { it.isNotBlank() }.joinToString(" · ")
        if (identity.isNotBlank()) {
          Text(
            identity,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
          )
        }
      }
      PortalHeaderAction(label = "БАРС", onClick = onOpenBrowser)
    }

    if (primary.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        primary.chunked(2).forEach { rowFacts ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            rowFacts.forEach { fact ->
              BarsProfileFactCard(
                fact = fact,
                modifier = Modifier.weight(1f),
              )
            }
            if (rowFacts.size == 1) Spacer(Modifier.weight(1f))
          }
        }
      }
    }

    val graded = state.disciplines.count {
      it.finalMarkValue != null || it.markValues.isNotEmpty()
    }
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        state.disciplines.size.toString() + " дисциплин · " +
          graded + " с оценками",
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
      )
      if (state.lastUpdatedAtMillis > 0) {
        Text(
          DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(Date(state.lastUpdatedAtMillis)),
          style = MaterialTheme.typography.labelSmall,
          color = colors.onSurfaceVariant,
        )
      }
    }

    if (secondary.isNotEmpty()) {
      TextButton(
        onClick = { showMore = !showMore },
        modifier = Modifier.padding(start = 0.dp),
      ) {
        AppIcon(
          AppIcons.Person,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Сведения об обучении")
        Spacer(Modifier.width(4.dp))
        AppIcon(
          if (showMore) AppIcons.ExpandLess else AppIcons.ExpandMore,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
        )
      }
      AnimatedVisibility(visible = showMore) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.extraLarge,
          color = colors.surfaceContainerLow,
        ) {
          Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            secondary.forEachIndexed { index, fact ->
              if (index != 0) HorizontalDivider(
                color = colors.outlineVariant.copy(alpha = 0.45f),
              )
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                AppIcon(
                  barsProfileFactIcon(fact.kind),
                  contentDescription = null,
                  tint = colors.onSurfaceVariant,
                  modifier = Modifier.size(20.dp),
                )
                Column(
                  modifier = Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  Text(
                    fact.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                  )
                  Text(
                    fact.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                  )
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
private fun BarsProfileFactCard(
  fact: BarsProfileFact,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    modifier = modifier.heightIn(min = 105.dp),
    shape = MaterialTheme.shapes.extraLarge,
    color = colors.surfaceContainerLow,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = colors.primaryContainer,
          contentColor = colors.onPrimaryContainer,
        ) {
          Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
          ) {
            AppIcon(
              barsProfileFactIcon(fact.kind),
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
          }
        }
        Text(
          fact.label,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelMedium,
          color = colors.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        fact.value,
        style = MaterialTheme.typography.titleMedium,
        color = colors.onSurface,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private fun barsProfileFactIcon(kind: BarsProfileKind): Int = when (kind) {
  BarsProfileKind.COURSE -> AppIcons.Education
  BarsProfileKind.RATING -> AppIcons.Ranking
  BarsProfileKind.SCORE -> AppIcons.Analytics
  BarsProfileKind.FORM -> AppIcons.StudyForm
  BarsProfileKind.STATUS -> AppIcons.Verified
  BarsProfileKind.FACULTY, BarsProfileKind.INSTITUTE -> AppIcons.Faculty
  BarsProfileKind.PROGRAM -> AppIcons.Education
  BarsProfileKind.GROUP -> AppIcons.GroupList
  BarsProfileKind.SEMESTER -> AppIcons.Schedule
  BarsProfileKind.STUDENT_ID -> AppIcons.StudentId
  BarsProfileKind.OTHER -> AppIcons.Person
}

/**
* Scannable subject-first card: title, real final mark, quiet metadata,
* then raw grades. The final mark must not be confused with an average.
*/
@Composable
private fun DisciplineCard(
  discipline: BarsDiscipline,
  changes: List<BarsGradeChange>,
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
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          discipline.disciplineName,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val finalMark = discipline.finalMarkValue
        if (finalMark != null) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            Text(
              "Итог",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GradeChip(formatBarsGrade(finalMark.toDouble()), finalMark)
          }
        } else {
          AppIcon(
            AppIcons.Forward,
            contentDescription = "Подробнее",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }
      val subtitle = listOf(
        discipline.assessmentType,
        discipline.personName,
      ).filter(String::isNotBlank).joinToString(" · ")
      if (subtitle.isNotBlank()) {
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (changes.isNotEmpty()) {
        Text(gradeChangesCaption(changes),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary)
      }
      if (discipline.markValues.isEmpty()) {
        Text(
          "Оценок пока нет",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          discipline.markValues.take(8).forEach { mark ->
            GradeChip(formatBarsGrade(mark.toDouble()), mark)
          }
          if (discipline.markValues.size > 8) {
            NeutralChip("+" + (discipline.markValues.size - 8))
          }
        }
      }
    }
  }
}

/**
* Expanded discipline remains an in-place BARS card, not a navigation
* destination. Its title stays pinned and collapses the card on tap.
* Content can scroll within the card, without reaching other disciplines.
*/
/**
* Discipline detail stays inside the BARS tab, with a pinned return header.
* Grade data is shown before supplementary control/assessment information.
*/
@Composable
private fun DisciplineExpandedCard(
  discipline: BarsDiscipline,
  changes: List<BarsGradeChange>,
  onCollapse: () -> Unit,
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
    Surface(
      onClick = onCollapse,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 17.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AppIcon(
          AppIcons.Back,
          contentDescription = "К списку дисциплин",
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          discipline.disciplineName,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Column(
      modifier = Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 6.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          val subtitle = listOf(
            discipline.assessmentType,
            discipline.personName,
          ).filter(String::isNotBlank).joinToString(" · ")
          if (subtitle.isNotBlank()) {
            Text(
              subtitle,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
            "Оценки",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          if (changes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
              changes.forEach { change ->
                Text(change.activityName + ": " +
                  (change.before?.let { "$it → " } ?: "") + change.after,
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary)
              }
            }
          }
          if (controls.isEmpty()) {
            Text(
              "Контрольных мероприятий нет",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              controls.forEach { control ->
                val mark = control.markValue
                if (mark != null) {
                  GradeChip(formatBarsGrade(mark.toDouble()), mark)
                } else {
                  NeutralChip("—")
                }
              }
            }
          }
          discipline.finalMarkValue?.let { final ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                "Итог",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              GradeChip(formatBarsGrade(final.toDouble()), final)
            }
          }
          discipline.currentScoreValue?.let { score ->
            Text(
              "Текущий балл БАРС: " +
                formatBarsGrade(score.toDouble()),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      // "По совокупности" assessments do not need an automatic-grade
      // forecast. Hide the entire section, not merely its combinations.
      if (shouldShowGradeForecastForAssessment(discipline.assessmentType)) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.extraLarge,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            GradeForecastSection(discipline)
          }
        }
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
              if (index < controls.lastIndex) {
                HorizontalDivider(
                  color = MaterialTheme.colorScheme.outlineVariant,
                )
              }
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
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              "Дополнительные оценки",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            otherActivities.forEachIndexed { index, activity ->
              ActivityDetailRow(activity)
              if (index < otherActivities.lastIndex) {
                HorizontalDivider(
                  color = MaterialTheme.colorScheme.outlineVariant,
                )
              }
            }
          }
        }
      }

      // Keep the bottom content above the shared floating portal action.
      Spacer(Modifier.height(96.dp))
    }
  }
}

/**
* Present and hypothetical marks occupy the same fixed-width columns.
* One horizontal scroll state keeps every combination aligned with the
* source slots, including in narrow display-size configurations.
*/
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
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Text(
      "Автомат",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
    if (controls.isNotEmpty()) {
      Column(
        modifier = Modifier.fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          controls.forEach { activity ->
            ForecastGradeSlot(activity.markValue)
          }
        }
        if (forecast is GradeForecast.Available) {
          forecast.combinations
            .takeIf { forecast.remaining.isNotEmpty() }
            ?.forEach { combination ->
              var upcomingIndex = 0
              Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                controls.forEach { activity ->
                  val existing = activity.markValue
                  val mark = when {
                    existing != null -> existing
                    parseBarsWeight(activity.weight)
                      ?.let { it > 0.0 } == true &&
                      upcomingIndex < combination.marks.size ->
                      combination.marks[upcomingIndex++].toFloat()
                    else -> null
                  }
                  ForecastGradeSlot(mark)
                }
              }
            }
        }
      }
    }
    when (forecast) {
      is GradeForecast.Unavailable -> Text(
        "Недостаточно данных",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      is GradeForecast.Available -> if (forecast.combinations.isEmpty()) {
        Text(
          "Недостижимо",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun ForecastGradeSlot(mark: Float?) {
  Box(
    modifier = Modifier.width(52.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (mark == null) {
      NeutralChip("—")
    } else {
      GradeChip(formatBarsGrade(mark.toDouble()), mark)
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
      fontFamily = FontFamily.Monospace,
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
      fontFamily = FontFamily.Monospace,
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
  BarsDocumentPolicy.isTrusted(url)

private fun isRetryableBarsWebError(errorCode: Int): Boolean =
  errorCode == WebViewClient.ERROR_TIMEOUT ||
    errorCode == WebViewClient.ERROR_CONNECT ||
    errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
    errorCode == WebViewClient.ERROR_UNKNOWN

