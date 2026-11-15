package org.thoughtcrime.securesms.videocircle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import org.thoughtcrime.securesms.util.getAccentColor
import kotlin.math.min

/**
 * The round live camera preview shown while a video circle is being recorded.
 *
 *  - [open] grows out of the microphone button (spring / overshoot),
 *  - a ring around the circle fills up over the 60 s limit and the circle gently "breathes",
 *  - [closeCancel] flings the circle off to the left, [closeSent] drops it down toward the chat.
 */
class CircleRecordingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    val previewView: PreviewView = PreviewView(context)

    private val ring: RingView
    private val diameter: Int
    private val ringPad = dp(8)

    private var progressAnimator: ValueAnimator? = null
    private var breathAnimator: Animator? = null

    init {
        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        diameter = if (landscape) min(dp(240), (dm.heightPixels * 0.5f).toInt())
        else min(dp(240), (dm.widthPixels * 0.66f).toInt())

        ring = RingView(context, context.getAccentColor())
        addView(ring, LayoutParams(diameter + ringPad * 2, diameter + ringPad * 2, Gravity.CENTER))

        previewView.apply {
            // TextureView based preview, so that the oval outline clip below actually applies.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        addView(previewView, LayoutParams(diameter, diameter, Gravity.CENTER))

        elevation = dp(6).toFloat()
        // Grow out of (and fall back into) the microphone button at the bottom right.
        val size = diameter + ringPad * 2
        pivotX = size - dp(36).toFloat()
        pivotY = size.toFloat()
        visibility = GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // region Animations

    /** Pops the circle out of the microphone button. Call as soon as the user starts holding. */
    fun open() {
        cancelAnimations()
        resetTransforms()
        ring.progress = 0f
        ring.alpha = 0f
        alpha = 0f
        scaleX = 0.25f
        scaleY = 0.25f
        visibility = VISIBLE
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(360)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    /** The camera really started recording: start filling the ring and breathing. */
    fun onRecordingStarted() {
        ring.animate().alpha(1f).setDuration(200).start()

        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = VideoCircleUtils.MAX_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { ring.progress = it.animatedValue as Float }
            start()
        }

        breathAnimator?.cancel()
        breathAnimator = ObjectAnimator.ofPropertyValuesHolder(
            previewView,
            android.animation.PropertyValuesHolder.ofFloat(SCALE_X, 1f, 1.025f),
            android.animation.PropertyValuesHolder.ofFloat(SCALE_Y, 1f, 1.025f)
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    /** Discarded: flick the circle away to the left while it shrinks and tilts. */
    fun closeCancel() {
        if (visibility != VISIBLE) return
        stopRecordingAnimations()
        animate().cancel()
        animate()
            .translationX(-width * 0.9f)
            .rotation(-14f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .alpha(0f)
            .setDuration(300)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction { hideAndReset() }
            .start()
    }

    /** Sent: the circle drops toward the chat, shrinking as if it lands as a message. */
    fun closeSent() {
        if (visibility != VISIBLE) return
        stopRecordingAnimations()
        animate().cancel()
        animate()
            .translationY(height * 0.55f)
            .scaleX(0.35f)
            .scaleY(0.35f)
            .alpha(0f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction { hideAndReset() }
            .start()
    }

    private fun stopRecordingAnimations() {
        progressAnimator?.cancel()
        progressAnimator = null
        breathAnimator?.cancel()
        breathAnimator = null
        previewView.scaleX = 1f
        previewView.scaleY = 1f
    }

    private fun cancelAnimations() {
        animate().cancel()
        ring.animate().cancel()
        stopRecordingAnimations()
    }

    private fun resetTransforms() {
        translationX = 0f
        translationY = 0f
        rotation = 0f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }

    private fun hideAndReset() {
        visibility = GONE
        resetTransforms()
        ring.progress = 0f
    }

    // endregion

    override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    /** Track + progress arc drawn around the circle. */
    private class RingView(context: Context, private val accent: Int) : View(context) {
        var progress: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        private val stroke = 3f * resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.argb(70, 255, 255, 255)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = accent
        }
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            val inset = stroke / 2f + 1f
            bounds.set(inset, inset, width - inset, height - inset)
            canvas.drawOval(bounds, track)
            if (progress > 0f) canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
        }
    }
}
