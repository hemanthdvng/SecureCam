package com.securecam.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Downloads yolov10n.tflite from the official Hugging Face Hub on first launch
 * and caches it in the app's files directory.
 *
 * Usage (from a coroutine):
 *   val modelFile = YoloModelDownloader.ensure(context)
 *
 * The ObjectDetectionManager uses FileUtil.loadMappedFile() which reads from
 * assets/ by default. If you bundle the model in assets at build time this
 * downloader is not needed — it exists purely as a runtime fallback.
 */
object YoloModelDownloader {
    private const val TAG      = "YoloDownloader"
    private const val FILENAME = "yolov10n.tflite"
    // Official Hugging Face Hub release (6 MB quantised)
    private const val MODEL_URL =
        "https://huggingface.co/THUdyh/YOLOv10/resolve/main/yolov10n.tflite"

    suspend fun ensure(context: Context): File = withContext(Dispatchers.IO) {
        val dst = File(context.filesDir, FILENAME)
        if (dst.exists() && dst.length() > 1_000_000L) {
            Log.d(TAG, "Model already cached (${dst.length()/1024} KB)")
            return@withContext dst
        }
        Log.d(TAG, "Downloading YOLOv10-N …")
        try {
            URL(MODEL_URL).openStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Download complete (${dst.length()/1024} KB)")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            throw e
        }
        dst
    }
}
