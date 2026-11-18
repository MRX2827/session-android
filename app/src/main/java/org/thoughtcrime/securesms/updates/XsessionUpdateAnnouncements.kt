package org.thoughtcrime.securesms.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XsessionUpdateAnnouncements @Inject constructor(private val updater: XsessionUpdates) {
    private val _available = MutableStateFlow<XsessionUpdates.Release?>(null)
    val available = _available.asStateFlow()

    private var latest: XsessionUpdates.Release? = null
    private var dismissedVersion: Long? = null
    private var checkJob: Job? = null

    fun checkOnForeground(scope: CoroutineScope) {
        checkJob?.cancel()
        checkJob = scope.launch {
            val release = try {
                updater.check()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            latest = release
            _available.value = release?.takeUnless { it.versionCode == dismissedVersion }
        }
    }

    fun clearBanner() {
        checkJob?.cancel()
        _available.value = null
    }

    fun dismiss(versionCode: Long) {
        dismissedVersion = versionCode
        if (_available.value?.versionCode == versionCode) _available.value = null
    }

    fun checkedRelease(versionCode: Long) = latest?.takeIf { it.versionCode == versionCode }
}
