package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLO26n object detection — NMS-free, CPU TFLite inference.
 *
 * Model file: assets/yolo26n.tflite  (float32, ~5 MB)
 *   Input  : [1, 640, 640, 3]  float32  normalised 0..1
 *   Output : [1, 300, 6]       float32  [x1,y1,x2,y2,confidence,class_id]
 *            (NMS-free — no post-processing needed, direct predictions)
 *
 * Key YOLO26 improvements vs YOLOv10/v11:
 *   • 43% faster CPU inference (no DFL module, no NMS)
 *   • ProgLoss + STAL loss → better small-object detection
 *   • MuSGD optimizer → more stable training convergence
 *
 * Android GPU delegate NOT used (known INT64 cast incompatibility with
 * YOLO26 TFLite export — tracked: ultralytics/ultralytics#23282).
 * YOLO26's CPU speed advantage makes GPU delegate unnecessary for nano.
 *
 * Detected COCO categories (security-relevant subset):
 *   Person(0), Bicycle(1), Car(2), Motorcycle(3), Bus(5), Truck(7)
 *   Cat(15), Dog(16), Horse(17), Sheep(18), Cow(19)
 *   Backpack(24), Suitcase(28)
 *
 * Smart logic:
 *   • Priority-aware deduplication cooldowns
 *   • Reappearance detection (>30 s gap = new event)
 *   • Priority: normal / warning / critical
 */
class ObjectDetectionManager(
    private val context: Context,
    private val listener: ObjectDetectionListener
) {
    private val TAG = "YOLO26n"

    private val MODEL_FILE         = "yolo26n.tflite"
    private val INPUT_SIZE         = 640
    private val MAX_DETS           = 300
    private val CONF_THRESH        = 0.40f
    private val DETECTION_INTERVAL = 500L   // ms between inference runs

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var isProcessing  = false
    private var lastRunTime   = 0L

    // Deduplication cooldowns by priority tier
    private val cdCritical = 3_000L
    private val cdWarning  = 5_000L
    private val cdNormal   = 8_000L
    private val labelLastAlert = mutableMapOf<String, Long>()
    private val classLastSeen  = mutableMapOf<Int, Long>()
    private val REAPPEAR_GAP   = 30_000L

    private val COCO_LABELS = mapOf(
        0  to "Person",     1  to "Bicycle",  2  to "Car",
        3  to "Motorcycle", 5  to "Bus",       7  to "Truck",
        15 to "Cat",        16 to "Dog",       17 to "Horse",
        18 to "Sheep",      19 to "Cow",
        24 to "Backpack",   28 to "Suitcase"
    )

    fun initialize() {
        try {
            // CPU-only: 4 threads — optimal for YOLO26n nano model
            val opts = Interpreter.Options().apply { numThreads = 4 }
            interpreter   = Interpreter(FileUtil.loadMappedFile(context, MODEL_FILE), opts)
            isInitialized = true
            Log.d(TAG, "YOLO26n ready [${INPUT_SIZE}×${INPUT_SIZE}, CPU, 4 threads]")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
            isInitialized = false
        }
    }

    fun processFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (isProcessing || !isInitialized || now - lastRunTime < DETECTION_INTERVAL) return
        isProcessing = true
        lastRunTime  = now

        Thread {
            try {
                val results = runInference(bitmap)
                if (results.isNotEmpty()) {
                    listener.onObjectsDetected(results)
                    results.forEach { handleAlert(it, now) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error: ${e.message}")
            } finally {
                isProcessing = false
            }
        }.start()
    }

    private fun runInference(src: Bitmap): List<DetectedObject> {
        val scaled = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        val buf    = bitmapToBuffer(scaled)
        scaled.recycle()

        // YOLO26 output: [1, 300, 6] — NMS-free direct predictions
        val out = Array(1) { Array(MAX_DETS) { FloatArray(6) } }
        interpreter!!.run(buf, out)

        return out[0].mapNotNull { row ->
            val conf  = row[4]
            val cid   = row[5].toInt()
            if (conf < CONF_THRESH || cid !in COCO_LABELS) return@mapNotNull null
            DetectedObject(
                classId    = cid,
                label      = COCO_LABELS[cid]!!,
                group      = groupFor(cid),
                confidence = conf,
                priority   = priorityFor(cid, conf),
                box        = RectF(
                    row[0].coerceIn(0f, 1f), row[1].coerceIn(0f, 1f),
                    row[2].coerceIn(0f, 1f), row[3].coerceIn(0f, 1f)
                )
            )
        }.sortedByDescending { it.confidence }.take(20)
    }

    private fun handleAlert(det: DetectedObject, now: Long) {
        val cd = when (det.priority) {
            "critical" -> cdCritical
            "warning"  -> cdWarning
            else       -> cdNormal
        }
        if (now - (labelLastAlert[det.label] ?: 0L) < cd) return
        val lastSeen   = classLastSeen[det.classId] ?: 0L
        val reappeared = lastSeen > 0 && (now - lastSeen) > REAPPEAR_GAP
        classLastSeen[det.classId] = now
        labelLastAlert[det.label]  = now
        listener.onObjectAlert(det.label, det.group, det.confidence, det.priority, reappeared)
    }

    private fun priorityFor(cid: Int, conf: Float) = when (cid) {
        0             -> if (conf > 0.75f) "critical" else "warning"
        1, 2, 3, 5, 7 -> "warning"
        else          -> "normal"
    }

    private fun groupFor(cid: Int) = when (cid) {
        0             -> "person"
        1, 2, 3, 5, 7 -> "vehicle"
        24, 28        -> "package"
        15,16,17,18,19 -> "animal"
        else          -> "object"
    }

    private fun bitmapToBuffer(bmp: Bitmap): ByteBuffer {
        val buf = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in px) {
            buf.putFloat((p shr 16 and 0xFF) / 255f)
            buf.putFloat((p shr  8 and 0xFF) / 255f)
            buf.putFloat((p        and 0xFF) / 255f)
        }
        return buf.apply { rewind() }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }

    data class DetectedObject(
        val classId   : Int,
        val label     : String,
        val group     : String,      // person | vehicle | package | animal | object
        val confidence: Float,
        val priority  : String,      // normal | warning | critical
        val box       : RectF
    )

    interface ObjectDetectionListener {
        fun onObjectsDetected(objects: List<DetectedObject>)
        fun onObjectAlert(
            label     : String,
            group     : String,
            confidence: Float,
            priority  : String,
            reappeared: Boolean
        )
    }
}
