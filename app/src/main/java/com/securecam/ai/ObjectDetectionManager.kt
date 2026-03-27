package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * YOLO26n — NMS-free CPU TFLite inference.
 * Input:  [1, 640, 640, 3] float32 normalised 0..1
 * Output: [1, 300, 6]      [x1,y1,x2,y2,conf,class_id]  (NMS-free)
 *
 * Model loading order:
 *   1. assets/yolo26n.tflite
 *   2. assets/yolo26n_float32.tflite
 *   3. filesDir/yolo26n.tflite (downloaded at runtime by YoloModelDownloader)
 *
 * GPU delegate NOT used (YOLO26 TFLite INT64 cast bug on Android GPU).
 */
class ObjectDetectionManager(
    private val context: Context,
    private val listener: ObjectDetectionListener
) {
    private val TAG = "YOLO26n"

    private val INPUT_SIZE        = 640
    private val MAX_DETS          = 300
    private val CONF_THRESH       = 0.35f   // slightly lower → catch more objects
    private val DETECTION_INTERVAL = 500L

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var isProcessing  = false
    private var lastRunTime   = 0L

    private val cdCritical = 3_000L
    private val cdWarning  = 5_000L
    private val cdNormal   = 8_000L
    private val labelLastAlert = mutableMapOf<String, Long>()
    private val classLastSeen  = mutableMapOf<Int, Long>()
    private val REAPPEAR_GAP   = 30_000L

    private val COCO_LABELS = mapOf(
        0  to "Person",     1  to "Bicycle",   2  to "Car",
        3  to "Motorcycle", 5  to "Bus",        7  to "Truck",
        15 to "Cat",        16 to "Dog",        17 to "Horse",
        18 to "Sheep",      19 to "Cow",
        24 to "Backpack",   28 to "Suitcase"
    )

    fun initialize() {
        val interp = tryLoadFromAssets("yolo26n.tflite")
            ?: tryLoadFromAssets("yolo26n_float32.tflite")
            ?: tryLoadFromFiles("yolo26n.tflite")
            ?: tryLoadFromFiles("yolo26n_float32.tflite")

        if (interp != null) {
            interpreter   = interp
            isInitialized = true
            Log.d(TAG, "YOLO26n loaded ✅  [${INPUT_SIZE}×${INPUT_SIZE}, CPU, 4 threads]")
        } else {
            Log.w(TAG, "YOLO26n model not found — object detection disabled. " +
                    "Add yolo26n.tflite to assets/ or let YoloModelDownloader fetch it.")
            isInitialized = false
        }
    }

    private fun tryLoadFromAssets(name: String): Interpreter? = try {
        val buf = FileUtil.loadMappedFile(context, name)
        Interpreter(buf, Interpreter.Options().apply { numThreads = 4 })
    } catch (_: Exception) { null }

    private fun tryLoadFromFiles(name: String): Interpreter? = try {
        val file = File(context.filesDir, name)
        if (!file.exists()) return null
        val buf = FileInputStream(file).channel
            .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        Interpreter(buf, Interpreter.Options().apply { numThreads = 4 })
    } catch (_: Exception) { null }

    fun processFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (isProcessing || !isInitialized || now - lastRunTime < DETECTION_INTERVAL) return
        isProcessing = true; lastRunTime = now

        Thread {
            try {
                val results = runInference(bitmap)
                if (results.isNotEmpty()) {
                    listener.onObjectsDetected(results)
                    results.forEach { handleAlert(it, now) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error: ${e.message}")
            } finally { isProcessing = false }
        }.start()
    }

    private fun runInference(src: Bitmap): List<DetectedObject> {
        val scaled = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        val buf    = bitmapToBuffer(scaled)
        scaled.recycle()

        val out = Array(1) { Array(MAX_DETS) { FloatArray(6) } }
        interpreter!!.run(buf, out)

        return out[0].mapNotNull { row ->
            val conf = row[4]; val cid = row[5].toInt()
            if (conf < CONF_THRESH || cid !in COCO_LABELS) return@mapNotNull null
            DetectedObject(
                classId = cid, label = COCO_LABELS[cid]!!,
                group = groupFor(cid), confidence = conf,
                priority = priorityFor(cid, conf),
                box = RectF(row[0].coerceIn(0f,1f), row[1].coerceIn(0f,1f),
                            row[2].coerceIn(0f,1f), row[3].coerceIn(0f,1f))
            )
        }.sortedByDescending { it.confidence }.take(20)
    }

    private fun handleAlert(det: DetectedObject, now: Long) {
        val cd = when (det.priority) { "critical" -> cdCritical; "warning" -> cdWarning; else -> cdNormal }
        if (now - (labelLastAlert[det.label] ?: 0L) < cd) return
        val lastSeen   = classLastSeen[det.classId] ?: 0L
        val reappeared = lastSeen > 0 && (now - lastSeen) > REAPPEAR_GAP
        classLastSeen[det.classId] = now; labelLastAlert[det.label] = now
        listener.onObjectAlert(det.label, det.group, det.confidence, det.priority, reappeared)
    }

    private fun priorityFor(cid: Int, conf: Float) = when (cid) {
        0             -> if (conf > 0.75f) "critical" else "warning"
        1, 2, 3, 5, 7 -> "warning"
        else          -> "normal"
    }

    private fun groupFor(cid: Int) = when (cid) {
        0              -> "person"
        1, 2, 3, 5, 7  -> "vehicle"
        24, 28         -> "package"
        15,16,17,18,19 -> "animal"
        else           -> "object"
    }

    private fun bitmapToBuffer(bmp: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val px  = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in px) { buf.putFloat((p shr 16 and 0xFF)/255f); buf.putFloat((p shr 8 and 0xFF)/255f); buf.putFloat((p and 0xFF)/255f) }
        return buf.apply { rewind() }
    }

    fun release() { interpreter?.close(); interpreter = null }

    data class DetectedObject(
        val classId: Int, val label: String, val group: String,
        val confidence: Float, val priority: String, val box: RectF
    )
    interface ObjectDetectionListener {
        fun onObjectsDetected(objects: List<DetectedObject>)
        fun onObjectAlert(label: String, group: String, confidence: Float, priority: String, reappeared: Boolean)
    }
}
