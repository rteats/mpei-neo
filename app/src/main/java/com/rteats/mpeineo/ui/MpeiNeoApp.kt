package com.rteats.mpeineo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.rteats.mpeineo.R

private enum class AppTab {
    SCHEDULE,
    SEARCH,
    BARS,
    MAIL,
    SETTINGS,
}

@Composable
fun MpeiNeoApp(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.SCHEDULE) }
    val barsViewModel: BarsViewModel = composeViewModel()

    BackHandler(enabled = tab == AppTab.SEARCH) {
        tab = AppTab.SCHEDULE
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.SCHEDULE || tab == AppTab.SEARCH,
                    onClick = { tab = AppTab.SCHEDULE },
                    icon = {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = "Расписание",
                        )
                    },
                )
                NavigationBarItem(
                    selected = tab == AppTab.BARS,
                    onClick = { tab = AppTab.BARS },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_bars),
                            contentDescription = "БАРС",
                        )
                    },
                )
                NavigationBarItem(
                    selected = tab == AppTab.MAIL,
                    onClick = { tab = AppTab.MAIL },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_mail),
                            contentDescription = "Почта",
                        )
                    },
                )
                NavigationBarItem(
                    selected = tab == AppTab.SETTINGS,
                    onClick = { tab = AppTab.SETTINGS },
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Настройки",
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        AppContent(
            viewModel = viewModel,
            barsViewModel = barsViewModel,
            tab = tab,
            onTabChanged = { tab = it },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        )
    }
}

@Composable
private fun AppContent(
    viewModel: MainViewModel,
    barsViewModel: BarsViewModel,
    tab: AppTab,
    onTabChanged: (AppTab) -> Unit,
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
                onRefresh = viewModel::refresh,
                onPreviousWeek = viewModel::previousWeek,
                onNextWeek = viewModel::nextWeek,
                onCurrentWeek = viewModel::currentWeek,
                onOpenSearch = { onTabChanged(AppTab.SEARCH) },
                modifier = Modifier.fillMaxSize(),
            )

            AppTab.SEARCH -> SearchScreen(
                state = state,
                onQueryChanged = viewModel::updateSearchQuery,
                onTypeChanged = viewModel::setSearchType,
                onSearch = viewModel::search,
                onSelect = {
                    viewModel.selectTarget(it)
                    onTabChanged(AppTab.SCHEDULE)
                },
                onToggleFavorite = viewModel::toggleFavorite,
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
                onRefreshOnLaunchChanged = viewModel::setRefreshOnLaunch,
                onClearCache = viewModel::clearCache,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
