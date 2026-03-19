package com.securecam.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*

/**
 * Face detection using ML Kit.
 * Detects faces, landmarks (eyes, nose, mouth), contours,
 * and estimates expressions (smiling, eyes open).
 */
class FaceDetectionManager(
    private val listener: FaceDetectionListener
) {
    private val TAG = "FaceDetection"
    private var detector: FaceDetector? = null
    private var isProcessing = false
    private var lastDetectionTime = 0L
    private val detectionIntervalMs = 300L

    private var lastFaceCount = 0
    private var noFaceTime = 0L
    private val unknownFaceCooldownMs = 10_000L
    private var lastFaceAlertTime = 0L

    fun initialize() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.1f)
            .enableTracking()
            .build()

        detector = FaceDetection.getClient(options)
        Log.d(TAG, "Face detector initialized")
    }

    fun processFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (isProcessing || now - lastDetectionTime < detectionIntervalMs) return
        isProcessing = true
        lastDetectionTime = now

        val image = InputImage.fromBitmap(bitmap, 0)

        detector?.process(image)
            ?.addOnSuccessListener { faces ->
                val results = faces.map { face ->
                    DetectedFace(
                        trackingId = face.trackingId ?: -1,
                        boundingBox = BoundingBox(
                            face.boundingBox.left, face.boundingBox.top,
                            face.boundingBox.right, face.boundingBox.bottom
                        ),
                        smilingProbability = face.smilingProbability ?: -1f,
                        leftEyeOpenProbability = face.leftEyeOpenProbability ?: -1f,
                        rightEyeOpenProbability = face.rightEyeOpenProbability ?: -1f,
                        headEulerAngleY = face.headEulerAngleY,
                        headEulerAngleZ = face.headEulerAngleZ,
                        landmarks = extractLandmarks(face)
                    )
                }

                val faceCount = results.size

                if (faceCount > 0) {
                    listener.onFacesDetected(results)
                    // Alert on new face detected
                    if (lastFaceCount == 0) {
                        val alertNow = System.currentTimeMillis()
                        if (alertNow - lastFaceAlertTime > unknownFaceCooldownMs) {
                            lastFaceAlertTime = alertNow
                            listener.onFaceAlert(faceCount)
                        }
                    }
                    noFaceTime = 0
                } else {
                    if (lastFaceCount > 0) {
                        noFaceTime = System.currentTimeMillis()
                    }
                    listener.onNoFaceDetected()
                }

                lastFaceCount = faceCount
                isProcessing = false
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed: ${e.message}")
                isProcessing = false
            }
    }

    private fun extractLandmarks(face: Face): Map<String, Pair<Float, Float>> {
        val map = mutableMapOf<String, Pair<Float, Float>>()
        val landmarkTypes = listOf(
            FaceLandmark.LEFT_EYE to "left_eye",
            FaceLandmark.RIGHT_EYE to "right_eye",
            FaceLandmark.NOSE_BASE to "nose",
            FaceLandmark.MOUTH_LEFT to "mouth_left",
            FaceLandmark.MOUTH_RIGHT to "mouth_right",
            FaceLandmark.LEFT_EAR to "left_ear",
            FaceLandmark.RIGHT_EAR to "right_ear"
        )
        for ((type, name) in landmarkTypes) {
            face.getLandmark(type)?.let {
                map[name] = Pair(it.position.x, it.position.y)
            }
        }
        return map
    }

    fun release() {
        detector?.close()
        detector = null
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
    }
}
