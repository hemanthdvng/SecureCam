package com.securecam.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads the ArcFace MobileNet TFLite model on demand.
 *
 * Model: MobileNet V2 trained with ArcFace loss — 512-dim embeddings, 112×112 input.
 * ~4 MB download, stored in context.filesDir as "arcface_mobilenet.tflite".
 *
 * To use your own model:
 *   1. Convert to TFLite with input [1,112,112,3] float32, output [1,512] float32
 *   2. Place in app/src/main/assets/arcface_mobilenet.tflite  OR
 *   3. Update ARCFACE_URL to your own hosting URL
 */
object FaceModelDownloader {
    private val TAG = "FaceModelDL"
    const val ARCFACE_NAME = "arcface_mobilenet.tflite"
    const val MOBILEFACENET_NAME = "mobilefacenet.tflite"

    // ── Hosted model URL ── Replace with your own if hosting elsewhere ─────
    // This points to a MobileFaceNet model trained with ArcFace loss (512-dim)
    // If this URL is unavailable, place the model manually in assets/
    private const val ARCFACE_URL =
        "https://github.com/hemanthdvng/SecureCam/releases/download/models/arcface_mobilenet.tflite"
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun downloadArcFace(
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val dest = File(context.filesDir, ARCFACE_NAME)
        if (dest.exists() && dest.length() > 100_000L) {
            Log.d(TAG, "ArcFace already present (${dest.length()} bytes)")
            return@withContext Result.success(dest)
        }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val resp = client.newCall(Request.Builder().url(ARCFACE_URL).build()).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${resp.code}: ${resp.message}"))
            }
            val body  = resp.body ?: return@withContext Result.failure(Exception("Empty response"))
            val total = body.contentLength()
            var done  = 0L
            FileOutputStream(dest).use { out ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(((done * 100) / total).toInt())
                    }
                }
            }
            if (dest.length() < 100_000L) {
                dest.delete()
                return@withContext Result.failure(Exception("Download incomplete"))
            }
            Log.d(TAG, "ArcFace downloaded: ${dest.length()} bytes")
            Result.success(dest)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            dest.delete()
            Result.failure(e)
        }
    }

    fun isArcFaceAvailable(context: Context): Boolean {
        val inFiles  = File(context.filesDir, ARCFACE_NAME).let { it.exists() && it.length() > 100_000L }
        val inAssets = try { context.assets.openFd(ARCFACE_NAME).close(); true } catch (_: Exception) { false }
        return inFiles || inAssets
    }

    fun isMobileFaceNetAvailable(context: Context): Boolean {
        val inAssets = try { context.assets.openFd(MOBILEFACENET_NAME).close(); true } catch (_: Exception) { false }
        val inFiles  = File(context.filesDir, MOBILEFACENET_NAME).let { it.exists() && it.length() > 100_000L }
        return inAssets || inFiles
    }
}
