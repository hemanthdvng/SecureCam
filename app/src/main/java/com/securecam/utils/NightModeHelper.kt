package com.securecam.utils

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import com.securecam.utils.AppPreferences
import kotlinx.coroutines.*

/**
 * Handles automatic night mode detection and camera exposure adjustment.
 * Analyzes frame brightness and switches camera parameters accordingly.
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class NightModeHelper(private val listener: NightModeListener) {

    private val TAG = "NightModeHelper"
    private var isNightMode = false
    private var camera: Camera? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Auto-detect config
    private val DARK_THRESHOLD = 60       // Average pixel value below this → night mode
    private val BRIGHT_THRESHOLD = 90     // Average pixel value above this → day mode
    private var lastCheckTime = 0L
    private val checkIntervalMs = 3000L   // Check every 3 seconds

    // Hysteresis counter (prevent flickering)
    private var darkFrameCount = 0
    private var brightFrameCount = 0
    private val hysteresisCount = 3

    fun attachCamera(cam: Camera) {
        camera = cam
        // Apply saved preference on attach
        if (AppPreferences.nightModeEnabled && !AppPreferences.autoNightModeEnabled) {
            applyNightMode(true)
        }
    }

    fun analyzeFrameBrightness(bitmap: Bitmap) {
        if (!AppPreferences.autoNightModeEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastCheckTime < checkIntervalMs) return
        lastCheckTime = now

        scope.launch {
            val brightness = calculateAverageBrightness(bitmap)
            Log.d(TAG, "Frame brightness: $brightness, nightMode: $isNightMode")

            withContext(Dispatchers.Main) {
                when {
                    brightness < DARK_THRESHOLD -> {
                        darkFrameCount++
                        brightFrameCount = 0
                        if (darkFrameCount >= hysteresisCount && !isNightMode) {
                            applyNightMode(true)
                        }
                    }
                    brightness > BRIGHT_THRESHOLD -> {
                        brightFrameCount++
                        darkFrameCount = 0
                        if (brightFrameCount >= hysteresisCount && isNightMode) {
                            applyNightMode(false)
                        }
                    }
                    else -> {
                        // In the middle zone, don't change
                        darkFrameCount = 0
                        brightFrameCount = 0
                    }
                }
            }
        }
    }

    private fun calculateAverageBrightness(bitmap: Bitmap): Int {
        // Sample a grid of pixels for efficiency
        val sampleSize = 20
        val stepX = bitmap.width / sampleSize
        val stepY = bitmap.height / sampleSize
        var totalBrightness = 0L
        var count = 0

        for (x in 0 until sampleSize) {
            for (y in 0 until sampleSize) {
                val pixel = bitmap.getPixel(x * stepX, y * stepY)
                val r = (pixel shr 16 and 0xFF)
                val g = (pixel shr 8 and 0xFF)
                val b = (pixel and 0xFF)
                // Luminance formula
                totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                count++
            }
        }

        return if (count > 0) (totalBrightness / count).toInt() else 128
    }

    fun applyNightMode(enable: Boolean) {
        isNightMode = enable
        val cam = camera ?: return

        try {
            if (enable) {
                // Increase exposure and ISO for night
                cam.cameraControl.setExposureCompensationIndex(3) // +3 EV
                Log.d(TAG, "Night mode ON: increased exposure")
            } else {
                // Reset to auto exposure
                cam.cameraControl.setExposureCompensationIndex(0)
                Log.d(TAG, "Night mode OFF: auto exposure")
            }

            listener.onNightModeChanged(enable)
            AppPreferences.nightModeEnabled = enable
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply night mode: ${e.message}")
        }
    }

    fun toggleNightMode() {
        applyNightMode(!isNightMode)
    }

    fun isNightModeActive() = isNightMode

    fun release() {
        scope.cancel()
        camera = null
    }

    interface NightModeListener {
        fun onNightModeChanged(isNight: Boolean)
    }
}
