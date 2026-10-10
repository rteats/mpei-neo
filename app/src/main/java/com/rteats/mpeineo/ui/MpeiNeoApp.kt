package com.rteats.mpeineo.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.rteats.mpeineo.MpeiNeoApplication
import com.rteats.mpeineo.R
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch

private enum class AppTab { SCHEDULE, BARS, MAIL, SETTINGS }

/**
 * All four destinations occupy identical pager constraints. Unlike the former
 * AnimatedContent route, Schedule's floating search doesn't shrink its page
 * and cause an animation-size jump.
 *
 * Native pages support full-width horizontal paging. Android WebViews own
 * their horizontal gestures; instead, narrow edge-swipe regions allow page
 * switching without stealing Outlook/BARS website scrolling and text input.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MpeiNeoApp(viewModel: MainViewModel) {
    val application = LocalContext.current.applicationContext as MpeiNeoApplication
    val barsViewModel: BarsViewModel = composeViewModel(
        factory = BarsViewModel.factory(
            application.container.diagnostics,
            application.container.barsNetworkDiagnostics,
        ),
    )
    val updateViewModel: UpdateViewModel = composeViewModel(
        factory = UpdateViewModel.factory(application.container),
    )
    val barsState by barsViewModel.state.collectAsState()
    val scheduleState by viewModel.state.collectAsState()
    val pager = rememberPagerState(initialPage = 0, pageCount = { AppTab.entries.size })
    val scope = rememberCoroutineScope()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var currentTab by rememberSaveable { mutableStateOf(AppTab.SCHEDULE) }
    var todayJumpRequest by rememberSaveable { mutableIntStateOf(0) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                currentTab = AppTab.entries[page]
                if (currentTab != AppTab.SCHEDULE) searchExpanded = false
            }
    }

    fun navigate(target: AppTab) {
        if (target == AppTab.SCHEDULE) {
            todayJumpRequest++
        }
        scope.launch {
            pager.animateScrollToPage(target.ordinal)
        }
    }

    val barsIsWeb = barsState.authStage != BarsAuthStage.AUTHENTICATED ||
        barsState.browserVisible
    val nativeSwipeAllowed = !searchExpanded && when (pager.currentPage) {
        AppTab.MAIL.ordinal -> false
        AppTab.BARS.ordinal -> !barsIsWeb
        else -> true
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!keyboardVisible) ShortNavigationBar(
                arrangement = ShortNavigationBarArrangement.EqualWeight,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                ShortNavigationBarItem(
                    selected = currentTab == AppTab.SCHEDULE,
                    onClick = { navigate(AppTab.SCHEDULE) },
                    iconPosition = NavigationItemIconPosition.Start,
                    icon = { Icon(Icons.Default.DateRange, contentDescription = "Расписание") },
                    label = {},
                )
                ShortNavigationBarItem(
                    selected = currentTab == AppTab.BARS,
                    onClick = {
                        if (currentTab == AppTab.BARS && barsState.browserVisible) {
                            barsViewModel.hideBrowser()
                        } else {
                            navigate(AppTab.BARS)
                        }
                    },
                    iconPosition = NavigationItemIconPosition.Start,
                    icon = {
                        if (currentTab == AppTab.BARS && barsState.browserVisible) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Вернуться к оценкам",
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_bars),
                                contentDescription = "БАРС",
                            )
                        }
                    },
                    label = {},
                )
                ShortNavigationBarItem(
                    selected = currentTab == AppTab.MAIL,
                    onClick = { navigate(AppTab.MAIL) },
                    iconPosition = NavigationItemIconPosition.Start,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_mail),
                            contentDescription = "Почта",
                        )
                    },
                    label = {},
                )
                ShortNavigationBarItem(
                    selected = currentTab == AppTab.SETTINGS,
                    onClick = { navigate(AppTab.SETTINGS) },
                    iconPosition = NavigationItemIconPosition.Start,
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                    label = {},
                )
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            userScrollEnabled = nativeSwipeAllowed,
            key = { AppTab.entries[it].name },
        ) { page ->
            val destination = AppTab.entries[page]
            Box(modifier = Modifier.fillMaxSize()) {
                when (destination) {
                    AppTab.SCHEDULE -> ScheduleScreen(
                        state = scheduleState,
                        onEnsureAgendaWeek = viewModel::ensureAgendaWeek,
                        onRefreshAgendaWeek = viewModel::refreshAgendaWeek,
                        onRetryAgendaWeek = viewModel::retryAgendaWeek,
                        todayJumpRequest = todayJumpRequest,
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppTab.BARS -> BarsScreen(
                        viewModel = barsViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppTab.MAIL -> WebPortalScreen(
                        url = "https://mail.mpei.ru/owa",
                        testTag = "portal-mail",
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppTab.SETTINGS -> SettingsScreen(
                        updateViewModel = updateViewModel,
                        onClearCache = viewModel::clearCache,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (destination == AppTab.SCHEDULE) {
                    ScheduleDockedSearch(
                        state = scheduleState,
                        onQueryChanged = viewModel::updateSearchQuery,
                        onTypeChanged = viewModel::setSearchType,
                        onSearch = viewModel::search,
                        onSelect = viewModel::selectTarget,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onRenameFavorite = viewModel::renameFavorite,
                        onExpandedChange = { searchExpanded = it },
                        active = pager.currentPage == AppTab.SCHEDULE.ordinal,
                        modifier = Modifier.fillMaxSize().align(Alignment.BottomCenter),
                    )
                } else if (
                    destination == AppTab.MAIL ||
                    (destination == AppTab.BARS && barsIsWeb)
                ) {
                    // A WebView isn't a Compose nested-scroll child. Only
                    // deliberate swipes starting within a 24dp edge zone page
                    // horizontally; in-page web interactions remain native.
                    WebViewPageEdges(
                        onPrevious = {
                            if (page > 0) navigate(AppTab.entries[page - 1])
                        },
                        onNext = {
                            if (page < AppTab.entries.lastIndex) {
                                navigate(AppTab.entries[page + 1])
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.WebViewPageEdges(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .width(24.dp)
            .fillMaxHeight()
            .pointerInput(onPrevious) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, delta -> drag += delta },
                    onDragEnd = {
                        if (drag > 64.dp.toPx()) onPrevious()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                )
            },
    )
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .width(24.dp)
            .fillMaxHeight()
            .pointerInput(onNext) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, delta -> drag += delta },
                    onDragEnd = {
                        if (drag < -64.dp.toPx()) onNext()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                )
            },
    )
}
