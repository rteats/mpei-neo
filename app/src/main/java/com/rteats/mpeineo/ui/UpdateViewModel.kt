package com.rteats.mpeineo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rteats.mpeineo.AppContainer
import com.rteats.mpeineo.BuildConfig
import com.rteats.mpeineo.data.GithubReleaseInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class UpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    ERROR,
}

internal data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: GithubReleaseInfo? = null,
    val progressPercent: Int? = null,
    val downloadedApkPath: String? = null,
    val error: String? = null,
)

internal class UpdateViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun checkForUpdates() {
        if (_state.value.phase == UpdatePhase.CHECKING) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = UpdatePhase.CHECKING,
                    progressPercent = null,
                    error = null,
                )
            }

            try {
                val release = container.updater.getLatestRelease()
                _state.value = if (release.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateUiState(
                        phase = UpdatePhase.AVAILABLE,
                        release = release,
                    )
                } else {
                    UpdateUiState(
                        phase = UpdatePhase.UP_TO_DATE,
                        release = release,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        phase = UpdatePhase.ERROR,
                        error = error.message ?: "Не удалось проверить обновления",
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        val release = _state.value.release ?: return
        if (_state.value.phase == UpdatePhase.DOWNLOADING) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = UpdatePhase.DOWNLOADING,
                    progressPercent = null,
                    downloadedApkPath = null,
                    error = null,
                )
            }

            try {
                val apk = container.updater.downloadRelease(release) { percent ->
                    _state.update { current ->
                        if (current.phase == UpdatePhase.DOWNLOADING) {
                            current.copy(progressPercent = percent)
                        } else {
                            current
                        }
                    }
                }

                _state.update {
                    it.copy(
                        phase = UpdatePhase.READY_TO_INSTALL,
                        progressPercent = 100,
                        downloadedApkPath = apk.absolutePath,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        phase = UpdatePhase.ERROR,
                        error = error.message ?: "Не удалось скачать обновление",
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate(context: Context) {
        val path = _state.value.downloadedApkPath ?: return
        val file = File(path)
        runCatching {
            context.startActivity(container.updater.createInstallIntent(file))
        }.onFailure { error ->
            _state.update {
                it.copy(
                    phase = UpdatePhase.ERROR,
                    error = error.message ?: "Не удалось открыть установщик Android",
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return UpdateViewModel(container) as T
                }
            }
    }
}
