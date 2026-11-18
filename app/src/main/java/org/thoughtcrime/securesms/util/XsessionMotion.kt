package org.thoughtcrime.securesms.util

import android.animation.ValueAnimator
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/** Fade and lift the parts of a new surface in reading order. */
internal fun animateXsessionEntrance(vararg views: View) {
    if (!ValueAnimator.areAnimatorsEnabled()) return
    for ((index, view) in views.withIndex()) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 11f * view.resources.displayMetrics.density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * 32L)
            .setDuration(280L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }
}
