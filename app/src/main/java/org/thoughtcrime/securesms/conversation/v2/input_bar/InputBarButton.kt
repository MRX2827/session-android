package org.thoughtcrime.securesms.conversation.v2.input_bar

import android.animation.PointFEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.annotation.DrawableRes
import network.loki.messenger.R
import org.session.libsession.utilities.getColorFromAttr
import org.thoughtcrime.securesms.util.GlowViewUtilities
import org.thoughtcrime.securesms.util.InputBarButtonImageViewContainer
import org.thoughtcrime.securesms.util.animateSizeChange
import org.thoughtcrime.securesms.util.getAccentColor
import org.thoughtcrime.securesms.util.toPx
import java.util.Date

class InputBarButton : RelativeLayout {
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var isSendButton = false
    private var hasOpaqueBackground = false
    @DrawableRes private var iconID = 0
    private var longPressCallback: Runnable? = null
    private var onDownTimestamp = 0L
    var onPress: (() -> Unit)? = null
    var onMove: ((MotionEvent) -> Unit)? = null
    var onCancel: ((MotionEvent) -> Unit)? = null
    var onUp: ((MotionEvent) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    companion object {
        const val animationDuration = 250.toLong()
        const val longPressDurationThreshold = 250L // ms
    }

    private val expandedImageViewPosition by lazy { PointF(0.0f, 0.0f) }
    private val collapsedImageViewPosition by lazy { PointF((expandedSize - collapsedSize) / 2, (expandedSize - collapsedSize) / 2) }
    private val backgroundColourId by lazy {
            if (hasOpaqueBackground) {
                R.attr.input_bar_button_background_opaque
            } else if (isSendButton) {
                R.attr.colorAccent
            } else {
                R.attr.input_bar_button_background
            }
    }

    val expandedSize by lazy { resources.getDimension(R.dimen.input_bar_button_expanded_size) }
    val collapsedSize by lazy { resources.getDimension(R.dimen.input_bar_button_collapsed_size) }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)

