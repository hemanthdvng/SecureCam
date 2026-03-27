package com.securecam.camera

import android.content.Context
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Smart recording manager:
 *  • Starts when motion score crosses threshold
 *  • Stops automatically when:
 *      a) Motion falls below threshold for MOTION_GRACE_MS (10 s default, max 15 s)
 *      b) MAX_DURATION_MS (15 s) hard cap reached
 *  • Uses SD quality for minimum storage footprint
 *  • Accessible from ViewerActivity via the recordings directory
 */
class RecordingManager(
    private val context: Context,
    private val listener: RecordingListener
) {
    private val TAG = "RecordingManager"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isRecording = false

    // ── Motion-driven stop thresholds ────────────────────────────────────────
    private val AUTO_MOTION_THRESHOLD  = 0.35f
    private val MOTION_GRACE_MS        = 10_000L  // stop 10 s after motion ceases
    private val MAX_DURATION_MS        = 15_000L  // hard cap per clip

    private var motionStopTime  = 0L
    private var recordingStartTime = 0L
    private var maxDurationRunnable: Runnable? = null

    private val shutterSound by lazy { MediaActionSound() }

    // ─────────────────────────────────────────────────────────────────────────
    fun buildVideoCapture(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            // SD quality → ~15-40 MB/min (vs ~150 MB/min for FHD)
            // Falls back to lowest available if SD not supported
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.SD, Quality.HD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .setExecutor(executor)
            .build()
        return VideoCapture.withOutput(recorder).also { videoCapture = it }
    }

    // ─────────────────────────────────────────────────────────────────────────
    fun startRecording(outputDir: File) {
        if (isRecording) return
        val vc = videoCapture ?: run {
            listener.onRecordingError("VideoCapture not ready")
            return
        }

        val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(outputDir.also { it.mkdirs() }, "SecureCam_$ts.mp4")

        try {
            activeRecording = vc.output
                .prepareRecording(context, FileOutputOptions.Builder(file).build())
                .apply { withAudioEnabled() }
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            recordingStartTime = System.currentTimeMillis()
                            shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
                            listener.onRecordingStarted(file.absolutePath)
                            Log.d(TAG, "Recording started → $file")
                            // Hard cap: stop after MAX_DURATION_MS regardless of motion
                            scheduleMaxDurationStop()
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            motionStopTime = 0L
                            cancelMaxDurationStop()
                            shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                            if (event.hasError()) {
                                Log.e(TAG, "Recording error: ${event.error}")
                                listener.onRecordingError("Error ${event.error}")
                            } else {
                                Log.d(TAG, "Saved: ${file.name}  ${file.length()/1024} KB")
                                listener.onRecordingStopped(file.absolutePath)
                            }
                        }
                        else -> {}
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Start failed: ${e.message}")
            listener.onRecordingError(e.message ?: "Unknown error")
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        cancelMaxDurationStop()
        try { activeRecording?.stop(); activeRecording = null }
        catch (e: Exception) { isRecording = false }
    }

    // ── Called on every motion score update from CameraActivity ──────────────
    fun onMotionScore(score: Float, outputDir: File, autoEnabled: Boolean) {
        if (!autoEnabled) return
        val hasMotion = score > AUTO_MOTION_THRESHOLD

        if (hasMotion) {
            motionStopTime = 0L
            if (!isRecording) startRecording(outputDir)
        } else if (isRecording) {
            if (motionStopTime == 0L) {
                motionStopTime = System.currentTimeMillis()
            } else {
                val elapsed = System.currentTimeMillis() - motionStopTime
                if (elapsed >= MOTION_GRACE_MS) stopRecording()
            }
        }
    }

    // ── Hard duration cap ─────────────────────────────────────────────────────
    private fun scheduleMaxDurationStop() {
        val r = Runnable {
            Log.d(TAG, "Max duration (${MAX_DURATION_MS}ms) reached — stopping")
            stopRecording()
        }
        maxDurationRunnable = r
        mainHandler.postDelayed(r, MAX_DURATION_MS)
    }

    private fun cancelMaxDurationStop() {
        maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        maxDurationRunnable = null
    }

    fun isRecording() = isRecording

    fun release() {
        stopRecording()
        cancelMaxDurationStop()
        shutterSound.release()
        executor.shutdown()
    }

    interface RecordingListener {
        fun onRecordingStarted(path: String)
        fun onRecordingStopped(path: String)
        fun onRecordingError(msg: String)
    }
}
