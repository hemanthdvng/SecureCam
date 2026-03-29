package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.securecam.utils.AppPreferences
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.*

/**
 * FaceRecognitionManager v4:
 * - tryLoadFromAssets: switched from openFd+mmap → assets.open().readBytes()
 *   (mmap via openFd fails silently on some OEMs / API levels)
 * - catch(Throwable) instead of catch(Exception) so native errors are logged
 * - useXNNPACK disabled (XNNPACK explicit flag causes crashes on some ABIs;
 *   TFLite 2.x enables it by default anyway when supported)
 * - Supports MobileFaceNet (128-dim) and ArcFace (512-dim)
 */
class FaceRecognitionManager(private val context: Context) {

    private val TAG = "FaceRecognition"

    private val MOBILEFACENET_NAME = "mobilefacenet.tflite"
    private val ARCFACE_NAME       = "arcface_mobilenet.tflite"
    private val INPUT_SIZE         = 112

    private var interpreter: Interpreter? = null
    private var embeddingSize = 128

    val isAvailable: Boolean get() = interpreter != null
    val currentModelName: String
        get() = if (AppPreferences.faceModelType == 1 && embeddingSize == 512) "ArcFace" else "MobileFaceNet"

    fun initialize() {
        val opts = buildInterpreterOptions()
        when (AppPreferences.faceModelType) {
            1 -> {
                val arcInterp = tryLoad(ARCFACE_NAME, opts)
                if (arcInterp != null) {
                    interpreter = arcInterp
                    embeddingSize = 512
                    Log.d(TAG, "Loaded ArcFace (512-dim)")
                } else {
                    Log.w(TAG, "ArcFace not found — falling back to MobileFaceNet")
                    interpreter = tryLoad(MOBILEFACENET_NAME, opts)
                    embeddingSize = 128
                }
            }
            else -> {
                interpreter = tryLoad(MOBILEFACENET_NAME, opts)
                embeddingSize = 128
                Log.d(TAG, if (interpreter != null) "Loaded MobileFaceNet (128-dim)" else "No TFLite model found")
            }
        }
    }

    /**
     * 4 threads for inference performance.
     * useXNNPACK = false: the explicit flag crashes on some ABIs.
     * TFLite 2.x auto-enables XNNPACK when the device supports it.
     */
    private fun buildInterpreterOptions(): Interpreter.Options =
        Interpreter.Options().apply {
            numThreads = 4
            useXNNPACK = false
        }

    private fun tryLoad(name: String, opts: Interpreter.Options): Interpreter? =
        tryLoadFromAssets(name, opts) ?: tryLoadFromFiles(name, opts)

    /**
     * Load model from assets using assets.open() + readBytes().
     * More compatible than openFd + memory-mapping, which fails silently
     * on certain OEM ROMs and API levels.
     * Catches Throwable (not just Exception) so UnsatisfiedLinkError and
     * other native failures are logged rather than swallowed.
     */
    private fun tryLoadFromAssets(name: String, opts: Interpreter.Options): Interpreter? = try {
        val bytes = context.assets.open(name).use { it.readBytes() }
        val buf   = ByteBuffer.wrap(bytes)
        Interpreter(buf, opts)
    } catch (e: Throwable) {
        Log.e(TAG, "tryLoadFromAssets($name) failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun tryLoadFromFiles(name: String, opts: Interpreter.Options): Interpreter? = try {
        val file = File(context.filesDir, name)
        if (!file.exists()) null
        else {
            val buf = FileInputStream(file).channel
                .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            Interpreter(buf, opts)
        }
    } catch (e: Throwable) {
        Log.e(TAG, "tryLoadFromFiles($name) failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * Generate L2-normalized embedding.
     * Input must be a face crop — use cropAndAlignFace() or cropFace() first.
     * Returns null only if no model is loaded.
     */
    fun generateEmbedding(faceBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            val input   = preprocessBitmap(resized)
            val output  = Array(1) { FloatArray(embeddingSize) }
            interp.run(input, output)
            l2Normalize(output[0])
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed: ${e.message}")
            null
        }
    }

    /**
     * Normalize pixel values to [-1, 1] — required by both MobileFaceNet and ArcFace.
     * Formula: (pixel / 255.0) * 2 - 1
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            buf.putFloat(((px shr 16 and 0xFF) / 255f) * 2f - 1f)  // R
            buf.putFloat(((px shr  8 and 0xFF) / 255f) * 2f - 1f)  // G
            buf.putFloat(((px        and 0xFF) / 255f) * 2f - 1f)  // B
        }
        buf.rewind()
        return buf
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 1e-6f) FloatArray(v.size) { v[it] / norm } else v
    }

    fun cropAndAlignFace(
        bitmap: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int,
        leftEyeX: Float, leftEyeY: Float,
        rightEyeX: Float, rightEyeY: Float,
        padding: Float = 0.25f
    ): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val fw = (right - left).toFloat(); val fh = (bottom - top).toFloat()
        val padX = (fw * padding).toInt(); val padY = (fh * padding).toInt()
        val x  = maxOf(0, left - padX)
        val y  = maxOf(0, top  - padY)
        val cw = minOf(w - x, right  - left + padX * 2)
        val ch = minOf(h - y, bottom - top  + padY * 2)
        if (cw <= 0 || ch <= 0) return bitmap

        val cropped = Bitmap.createBitmap(bitmap, x, y, cw, ch)

        val eyeDx = rightEyeX - leftEyeX
        val eyeDy = rightEyeY - leftEyeY
        val angleDeg = Math.toDegrees(atan2(eyeDy.toDouble(), eyeDx.toDouble())).toFloat()

        if (abs(angleDeg) < 1.5f) return cropped

        return try {
            val matrix = Matrix().apply { postRotate(-angleDeg, cw / 2f, ch / 2f) }
            Bitmap.createBitmap(cropped, 0, 0, cw, ch, matrix, true)
        } catch (e: Exception) { cropped }
    }

    fun cropFace(
        bitmap: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int,
        padding: Float = 0.25f
    ): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val fw = (right - left).toFloat()
        val fh = (bottom - top).toFloat()
        val padX = (fw * padding).toInt(); val padY = (fh * padding).toInt()
        val x  = maxOf(0, left - padX)
        val y  = maxOf(0, top  - padY)
        val cw = minOf(w - x, right  - left + padX * 2)
        val ch = minOf(h - y, bottom - top  + padY * 2)
        if (cw <= 0 || ch <= 0) return bitmap
        return Bitmap.createBitmap(bitmap, x, y, cw, ch)
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
