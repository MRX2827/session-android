package org.thoughtcrime.securesms.preferences

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.compose.HelpSettingsScreen
import org.thoughtcrime.securesms.preferences.compose.HelpSettingsViewModel
import org.thoughtcrime.securesms.updates.XsessionUpdates
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class HelpSettingsActivity: FullComposeScreenLockActivity() {
    @Inject lateinit var updates: XsessionUpdates

    private var updateJob: Job? = null
    private var pendingApk: File? = null
    private val allowInstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = pendingApk
        pendingApk = null
        if (file != null && packageManager.canRequestPackageInstalls()) openInstaller(file)
        else file?.delete()
    }

    @Composable
    override fun ComposeContent() {
        val viewModel: HelpSettingsViewModel by viewModels()

        HelpSettingsScreen(
            viewModel = viewModel,
            onBackPressed = this::finish,
            onCheckUpdates = if (BuildConfig.XSESSION_OTA_ENABLED) this::checkForUpdates else null
        )
    }

    private fun checkForUpdates() {
        runUpdateTask(R.string.xsessionCheckingUpdates) {
            val release = updates.check()
            if (release == null) {
                Toast.makeText(this, R.string.xsessionUpdateCurrent, Toast.LENGTH_LONG).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.xsessionUpdateAvailable)
                    .setMessage(getString(R.string.xsessionUpdateDescription,
                        release.versionCode, (release.size + MB - 1) / MB))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.download) { _, _ ->
                        runUpdateTask(R.string.xsessionDownloadingUpdate) {
                            offerInstall(updates.download(release))
                        }
                    }
                    .show()
            }
        }
    }

    private fun runUpdateTask(message: Int, action: suspend () -> Unit) {
        if (updateJob?.isActive == true) return
        val progress = AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton(R.string.cancel) { _, _ -> updateJob?.cancel() }
            .setOnCancelListener { updateJob?.cancel() }
            .show()

        updateJob = lifecycleScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showUpdateError()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun offerInstall(file: File) {
        if (packageManager.canRequestPackageInstalls()) {
            openInstaller(file)
        } else {
            AlertDialog.Builder(this)
                .setMessage(R.string.xsessionUpdatePermission)
                .setOnCancelListener { file.delete() }
                .setNegativeButton(R.string.cancel) { _, _ -> file.delete() }
                .setPositiveButton(R.string.xsessionUpdateOpenSettings) { _, _ ->
                    pendingApk = file
                    try {
                        allowInstall.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.fromParts("package", packageName, null)))
                    } catch (_: Exception) {
                        pendingApk = null
                        file.delete()
                        showUpdateError()
                    }
                }
                .show()
        }
    }

    private fun openInstaller(file: File) {
        try {
            startActivity(updates.installIntent(file))
        } catch (_: Exception) {
            file.delete()
            showUpdateError()
        }
    }

    private fun showUpdateError() {
        Toast.makeText(this, R.string.xsessionUpdateError, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val MB = 1024L * 1024L
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
}

