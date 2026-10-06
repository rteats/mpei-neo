package com.rteats.mpeineo.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding

private enum class AppTab(val title: String) {
    SCHEDULE("Расписание"),
    SEARCH("Поиск"),
    SETTINGS("Настройки"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MpeiNeoApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(AppTab.SCHEDULE) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MPEI Neo", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.SCHEDULE,
                    onClick = { tab = AppTab.SCHEDULE },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    label = { Text("Расписание") },
                )
                NavigationBarItem(
                    selected = tab == AppTab.SEARCH,
                    onClick = { tab = AppTab.SEARCH },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Поиск") },
                )
                NavigationBarItem(
                    selected = tab == AppTab.SETTINGS,
                    onClick = { tab = AppTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Настройки") },
                )
            }
        },
    ) { innerPadding ->
        when (tab) {
            AppTab.SCHEDULE -> ScheduleScreen(
                state = state,
                onSelect = viewModel::selectTarget,
                onToggleFavorite = viewModel::toggleFavorite,
                onRefresh = viewModel::refresh,
                onPreviousWeek = viewModel::previousWeek,
                onNextWeek = viewModel::nextWeek,
                onCurrentWeek = viewModel::currentWeek,
                onOpenSearch = { tab = AppTab.SEARCH },
                modifier = Modifier.padding(innerPadding),
            )

            AppTab.SEARCH -> SearchScreen(
                state = state,
                onQueryChanged = viewModel::updateSearchQuery,
                onTypeChanged = viewModel::setSearchType,
                onSearch = viewModel::search,
                onSelect = {
                    viewModel.selectTarget(it)
                    tab = AppTab.SCHEDULE
                },
                onToggleFavorite = viewModel::toggleFavorite,
                modifier = Modifier.padding(innerPadding),
            )

            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                onRefreshOnLaunchChanged = viewModel::setRefreshOnLaunch,
                onClearCache = viewModel::clearCache,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
