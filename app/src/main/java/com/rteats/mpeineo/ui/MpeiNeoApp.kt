package com.rteats.mpeineo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.rteats.mpeineo.MpeiNeoApplication
import com.rteats.mpeineo.R

private enum class AppTab { SCHEDULE, BARS, MAIL, SETTINGS }

/**
 * Material 3 Expressive short navigation bar with four centered icon-only
 * destinations. Schedule search floats directly above the navigation bar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MpeiNeoApp(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.SCHEDULE) }
    var todayJumpRequest by rememberSaveable { mutableIntStateOf(0) }

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
    val state by viewModel.state.collectAsState()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!keyboardVisible) ShortNavigationBar(
                arrangement = ShortNavigationBarArrangement.EqualWeight,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                ShortNavigationBarItem(
                    selected = tab == AppTab.SCHEDULE,
                    onClick = {
                        tab = AppTab.SCHEDULE
                        // Also works when Schedule is already selected.
                        todayJumpRequest++
                    },
                    iconPosition = NavigationItemIconPosition.Start,
                    icon = { Icon(Icons.Default.DateRange, contentDescription = "Расписание") },
                    label = {},
                )
                ShortNavigationBarItem(
                    selected = tab == AppTab.BARS,
                    onClick = {
                        if (tab == AppTab.BARS && barsState.browserVisible) {
                            barsViewModel.hideBrowser()
                        } else {
                            tab = AppTab.BARS
                        }
                    },
                    iconPosition = NavigationItemIconPosition.Start,
                    icon = {
                        if (tab == AppTab.BARS && barsState.browserVisible) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Вернуться к оценкам")
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
                    selected = tab == AppTab.MAIL,
                    onClick = { tab = AppTab.MAIL },
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
                    selected = tab == AppTab.SETTINGS,
                    onClick = { tab = AppTab.SETTINGS },
                    iconPosition = NavigationItemIconPosition.Start,
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                    label = {},
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AppContent(
                viewModel = viewModel,
                barsViewModel = barsViewModel,
                updateViewModel = updateViewModel,
                tab = tab,
                todayJumpRequest = todayJumpRequest,
                modifier = Modifier
                    .fillMaxSize()
                    // Reserve only the small input row; the suggestions panel
                    // is an overlay above it and never shifts the timeline.
                    .padding(bottom = if (tab == AppTab.SCHEDULE) 76.dp else 0.dp),
            )
            if (tab == AppTab.SCHEDULE) {
                ScheduleDockedSearch(
                    state = state,
                    onQueryChanged = viewModel::updateSearchQuery,
                    onTypeChanged = viewModel::setSearchType,
                    onSearch = viewModel::search,
                    onSelect = viewModel::selectTarget,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onRenameFavorite = viewModel::renameFavorite,
                    modifier = Modifier.fillMaxSize().align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun AppContent(
    viewModel: MainViewModel,
    barsViewModel: BarsViewModel,
    updateViewModel: UpdateViewModel,
    tab: AppTab,
    todayJumpRequest: Int,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    AnimatedContent(
        targetState = tab,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            val enter = slideInHorizontally(
                animationSpec = tween(220),
                initialOffsetX = { width -> if (forward) width / 6 else -width / 6 },
            ) + fadeIn(animationSpec = tween(180))
            val exit = slideOutHorizontally(
                animationSpec = tween(180),
                targetOffsetX = { width -> if (forward) -width / 8 else width / 8 },
            ) + fadeOut(animationSpec = tween(120))
            enter togetherWith exit
        },
        label = "main-navigation",
    ) { targetTab ->
        when (targetTab) {
            AppTab.SCHEDULE -> ScheduleScreen(
                state = state,
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
    }
}
