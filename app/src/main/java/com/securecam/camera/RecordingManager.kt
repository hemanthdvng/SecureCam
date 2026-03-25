package com.securecam.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaActionSound
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
 * Manages automatic + manual recording using CameraX VideoCapture.
 * Triggered by motion score crossing a threshold.
 * Saves to user-chosen directory (Movies/SecureCam by default).
 */
class RecordingManager(
    private val context: Context,
    private val listener: RecordingListener
) {
    private val TAG = "RecordingManager"
    private val recordingExecutor = Executors.newSingleThreadExecutor()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isRecording = false

    // Auto-recording logic
    private var motionStopTime = 0L
    private val MOTION_STOP_DELAY_MS = 10_000L  // stop 10s after motion ends
    private val AUTO_MOTION_THRESHOLD = 0.35f

    // Sound for shutter feedback
    private val shutterSound by lazy { MediaActionSound() }

    /**
     * Called once from CameraActivity after camera is bound.
     * Returns the VideoCapture use case to bind alongside Preview + Analysis.
     */
    fun buildVideoCapture(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .setExecutor(recordingExecutor)
            .build()
        val vc = VideoCapture.withOutput(recorder)
        videoCapture = vc
        return vc
    }

    fun startRecording(outputDir: File) {
        if (isRecording) return
        val vc = videoCapture ?: run {
            Log.w(TAG, "VideoCapture not ready")
            listener.onRecordingError("Recording not available")
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SecureCam_$timestamp.mp4"

        try {
            outputDir.mkdirs()
            val outputFile = File(outputDir, fileName)

            val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()

            activeRecording = vc.output
                .prepareRecording(context, fileOutputOptions)
                .apply { withAudioEnabled() }
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
                            listener.onRecordingStarted(outputFile.absolutePath)
                            Log.d(TAG, "Recording started: $fileName")
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                            if (event.hasError()) {
                                Log.e(TAG, "Recording error: ${event.error}")
                                listener.onRecordingError("Error code ${event.error}")
                            } else {
                                val size = event.outputResults.outputUri.toString()
                                Log.d(TAG, "Recording saved: $fileName")
                                listener.onRecordingStopped(outputFile.absolutePath)
                            }
                        }
                        else -> {}
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            listener.onRecordingError(e.message ?: "Unknown error")
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        try {
            activeRecording?.stop()
            activeRecording = null
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording error: ${e.message}")
            isRecording = false
        }
    }

    /** Call this from motion callback for auto-recording */
    fun onMotionScore(score: Float, outputDir: File, autoEnabled: Boolean) {
        if (!autoEnabled) return
        val hasMotion = score > AUTO_MOTION_THRESHOLD
        if (hasMotion) {
            motionStopTime = 0L
            if (!isRecording) startRecording(outputDir)
        } else if (isRecording) {
            if (motionStopTime == 0L) {
                motionStopTime = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - motionStopTime > MOTION_STOP_DELAY_MS) {
                stopRecording()
                motionStopTime = 0L
            }
        }
    }

    fun isRecording() = isRecording

    fun release() {
        stopRecording()
        shutterSound.release()
        recordingExecutor.shutdown()
    }

    interface RecordingListener {
        fun onRecordingStarted(path: String)
        fun onRecordingStopped(path: String)
        fun onRecordingError(msg: String)
    }
}
