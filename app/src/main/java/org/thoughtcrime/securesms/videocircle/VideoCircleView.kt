package org.thoughtcrime.securesms.videocircle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.RequestManager
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.getAccentColor
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * Round in-chat player for a video circle.
 *
 * Idle it shows the first frame as a circle with a play button and the duration. A tap "opens" it:
 * the circle springs slightly larger, the play button shrinks away, a progress ring fades in and the
 * video plays with sound. Tapping again pauses, and at the end (or when another circle starts) it
 * "closes" back to the idle state. Only one circle plays at a time.
 */
class VideoCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val sizePx = dp(200)
    private val inset = dp(4)

    private val clipped = FrameLayout(context)
    private val thumb = ImageView(context)
    private val texture = TextureView(context)
    private val playButton = FrameLayout(context)
    private val playIcon = ImageView(context)
    private val durationChip = TextView(context)
    private val ring = RingView(context, context.getAccentColor())

    private var player: ExoPlayer? = null
    private var uri: Uri? = null
    private var totalMs = 0L

    private val progressTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val duration = if (p.duration > 0) p.duration else totalMs
            if (duration > 0) {
                ring.progress = (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                durationChip.text = MediaUtil.getFormattedVoiceMessageDuration((duration - p.currentPosition).coerceAtLeast(0))
            }
            if (p.isPlaying) postOnAnimation(this)
        }
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = centerCrop(videoSize)

        override fun onRenderedFirstFrame() {
            thumb.animate().alpha(0f).setDuration(180).start()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) postOnAnimation(progressTick)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) stopAndReset()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Video circle playback error", error)
            stopAndReset()
        }
    }

    init {
        contentDescription = context.getString(R.string.video)

        clipped.apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            setBackgroundColor(Color.parseColor("#262626"))
        }
        addView(clipped, LayoutParams(sizePx - inset * 2, sizePx - inset * 2, Gravity.CENTER))

        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        clipped.addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clipped.addView(thumb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        playButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(120, 0, 0, 0))
        }
        playIcon.setImageResource(R.drawable.play)
        playIcon.setColorFilter(Color.WHITE)
        playButton.addView(playIcon, LayoutParams(dp(22), dp(22), Gravity.CENTER))
        clipped.addView(playButton, LayoutParams(dp(52), dp(52), Gravity.CENTER))

        durationChip.apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(130, 0, 0, 0))
            }
        }
        addView(
            durationChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(14)
                bottomMargin = dp(14)
            }
        )

        ring.alpha = 0f
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // region Binding

    fun bind(slide: Slide, glide: RequestManager) {
        val newUri = slide.uri ?: return
        // The row is re-bound for unrelated updates (reactions, read receipts...): leave playback alone.
        if (newUri == uri) return

        stopAndReset(animate = false)
        uri = newUri
        totalMs = 0L
        durationChip.visibility = GONE

        thumb.setImageDrawable(null)
        val storedThumbnail = slide.thumbnailUri
        if (storedThumbnail != null) {
            glide.load(DecryptableUri(storedThumbnail))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .centerCrop()
                .into(thumb)
        }

        // Duration, and the poster frame when there is no stored thumbnail (our own recordings are
        // plain files, and Glide cannot pull a frame out of a decrypted stream).
        loadMetadata(newUri, needPoster = storedThumbnail == null)
    }

    private fun loadMetadata(forUri: Uri, needPoster: Boolean) {
        val appContext = context.applicationContext
        thread(name = "VideoCircleMetadata") {
            // Not `use {}`: MediaMetadataRetriever only became Closeable in API 29 and minSdk is 26.
            val retriever = MediaMetadataRetriever()
            var ms: Long? = null
            var poster: Bitmap? = null
            try {
                retriever.setDataSource(appContext, forUri)
                ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (needPoster) {
                    poster = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read video circle metadata", e)
            } finally {
                runCatching { retriever.release() }
            }

            val durationMs = ms
            val posterFrame = poster
            post {
                if (uri != forUri) return@post
                if (durationMs != null) {
                    totalMs = durationMs
                    durationChip.text = MediaUtil.getFormattedVoiceMessageDuration(durationMs)
                    durationChip.visibility = VISIBLE
                }
                if (posterFrame != null) thumb.setImageBitmap(posterFrame)
            }
        }
    }

    // endregion

    // region Interaction

    /** True if the raw screen position of [event] lies on the round video. */
    fun containsRaw(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val radius = width / 2f
        val dx = event.rawX - (loc[0] + radius)
        val dy = event.rawY - (loc[1] + height / 2f)
        return dx * dx + dy * dy <= radius * radius
    }

    fun toggle() {
        val p = player
        when {
            p == null -> startPlayback()
            p.isPlaying -> pause()
            else -> resume()
        }
    }

    private fun startPlayback() {
        val source = uri ?: return
        // Only one circle talks at a time.
        activeView?.get()?.takeIf { it !== this }?.stopAndReset()
        activeView = WeakReference(this)

        val exo = ExoPlayer.Builder(context).build()
        player = exo
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        exo.setVideoTextureView(texture)
        exo.addListener(listener)
        exo.setMediaItem(MediaItem.fromUri(source))
        exo.prepare()
        exo.playWhenReady = true

        animateOpen()
    }

    private fun pause() {
        player?.pause()
        animatePlayButton(show = true)
    }

    private fun resume() {
        player?.play()
        animatePlayButton(show = false)
    }

    /** Back to the idle poster: stops and releases the player and plays the "close" animation. */
    fun stopAndReset(animate: Boolean = true) {
        removeCallbacks(progressTick)
        player?.let {
            it.removeListener(listener)
            it.clearVideoTextureView(texture)
            it.release()
        }
        player = null
        if (activeView?.get() === this) activeView = null

        ring.animate().cancel()
        ring.progress = 0f
        if (totalMs > 0) durationChip.text = MediaUtil.getFormattedVoiceMessageDuration(totalMs)

        if (animate && isAttachedToWindow) {
            thumb.animate().alpha(1f).setDuration(160).start()
            ring.animate().alpha(0f).setDuration(160).start()
            animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
            animatePlayButton(show = true)
        } else {
            animate().cancel()
            scaleX = 1f
            scaleY = 1f
            thumb.alpha = 1f
            ring.alpha = 0f
            playButton.animate().cancel()
            playButton.alpha = 1f
            playButton.scaleX = 1f
            playButton.scaleY = 1f
        }
    }

    /** Release everything; called when the row is recycled or detached. */
    fun release() = stopAndReset(animate = false)

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    // endregion

    // region Animations

    private fun animateOpen() {
        animatePlayButton(show = false)
        ring.animate().alpha(1f).setDuration(220).start()
        animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()
    }

    private fun animatePlayButton(show: Boolean) {
        playButton.animate().cancel()
        if (show) {
            playButton.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
        } else {
            playButton.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // endregion

    /**
     * TextureView stretches the video to the view. Undo the stretch and zoom so the picture
     * fills the circle without distortion (our own recordings are square, others may not be).
     */
    private fun centerCrop(size: VideoSize) {
        val videoWidth = size.width * size.pixelWidthHeightRatio
        val videoHeight = size.height.toFloat()
        if (videoWidth <= 0f || videoHeight <= 0f) return
        texture.post {
            val aspect = videoWidth / videoHeight
            val cx = texture.width / 2f
            val cy = texture.height / 2f
            val matrix = Matrix()
            if (aspect > 1f) matrix.setScale(aspect, 1f, cx, cy) else matrix.setScale(1f, 1f / aspect, cx, cy)
            texture.setTransform(matrix)
        }
    }

    private class RingView(context: Context, accent: Int) : View(context) {
        var progress: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        private val stroke = 3f * resources.displayMetrics.density
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = accent
        }
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            if (progress <= 0f) return
            val pad = stroke / 2f + 1f
            bounds.set(pad, pad, width - pad, height - pad)
            canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
        }
    }

    companion object {
        private const val TAG = "VideoCircleView"
        private var activeView: WeakReference<VideoCircleView>? = null
    }
}
