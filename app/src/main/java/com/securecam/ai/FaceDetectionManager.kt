package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*

/**
 * Face detection + recognition.
 * ML Kit detects faces + crops them.
 * FaceRecognitionManager generates embeddings.
 * KnownFaceDatabase matches against stored persons.
 * Fires onUnknownFace when an unrecognised person is detected.
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

    private var lastFaceCount = 0
    private var lastFaceAlertTime = 0L
    private val ALERT_COOLDOWN_MS = 15_000L

    fun initialize() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(options)

        recognitionManager = FaceRecognitionManager(context).also { it.initialize() }
        faceDatabase = KnownFaceDatabase(context)
        Log.d(TAG, "Face detection initialized. Recognition: ${recognitionManager?.isAvailable}")
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
                        trackingId = face.trackingId ?: -1,
                        boundingBox = BoundingBox(
                            face.boundingBox.left, face.boundingBox.top,
                            face.boundingBox.right, face.boundingBox.bottom
                        ),
                        smilingProbability      = face.smilingProbability ?: -1f,
                        leftEyeOpenProbability  = face.leftEyeOpenProbability ?: -1f,
                        rightEyeOpenProbability = face.rightEyeOpenProbability ?: -1f,
                        headEulerAngleY = face.headEulerAngleY,
                        headEulerAngleZ = face.headEulerAngleZ,
                        landmarks = extractLandmarks(face)
                    )
                }

                if (results.isNotEmpty()) {
                    listener.onFacesDetected(results)

                    // Recognition pass — only alert cooldown-gated
                    val now2 = System.currentTimeMillis()
                    if (now2 - lastFaceAlertTime > ALERT_COOLDOWN_MS) {
                        runRecognition(bitmap, mlFaces)
                        lastFaceAlertTime = now2
                    }
                    if (lastFaceCount == 0) listener.onFaceAlert(results.size)
                } else {
                    listener.onNoFaceDetected()
                }
                lastFaceCount = results.size
                isProcessing = false
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed: ${e.message}")
                isProcessing = false
            }
    }

    private fun runRecognition(fullBitmap: Bitmap, faces: List<Face>) {
        val rm = recognitionManager ?: return
        val db = faceDatabase ?: return

        for (face in faces) {
            try {
                val bb = face.boundingBox
                val crop = rm.cropFace(fullBitmap, bb.left, bb.top, bb.right, bb.bottom)

                if (rm.isAvailable) {
                    val emb = rm.generateEmbedding(crop)
                    if (emb != null) {
                        val match = db.findMatch(emb)
                        if (match != null) {
                            listener.onFaceRecognised(match.first, match.second)
                        } else {
                            listener.onUnknownFace()
                        }
                    }
                } else {
                    // Pixel-level fallback
                    if (!db.isEmpty()) {
                        val match = db.findMatchByPixel(crop)
                        if (match != null) {
                            listener.onFaceRecognised(match.first, match.second)
                        } else {
                            listener.onUnknownFace()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error: ${e.message}")
            }
        }
    }

    /** Call this to register a new person from a face crop */
    fun registerFace(name: String, faceBitmap: Bitmap) {
        val rm = recognitionManager
        val db = faceDatabase ?: return
        val emb = rm?.generateEmbedding(faceBitmap)
        db.addFace(name, faceBitmap, emb)
        Log.d(TAG, "Registered face: $name")
    }

    private fun extractLandmarks(face: Face): Map<String, Pair<Float, Float>> {
        val map = mutableMapOf<String, Pair<Float, Float>>()
        listOf(
            FaceLandmark.LEFT_EYE   to "left_eye",
            FaceLandmark.RIGHT_EYE  to "right_eye",
            FaceLandmark.NOSE_BASE  to "nose",
            FaceLandmark.MOUTH_LEFT to "mouth_left",
            FaceLandmark.MOUTH_RIGHT to "mouth_right"
        ).forEach { (type, name) ->
            face.getLandmark(type)?.let { map[name] = Pair(it.position.x, it.position.y) }
        }
        return map
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
