package com.rteats.mpeineo.ui

import android.content.Context
import android.os.Build
import android.view.WindowInsets as AndroidWindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.rteats.mpeineo.AppIcon
import com.rteats.mpeineo.AppIcons
import com.rteats.mpeineo.MailNotificationTarget
import com.rteats.mpeineo.data.MailSummary
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.MpeiNeoApplication
import com.rteats.mpeineo.data.InterfacePreferences
import com.rteats.mpeineo.data.InterfaceTheme
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class AppTab { SCHEDULE, BARS, MAIL, MAPS, SETTINGS }

/** Preserve the existing pager-policy unit tests while using stack navigation. */
internal fun lockedBrowserTab(
    intended: AppTab,
    mailWebVisible: Boolean,
    barsWebVisible: Boolean,
): AppTab? = when (intended) {
    AppTab.MAIL -> AppTab.MAIL.takeIf { mailWebVisible }
    AppTab.BARS -> AppTab.BARS.takeIf { barsWebVisible }
    else -> null
}

internal fun scheduleDockRevealProgress(
    currentPage: Int,
    pageOffsetFraction: Float,
): Float = (1f - kotlin.math.abs(currentPage + pageOffsetFraction)).coerceIn(0f, 1f)

/** Complete an intentional rightward swipe, otherwise spring the page back. */
internal fun shouldCommitServiceBackSwipe(distancePx: Float, widthPx: Float): Boolean =
    widthPx > 0f && distancePx >= widthPx * 0.20f

/** Map-only back target is narrow; regular service pages retain full-width swipes. */
internal fun acceptsServiceBackStart(
    x: Float,
    width: Float,
    systemEdge: Float,
    mapEdgeZone: Boolean,
): Boolean = x >= systemEdge && (mapEdgeZone || x <= width - systemEdge)

