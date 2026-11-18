package org.thoughtcrime.securesms.updates

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentXsessionUpdateBinding
import org.thoughtcrime.securesms.updates.XsessionUpdateViewModel.State
import org.thoughtcrime.securesms.util.XsessionSurfaces
import org.thoughtcrime.securesms.util.animateXsessionEntrance
import javax.inject.Inject

@AndroidEntryPoint
class XsessionUpdateSheet : BottomSheetDialogFragment(R.layout.fragment_xsession_update) {
    @Inject lateinit var updater: XsessionUpdates
    private val viewModel: XsessionUpdateViewModel by viewModels()
    private var binding: FragmentXsessionUpdateBinding? = null
    private var closing = false

    private val installationPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ready = viewModel.state.value as? State.Ready ?: return@registerForActivityResult
        if (requireContext().packageManager.canRequestPackageInstalls()) openInstaller(ready)
        binding?.let { render(it, viewModel.state.value) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            dismissWithAnimation = true
            behavior.skipCollapsed = true
        }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = FragmentXsessionUpdateBinding.bind(view)
        binding = ui
        val surfaces = XsessionSurfaces(view.context)
        val accent = surfaces.iconColor()
        ui.updateSurface.background = surfaces.editor()
        ui.updateStatusCard.background = surfaces.input()
        ui.updateHandle.background = surfaces.handle()
        ui.updateIcon.background = surfaces.icon()
        ui.updateIcon.imageTintList = ColorStateList.valueOf(accent)
        ui.updateSpinner.indeterminateTintList = ColorStateList.valueOf(accent)
        ui.updateProgress.progressTintList = ColorStateList.valueOf(accent)
        ui.updateProgress.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 42))
        ui.updatePercent.setTextColor(accent)
        surfaces.primaryButton(ui.updatePrimary)
        render(ui, viewModel.state.value)
        view.doOnPreDraw {
            animateXsessionEntrance(ui.updateHandle, ui.updateHeader, ui.updateStatusCard, ui.updateActions)
        }

        ui.updateDismiss.setOnClickListener {
            viewModel.cancel()
            closeSheet()
        }
        ui.updatePrimary.setOnClickListener {
            when (val state = viewModel.state.value) {
                is State.Available -> viewModel.download(state.release)
                is State.Ready -> install(state)
                is State.Failed -> viewModel.retry(state.release)
                else -> Unit
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousStage: Class<out State>? = null
                viewModel.state.collect { state ->
                    if (closing) return@collect
                    render(ui, state)
                    if (previousStage != null && previousStage != state.javaClass) {
                        animateXsessionEntrance(ui.updateStatus, ui.updateDetail, ui.updateActions)
                    }
                    previousStage = state.javaClass
                }
            }
        }
        viewModel.open(arguments?.getLong(ARG_RELEASE_VERSION)?.takeIf { it > 0 })
    }

    private fun render(ui: FragmentXsessionUpdateBinding, state: State) {
        val download = state as? State.Downloading
        val waiting = state is State.Checking || state is State.Verifying
        val busy = waiting || download != null
        if (isCancelable == busy) isCancelable = !busy
        (dialog as? BottomSheetDialog)?.behavior?.isDraggable = !busy

        ui.updateBuild.isVisible = state.release != null
        state.release?.let { release ->
            ui.updateBuild.text = getString(
                R.string.xsessionUpdateBannerVersion,
                release.versionLabel,
                Formatter.formatShortFileSize(ui.root.context, release.size),
            )
        }
        ui.updateSpinner.isVisible = waiting
        ui.updateProgress.isVisible = download != null
        ui.updatePercent.isVisible = download != null
        if (download != null) {
            val percent = (download.bytes * 100 / download.release.size).toInt()
            ui.updatePercent.text = getString(R.string.xsessionUpdatePercent, percent)
            ui.updateProgress.setProgress(percent, ValueAnimator.areAnimatorsEnabled())
        } else {
            ui.updateProgress.progress = 0
        }

        ui.updateStatus.setText(when (state) {
            State.Checking -> R.string.xsessionCheckingUpdates
            State.UpToDate -> R.string.xsessionUpdateCurrent
            is State.Available -> R.string.xsessionUpdateAvailable
            is State.Downloading -> R.string.xsessionDownloadingUpdate
            is State.Verifying -> R.string.xsessionUpdateVerifying
            is State.Ready -> R.string.xsessionUpdateReady
            is State.Failed -> R.string.xsessionUpdateFailed
        })
        val detail = when (state) {
            is State.Available -> state.release.notes
            is State.Downloading -> getString(
                R.string.xsessionUpdateDownloadedBytes,
                Formatter.formatShortFileSize(requireContext(), state.bytes),
                Formatter.formatShortFileSize(requireContext(), state.release.size),
            )
            is State.Verifying -> getString(R.string.xsessionUpdateVerifyingDetail)
            is State.Ready -> getString(when {
                state.installError -> R.string.xsessionUpdateInstallFailed
                requireContext().packageManager.canRequestPackageInstalls() -> R.string.xsessionUpdateReadyDetail
                else -> R.string.xsessionUpdatePermission
            })
            is State.Failed -> getString(state.reason)
            else -> ""
        }
        ui.updateDetail.isVisible = detail.isNotBlank()
        ui.updateDetail.text = detail
        val action = when (state) {
            is State.Available -> R.string.download
            is State.Ready -> if (requireContext().packageManager.canRequestPackageInstalls()) {
                R.string.xsessionUpdateInstall
            } else R.string.xsessionUpdateOpenSettings
            is State.Failed -> R.string.xsessionUpdateRetry
            else -> null
        }
        ui.updatePrimary.isVisible = action != null
        action?.let { ui.updatePrimary.setText(it) }
        ui.updateDismiss.setText(if (busy) R.string.cancel else R.string.xsessionUpdateClose)
    }

    private fun install(state: State.Ready) {
        if (!state.apk.isFile) {
            viewModel.download(state.release)
            return
        }
        val context = requireContext()
        if (context.packageManager.canRequestPackageInstalls()) {
            openInstaller(state)
            return
        }
        try {
            installationPermission.launch(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", context.packageName, null),
            ))
        } catch (_: Exception) {
            viewModel.installFailed()
        }
    }

    private fun openInstaller(state: State.Ready) {
        try {
            startActivity(updater.installIntent(state.apk))
        } catch (_: Exception) {
            viewModel.installFailed()
        }
    }

    private fun closeSheet() {
        closing = true
        isCancelable = true
        dismiss()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "XsessionUpdate"
        private const val ARG_RELEASE_VERSION = "release_version"

        internal fun show(manager: FragmentManager, releaseVersion: Long? = null) {
            if (!BuildConfig.XSESSION_OTA_ENABLED || manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            XsessionUpdateSheet().apply {
                arguments = bundleOf(ARG_RELEASE_VERSION to (releaseVersion ?: 0L))
            }.show(manager, TAG)
        }
    }
}
