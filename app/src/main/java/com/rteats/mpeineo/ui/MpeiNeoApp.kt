package com.rteats.mpeineo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection.Companion.Bottom
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.rteats.mpeineo.MpeiNeoApplication
import com.rteats.mpeineo.R

private enum class AppTab {
    SCHEDULE,
    BARS,
    MAIL,
    SETTINGS,
}

/**
 * Floating navigation follows ScrollableHorizontalFloatingToolbarSample.
 * A selected destination is a FilledIconToggleButton; Search belongs to Schedule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MpeiNeoApp(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.SCHEDULE) }
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
    val toolbarScrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = Bottom,
    )

    Scaffold(
        modifier = Modifier.nestedScroll(toolbarScrollBehavior),
        // The toolbar is overlaid by the outer Box; there must be no opaque
        // navigation bar surface or reserved strip underneath it.
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            AppContent(
                viewModel = viewModel,
                barsViewModel = barsViewModel,
                updateViewModel = updateViewModel,
                tab = tab,
                // The page draws all the way to the bottom behind the floating
                // toolbar. Scrollable screens provide their own *scrollable* end
                // space, so the last row can still be reached when the toolbar is visible.
                modifier = Modifier.fillMaxSize(),
            )

            HorizontalFloatingToolbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -FloatingToolbarDefaults.ScreenOffset)
                    .zIndex(1f),
                expanded = true,
                scrollBehavior = toolbarScrollBehavior,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FloatingDestination(
                        selected = tab == AppTab.SCHEDULE,
                        onSelect = { tab = AppTab.SCHEDULE },
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = "Расписание")
                    }
                    FloatingDestination(
                        selected = tab == AppTab.BARS,
                        onSelect = {
                            if (tab == AppTab.BARS && barsState.browserVisible) {
                                barsViewModel.hideBrowser()
                            } else {
                                tab = AppTab.BARS
                            }
                        },
                    ) {
                        if (tab == AppTab.BARS && barsState.browserVisible) {
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
                    }
                    FloatingDestination(
                        selected = tab == AppTab.MAIL,
                        onSelect = { tab = AppTab.MAIL },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_mail),
                            contentDescription = "Почта",
                        )
                    }
                    FloatingDestination(
                        selected = tab == AppTab.SETTINGS,
                        onSelect = { tab = AppTab.SETTINGS },
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingDestination(
    selected: Boolean,
    onSelect: () -> Unit,
    icon: @Composable () -> Unit,
) {
    FilledIconToggleButton(
        checked = selected,
        onCheckedChange = { onSelect() },
        shapes = IconButtonDefaults.toggleableShapes(),
    ) {
        icon()
    }
}

@Composable
private fun AppContent(
    viewModel: MainViewModel,
    barsViewModel: BarsViewModel,
    updateViewModel: UpdateViewModel,
    tab: AppTab,
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
                onSelect = viewModel::selectTarget,
                onToggleFavorite = viewModel::toggleFavorite,
                onQueryChanged = viewModel::updateSearchQuery,
                onTypeChanged = viewModel::setSearchType,
                onSearch = viewModel::search,
                onEnsureAgendaWeek = viewModel::ensureAgendaWeek,
                onRefreshAgendaWeek = viewModel::refreshAgendaWeek,
                onRetryAgendaWeek = viewModel::retryAgendaWeek,
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
                state = state,
                updateViewModel = updateViewModel,
                onRefreshOnLaunchChanged = viewModel::setRefreshOnLaunch,
                onClearCache = viewModel::clearCache,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
