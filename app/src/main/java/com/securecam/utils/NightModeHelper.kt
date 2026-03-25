package com.securecam.utils

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.Camera
import kotlinx.coroutines.*

/**
 * Fixed NightModeHelper:
 * - Clamps exposure index to the camera's supported range before applying
 * - Guards against null camera (pending state applied on attach)
 * - Auto-detect uses luminance correctly
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class NightModeHelper(private val listener: NightModeListener) {

    private val TAG = "NightModeHelper"
    private var isNightMode = false
    private var camera: Camera? = null
    private var pendingNightMode: Boolean? = null       // apply when camera attaches
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val DARK_THRESHOLD   = 55   // below → night
    private val BRIGHT_THRESHOLD = 85   // above → day

    private var lastCheckTime = 0L
    private val CHECK_INTERVAL_MS = 3000L

    private var darkFrames   = 0
    private var brightFrames = 0
    private val HYSTERESIS   = 3

    fun attachCamera(cam: Camera) {
        camera = cam
        // Apply any pending state set before camera was ready
        pendingNightMode?.let { apply(it) }
        pendingNightMode = null
        // Also apply saved preference
        if (AppPreferences.nightModeEnabled && !AppPreferences.autoNightModeEnabled) {
            apply(true)
        }
    }

    fun analyzeFrameBrightness(bitmap: Bitmap) {
        if (!AppPreferences.autoNightModeEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_INTERVAL_MS) return
        lastCheckTime = now

        scope.launch {
            val brightness = calcBrightness(bitmap)
            Log.d(TAG, "Brightness=$brightness nightMode=$isNightMode")
            withContext(Dispatchers.Main) {
                when {
                    brightness < DARK_THRESHOLD -> {
                        brightFrames = 0
                        if (++darkFrames >= HYSTERESIS && !isNightMode) apply(true)
                    }
                    brightness > BRIGHT_THRESHOLD -> {
                        darkFrames = 0
                        if (++brightFrames >= HYSTERESIS && isNightMode) apply(false)
                    }
                    else -> { darkFrames = 0; brightFrames = 0 }
                }
            }
        }
    }

    private fun calcBrightness(bitmap: Bitmap): Int {
        val stepX = maxOf(1, bitmap.width  / 20)
        val stepY = maxOf(1, bitmap.height / 20)
        var total = 0L; var count = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16 and 0xFF)
                val g = (p shr 8  and 0xFF)
                val b = (p        and 0xFF)
                total += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                count++
                y += stepY
            }
            x += stepX
        }
        return if (count > 0) (total / count).toInt() else 128
    }

    fun applyNightMode(enable: Boolean) = apply(enable)

    private fun apply(enable: Boolean) {
        val cam = camera
        if (cam == null) {
            pendingNightMode = enable   // will be applied when camera attaches
            return
        }
        try {
            // Clamp to the supported range — this is the key fix
            val range = cam.cameraInfo.exposureState.exposureCompensationRange
            val target = if (enable) {
                minOf(range.upper, 3)   // +3 EV for night, or max supported
            } else {
                0                       // reset to 0 (auto)
            }
            cam.cameraControl.setExposureCompensationIndex(target)
                .addListener({
                    Log.d(TAG, "Night mode ${if (enable) "ON" else "OFF"} — EV=$target")
                }, { it.run() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply night mode: ${e.message}")
        }
        isNightMode = enable
        listener.onNightModeChanged(enable)
        AppPreferences.nightModeEnabled = enable
    }

    fun toggleNightMode() = apply(!isNightMode)

    fun isNightModeActive() = isNightMode

    fun release() {
        scope.cancel()
        camera = null
    }

    interface NightModeListener {
        fun onNightModeChanged(isNight: Boolean)
    }
}
