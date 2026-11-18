package org.thoughtcrime.securesms.videocircle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.Property
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.asFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.R
import org.thoughtcrime.securesms.util.getAccentColor
import kotlin.coroutines.resume
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

    var previewView: PreviewView = newPreview()
        private set

    private val cameraFace = FrameLayout(context)
    private val snapshot = ImageView(context)
    private val flipButton = ImageButton(context)
    private var onFlipRequested: (() -> Unit)? = null

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

        cameraFace.apply {
            setBackgroundColor(Color.rgb(30, 30, 30))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            // A longer perspective distance keeps the tilted circle's edges inside the ring.
            cameraDistance = dp(1700).toFloat()
        }
        cameraFace.addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        snapshot.scaleType = ImageView.ScaleType.FIT_XY
        snapshot.visibility = GONE
        cameraFace.addView(snapshot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cameraFace, LayoutParams(diameter, diameter, Gravity.CENTER))

        flipButton.apply {
            visibility = GONE
            setImageResource(R.drawable.ic_switch_camera)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = context.getString(R.string.ic_flip_24)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(226, 25, 29, 28))
                    setStroke(dp(1).coerceAtLeast(1), Color.argb(90, 255, 255, 255))
                },
                null,
            )
            elevation = dp(3).toFloat()
            setOnClickListener { onFlipRequested?.invoke() }
        }
        addView(flipButton, LayoutParams(dp(48), dp(48), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(13)
            bottomMargin = dp(13)
        })

        elevation = dp(6).toFloat()
        // Grow out of (and fall back into) the microphone button at the bottom right.
        val size = diameter + ringPad * 2
        pivotX = size - dp(36).toFloat()
        pivotY = size.toFloat()
        visibility = GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun newPreview() = PreviewView(context).apply {
        // TextureView draws into the oval and lets the transition hold the last live frame.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    /** Turns the old preview out, covers the hardware handoff, and turns the new preview in. */
    suspend fun flipCamera(rebind: (PreviewView) -> Boolean): Boolean {
        val previous = previewView
        flipButton.isEnabled = false
        val animateTurn = ValueAnimator.areAnimatorsEnabled()
        val frozenFrame = try {
            previous.bitmap
        } catch (_: IllegalStateException) {
            null
        }
        snapshot.setImageBitmap(frozenFrame)
        snapshot.alpha = 1f
        snapshot.visibility = if (frozenFrame == null) GONE else VISIBLE

        try {
            if (animateTurn) {
                flipButton.animate().rotationBy(180f).setDuration(350L)
                    .setInterpolator(FastOutSlowInInterpolator()).start()
                animateProperty(cameraFace, ROTATION_Y, 88f, 140L)
            }
            val next = newPreview()
            // A fresh PreviewView only reports streaming when the rebound camera produces frames.
            cameraFace.addView(next, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val switched = try {
                rebind(next)
            } catch (error: Exception) {
                cameraFace.removeView(next)
                throw error
            }
            previewView = next
            cameraFace.removeView(previous)
            coroutineScope {
                if (animateTurn) {
                    cameraFace.rotationY = -88f
                    launch { animateProperty(cameraFace, ROTATION_Y, 0f, 220L) }
                }
                withTimeoutOrNull(1500L) {
                    next.previewStreamState.asFlow().first { it == PreviewView.StreamState.STREAMING }
                }
                if (animateTurn && snapshot.visibility == VISIBLE) {
                    animateProperty(snapshot, ALPHA, 0f, 135L)
                }
                snapshot.visibility = GONE
            }
            return switched
        } finally {
            cameraFace.rotationY = 0f
            snapshot.visibility = GONE
            snapshot.setImageDrawable(null)
            flipButton.isEnabled = onFlipRequested != null
        }
    }

    private suspend fun animateProperty(view: View, property: Property<View, Float>, target: Float, milliseconds: Long) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val animator = ObjectAnimator.ofFloat(view, property, target).apply {
                duration = milliseconds
                interpolator = FastOutSlowInInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                })
            }
            continuation.invokeOnCancellation { animator.cancel() }
            animator.start()
        }
    }

    // region Animations

    /** Pops the circle out of the microphone button. Call as soon as the user starts holding. */
    fun open() {
        cancelAnimations()
        resetTransforms()
        flipButton.visibility = GONE
        flipButton.rotation = 0f
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
    fun onRecordingStarted(onFlip: (() -> Unit)?) {
        onFlipRequested = onFlip
        flipButton.isEnabled = onFlip != null
        if (onFlip != null) {
            flipButton.alpha = 0f
            flipButton.scaleX = 0.8f
            flipButton.scaleY = 0.8f
            flipButton.visibility = VISIBLE
            flipButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(230L)
                .setInterpolator(FastOutSlowInInterpolator()).start()
        }

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
            cameraFace,
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
        cameraFace.scaleX = 1f
        cameraFace.scaleY = 1f
        onFlipRequested = null
        flipButton.animate().cancel()
        flipButton.isEnabled = false
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
        flipButton.visibility = GONE
        flipButton.rotation = 0f
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
