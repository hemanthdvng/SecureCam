package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*

/**
 * Fixed FaceDetectionManager:
 *  - Recognition runs every frame pass (cooldown reduced to 3s per-face by trackingId)
 *  - Fires onFaceRecognised / onUnknownFace reliably every cooldown window
 *  - Per-trackingId cooldown prevents spam without suppressing detection
 */
class FaceDetectionManager(
    private val context: Context,
    private val listener: FaceDetectionListener
) {
    private val TAG = "FaceDetection"
    private var detector: FaceDetector? = null
    private var recognitionManager: FaceRecognitionManager? = null
    var faceDatabase: KnownFaceDatabase? = null

    private var isProcessing = false
    private var lastDetectionTime = 0L
    private val DETECTION_INTERVAL_MS = 350L

    // Per-trackingId cooldown — avoids duplicate alerts for same face
    private val faceAlertCooldown = mutableMapOf<Int, Long>()
    private val FACE_ALERT_COOLDOWN_MS = 8_000L   // 8 s per unique face

    // Global unknown face cooldown
    private var lastUnknownAlertTime = 0L
    private val UNKNOWN_ALERT_COOLDOWN_MS = 6_000L

    private var lastFaceCount = 0

    fun initialize() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)  // faster for real-time
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)        // skip landmarks for speed
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.06f)   // detect smaller faces
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(options)
        recognitionManager = FaceRecognitionManager(context).also { it.initialize() }
        faceDatabase = KnownFaceDatabase(context)
        Log.d(TAG, "FaceDetectionManager ready. Recognition=${recognitionManager?.isAvailable}")
    }

    fun processFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (isProcessing || now - lastDetectionTime < DETECTION_INTERVAL_MS) return
        isProcessing = true
        lastDetectionTime = now

        val image = InputImage.fromBitmap(bitmap, 0)
        detector?.process(image)
            ?.addOnSuccessListener { mlFaces ->
                val results = mlFaces.map { face ->
                    DetectedFace(
                        trackingId  = face.trackingId ?: -1,
                        boundingBox = BoundingBox(
                            face.boundingBox.left, face.boundingBox.top,
                            face.boundingBox.right, face.boundingBox.bottom
                        ),
                        smilingProbability      = face.smilingProbability ?: -1f,
                        leftEyeOpenProbability  = face.leftEyeOpenProbability ?: -1f,
                        rightEyeOpenProbability = face.rightEyeOpenProbability ?: -1f,
                        headEulerAngleY = face.headEulerAngleY,
                        headEulerAngleZ = face.headEulerAngleZ,
                        landmarks = emptyMap()
                    )
                }

                if (results.isNotEmpty()) {
                    listener.onFacesDetected(results)
                    if (lastFaceCount == 0) listener.onFaceAlert(results.size)
                    // Run recognition on every detection pass (gated per-face by cooldown)
                    runRecognition(bitmap, mlFaces, now)
                } else {
                    listener.onNoFaceDetected()
                }
                lastFaceCount = results.size
                isProcessing = false
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Detection failed: ${e.message}")
                isProcessing = false
            }
    }

    private fun runRecognition(fullBitmap: Bitmap, faces: List<Face>, now: Long) {
        val rm = recognitionManager ?: return
        val db = faceDatabase ?: return

        for (face in faces) {
            val tid = face.trackingId ?: -1
            // Per-face cooldown check
            if (now - (faceAlertCooldown[tid] ?: 0L) < FACE_ALERT_COOLDOWN_MS) continue

            try {
                val bb   = face.boundingBox
                val crop = rm.cropFace(fullBitmap, bb.left, bb.top, bb.right, bb.bottom)

                val matched: Pair<String, Float>? = if (rm.isAvailable) {
                    val emb = rm.generateEmbedding(crop)
                    if (emb != null) db.findMatch(emb) else null
                } else {
                    if (!db.isEmpty()) db.findMatchByPixel(crop) else null
                }

                if (matched != null) {
                    faceAlertCooldown[tid] = now
                    listener.onFaceRecognised(matched.first, matched.second)
                } else if (!db.isEmpty()) {
                    // Only fire unknown alert if DB has faces (otherwise nobody enrolled yet)
                    if (now - lastUnknownAlertTime > UNKNOWN_ALERT_COOLDOWN_MS) {
                        lastUnknownAlertTime = now
                        faceAlertCooldown[tid] = now
                        listener.onUnknownFace()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error: ${e.message}")
            }
        }
    }

    fun registerFace(name: String, faceBitmap: Bitmap) {
        val rm = recognitionManager
        val db = faceDatabase ?: return
        val emb = rm?.generateEmbedding(faceBitmap)
        db.addFace(name, faceBitmap, emb)
        Log.d(TAG, "Registered: $name  embedding=${emb != null}")
    }

    fun release() {
        detector?.close()
        recognitionManager?.release()
    }

    data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class DetectedFace(
        val trackingId: Int,
        val boundingBox: BoundingBox,
        val smilingProbability: Float,
        val leftEyeOpenProbability: Float,
        val rightEyeOpenProbability: Float,
        val headEulerAngleY: Float,
        val headEulerAngleZ: Float,
        val landmarks: Map<String, Pair<Float, Float>>
    )

    interface FaceDetectionListener {
        fun onFacesDetected(faces: List<DetectedFace>)
        fun onNoFaceDetected()
        fun onFaceAlert(count: Int)
        fun onFaceRecognised(name: String, confidence: Float)
        fun onUnknownFace()
    }
}
