package org.thoughtcrime.securesms.preferences

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.BuildConfig
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.compose.HelpSettingsScreen
import org.thoughtcrime.securesms.preferences.compose.HelpSettingsViewModel
import org.thoughtcrime.securesms.updates.XsessionUpdateSheet

@AndroidEntryPoint
class HelpSettingsActivity : FullComposeScreenLockActivity() {
    @Composable
    override fun ComposeContent() {
        val viewModel: HelpSettingsViewModel by viewModels()

        HelpSettingsScreen(
            viewModel = viewModel,
            onBackPressed = this::finish,
            onCheckUpdates = if (BuildConfig.XSESSION_OTA_ENABLED) this::showUpdates else null,
        )
    }

    private fun showUpdates() = XsessionUpdateSheet.show(supportFragmentManager)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
}
