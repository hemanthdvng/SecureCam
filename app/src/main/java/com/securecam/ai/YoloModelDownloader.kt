package com.securecam.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Downloads yolo26n.tflite (float32, ~5 MB) from Hugging Face on first launch
 * and caches it in the app's files directory.
 *
 * Export command used to generate the model:
 *   from ultralytics import YOLO
 *   YOLO("yolo26n.pt").export(format="tflite")  # → yolo26n_float32.tflite
 *
 * Note: half-precision (float16) export is NOT used because the YOLO26 TFLite
 * GPU delegate has a known INT64 operator incompatibility on Android
 * (ultralytics/ultralytics#23282). float32 + CPU works perfectly.
 *
 * Usage from a coroutine:
 *   val ok = YoloModelDownloader.ensure(context)
 */
object YoloModelDownloader {
    private const val TAG      = "YOLO26Downloader"
    private const val FILENAME = "yolo26n.tflite"
    private const val MIN_SIZE = 1_000_000L  // 1 MB sanity check

    // Ultralytics official Hugging Face Hub — float32 TFLite export
    private const val MODEL_URL =
        "https://huggingface.co/Ultralytics/YOLO26/resolve/main/yolo26n_float32.tflite"

    /**
     * Ensures the model file exists and is valid.
     * Returns true on success, false if download failed.
     */
    suspend fun ensure(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dst = File(context.filesDir, FILENAME)
        if (dst.exists() && dst.length() > MIN_SIZE) {
            Log.d(TAG, "YOLO26n already cached (${dst.length() / 1024} KB)")
            return@withContext true
        }
        Log.d(TAG, "Downloading YOLO26n float32 TFLite (~5 MB)…")
        return@withContext try {
            URL(MODEL_URL).openStream().use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            }
            Log.d(TAG, "Download complete (${dst.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            dst.delete()
            false
        }
    }

    fun getCachedFile(context: Context): File = File(context.filesDir, FILENAME)
}
