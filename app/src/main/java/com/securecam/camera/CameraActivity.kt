package com.securecam.camera

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.securecam.ai.AIProcessor
import com.securecam.ai.FaceDetectionManager
import com.securecam.ai.MotionDetector
import com.securecam.ai.ObjectDetectionManager
import com.securecam.databinding.ActivityCameraBinding
import com.securecam.ui.ConnectionActivity
import com.securecam.utils.NightModeHelper
import com.securecam.utils.NotificationHelper
import com.securecam.utils.PermissionHelper
import com.securecam.webrtc.SignalingClient
import com.securecam.webrtc.WebRTCManager
import com.securecam.websocket.WebSocketStreamManager
import org.webrtc.IceCandidate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity(),
    AIProcessor.AIEventListener,
    NightModeHelper.NightModeListener {

    private val TAG = "CameraActivity"
    private lateinit var binding: ActivityCameraBinding
    private var roomCode = ""
    private var connectionType = ""
    private var serverUrl = ""
    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null
    private var wsStream: WebSocketStreamManager? = null
    private var ai: AIProcessor? = null
    private var night: NightModeHelper? = null
    private var isBackCamera = true
    private var isStreaming = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var exec: ExecutorService? = null

    // Global exception handler — shows error instead of crashing
    private val exceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
        Log.e(TAG, "UNCAUGHT: ${throwable.message}", throwable)
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(this, "Error: ${throwable.message?.take(100)}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.currentThread().uncaughtExceptionHandler = exceptionHandler
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityCameraBinding.inflate(layoutInflater)
            setContentView(binding.root)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.e(TAG, "Binding failed: ${e.message}")
            Toast.makeText(this, "Layout error: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        roomCode = intent.getStringExtra(ConnectionActivity.EXTRA_ROOM_CODE) ?: ""
        connectionType = intent.getStringExtra(ConnectionActivity.EXTRA_CONNECTION_TYPE) ?: ConnectionActivity.TYPE_WEBRTC
        serverUrl = intent.getStringExtra(ConnectionActivity.EXTRA_SERVER_URL) ?: ""

        Log.d(TAG, "room=$roomCode type=$connectionType url=$serverUrl android=${Build.VERSION.SDK_INT}")

        exec = Executors.newSingleThreadExecutor()
        setupUI()

        if (!PermissionHelper.hasAllPermissions(this)) {
            updateStatus("Requesting permissions...", false)
            PermissionHelper.requestPermissions(this)
        } else {
            // Delay init slightly so UI renders first — prevents blank screen crash
            Handler(Looper.getMainLooper()).postDelayed({ init() }, 300)
        }
    }

    private fun init() {
        updateStatus("Starting...", false)

        // Start foreground service
        try {
            val si = Intent(this, CameraStreamingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(si)
            } else {
                startService(si)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service: ${e.message}")
            // Not fatal — continue anyway
        }

        // Init AI
        try {
            ai = AIProcessor(this)
            ai?.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "AI init: ${e.message}")
        }

        // Init night mode
        try {
            night = NightModeHelper(this)
        } catch (e: Exception) {
            Log.e(TAG, "Night: ${e.message}")
        }

        // Start camera first — most important
        startAnalysisCamera()

        // Then connect to server
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (connectionType == ConnectionActivity.TYPE_WEBRTC) initWebRTC()
                else initWS()
            } catch (e: Exception) {
                Log.e(TAG, "Connection: ${e.message}")
                updateStatus("Server error: ${e.message?.take(50)}", false)
            }
        }, 500)
    }

    private fun setupUI() {
        binding.tvRoomCode.text = "Room: $roomCode"
        binding.tvConnectionType.text = if (connectionType == ConnectionActivity.TYPE_WEBRTC) "WEBRTC" else "RELAY"
        updateStatus("Waiting...", false)

        binding.btnSwitchCamera.setOnClickListener {
            isBackCamera = !isBackCamera
            webRTCManager?.switchCamera()
            startAnalysisCamera()
        }
        binding.btnMute.setOnClickListener {
            val muted = binding.btnMute.alpha < 0.7f
            webRTCManager?.setAudioEnabled(muted)
            binding.btnMute.alpha = if (muted) 1f else 0.4f
        }
        binding.btnVideoOff.setOnClickListener {
            val off = binding.btnVideoOff.alpha < 0.7f
            webRTCManager?.setVideoEnabled(off)
            binding.btnVideoOff.alpha = if (off) 1f else 0.4f
        }
        binding.btnNightMode.setOnClickListener { night?.toggleNightMode() }
        binding.btnEndStream.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("End Stream?")
                .setPositiveButton("End") { _, _ -> finish() }
                .setNegativeButton("Cancel", null).show()
        }
        binding.motionBar.max = 100
    }

    private fun initWebRTC() {
        webRTCManager = WebRTCManager(this, true, object : WebRTCManager.WebRTCListener {
            override fun onLocalIceCandidate(c: IceCandidate) {
                signalingClient?.sendIceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex)
            }
            override fun onConnectionEstablished() {
                runOnUiThread { isStreaming = true; updateStatus("🟢 Viewer Connected", true) }
            }
            override fun onConnectionFailed() {
                runOnUiThread { updateStatus("Connection failed — check server", false) }
            }
            override fun onRemoteVideoReceived() {}
        })
        webRTCManager?.initialize()
        webRTCManager?.startLocalCamera(binding.localVideoView, isBackCamera)

        signalingClient = SignalingClient(serverUrl, roomCode, true, object : SignalingClient.SignalingListener {
            override fun onConnected() { runOnUiThread { updateStatus("⏳ Waiting for viewer...", false) } }
            override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected from server", false) } }
            override fun onPeerJoined() {
                webRTCManager?.createPeerConnection()
                webRTCManager?.createOffer { sdp -> signalingClient?.sendOffer(sdp.description) }
            }
            override fun onPeerLeft() { runOnUiThread { isStreaming = false; updateStatus("⏳ Viewer disconnected", false) } }
            override fun onOfferReceived(sdp: String) {}
            override fun onAnswerReceived(sdp: String) { webRTCManager?.setRemoteAnswer(sdp) }
            override fun onIceCandidateReceived(c: String, m: String, i: Int) { webRTCManager?.addIceCandidate(c, m, i) }
            override fun onError(msg: String) { runOnUiThread { updateStatus("⚠️ $msg", false) } }
        })
        signalingClient?.connect()
    }

    private fun initWS() {
        wsStream = WebSocketStreamManager(serverUrl, roomCode, true, object : WebSocketStreamManager.StreamListener {
            override fun onConnected() { runOnUiThread { updateStatus("⏳ Waiting for viewer...", false) } }
            override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected", false) } }
            override fun onPeerJoined() { runOnUiThread { isStreaming = true; updateStatus("🟢 Viewer (Relay)", true) } }
            override fun onPeerLeft() { runOnUiThread { isStreaming = false; updateStatus("Viewer left", false) } }
            override fun onFrameReceived(d: ByteArray) {}
            override fun onStreamInfo(w: Int, h: Int) {}
            override fun onMotionEventReceived(t: Long) {}
            override fun onAiEventReceived(l: String, c: Float) {}
            override fun onError(m: String) { runOnUiThread { updateStatus("⚠️ $m", false) } }
        })
        wsStream?.connect()
    }

    private fun startAnalysisCamera() {
        val execLocal = exec ?: return
        try {
            val fut = ProcessCameraProvider.getInstance(this)
            fut.addListener({
                try {
                    cameraProvider = fut.get()
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also { ia ->
                            ia.setAnalyzer(execLocal) { proxy ->
                                try {
                                    val bmp = proxy.toBitmap()
                                    ai?.processFrame(bmp)
                                    night?.analyzeFrameBrightness(bmp)
                                    if (connectionType == ConnectionActivity.TYPE_WEBSOCKET && isStreaming)
                                        wsStream?.sendFrame(bmp)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Frame: ${e.message}")
                                } finally {
                                    proxy.close()
                                }
                            }
                        }
                    val sel = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA
                    cameraProvider?.unbindAll()
                    val cam = cameraProvider?.bindToLifecycle(this, sel, analysis)
                    cam?.let { night?.attachCamera(it) }
                    Log.d(TAG, "Camera started OK")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind: ${e.message}")
                    runOnUiThread { updateStatus("Camera: ${e.message?.take(60)}", false) }
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(TAG, "Camera init: ${e.message}")
        }
    }

    // AIProcessor callbacks
    override fun onMotionAlert(score: Float, regions: List<MotionDetector.MotionRegion>) {
        runOnUiThread {
            binding.tvMotionAlert.visibility = View.VISIBLE
            binding.tvMotionAlert.postDelayed({ binding.tvMotionAlert.visibility = View.GONE }, 3000)
        }
        try { NotificationHelper.showMotionAlert(this, score) } catch (e: Exception) {}
        wsStream?.sendMotionEvent()
    }
    override fun onMotionScoreUpdate(score: Float) {
        runOnUiThread { binding.motionBar.progress = (score * 100).toInt() }
    }
    override fun onObjectsUpdate(objects: List<ObjectDetectionManager.DetectedObject>) {
        runOnUiThread {
            if (objects.isEmpty()) binding.tvObjectLabel.visibility = View.GONE
            else { binding.tvObjectLabel.text = objects.joinToString { it.topLabel.text }; binding.tvObjectLabel.visibility = View.VISIBLE }
        }
    }
    override fun onObjectAlert(label: String, confidence: Float) {
        try { NotificationHelper.showObjectDetectionAlert(this, label, confidence) } catch (e: Exception) {}
        wsStream?.sendAiEvent(label, confidence)
    }
    override fun onFacesUpdate(faces: List<FaceDetectionManager.DetectedFace>) {
        runOnUiThread {
            if (faces.isEmpty()) binding.tvFaceLabel.visibility = View.GONE
            else { binding.tvFaceLabel.text = "\uD83D\uDC64 ${faces.size}"; binding.tvFaceLabel.visibility = View.VISIBLE }
        }
    }
    override fun onFaceAlert(count: Int) {
        try { NotificationHelper.showFaceAlert(this, count) } catch (e: Exception) {}
    }
    override fun onNightModeChanged(isNight: Boolean) {
        runOnUiThread {
            binding.tvNightMode.visibility = if (isNight) View.VISIBLE else View.GONE
            binding.btnNightMode.alpha = if (isNight) 1f else 0.5f
        }
    }

    private fun updateStatus(msg: String, connected: Boolean) {
        binding.tvStatus.text = msg
        binding.statusDot.setBackgroundResource(
            if (connected) android.R.drawable.presence_online else android.R.drawable.presence_busy
        )
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == PermissionHelper.REQUEST_CODE) {
            if (PermissionHelper.hasAllPermissions(this)) {
                Handler(Looper.getMainLooper()).postDelayed({ init() }, 300)
            } else {
                Toast.makeText(this, "Camera & audio permissions required", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ai?.release() } catch (e: Exception) {}
        try { night?.release() } catch (e: Exception) {}
        try { webRTCManager?.release() } catch (e: Exception) {}
        try { signalingClient?.disconnect() } catch (e: Exception) {}
        try { wsStream?.disconnect() } catch (e: Exception) {}
        try { exec?.shutdown() } catch (e: Exception) {}
        try { stopService(Intent(this, CameraStreamingService::class.java)) } catch (e: Exception) {}
    }
}
