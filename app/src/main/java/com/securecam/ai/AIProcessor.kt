package com.securecam.ai

import android.content.Context
import android.graphics.Bitmap
import com.securecam.utils.AppPreferences

class AIProcessor(
    private val context: Context,
    private val listener: AIEventListener
) : MotionDetector.MotionListener,
    ObjectDetectionManager.ObjectDetectionListener,
    FaceDetectionManager.FaceDetectionListener {

    private var motionDetector: MotionDetector? = null
    private var objectDetector: ObjectDetectionManager? = null
    private var faceDetector: FaceDetectionManager? = null
    private var frameCount = 0L

    var lastMotionScore = 0f
        private set
    var lastDetectedObjects = listOf<ObjectDetectionManager.DetectedObject>()
        private set
    var lastDetectedFaces = listOf<FaceDetectionManager.DetectedFace>()
        private set

    val faceDatabase: KnownFaceDatabase? get() = faceDetector?.faceDatabase

    fun initialize() {
        if (AppPreferences.motionAlertsEnabled) {
            motionDetector = MotionDetector(AppPreferences.motionSensitivity, this)
        }
        if (AppPreferences.objectDetectionEnabled) {
            objectDetector = ObjectDetectionManager(this).also { it.initialize() }
        }
        if (AppPreferences.faceDetectionEnabled) {
            faceDetector = FaceDetectionManager(context, this).also { it.initialize() }
        }
    }

    fun processFrame(bitmap: Bitmap) {
        frameCount++
        motionDetector?.processFrame(bitmap)
        if (frameCount % 3L == 0L) objectDetector?.processFrame(bitmap)
        if (frameCount % 2L == 0L) faceDetector?.processFrame(bitmap)
    }

    fun registerFace(name: String, bitmap: Bitmap) {
        faceDetector?.registerFace(name, bitmap)
    }

    // MotionDetector callbacks
    override fun onMotionDetected(score: Float, regions: List<MotionDetector.MotionRegion>) {
        lastMotionScore = score
        listener.onMotionAlert(score, regions)
    }
    override fun onMotionScore(score: Float) {
        lastMotionScore = score
        listener.onMotionScoreUpdate(score)
    }

    // ObjectDetection callbacks
    override fun onObjectsDetected(objects: List<ObjectDetectionManager.DetectedObject>) {
        lastDetectedObjects = objects
        listener.onObjectsUpdate(objects)
    }
    override fun onObjectAlert(label: String, confidence: Float) {
        listener.onObjectAlert(label, confidence)
    }

    // FaceDetection callbacks
    override fun onFacesDetected(faces: List<FaceDetectionManager.DetectedFace>) {
        lastDetectedFaces = faces
        listener.onFacesUpdate(faces)
    }
    override fun onNoFaceDetected() {
        lastDetectedFaces = emptyList()
        listener.onFacesUpdate(emptyList())
    }
    override fun onFaceAlert(count: Int) {
        listener.onFaceAlert(count)
    }
    override fun onFaceRecognised(name: String, confidence: Float) {
        listener.onFaceRecognised(name, confidence)
    }
    override fun onUnknownFace() {
        listener.onUnknownFace()
    }

    fun release() {
        motionDetector?.release()
        objectDetector?.release()
        faceDetector?.release()
    }

    interface AIEventListener {
        fun onMotionAlert(score: Float, regions: List<MotionDetector.MotionRegion>)
        fun onMotionScoreUpdate(score: Float)
        fun onObjectsUpdate(objects: List<ObjectDetectionManager.DetectedObject>)
        fun onObjectAlert(label: String, confidence: Float)
        fun onFacesUpdate(faces: List<FaceDetectionManager.DetectedFace>)
        fun onFaceAlert(count: Int)
        fun onFaceRecognised(name: String, confidence: Float)
        fun onUnknownFace()
    }
}
