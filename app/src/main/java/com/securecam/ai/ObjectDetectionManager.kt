package com.securecam.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/**
 * Real-time object detection using ML Kit.
 * Detects objects in each camera frame and classifies them.
 */
class ObjectDetectionManager(
    private val listener: ObjectDetectionListener
) {
    private val TAG = "ObjectDetection"
    private var detector: ObjectDetector? = null
    private var isProcessing = false
    private var lastDetectionTime = 0L
    private val detectionIntervalMs = 500L // Run detection every 500ms for performance

    // Track detected labels to avoid duplicate notifications
    private val recentLabels = mutableMapOf<String, Long>()
    private val labelCooldownMs = 5000L // 5s between same-label alerts

    fun initialize() {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()

        detector = ObjectDetection.getClient(options)
        Log.d(TAG, "Object detector initialized")
    }

    fun processFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (isProcessing || now - lastDetectionTime < detectionIntervalMs) return
        isProcessing = true
        lastDetectionTime = now

        val image = InputImage.fromBitmap(bitmap, 0)

        detector?.process(image)
            ?.addOnSuccessListener { objects ->
                val results = mutableListOf<DetectedObject>()
                for (obj in objects) {
                    val boundingBox = obj.boundingBox
                    val labels = obj.labels.map { label ->
                        ObjectLabel(label.text, label.confidence, label.index)
                    }

                    if (labels.isNotEmpty()) {
                        val topLabel = labels.maxByOrNull { it.confidence }!!
                        results.add(DetectedObject(
                            id = obj.trackingId ?: -1,
                            labels = labels,
                            topLabel = topLabel,
                            boundingBox = BoundingBox(
                                boundingBox.left, boundingBox.top,
                                boundingBox.right, boundingBox.bottom
                            )
                        ))

                        // Fire alert for new high-confidence detections
                        if (topLabel.confidence > 0.7f) {
                            checkAndFireAlert(topLabel.text, topLabel.confidence)
                        }
                    }
                }

                if (results.isNotEmpty()) {
                    listener.onObjectsDetected(results)
                }
                isProcessing = false
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Object detection failed: ${e.message}")
                isProcessing = false
            }
    }

    private fun checkAndFireAlert(label: String, confidence: Float) {
        val now = System.currentTimeMillis()
        val lastTime = recentLabels[label] ?: 0L
        if (now - lastTime > labelCooldownMs) {
            recentLabels[label] = now
            listener.onObjectAlert(label, confidence)
        }
    }

    fun release() {
        detector?.close()
        detector = null
    }

    data class ObjectLabel(val text: String, val confidence: Float, val index: Int)
    data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class DetectedObject(
        val id: Int,
        val labels: List<ObjectLabel>,
        val topLabel: ObjectLabel,
        val boundingBox: BoundingBox
    )

    interface ObjectDetectionListener {
        fun onObjectsDetected(objects: List<DetectedObject>)
        fun onObjectAlert(label: String, confidence: Float)
    }
}
