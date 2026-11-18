package org.thoughtcrime.securesms.updates

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import network.loki.messenger.R
import java.io.File
import javax.inject.Inject

@HiltViewModel
class XsessionUpdateViewModel @Inject constructor(
    private val updater: XsessionUpdates,
    private val announcements: XsessionUpdateAnnouncements,
) : ViewModel() {
    sealed interface State {
        val release: XsessionUpdates.Release? get() = null

        data object Checking : State
        data object UpToDate : State
        data class Available(override val release: XsessionUpdates.Release) : State
        data class Downloading(override val release: XsessionUpdates.Release, val bytes: Long) : State
        data class Verifying(override val release: XsessionUpdates.Release) : State
        data class Ready(
            override val release: XsessionUpdates.Release,
            val apk: File,
            val installError: Boolean = false,
        ) : State
        data class Failed(@StringRes val reason: Int, override val release: XsessionUpdates.Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Checking)
    val state = _state.asStateFlow()
    private var started = false
    private var task: Job? = null

    fun open(version: Long?) {
        if (started) return
        started = true
        checkForUpdate(version)
    }

    fun retry(release: XsessionUpdates.Release?) {
        if (release == null) checkForUpdate(null) else download(release)
    }

    fun download(release: XsessionUpdates.Release) {
        if (task?.isActive == true) return
        task = viewModelScope.launch { fetchApk(release) }
    }

    fun cancel() {
        task?.cancel()
    }

    fun installFailed() {
        val ready = _state.value as? State.Ready ?: return
        _state.value = ready.copy(installError = true)
    }

    private fun checkForUpdate(downloadVersion: Long?) {
        if (task?.isActive == true) return
        _state.value = State.Checking
        task = viewModelScope.launch {
            val release = try {
                downloadVersion?.let(announcements::checkedRelease) ?: updater.check()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = State.Failed(R.string.xsessionUpdateCheckFailed)
                return@launch
            }
            when {
                release == null -> _state.value = State.UpToDate
                release.versionCode == downloadVersion -> fetchApk(release)
                else -> _state.value = State.Available(release)
            }
        }
    }

    private suspend fun fetchApk(release: XsessionUpdates.Release) {
        _state.value = State.Downloading(release, 0)
        try {
            val apk = updater.download(
                release,
                onProgress = { bytes -> _state.value = State.Downloading(release, bytes) },
                onVerifying = { _state.value = State.Verifying(release) },
            )
            _state.value = State.Ready(release, apk)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val error = if (_state.value is State.Verifying) R.string.xsessionUpdateVerificationFailed
            else R.string.xsessionUpdateDownloadFailed
            _state.value = State.Failed(error, release)
        }
    }
}
