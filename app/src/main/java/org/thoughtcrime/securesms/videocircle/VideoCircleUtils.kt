package org.thoughtcrime.securesms.videocircle

import android.content.Context
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideDeck
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the microphone button records on a long press. A short tap toggles between the two. */
enum class RecordingMode { VOICE, CIRCLE }

/**
 * Video circles ("кружки") travel as ordinary mp4 video attachments, so every other Session client
 * simply sees a normal video. We mark them with a filename prefix, which is part of the attachment
 * metadata and survives the trip, and render those as a round player.
 */
object VideoCircleUtils {
    const val FILENAME_PREFIX = "Session-VideoCircle_"
    const val MAX_DURATION_MS = 60_000L

    /** Stay safely below the attachment size limit: 60 s at ~1 Mbps is about 7.5 MB. */
    const val MAX_FILE_BYTES = 9_000_000L
    const val VIDEO_BITRATE = 900_000

    private const val PREFS = "video_circles"
    private const val KEY_MODE = "recording_mode_circle"
    private const val KEY_FRONT = "front_camera"

    fun getMode(context: Context): RecordingMode =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MODE, false))
            RecordingMode.CIRCLE else RecordingMode.VOICE

    fun setMode(context: Context, mode: RecordingMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODE, mode == RecordingMode.CIRCLE).apply()
    }

    fun useFrontCamera(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FRONT, true)

    fun setUseFrontCamera(context: Context, front: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FRONT, front).apply()
    }

    fun newFilename(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        return "$FILENAME_PREFIX$stamp.mp4"
    }

    fun isCircleFilename(name: String?): Boolean =
        name?.startsWith(FILENAME_PREFIX, ignoreCase = true) == true

    /** The circle video of a message, or null if the message is not a video circle. */
    fun findCircleSlide(deck: SlideDeck): Slide? {
        val slides = deck.slides
        if (slides.size != 1) return null
        val slide = slides.first()
        return if (slide.hasVideo() && isCircleFilename(slide.asAttachment().filename)) slide else null
    }
}
