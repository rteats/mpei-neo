package com.rteats.mpeineo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rteats.mpeineo.AppContainer
import com.rteats.mpeineo.model.ScheduleLoad
import com.rteats.mpeineo.model.ScheduleSource
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import com.rteats.mpeineo.model.ScheduleWeek
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val selected: ScheduleTarget? = null,
    val favorites: List<ScheduleTarget> = emptyList(),
    val weekOffset: Int = 0,
    val week: ScheduleWeek? = null,
    val source: ScheduleSource? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val searchType: ScheduleTargetType? = null,
    val searchResults: List<ScheduleTarget> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val refreshOnLaunch: Boolean = true,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            container.preferences.favorites.collect { favorites ->
                _state.update { it.copy(favorites = favorites) }
            }
        }
        viewModelScope.launch {
            container.preferences.refreshOnLaunch.collect { enabled ->
                _state.update { it.copy(refreshOnLaunch = enabled) }
            }
        }
        viewModelScope.launch {
            val selected = container.preferences.selected.first()
            val refresh = container.preferences.refreshOnLaunch.first()
            if (selected != null) {
                _state.update { it.copy(selected = selected) }
                loadWeek(forceNetwork = refresh)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _state.update {
            it.copy(
                searchQuery = query,
                searchError = null,
            )
        }

        if (query.trim().length < 2) {
            searchJob?.cancel()
            _state.update {
                it.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                )
            }
            return
        }

        scheduleSearch(delayMillis = 200)
    }

    fun setSearchType(type: ScheduleTargetType?) {
        _state.update { it.copy(searchType = type, searchError = null) }
        if (state.value.searchQuery.trim().length >= 2) {
            scheduleSearch(delayMillis = 0)
        }
    }

    fun search() {
        val query = state.value.searchQuery.trim()
        if (query.length < 2) {
            searchJob?.cancel()
            _state.update {
                it.copy(
                    isSearching = false,
                    searchError = "Введите минимум 2 символа",
                )
            }
            return
        }
        scheduleSearch(delayMillis = 0)
    }

    fun selectTarget(target: ScheduleTarget) {
        viewModelScope.launch {
            container.preferences.setSelected(target)
            _state.update {
                it.copy(
                    selected = target,
                    weekOffset = 0,
                    week = null,
                    source = null,
                    error = null,
                )
            }
            loadWeek(forceNetwork = false)
        }
    }

    fun toggleFavorite(target: ScheduleTarget) {
        viewModelScope.launch {
            container.preferences.toggleFavorite(target)
        }
    }

    fun previousWeek() {
        _state.update { it.copy(weekOffset = it.weekOffset - 1) }
        loadWeek(forceNetwork = false)
    }

    fun nextWeek() {
        _state.update { it.copy(weekOffset = it.weekOffset + 1) }
        loadWeek(forceNetwork = false)
    }

    fun currentWeek() {
        if (state.value.weekOffset == 0) return
        _state.update { it.copy(weekOffset = 0) }
        loadWeek(forceNetwork = false)
    }

    fun refresh() {
        loadWeek(forceNetwork = true)
    }

    fun setRefreshOnLaunch(enabled: Boolean) {
        viewModelScope.launch {
            container.preferences.setRefreshOnLaunch(enabled)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            container.repository.clearCache()
            _state.update { it.copy(source = null) }
        }
    }

    private fun scheduleSearch(delayMillis: Long) {
        searchJob?.cancel()
        val query = state.value.searchQuery.trim()
        val type = state.value.searchType

        searchJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (state.value.searchQuery.trim() != query || state.value.searchType != type) return@launch

            _state.update { it.copy(isSearching = true, searchError = null) }

            try {
                val results = container.repository.search(query, type)
                if (state.value.searchQuery.trim() == query && state.value.searchType == type) {
                    _state.update {
                        it.copy(
                            isSearching = false,
                            searchResults = results,
                            searchError = null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (state.value.searchQuery.trim() == query && state.value.searchType == type) {
                    _state.update {
                        it.copy(
                            isSearching = false,
                            searchResults = emptyList(),
                            searchError = error.message ?: "Не удалось выполнить поиск",
                        )
                    }
                }
            }
        }
    }

    private fun loadWeek(forceNetwork: Boolean) {
        val target = state.value.selected ?: return
        val offset = state.value.weekOffset
        val weekStart = mondayFor(LocalDate.now()).plusWeeks(offset.toLong())

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = try {
                container.repository.cachedWeek(target, weekStart)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }

            if (cached != null && matchesCurrentSelection(target, offset)) {
                _state.update {
                    it.copy(
                        week = cached.week,
                        source = cached.source,
                        isLoading = forceNetwork,
                        error = null,
                    )
                }
                if (!forceNetwork) return@launch
            } else if (matchesCurrentSelection(target, offset)) {
                _state.update { it.copy(isLoading = true, error = null) }
            }

            try {
                val load: ScheduleLoad = container.repository.loadWeek(
                    target = target,
                    weekStart = weekStart,
                    forceNetwork = true,
                )
                if (matchesCurrentSelection(target, offset)) {
                    _state.update {
                        it.copy(
                            week = load.week,
                            source = load.source,
                            isLoading = false,
                            error = null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (matchesCurrentSelection(target, offset)) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Не удалось загрузить расписание",
                        )
                    }
                }
            }
        }
    }

    private fun matchesCurrentSelection(target: ScheduleTarget, offset: Int): Boolean {
        val current = state.value
        val selected = current.selected ?: return false
        return selected.id == target.id &&
            selected.type == target.type &&
            current.weekOffset == offset
    }

    private fun mondayFor(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(container) as T
                }
            }
    }
}