/**
 * A single composited foreground page over a permanently mounted background.
 *
 * Both drag progress and automatic push/pop operate on the SAME reveal value.
 * The outgoing route is removed only after its foreground is entirely offscreen:
 * no AnimatedContent outgoing slot or delayed translation reset can flash it back.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MpeiNeoApp(
    viewModel: MainViewModel,
    initialMailTab: Boolean = false,
    mailNotificationTarget: MailNotificationTarget? = null,
    interfaceTheme: InterfaceTheme = InterfaceTheme.SYSTEM,
    onInterfaceThemeChange: (InterfaceTheme) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as MpeiNeoApplication
    val diagnostics = application.container.diagnostics
    val hostView = LocalView.current
    val hapticFeedback = LocalHapticFeedback.current
    val homePrefs = remember(application) { HomeLayoutPreferences(application) }
    var homeTiles by remember(homePrefs) { mutableStateOf(homePrefs.read()) }
    LaunchedEffect(homePrefs) {
        diagnostics.log("HOME_LAYOUT", "restored=" + HomeTileLayout.encode(homeTiles))
    }
    val interfacePrefs = remember(application) { InterfacePreferences(application) }
    var navigationHaptics by remember(interfacePrefs) {
        mutableStateOf(interfacePrefs.navigationHaptics)
    }
    fun changeHomeLayout(next: List<HomeTile>) {
        val normalized = HomeTileLayout.normalize(next)
        val before = homeTiles
        if (before == normalized) return
        homePrefs.write(normalized)
        homeTiles = normalized
        val oldIndex = before.map { it.tab }
        val newIndex = normalized.map { it.tab }
        if (oldIndex != newIndex) {
            diagnostics.log("HOME_LAYOUT", "reorder=" + newIndex.joinToString(",") { it.name })
        } else {
            normalized.firstOrNull { tile ->
                before.firstOrNull { it.tab == tile.tab }?.size != tile.size
            }?.let { tile ->
                diagnostics.log("HOME_LAYOUT", "resize=" + tile.tab.name + " size=" + tile.size.name)
            }
        }
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputMethodManager = remember(hostView) {
        hostView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    fun logNavigation(event: String) = diagnostics.log("NAV", event)
    fun dismissIme(reason: String) {
        val focused = hostView.rootView.findFocus()
        logNavigation("IME cleanup reason=" + reason +
            " focused=" + (focused?.javaClass?.simpleName ?: "none") +
            " web=" + (focused is WebView))
        focused?.clearFocus()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        inputMethodManager.hideSoftInputFromWindow(hostView.windowToken, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hostView.windowInsetsController?.hide(AndroidWindowInsets.Type.ime())
        }
    }

    val barsViewModel: BarsViewModel = composeViewModel(
        factory = BarsViewModel.factory(
            application.container.diagnostics,
            application.container.barsNetworkDiagnostics,
            application.container.barsSnapshots,
        ),
    )
    val mailViewModel: MailViewModel = composeViewModel(
        factory = MailViewModel.factory(
            application.container.mailRepository,
            application.container.owaAuthProbe,
            application.container.owaMailRepository,
        ),
    )
    val updateViewModel: UpdateViewModel = composeViewModel(
        factory = UpdateViewModel.factory(application.container),
    )
    val barsState by barsViewModel.state.collectAsState()
    val mailState by mailViewModel.state.collectAsState()
    val scheduleState by viewModel.state.collectAsState()
    val barsPrefs = remember(application) {
        application.getSharedPreferences("bars_settings", Context.MODE_PRIVATE)
    }
    var barsAutoRefreshEnabled by remember(barsPrefs) {
        mutableStateOf(barsPrefs.getBoolean("auto_refresh", true))
    }

    // Only strings are saved in the route stack (Android Bundle compatible).
    // The second entry exists only for Schedule -> classroom Map navigation.
    var routeNames by rememberSaveable {
        mutableStateOf(
            if (initialMailTab) arrayListOf(AppTab.MAIL.name) else arrayListOf<String>(),
        )
    }
    val currentService = routeNames.lastOrNull()?.let(AppTab::valueOf)
    val beneathService = routeNames.dropLast(1).lastOrNull()?.let(AppTab::valueOf)
    val savedServiceState = rememberSaveableStateHolder()
    var reveal by rememberSaveable {
        mutableFloatStateOf(if (initialMailTab) 1f else 0f)
    }
    var isTransitioning by remember { mutableStateOf(false) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var widthPx by remember { mutableFloatStateOf(1f) }
    var todayJumpRequest by rememberSaveable { mutableIntStateOf(0) }
    var searchExpanded by remember { mutableStateOf(false) }
    var globalSearchOpen by rememberSaveable { mutableStateOf(false) }
    var globalQuery by rememberSaveable { mutableStateOf("") }
    val searchHistory = remember(application) { GlobalSearchHistory(application) }
    var recentQueries by remember { mutableStateOf(searchHistory.load()) }
    var barsSearchRequest by remember { mutableIntStateOf(0) }
    var barsRequestedDiscipline by remember { mutableStateOf<String?>(null) }
    var mailWebVisible by rememberSaveable { mutableStateOf(false) }
    var barsDetailOpen by remember { mutableStateOf(false) }
    var viewedScheduleWeek by remember { mutableIntStateOf(0) }
    var mapFocus by remember { mutableStateOf<CampusMapFocus?>(null) }
    var nextMapFocusId by remember { mutableIntStateOf(0) }
    var agendaScrolling by remember { mutableStateOf(false) }
    var weekInfoVisible by remember { mutableStateOf(false) }
    var barsBrowserJustClosed by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(agendaScrolling) {
        if (agendaScrolling) weekInfoVisible = true
        else {
            delay(1800)
            weekInfoVisible = false
        }
    }
    LaunchedEffect(scheduleState.selected?.id, scheduleState.selected?.type) {
        viewedScheduleWeek = 0
        agendaScrolling = false
        weekInfoVisible = false
    }
    LaunchedEffect(barsBrowserJustClosed, barsState.browserVisible, imeVisible) {
        if (barsBrowserJustClosed && !barsState.browserVisible && imeVisible) {
            dismissIme("bars-post-exit")
        }
    }

    fun animateReveal(target: Float, onComplete: () -> Unit = {}) {
        transitionJob?.cancel()
        isTransitioning = true
        transitionJob = scope.launch {
            animate(
                initialValue = reveal.coerceIn(0f, 1f),
                targetValue = target,
                animationSpec = tween(
                    durationMillis = if (target == 0f) 260 else 310,
                    easing = FastOutSlowInEasing,
                ),
            ) { value, _ ->
                reveal = value
            }
            reveal = target
            onComplete()
            isTransitioning = false
        }
    }

    fun openService(target: AppTab, viaClassroom: Boolean = false) {
        transitionJob?.cancel()
        if (navigationHaptics && !viaClassroom) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        if (currentService == AppTab.MAIL && mailWebVisible) {
            dismissIme("mail-leave-service")
            mailWebVisible = false
        }
        if (currentService == AppTab.BARS && barsState.browserVisible) {
            dismissIme("bars-leave-service")
        }
        searchExpanded = false
        barsBrowserJustClosed = false
        barsDetailOpen = false
        routeNames = if (viaClassroom && currentService == AppTab.SCHEDULE) {
            arrayListOf(AppTab.SCHEDULE.name, target.name)
        } else {
            arrayListOf(target.name)
        }
        reveal = 0f
        logNavigation("superapp push " + target.name +
            " origin=" + (currentService?.name ?: "HOME") +
            " classroom=" + viaClassroom)
        animateReveal(1f)
    }

    fun goBackOneService() {
        if (isTransitioning || routeNames.isEmpty()) return
        if (navigationHaptics) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        logNavigation("superapp pop " + (currentService?.name ?: "HOME") +
            " depth=" + routeNames.size)
        if (mailWebVisible || barsState.browserVisible) dismissIme("superapp-pop")
        mailWebVisible = false
        searchExpanded = false
        barsBrowserJustClosed = false
        animateReveal(0f) {
            routeNames = ArrayList(routeNames.dropLast(1))
            // The underlying service is already fully rendered, at rest.
            reveal = if (routeNames.isEmpty()) 0f else 1f
            barsDetailOpen = false
        }
    }

    fun closeMailBrowser() {
        dismissIme("mail-browser-back")
        mailWebVisible = false
        mailViewModel.refresh()
    }

    fun updateGlobalQuery(value: String) {
        globalQuery = value
        viewModel.updateSearchQuery(value)
    }

    fun rememberGlobalQuery() {
        if (globalQuery.isNotBlank()) recentQueries = searchHistory.record(globalQuery)
    }

    fun closeGlobalSearch() {
        globalSearchOpen = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        globalQuery = ""
        viewModel.updateSearchQuery("")
    }

    fun openGlobalSearch() {
        globalSearchOpen = true
        globalQuery = ""
        viewModel.updateSearchQuery("")
    }

    fun openScheduleResult(target: ScheduleTarget) {
        rememberGlobalQuery()
        closeGlobalSearch()
        viewModel.selectTarget(target)
        openService(AppTab.SCHEDULE)
    }

    fun openMailResult(summary: MailSummary) {
        rememberGlobalQuery()
        closeGlobalSearch()
        openService(AppTab.MAIL)
        mailViewModel.openInboxMessage(summary)
    }

    fun openBarsResult(discipline: String) {
        rememberGlobalQuery()
        closeGlobalSearch()
        barsSearchRequest++
        barsRequestedDiscipline = discipline
        openService(AppTab.BARS)
    }

    LaunchedEffect(mailNotificationTarget?.requestId) {
        val target = mailNotificationTarget ?: return@LaunchedEffect
        openService(AppTab.MAIL)
        if (target.uid > 0 && target.validity > 0) {
            mailViewModel.openFromNotification(target.uid, target.validity)
        } else mailViewModel.closeMessage()
    }

    // Nested BackHandlers in MailScreen and BARS take precedence over the root.
    BackHandler(enabled = currentService != null && !mailWebVisible) {
        goBackOneService()
    }
    BackHandler(enabled = currentService == AppTab.MAIL && mailWebVisible) {
        closeMailBrowser()
    }

    // Share the existing screen instances and saveable state between push,
    // background, and return. No second copy of the current page is ever drawn.
    val serviceContent: @Composable (AppTab, Boolean) -> Unit = { destination, active ->
        savedServiceState.SaveableStateProvider(destination.name) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(Modifier.fillMaxSize()) {
                    when (destination) {
                        AppTab.SCHEDULE -> {
                            ScheduleScreen(
                                state = scheduleState,
                                onEnsureAgendaWeek = viewModel::ensureAgendaWeek,
                                onRefreshAgendaWeek = viewModel::refreshAgendaWeek,
                                onRetryAgendaWeek = viewModel::retryAgendaWeek,
                                onViewedAgendaWeek = { viewedScheduleWeek = it },
                                onAgendaScrollingChanged = { agendaScrolling = it },
                                onLocateRoom = { room ->
                                    campusBuildingForRoom(room)?.let { building ->
                                        nextMapFocusId++
                                        mapFocus = CampusMapFocus(
                                            nextMapFocusId, building, room.take(100),
                                        )
                                        openService(AppTab.MAPS, viaClassroom = true)
                                    }
                                },
                                todayJumpRequest = todayJumpRequest,
                                modifier = Modifier.fillMaxSize(),
                            )
                            ScheduleDockedSearch(
                                state = scheduleState,
                                onQueryChanged = viewModel::updateSearchQuery,
                                onTypeChanged = viewModel::setSearchType,
                                onSelect = viewModel::selectTarget,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onRenameFavorite = viewModel::renameFavorite,
                                visibleWeekOffset = viewedScheduleWeek,
                                onRefreshWeek = viewModel::refreshAgendaWeek,
                                showWeekInfo = weekInfoVisible,
                                expanded = searchExpanded,
                                onExpandedChange = { searchExpanded = it },
                                active = active,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // BARS lives in a permanent sibling composition below.
                        // Its WebView must not be destroyed by Home/service routing.
                        AppTab.BARS -> Unit
                        AppTab.MAIL -> {
                            MailScreen(
                                viewModel = mailViewModel,
                                active = active && !mailWebVisible,
                                onOpenPortal = { mailWebVisible = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (mailWebVisible && active) {
                                WebPortalScreen(
                                    url = "https://mail.mpei.ru/owa",
                                    testTag = "portal-mail",
                                    modifier = Modifier.fillMaxSize()
                                        .testTag("mail-webview"),
                                    onDebugEvent = { message ->
                                        diagnostics.log("MAIL_WEB", message)
                                    },
                                )
                            }
                        }
                        AppTab.MAPS -> CampusMapScreen(
                            active = active,
                            focus = mapFocus,
                            onFocusConsumed = { mapFocus = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            updateViewModel = updateViewModel,
                            mailViewModel = mailViewModel,
                            barsAutoRefreshEnabled = barsAutoRefreshEnabled,
                            onBarsAutoRefreshEnabledChange = { enabled ->
                                barsPrefs.edit().putBoolean("auto_refresh", enabled).apply()
                                barsAutoRefreshEnabled = enabled
                            },
                            active = active,
                            interfaceTheme = interfaceTheme,
                            onInterfaceThemeChange = onInterfaceThemeChange,
                            navigationHaptics = navigationHaptics,
                            onNavigationHapticsChange = { enabled ->
                                interfacePrefs.setNavigationHaptics(enabled)
                                navigationHaptics = enabled
                                diagnostics.log("UI_PREF", "navigation_haptics=" + enabled)
                            },
                            onResetHomeLayout = {
                                homePrefs.reset()
                                homeTiles = HomeTileLayout.defaults
                                diagnostics.log("HOME_LAYOUT", "reset=defaults")
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    AnimatedVisibility(
                        visible = destination == AppTab.MAIL && active &&
                            !imeVisible && !mailWebVisible && mailState.configured &&
                            !mailState.composing && mailState.selected == null,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp),
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(110)),
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = mailViewModel::startCompose,
                            modifier = Modifier.testTag("mail-compose-fab"),
                            icon = {
                                AppIcon(
                                    AppIcons.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            text = { Text("Написать") },
                        )
                    }
                }
            }
        }
    }


    // Shared swipe-back handler. BARS uses the same gesture even when its
    // single WebView was initialized from the Home screen.
    fun Modifier.serviceBackGesture(
        eligible: Boolean,
        interactive: Boolean,
        mapEdgeZone: Boolean = false,
    ): Modifier =
        pointerInput(currentService, eligible, interactive, mapEdgeZone) {
            if (!eligible) return@pointerInput
            var acceptedGesture = false
            var gestureDistance = 0f
            val systemBackEdge = 24.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { start ->
                    acceptedGesture = acceptsServiceBackStart(
                        start.x, size.width.toFloat(), systemBackEdge, mapEdgeZone,
                    )
                    gestureDistance = 0f
                },
                onHorizontalDrag = { change, amount ->
                    if (acceptedGesture) {
                        gestureDistance = (gestureDistance + amount).coerceAtLeast(0f)
                        if (interactive) {
                            reveal = (1f - gestureDistance /
                                widthPx).coerceIn(0f, 1f)
                        }
                        if (amount > 0f) change.consume()
                    }
                },
                onDragCancel = {
                    if (acceptedGesture && interactive) animateReveal(1f)
                    acceptedGesture = false
                },
                onDragEnd = {
                    if (acceptedGesture && shouldCommitServiceBackSwipe(
                            gestureDistance, widthPx,
                        )
                    ) {
                        if (interactive) goBackOneService()
                        else backDispatcher?.onBackPressed()
                    } else if (interactive) animateReveal(1f)
                    acceptedGesture = false
                },
            )
        }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding).clipToBounds()
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) },
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { translationX = -widthPx * reveal / 7f },
            ) {
                if (beneathService == null) {
                    SuperAppHome(
                        schedule = scheduleState,
                        bars = barsState,
                        mail = mailState,
                        tiles = homeTiles,
                        onChangeLayout = ::changeHomeLayout,
                        onEditModeChange = { editing ->
                            diagnostics.log("HOME_LAYOUT", "edit_mode=" + editing)
                        },
                        onOpen = { target ->
                            if (currentService == null && !isTransitioning) {
                                openService(target)
                            }
                        },
                        onSearch = {
                            if (currentService == null && !isTransitioning) openGlobalSearch()
                        },
                        onOpenBarsDiscipline = { discipline ->
                            if (currentService == null && !isTransitioning) {
                                barsSearchRequest++
                                barsRequestedDiscipline = discipline
                                openService(AppTab.BARS)
                            }
                        },
                        onOpenMailMessage = { summary ->
                            if (currentService == null && !isTransitioning) {
                                openService(AppTab.MAIL)
                                mailViewModel.openInboxMessage(summary)
                            }
                        },
                    )
                } else serviceContent(beneathService, false)
            }

            if (currentService != null && currentService != AppTab.BARS) {
                val isNativeContent = currentService != AppTab.BARS ||
                    (barsState.authStage == BarsAuthStage.AUTHENTICATED &&
                        !barsState.browserVisible)
                val canHandleGesture = !isTransitioning && !searchExpanded &&
                    !imeVisible && !mailWebVisible && isNativeContent
                val canInteractivelyPop = canHandleGesture &&
                    !barsDetailOpen &&
                    !(currentService == AppTab.MAIL && mailState.selected != null)
                Box(
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer { translationX = widthPx * (1f - reveal) }
                        .serviceBackGesture(
                            canHandleGesture && currentService != AppTab.MAPS,
                            canInteractivelyPop,
                        ),
                ) {
                    serviceContent(currentService, true)
                    if (currentService == AppTab.MAPS && canHandleGesture) {
                        // Reserve a slim left strip for the interactive pop.
                        // Everywhere else is exclusively for map panning/pinching.
                        // Keep the top building-list and bottom attribution clear.
                        Box(
                            modifier = Modifier.align(Alignment.CenterStart)
                                .width(96.dp).fillMaxHeight()
                                .padding(top = 88.dp, bottom = 200.dp)
                                .testTag("campus-map-swipe-back-zone")
                                .serviceBackGesture(
                                    eligible = true,
                                    interactive = canInteractivelyPop,
                                    mapEdgeZone = true,
                                ),
                        )
                    }
                }
            }
            // The BARS composition is mounted exactly once, including on Home.
            // Place it offscreen while inactive rather than recreating WebViews.
            // Compose layout still grants the full viewport to BARS DOM extraction.
            val barsForeground = currentService == AppTab.BARS
            val barsCanSwipe = barsForeground && !isTransitioning && !imeVisible &&
                !barsState.browserVisible && !barsDetailOpen &&
                barsState.authStage == BarsAuthStage.AUTHENTICATED
            Box(
                modifier = Modifier.fillMaxSize()
                    .offset {
                        IntOffset(
                            x = (widthPx *
                                (if (barsForeground) 1f - reveal else 1f)).roundToInt(),
                            y = 0,
                        )
                    }
                    .serviceBackGesture(barsCanSwipe, barsCanSwipe),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BarsScreen(
                        viewModel = barsViewModel,
                        autoRefreshEnabled = barsAutoRefreshEnabled,
                        active = barsForeground,
                        allowBackgroundSync = barsAutoRefreshEnabled,
                        onDetailOpenChange = { if (barsForeground) barsDetailOpen = it },
                        openDisciplineName = barsRequestedDiscipline,
                        openDisciplineRequest = barsSearchRequest,
                        onOpenDisciplineConsumed = { barsRequestedDiscipline = null },
                        onBrowserExit = {
                            barsBrowserJustClosed = true
                            dismissIme("bars-browser-back")
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            AnimatedVisibility(
                visible = globalSearchOpen,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxSize(),
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(160)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(110)),
            ) {
                GlobalSearchScreen(
                    query = globalQuery,
                    onQueryChange = ::updateGlobalQuery,
                    history = recentQueries,
                    onHistorySelected = ::updateGlobalQuery,
                    onRemoveHistory = { recentQueries = searchHistory.remove(it) },
                    onClearHistory = { recentQueries = searchHistory.clear() },
                    schedule = scheduleState,
                    mail = mailState,
                    bars = barsState,
                    onSchedule = ::openScheduleResult,
                    onMail = ::openMailResult,
                    onDiscipline = ::openBarsResult,
                    onClose = ::closeGlobalSearch,
                    onSubmit = ::rememberGlobalQuery,
                )
            }
        }
    }
}
