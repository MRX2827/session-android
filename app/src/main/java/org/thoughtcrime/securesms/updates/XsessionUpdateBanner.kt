package org.thoughtcrime.securesms.updates

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewXsessionUpdateBannerBinding
import org.thoughtcrime.securesms.preferences.HelpSettingsActivity
import org.thoughtcrime.securesms.util.XsessionSurfaces
import org.thoughtcrime.securesms.util.animateXsessionEntrance
import kotlin.math.roundToInt

internal fun AppCompatActivity.showXsessionUpdateBanners(
    announcements: XsessionUpdateAnnouncements,
    canShow: () -> Boolean,
) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var displayed: XsessionUpdateBanner? = null
            try {
                announcements.available.collect { release ->
                    if (release != null && canShow()) {
                        if (displayed?.versionCode != release.versionCode) {
                            displayed?.remove()
                            displayed = XsessionUpdateBanner.show(this@showXsessionUpdateBanners, release) { download ->
                                announcements.dismiss(release.versionCode)
                                if (download && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    startActivity(
                                        Intent(this@showXsessionUpdateBanners, HelpSettingsActivity::class.java)
                                            .putExtra(HelpSettingsActivity.XSESSION_UPDATE_VERSION, release.versionCode)
                                    )
                                }
                            }
                        }
                    } else {
                        displayed?.dismiss()
                        displayed = null
                    }
                }
            } finally {
                displayed?.remove()
            }
        }
    }
}

private class XsessionUpdateBanner(
    private val host: FrameLayout,
    private val binding: ViewXsessionUpdateBannerBinding,
    val versionCode: Long,
) {
    private var closing = false

    fun dismiss(afterDismiss: () -> Unit = {}) {
        if (closing) return
        closing = true
        binding.updateDownload.isEnabled = false
        binding.updateLater.isEnabled = false
        binding.root.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            remove()
            afterDismiss()
            return
        }
        binding.root.animate()
            .alpha(0f)
            .translationY(binding.root.height.toFloat())
            .setDuration(230L)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                remove()
                afterDismiss()
            }
            .start()
    }

    fun remove() {
        binding.root.animate().cancel()
        host.removeView(binding.root)
    }

    companion object {
        fun show(
            activity: AppCompatActivity,
            release: XsessionUpdates.Release,
            onDismiss: (download: Boolean) -> Unit,
        ): XsessionUpdateBanner? {
            val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return null
            val binding = ViewXsessionUpdateBannerBinding.inflate(LayoutInflater.from(activity), host, false)
            val banner = XsessionUpdateBanner(host, binding, release.versionCode)
            val surfaces = XsessionSurfaces(activity)
            binding.root.background = surfaces.banner()
            binding.updateIcon.background = surfaces.icon()
            binding.updateIcon.imageTintList = ColorStateList.valueOf(surfaces.iconColor())
            surfaces.primaryButton(binding.updateDownload)
            val density = host.resources.displayMetrics.density
            val margin = (16 * density).roundToInt()
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
            ).apply { setMargins(margin, margin, margin, margin) }

            binding.updateVersion.text = activity.getString(
                R.string.xsessionUpdateBannerVersion,
                release.versionLabel,
                (release.size + 1_048_575L) / 1_048_576L,
            )
            binding.updateNotes.text = release.notes.ifBlank {
                activity.getString(R.string.xsessionUpdateBannerFallback)
            }
            binding.updateLater.setOnClickListener { banner.dismiss { onDismiss(false) } }
            binding.updateDownload.setOnClickListener { banner.dismiss { onDismiss(true) } }

            val animating = ValueAnimator.areAnimatorsEnabled()
            if (animating) binding.root.alpha = 0f
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
                val decor = activity.window.decorView
                val location = IntArray(2)
                host.getLocationInWindow(location)
                val alreadyInset = (decor.height - location[1] - host.height).coerceAtLeast(0)
                val targetMargin = margin + (bottom - alreadyInset).coerceAtLeast(0)
                if (params.bottomMargin != targetMargin) {
                    params.bottomMargin = targetMargin
                    view.layoutParams = params
                }
                insets
            }
            host.addView(binding.root, params)
            ViewCompat.requestApplyInsets(binding.root)
            if (animating) binding.root.doOnLayout { card ->
                card.translationY = (card.height + margin).toFloat()
                card.animate().alpha(1f).translationY(0f)
                    .setDuration(390L).setInterpolator(FastOutSlowInInterpolator()).start()
                animateXsessionEntrance(
                    binding.updateIcon, binding.updateTitle, binding.updateVersion,
                    binding.updateNotesLabel, binding.updateNotes, binding.updateLater, binding.updateDownload,
                )
            }
            return banner
        }
    }
}
