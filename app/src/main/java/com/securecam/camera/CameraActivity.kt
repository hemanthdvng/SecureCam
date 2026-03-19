package com.securecam.camera

import android.content.Intent
import android.os.Bundle
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

    private lateinit var binding: ActivityCameraBinding
    private var roomCode = ""
    private var connectionType = ""
    private var serverUrl = ""
    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null
    private var wsStream: WebSocketStreamManager? = null
    private lateinit var ai: AIProcessor
    private lateinit var night: NightModeHelper
    private var isMuted = false
    private var isVideoOff = false
    private var isBackCamera = true
    private var isStreaming = false
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var exec: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        roomCode = intent.getStringExtra(ConnectionActivity.EXTRA_ROOM_CODE) ?: ""
        connectionType = intent.getStringExtra(ConnectionActivity.EXTRA_CONNECTION_TYPE) ?: ConnectionActivity.TYPE_WEBRTC
        serverUrl = intent.getStringExtra(ConnectionActivity.EXTRA_SERVER_URL) ?: ""
        exec = Executors.newSingleThreadExecutor()
        if (!PermissionHelper.hasAllPermissions(this)) PermissionHelper.requestPermissions(this) else init()
    }

    private fun init() {
        setupUI()
        ai = AIProcessor(this)
        ai.initialize()
        night = NightModeHelper(this)
        startForegroundService(Intent(this, CameraStreamingService::class.java))
        if (connectionType == ConnectionActivity.TYPE_WEBRTC) initWebRTC() else initWS()
        startAnalysisCamera()
    }

    private fun setupUI() {
        binding.tvRoomCode.text = "Room: $roomCode"
        binding.tvConnectionType.text = connectionType.uppercase()
        updateStatus("Initializing...", false)
        binding.btnSwitchCamera.setOnClickListener { isBackCamera = !isBackCamera; webRTCManager?.switchCamera() }
        binding.btnMute.setOnClickListener { isMuted = !isMuted; webRTCManager?.setAudioEnabled(!isMuted) }
        binding.btnVideoOff.setOnClickListener {
            isVideoOff = !isVideoOff
            webRTCManager?.setVideoEnabled(!isVideoOff)
            binding.btnVideoOff.alpha = if (isVideoOff) 0.4f else 1f
        }
        binding.btnTorch.setOnClickListener {
            binding.btnTorch.alpha = if (binding.btnTorch.alpha > 0.6f) 0.4f else 1f
        }
        binding.btnNightMode.setOnClickListener { night.toggleNightMode() }
        binding.btnEndStream.setOnClickListener {
            AlertDialog.Builder(this).setTitle("End Stream?")
                .setPositiveButton("End") { _, _ -> finish() }
                .setNegativeButton("Cancel", null).show()
        }
        binding.motionBar.max = 100
    }

    private fun initWebRTC() {
        webRTCManager = WebRTCManager(this, true, object : WebRTCManager.WebRTCListener {
            override fun onLocalIceCandidate(c: IceCandidate) { signalingClient?.sendIceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex) }
            override fun onConnectionEstablished() { runOnUiThread { isStreaming = true; updateStatus("Viewer Connected", true) } }
            override fun onConnectionFailed() { runOnUiThread { updateStatus("Connection failed", false) } }
            override fun onRemoteVideoReceived() {}
        })
        webRTCManager?.initialize()
        webRTCManager?.startLocalCamera(binding.localVideoView, isBackCamera)
        signalingClient = SignalingClient(serverUrl, roomCode, true, object : SignalingClient.SignalingListener {
            override fun onConnected() { runOnUiThread { updateStatus("Waiting for viewer...", false) } }
            override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected", false) } }
            override fun onPeerJoined() {
                webRTCManager?.createPeerConnection()
                webRTCManager?.createOffer { sdp -> signalingClient?.sendOffer(sdp.description) }
            }
            override fun onPeerLeft() { runOnUiThread { updateStatus("Viewer left...", false) } }
            override fun onOfferReceived(sdp: String) {}
            override fun onAnswerReceived(sdp: String) { webRTCManager?.setRemoteAnswer(sdp) }
            override fun onIceCandidateReceived(c: String, m: String, i: Int) { webRTCManager?.addIceCandidate(c, m, i) }
            override fun onError(msg: String) { runOnUiThread { updateStatus("Error: $msg", false) } }
        })
        signalingClient?.connect()
    }

    private fun initWS() {
        wsStream = WebSocketStreamManager(serverUrl, roomCode, true, object : WebSocketStreamManager.StreamListener {
            override fun onConnected() { runOnUiThread { updateStatus("Waiting for viewer...", false) } }
            override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected", false) } }
            override fun onPeerJoined() { runOnUiThread { isStreaming = true; updateStatus("Viewer (Relay)", true) } }
            override fun onPeerLeft() { runOnUiThread { isStreaming = false; updateStatus("Viewer left...", false) } }
            override fun onFrameReceived(d: ByteArray) {}
            override fun onStreamInfo(w: Int, h: Int) {}
            override fun onMotionEventReceived(t: Long) {}
            override fun onAiEventReceived(l: String, c: Float) {}
            override fun onError(m: String) { runOnUiThread { updateStatus("Error: $m", false) } }
        })
        wsStream?.connect()
    }

    private fun startAnalysisCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            cameraProvider = fut.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { ia ->
                    ia.setAnalyzer(exec) { proxy ->
                        val bmp = proxy.toBitmap()
                        ai.processFrame(bmp)
                        night.analyzeFrameBrightness(bmp)
                        if (connectionType == ConnectionActivity.TYPE_WEBSOCKET && isStreaming) wsStream?.sendFrame(bmp)
                        proxy.close()
                    }
                }
            val sel = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider?.unbindAll()
                val cam = cameraProvider?.bindToLifecycle(this, sel, analysis)
                cam?.let { night.attachCamera(it) }
            } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onMotionAlert(score: Float, regions: List<MotionDetector.MotionRegion>) {
        runOnUiThread {
            binding.tvMotionAlert.visibility = View.VISIBLE
            binding.tvMotionAlert.postDelayed({ binding.tvMotionAlert.visibility = View.GONE }, 3000)
        }
        NotificationHelper.showMotionAlert(this, score)
        wsStream?.sendMotionEvent()
    }

    override fun onMotionScoreUpdate(score: Float) {
        runOnUiThread { binding.motionBar.progress = (score * 100).toInt() }
    }

    override fun onObjectsUpdate(objects: List<ObjectDetectionManager.DetectedObject>) {
        runOnUiThread {
            if (objects.isEmpty()) binding.tvObjectLabel.visibility = View.GONE
            else {
                binding.tvObjectLabel.text = objects.joinToString { it.topLabel.text }
                binding.tvObjectLabel.visibility = View.VISIBLE
            }
        }
    }

    override fun onObjectAlert(label: String, confidence: Float) {
        NotificationHelper.showObjectDetectionAlert(this, label, confidence)
        wsStream?.sendAiEvent(label, confidence)
    }

    override fun onFacesUpdate(faces: List<FaceDetectionManager.DetectedFace>) {
        runOnUiThread {
            if (faces.isEmpty()) binding.tvFaceLabel.visibility = View.GONE
            else {
                binding.tvFaceLabel.text = "\uD83D\uDC64 ${faces.size}"
                binding.tvFaceLabel.visibility = View.VISIBLE
            }
        }
    }

    override fun onFaceAlert(count: Int) { NotificationHelper.showFaceAlert(this, count) }

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
            if (PermissionHelper.hasAllPermissions(this)) init()
            else Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ai.release(); night.release()
        webRTCManager?.release(); signalingClient?.disconnect()
        wsStream?.disconnect(); exec.shutdown()
        stopService(Intent(this, CameraStreamingService::class.java))
    }
}
