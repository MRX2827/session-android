package org.thoughtcrime.securesms.videocircle

import android.annotation.SuppressLint
import android.content.Context
import android.util.Rational
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.guava.await
import org.session.libsignal.utilities.Log
import java.io.File

/**
 * Records a square video circle with CameraX: live preview + 480p video + AAC audio, cropped to a
 * 1:1 viewport so what the user sees in the round preview is what gets recorded.
 *
 * Lifecycle: [bind] -> [start] -> [stop] (or [cancel]) -> [release]. All callbacks run on main.
 */
class CircleVideoRecorder(context: Context) {

    class Output(val file: File, val length: Long, val durationMs: Long)

    private val appContext = context.applicationContext

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var cameraPreview: Preview? = null
    private var usingFrontCamera = false
    private var hasBothCameras = false
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var file: File? = null
    private var done: CompletableDeferred<Output?>? = null
    private var started = false

    @Volatile
    private var cancelled = false

    val canSwitchCamera: Boolean
        get() = hasBothCameras && cameraPreview != null && started && recording != null

    suspend fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val cameraProvider = ProcessCameraProvider.getInstance(appContext).await()
        provider = cameraProvider

        this.owner = owner

        val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        hasBothCameras = hasFront && hasBack
        usingFrontCamera = hasFront && (!hasBack || VideoCircleUtils.useFrontCamera(appContext))

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.SD, Quality.LOWEST),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .setTargetVideoEncodingBitRate(VideoCircleUtils.VIDEO_BITRATE)
            .build()
        val capture = VideoCapture.withOutput(recorder)
        videoCapture = capture
        bindCamera(cameraProvider, owner, usingFrontCamera, previewView, capture)
    }

    /** Rebinds the same VideoCapture while leaving its MP4 recording and microphone active. */
    fun switchCamera(previewView: PreviewView): Boolean {
        if (!canSwitchCamera) return false
        val cameraProvider = provider ?: return false
        val lifecycleOwner = owner ?: return false
        val capture = videoCapture ?: return false
        val before = usingFrontCamera
        unbindCamera()
        return try {
            bindCamera(cameraProvider, lifecycleOwner, !before, previewView, capture)
            usingFrontCamera = !before
            VideoCircleUtils.setUseFrontCamera(appContext, usingFrontCamera)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to switch video circle camera", error)
            try {
                bindCamera(cameraProvider, lifecycleOwner, before, previewView, capture)
            } catch (restoreError: Exception) {
                Log.e(TAG, "Unable to restore video circle camera", restoreError)
            }
            false
        }
    }

    private fun bindCamera(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        front: Boolean,
        previewView: PreviewView,
        capture: VideoCapture<Recorder>,
    ) {
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .setViewPort(
                ViewPort.Builder(Rational(1, 1), rotation)
                    .setScaleType(ViewPort.FILL_CENTER)
                    .build()
            )
            .build()

        try {
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, group)
        } catch (error: Exception) {
            cameraProvider.unbind(preview, capture)
            throw error
        }
        cameraPreview = preview
    }

    private fun unbindCamera() {
        val preview = cameraPreview ?: return
        val capture = videoCapture ?: return
        provider?.unbind(preview, capture)
        cameraPreview = null
    }

    /** Starts recording. [onStarted] receives null on success, or the failure. */
    @OptIn(ExperimentalPersistentRecording::class)
    @SuppressLint("MissingPermission") // checked by the caller before binding
    fun start(onStarted: (Throwable?) -> Unit) {
        val capture = videoCapture ?: return onStarted(IllegalStateException("Camera not bound"))
        val target = File.createTempFile("video_circle_", ".mp4", appContext.cacheDir)
        file = target
        val result = CompletableDeferred<Output?>()
        done = result

        val options = FileOutputOptions.Builder(target)
            .setDurationLimitMillis(VideoCircleUtils.MAX_DURATION_MS + 1_000)
            .setFileSizeLimit(VideoCircleUtils.MAX_FILE_BYTES)
            .build()

        recording = capture.output
            .prepareRecording(appContext, options)
            .withAudioEnabled()
            .asPersistentRecording()
            .start(ContextCompat.getMainExecutor(appContext)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        started = true
                        onStarted(null)
                    }

                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        val error = event.error
                        // Hitting the duration / size limit still leaves a perfectly valid file.
                        val ok = error == VideoRecordEvent.Finalize.ERROR_NONE ||
                                error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                                error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                        if (ok && !cancelled) {
                            result.complete(
                                Output(
                                    file = target,
                                    length = target.length(),
                                    durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                                )
                            )
                        } else {
                            if (!ok) Log.e(TAG, "Video circle finalize error: $error", event.cause)
                            target.delete()
                            if (!started) onStarted(event.cause ?: RuntimeException("Recording error $error"))
                            result.complete(null)
                        }
                    }
                    else -> Unit
                }
            }
    }

    /** Stops recording and returns the finished file, or null if recording failed. */
    suspend fun stop(): Output? {
        recording?.stop()
        recording = null
        unbindCamera()
        return done?.await()
    }

    /** Stops recording and throws the file away. */
    fun cancel() {
        cancelled = true
        recording?.stop()
        recording = null
        // If recording never began there is no Finalize event to delete the temp file for us.
        if (!started) file?.delete()
    }

    fun release() {
        recording?.stop()
        recording = null
        unbindCamera()
        owner = null
        provider = null
        videoCapture = null
    }

    companion object {
        private const val TAG = "CircleVideoRecorder"
    }
}
