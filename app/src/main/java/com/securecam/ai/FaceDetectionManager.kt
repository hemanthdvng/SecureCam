package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*

/**
 * Enhanced FaceDetectionManager v2:
 * - PERFORMANCE_MODE_ACCURATE: detects faces at steeper angles, smaller faces
 * - LANDMARK_MODE_ALL: enables eye positions for crop alignment
 * - Per-face cooldown prevents spam without suppressing detection
 * - Eye landmarks forwarded to FaceRecognitionManager.cropAndAlignFace()
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
    private val DETECTION_INTERVAL_MS = 400L

    private val faceAlertCooldown = mutableMapOf<Int, Long>()
    private val FACE_ALERT_COOLDOWN_MS = 8_000L

    private var lastUnknownAlertTime = 0L
    private val UNKNOWN_ALERT_COOLDOWN_MS = 6_000L

    private var lastFaceCount = 0

    fun initialize() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)   // eyes, nose, mouth
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.05f)
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(options)
        recognitionManager = FaceRecognitionManager(context).also { it.initialize() }
        faceDatabase = KnownFaceDatabase(context)
        Log.d(TAG, "FaceDetectionManager ready — ACCURATE+LANDMARKS. model=${recognitionManager?.currentModelName}")
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
                    val leftEye  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
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
                        landmarks = buildMap {
                            leftEye?.let  { put("leftEye",  Pair(it.x, it.y)) }
                            rightEye?.let { put("rightEye", Pair(it.x, it.y)) }
                            face.getLandmark(FaceLandmark.NOSE_BASE)?.position?.let  { put("nose",       Pair(it.x, it.y)) }
                            face.getLandmark(FaceLandmark.LEFT_MOUTH)?.position?.let { put("mouthLeft",  Pair(it.x, it.y)) }
                            face.getLandmark(FaceLandmark.RIGHT_MOUTH)?.position?.let{ put("mouthRight", Pair(it.x, it.y)) }
                        }
                    )
                }

                if (results.isNotEmpty()) {
                    listener.onFacesDetected(results)
                    if (lastFaceCount == 0) listener.onFaceAlert(results.size)
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
            if (now - (faceAlertCooldown[tid] ?: 0L) < FACE_ALERT_COOLDOWN_MS) continue

            try {
                val bb       = face.boundingBox
                val leftEye  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                // Use eye-based alignment when landmarks are available
                val crop = if (leftEye != null && rightEye != null) {
                    rm.cropAndAlignFace(
                        fullBitmap,
                        bb.left, bb.top, bb.right, bb.bottom,
                        leftEye.x, leftEye.y, rightEye.x, rightEye.y
                    )
                } else {
                    rm.cropFace(fullBitmap, bb.left, bb.top, bb.right, bb.bottom)
                }

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
