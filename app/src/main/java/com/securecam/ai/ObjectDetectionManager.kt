package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLOv10-N object detection with GPU acceleration.
 *
 * Model file: assets/yolov10n.tflite
 *   Input  : [1, 640, 640, 3] float32 (normalised 0..1)
 *   Output : [1, 300, 6]  — rows of [x1,y1,x2,y2,confidence,class_id]
 *
 * Detected categories (COCO subset):
 *   Person(0), Bicycle(1), Car(2), Motorcycle(3), Bus(5), Truck(7)
 *   Cat(15), Dog(16), Horse(17), Sheep(18), Cow(19)
 *   Backpack(24), Suitcase(28) — package proxies
 *
 * Smart logic:
 *   • Per-class deduplication with priority-aware cooldown
 *   • Reappearance detection (gap > 30 s = reappeared)
 *   • Priority: normal / warning / critical
 */
class ObjectDetectionManager(
    private val context: Context,
    private val listener: ObjectDetectionListener
) {
    private val TAG = "YOLOv10N"

    private val MODEL_FILE          = "yolov10n.tflite"
    private val INPUT_SIZE          = 640
    private val MAX_DETS            = 300
    private val CONF_THRESH         = 0.40f
    private val DETECTION_INTERVAL  = 500L

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isInitialized = false
    private var isProcessing  = false
    private var lastRunTime   = 0L

    // Deduplication cooldowns (ms) per priority tier
    private val cooldownCritical = 3_000L
    private val cooldownWarning  = 5_000L
    private val cooldownNormal   = 8_000L
    private val labelLastAlert   = mutableMapOf<String, Long>()
    private val classLastSeen    = mutableMapOf<Int, Long>()
    private val REAPPEAR_GAP_MS  = 30_000L

    private val COCO_LABELS = mapOf(
        0 to "Person",  1 to "Bicycle", 2 to "Car",  3 to "Motorcycle",
        5 to "Bus",     7 to "Truck",
        15 to "Cat",   16 to "Dog",    17 to "Horse", 18 to "Sheep", 19 to "Cow",
        24 to "Backpack", 28 to "Suitcase"
    )

    fun initialize() {
        try {
            val opts = Interpreter.Options().apply {
                numThreads = 2
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate(compat.bestOptionsForThisDevice)
                    addDelegate(gpuDelegate)
                    Log.d(TAG, "GPU delegate enabled")
                }
            }
            interpreter = Interpreter(FileUtil.loadMappedFile(context, MODEL_FILE), opts)
            isInitialized = true
            Log.d(TAG, "YOLOv10-N ready")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
            isInitialized = false
        }
    }

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
            } catch (e: Exception) { Log.e(TAG, "Inference: ${e.message}") }
            finally { isProcessing = false }
        }.start()
    }

    private fun runInference(src: Bitmap): List<DetectedObject> {
        val scaled = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        val buf    = bitmapToBuffer(scaled); scaled.recycle()
        val out    = Array(1) { Array(MAX_DETS) { FloatArray(6) } }
        interpreter!!.run(buf, out)

        return out[0].mapNotNull { row ->
            val conf    = row[4]; val cid = row[5].toInt()
            if (conf < CONF_THRESH || cid !in COCO_LABELS) return@mapNotNull null
            val box = RectF(row[0].coerceIn(0f,1f), row[1].coerceIn(0f,1f),
                            row[2].coerceIn(0f,1f), row[3].coerceIn(0f,1f))
            DetectedObject(cid, COCO_LABELS[cid]!!, groupFor(cid), conf, priorityFor(cid, conf), box)
        }.sortedByDescending { it.confidence }.take(20)
    }

    private fun handleAlert(det: DetectedObject, now: Long) {
        val cd = when (det.priority) { "critical" -> cooldownCritical; "warning" -> cooldownWarning; else -> cooldownNormal }
        if (now - (labelLastAlert[det.label] ?: 0L) < cd) return
        val lastSeen   = classLastSeen[det.classId] ?: 0L
        val reappeared = lastSeen > 0 && (now - lastSeen) > REAPPEAR_GAP_MS
        classLastSeen[det.classId] = now; labelLastAlert[det.label] = now
        listener.onObjectAlert(det.label, det.group, det.confidence, det.priority, reappeared)
    }

    private fun priorityFor(cid: Int, conf: Float) = when (cid) {
        0              -> if (conf > 0.75f) "critical" else "warning"
        1, 2, 3, 5, 7  -> "warning"
        else           -> "normal"
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

    fun release() { interpreter?.close(); gpuDelegate?.close() }

    data class DetectedObject(
        val classId: Int, val label: String, val group: String,
        val confidence: Float, val priority: String, val box: RectF
    )

    interface ObjectDetectionListener {
        fun onObjectsDetected(objects: List<DetectedObject>)
        fun onObjectAlert(label: String, group: String, confidence: Float, priority: String, reappeared: Boolean)
    }
}
