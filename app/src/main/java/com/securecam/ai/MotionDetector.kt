package com.securecam.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Motion detection using pixel-level frame differencing.
 * Compares consecutive frames and calculates changed pixel percentage.
 * Fires callback when motion exceeds sensitivity threshold.
 */
class MotionDetector(
    private val sensitivity: Int = 30, // 1-100, higher = more sensitive
    private val listener: MotionListener
) {
    private val TAG = "MotionDetector"
    private var previousFrame: IntArray? = null
    private var isProcessing = false

    // Sensitivity converts to a pixel difference threshold (lower diff = more sensitive)
    private val diffThreshold: Int get() = (255 * (1.0 - sensitivity / 100.0) * 0.5 + 10).toInt()
    private val motionThresholdPercent: Double get() = 0.02 + (100 - sensitivity) / 100.0 * 0.08 // 2-10%

    private var lastMotionTime = 0L
    private val motionCooldownMs = 2000L // Min 2s between motion events

    // Motion score history for smoothing
    private val motionHistory = ArrayDeque<Float>(5)

    // Downscaled analysis size for performance
    private val ANALYSIS_WIDTH = 160
    private val ANALYSIS_HEIGHT = 90

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun processFrame(bitmap: Bitmap) {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            try {
                val scaled = Bitmap.createScaledBitmap(bitmap, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, false)
                val pixels = IntArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
                scaled.getPixels(pixels, 0, ANALYSIS_WIDTH, 0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
                scaled.recycle()

                val prev = previousFrame
                if (prev != null && prev.size == pixels.size) {
                    val motionScore = calculateMotionScore(prev, pixels)
                    updateMotionHistory(motionScore)
                    val smoothedScore = motionHistory.average().toFloat()

                    if (smoothedScore > motionThresholdPercent) {
                        val now = System.currentTimeMillis()
                        if (now - lastMotionTime > motionCooldownMs) {
                            lastMotionTime = now
                            val regions = detectMotionRegions(prev, pixels)
                            withContext(Dispatchers.Main) {
                                listener.onMotionDetected(smoothedScore, regions)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            listener.onMotionScore(smoothedScore)
                        }
                    }
                }

                previousFrame = pixels
            } catch (e: Exception) {
                Log.e(TAG, "Motion detection error: ${e.message}")
            } finally {
                isProcessing = false
            }
        }
    }

    private fun calculateMotionScore(prev: IntArray, curr: IntArray): Float {
        var changedPixels = 0
        for (i in prev.indices) {
            val pR = Color.red(prev[i])
            val pG = Color.green(prev[i])
            val pB = Color.blue(prev[i])
            val cR = Color.red(curr[i])
            val cG = Color.green(curr[i])
            val cB = Color.blue(curr[i])

            val diff = (abs(pR - cR) + abs(pG - cG) + abs(pB - cB)) / 3
            if (diff > diffThreshold) changedPixels++
        }
        return changedPixels.toFloat() / prev.size
    }

    /**
     * Divide frame into a 4x3 grid and detect which cells have motion
     */
    private fun detectMotionRegions(prev: IntArray, curr: IntArray): List<MotionRegion> {
        val regions = mutableListOf<MotionRegion>()
        val gridCols = 4
        val gridRows = 3
        val cellW = ANALYSIS_WIDTH / gridCols
        val cellH = ANALYSIS_HEIGHT / gridRows

        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                var cellChanged = 0
                var cellTotal = 0
                for (y in row * cellH until (row + 1) * cellH) {
                    for (x in col * cellW until (col + 1) * cellW) {
                        val i = y * ANALYSIS_WIDTH + x
                        if (i < prev.size) {
                            val diff = (abs(Color.red(prev[i]) - Color.red(curr[i])) +
                                    abs(Color.green(prev[i]) - Color.green(curr[i])) +
                                    abs(Color.blue(prev[i]) - Color.blue(curr[i]))) / 3
                            if (diff > diffThreshold) cellChanged++
                            cellTotal++
                        }
                    }
                }
                val score = if (cellTotal > 0) cellChanged.toFloat() / cellTotal else 0f
                if (score > 0.05f) {
                    regions.add(MotionRegion(col, row, score))
                }
            }
        }
        return regions
    }

    private fun updateMotionHistory(score: Float) {
        if (motionHistory.size >= 5) motionHistory.removeFirst()
        motionHistory.addLast(score)
    }

    fun reset() {
        previousFrame = null
        motionHistory.clear()
    }

    fun release() {
        scope.cancel()
        reset()
    }

    data class MotionRegion(val col: Int, val row: Int, val score: Float)

    interface MotionListener {
        fun onMotionDetected(score: Float, regions: List<MotionRegion>)
        fun onMotionScore(score: Float)
    }
}
