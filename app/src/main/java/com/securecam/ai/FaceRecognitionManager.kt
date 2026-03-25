package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Generates 128-dim face embeddings using MobileFaceNet TFLite model.
 * Model file: assets/mobilefacenet.tflite OR app's files dir.
 * Falls back gracefully to null if model unavailable.
 */
class FaceRecognitionManager(private val context: Context) {

    private val TAG = "FaceRecognition"
    private val MODEL_NAME = "mobilefacenet.tflite"
    private val INPUT_SIZE = 112
    private val EMBEDDING_SIZE = 128

    private var interpreter: Interpreter? = null
    val isAvailable: Boolean get() = interpreter != null

    fun initialize() {
        interpreter = tryLoadFromAssets() ?: tryLoadFromFiles()
        if (interpreter == null) {
            Log.w(TAG, "Model not found — face recognition disabled. Add $MODEL_NAME to assets/ to enable.")
        } else {
            Log.d(TAG, "MobileFaceNet loaded successfully")
        }
    }

    private fun tryLoadFromAssets(): Interpreter? {
        return try {
            val afd = context.assets.openFd(MODEL_NAME)
            val buf = FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })
        } catch (e: Exception) { null }
    }

    private fun tryLoadFromFiles(): Interpreter? {
        return try {
            val file = File(context.filesDir, MODEL_NAME)
            if (!file.exists()) return null
            val buf = FileInputStream(file).channel
                .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })
        } catch (e: Exception) { null }
    }

    /**
     * Generate a 128-dim L2-normalized embedding for the given face crop.
     * Input should be a face crop (preferably aligned to face landmarks).
     */
    fun generateEmbedding(faceBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            val input = preprocessBitmap(resized)
            val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
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
            // Normalize to [-1, 1]
            buf.putFloat(((px shr 16 and 0xFF) / 255f) * 2f - 1f)
            buf.putFloat(((px shr 8  and 0xFF) / 255f) * 2f - 1f)
            buf.putFloat(((px        and 0xFF) / 255f) * 2f - 1f)
        }
        buf.rewind()
        return buf
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 1e-6f) FloatArray(v.size) { v[it] / norm } else v
    }

    /** Crop face region from full-frame bitmap using bounding box */
    fun cropFace(
        bitmap: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int,
        padding: Float = 0.2f
    ): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val fw = (right - left).toFloat(); val fh = (bottom - top).toFloat()
        val padX = (fw * padding).toInt(); val padY = (fh * padding).toInt()
        val x = maxOf(0, left - padX)
        val y = maxOf(0, top  - padY)
        val cw = minOf(w - x, right  - left + padX * 2)
        val ch = minOf(h - y, bottom - top  + padY * 2)
        return Bitmap.createBitmap(bitmap, x, y, cw, ch)
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