        setIconTintColour()
    }

    private val imageViewContainer by lazy {
        val result = InputBarButtonImageViewContainer(context)
        val size = collapsedSize.toInt()
        result.layoutParams = LayoutParams(size, size)
        result.setBackgroundResource(R.drawable.input_bar_button_background)
        result.mainColor = context.getColorFromAttr(backgroundColourId)
        if (hasOpaqueBackground) {
            result.strokeColor = context.getColorFromAttr(R.attr.input_bar_button_background_opaque_border)
        }
        result
    }

    private val imageView by lazy {
        val result = ImageView(context)
        val size = toPx(20, resources)
        result.layoutParams = LayoutParams(size, size)
        result.scaleType = ImageView.ScaleType.CENTER_INSIDE
        result.setImageResource(iconID)
        result
    }

    constructor(context: Context) : super(context) { throw IllegalAccessException("Use InputBarButton(context:iconID:) instead.") }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { throw IllegalAccessException("Use InputBarButton(context:iconID:) instead.") }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { throw IllegalAccessException("Use InputBarButton(context:iconID:) instead.") }

    constructor(context: Context,
                @DrawableRes iconID: Int,
                isSendButton: Boolean = false,
                hasOpaqueBackground: Boolean = false
    ) : super(context) {
        this.isSendButton = isSendButton
        this.iconID = iconID
        this.hasOpaqueBackground = hasOpaqueBackground
        val size = resources.getDimension(R.dimen.input_bar_button_expanded_size).toInt()
        val layoutParams = LayoutParams(size, size)
        this.layoutParams = layoutParams
        addView(imageViewContainer)
        imageViewContainer.x = collapsedImageViewPosition.x
        imageViewContainer.y = collapsedImageViewPosition.y
        imageViewContainer.addView(imageView)
        val imageViewLayoutParams = imageView.layoutParams as LayoutParams
        imageViewLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
        imageView.layoutParams = imageViewLayoutParams
        gravity = Gravity.TOP or Gravity.LEFT // Intentionally not Gravity.START
        isHapticFeedbackEnabled = true
        this.isEnabled = isSendButton // Only enable the send button by default
    }

    fun getIconID() = iconID

    /**
     * Swaps the icon with a small "flip": the old one spins and shrinks away, the new one springs in.
     * Used by the microphone button to switch between voice message and video circle.
     */
    fun setIconAnimated(@DrawableRes newIconID: Int) {
        if (newIconID == iconID) return
        iconID = newIconID
        imageView.animate().cancel()
        imageView.animate()
            .scaleX(0f).scaleY(0f).rotation(-90f).alpha(0f)
            .setDuration(110)
            .withEndAction {
                imageView.setImageResource(newIconID)
                imageView.rotation = 90f
                imageView.animate()
                    .scaleX(1f).scaleY(1f).rotation(0f).alpha(1f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(2.4f))
                    .start()
            }
            .start()
    }

    fun expand() {
        val backgroundFromColor = context.getColorFromAttr(backgroundColourId)
        val backgroundToColor   = context.getAccentColor()
        GlowViewUtilities.animateColorChange(imageViewContainer, backgroundFromColor, backgroundToColor)
        imageViewContainer.animateSizeChange(R.dimen.input_bar_button_collapsed_size, R.dimen.input_bar_button_expanded_size, animationDuration)
        animateImageViewContainerPositionChange(collapsedImageViewPosition, expandedImageViewPosition)
    }

    fun collapse() {
        val backgroundFromColor = context.getAccentColor()
        val backgroundToColor   = context.getColorFromAttr(backgroundColourId)
        GlowViewUtilities.animateColorChange(imageViewContainer, backgroundFromColor, backgroundToColor)
        imageViewContainer.animateSizeChange(R.dimen.input_bar_button_expanded_size, R.dimen.input_bar_button_collapsed_size, animationDuration)
        animateImageViewContainerPositionChange(expandedImageViewPosition, collapsedImageViewPosition)
    }

    private fun animateImageViewContainerPositionChange(startPosition: PointF, endPosition: PointF) {
        val animation = ValueAnimator.ofObject(PointFEvaluator(), startPosition, endPosition)
        animation.duration = animationDuration
        animation.addUpdateListener { animator ->
            val point = animator.animatedValue as PointF
            imageViewContainer.x = point.x
            imageViewContainer.y = point.y
        }
        animation.start()
    }

    // Tint the button icon the appropriate colour for the user's theme
    private fun setIconTintColour() {
        if (isEnabled) {
            imageView.imageTintList = if (isSendButton) {
                ColorStateList.valueOf(context.getColorFromAttr(R.attr.message_sent_text_color))
            } else {
                ColorStateList.valueOf(context.getColorFromAttr(R.attr.input_bar_button_text_color))
            }
        } else {
            // Use the greyed out colour from the user theme
            imageView.imageTintList = ColorStateList.valueOf(context.getColorFromAttr(R.attr.disabled))
        }
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Ensure disabled buttons don't respond to events.
        // Caution: We MUST return false here to propagate the event through to any other
        // clickable elements such as avatar icons or media elements we might want to click on.
        if (!this.isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> onCancel(event)
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        expand()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

        longPressCallback?.let { gestureHandler.removeCallbacks(it) }
        val newLongPressCallback = Runnable { onLongPress?.invoke() }
        this.longPressCallback = newLongPressCallback
        gestureHandler.postDelayed(newLongPressCallback, longPressDurationThreshold)
        onDownTimestamp = Date().time
    }

    private fun onMove(event: MotionEvent) {
        onMove?.invoke(event)
    }

    private fun onCancel(event: MotionEvent) {
        onCancel?.invoke(event)
        collapse()
        longPressCallback?.let { gestureHandler.removeCallbacks(it) }
    }

    private fun onUp(event: MotionEvent) {
        onUp?.invoke(event)
        collapse()
        if ((Date().time - onDownTimestamp) < longPressDurationThreshold) {
            longPressCallback?.let { gestureHandler.removeCallbacks(it) }
            onPress?.invoke()
        }
    }
}