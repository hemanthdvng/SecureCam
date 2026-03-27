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
 * FaceRecognitionManager v2:
 * - Supports MobileFaceNet (128-dim, assets/mobilefacenet.tflite)
 * - Supports ArcFace (512-dim, assets/arcface_mobilenet.tflite or filesDir)
 * - cropAndAlignFace(): rotates crop so eyes are horizontal — major accuracy improvement
 * - Model selected via AppPreferences.faceModelType (0=MobileFaceNet, 1=ArcFace)
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
        when (AppPreferences.faceModelType) {
            1 -> {
                // Try ArcFace first, fall back to MobileFaceNet
                val arcInterp = tryLoad(ARCFACE_NAME)
                if (arcInterp != null) {
                    interpreter = arcInterp
                    embeddingSize = 512
                    Log.d(TAG, "Loaded ArcFace (512-dim)")
                } else {
                    Log.w(TAG, "ArcFace not found — falling back to MobileFaceNet")
                    interpreter = tryLoad(MOBILEFACENET_NAME)
                    embeddingSize = 128
                }
            }
            else -> {
                interpreter = tryLoad(MOBILEFACENET_NAME)
                embeddingSize = 128
                Log.d(TAG, if (interpreter != null) "Loaded MobileFaceNet (128-dim)" else "No model found")
            }
        }
    }

    private fun tryLoad(name: String): Interpreter? = tryLoadFromAssets(name) ?: tryLoadFromFiles(name)

    private fun tryLoadFromAssets(name: String): Interpreter? = try {
        val afd = context.assets.openFd(name)
        val buf = FileInputStream(afd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })
    } catch (e: Exception) { null }

    private fun tryLoadFromFiles(name: String): Interpreter? = try {
        val file = File(context.filesDir, name)
        if (!file.exists()) null
        else {
            val buf = FileInputStream(file).channel
                .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })
        }
    } catch (e: Exception) { null }

    /**
     * Generate L2-normalized embedding.
     * Compatible with both MobileFaceNet (128-dim) and ArcFace (512-dim).
     * Both use 112x112 input, [-1,1] normalization.
     */
    fun generateEmbedding(faceBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            val input  = preprocessBitmap(resized)
            val output = Array(1) { FloatArray(embeddingSize) }
            interp.run(input, output)
            l2Normalize(output[0])
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed: ${e.message}")
            null
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            buf.putFloat(((px shr 16 and 0xFF) / 255f) * 2f - 1f)
            buf.putFloat(((px shr  8 and 0xFF) / 255f) * 2f - 1f)
            buf.putFloat(((px        and 0xFF) / 255f) * 2f - 1f)
        }
        buf.rewind()
        return buf
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 1e-6f) FloatArray(v.size) { v[it] / norm } else v
    }

    /**
     * Crop + align face using eye landmark positions.
     * Rotates so both eyes are at the same height — significantly improves recognition
     * for tilted/profile faces.
     */
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

        // Angle between eyes — rotate so they're horizontal
        val eyeDx = rightEyeX - leftEyeX
        val eyeDy = rightEyeY - leftEyeY
        val angleDeg = Math.toDegrees(atan2(eyeDy.toDouble(), eyeDx.toDouble())).toFloat()

        if (abs(angleDeg) < 1.5f) return cropped   // negligible tilt, skip rotation

        return try {
            val matrix = Matrix().apply { postRotate(-angleDeg, cw / 2f, ch / 2f) }
            Bitmap.createBitmap(cropped, 0, 0, cw, ch, matrix, true)
        } catch (e: Exception) { cropped }
    }

    /** Crop without alignment (fallback when landmarks unavailable) */
    fun cropFace(
        bitmap: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int,
        padding: Float = 0.25f
    ): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val fw = (right - left).toFloat(); val fh = (bottom - top).toFloat()
        val padX = (fw * padding).toInt(); val padY = (fh * padding).toInt()
        val x  = maxOf(0, left - padX)
        val y  = maxOf(0, top  - padY)
        val cw = minOf(w - x, right  - left + padX * 2)
        val ch = minOf(h - y, bottom - top  + padY * 2)
        return Bitmap.createBitmap(bitmap, x, y, cw, ch)
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
