package com.securecam.camera

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.securecam.ai.AIProcessor
import com.securecam.ai.FaceDetectionManager
import com.securecam.ai.MotionDetector
import com.securecam.ai.ObjectDetectionManager
import com.securecam.databinding.ActivityCameraBinding
import com.securecam.ui.ConnectionActivity
import com.securecam.utils.AppPreferences
import com.securecam.utils.NightModeHelper
import com.securecam.utils.NotificationHelper
import com.securecam.utils.PermissionHelper
import com.securecam.webrtc.CommandChannel
import com.securecam.webrtc.SignalingClient
import com.securecam.webrtc.WebRTCManager
import com.securecam.websocket.WebSocketStreamManager
import org.json.JSONObject
import org.webrtc.CapturerObserver
import org.webrtc.IceCandidate
import org.webrtc.NV21Buffer
import org.webrtc.VideoFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class CameraActivity : AppCompatActivity(),
    AIProcessor.AIEventListener,
    NightModeHelper.NightModeListener,
    RecordingManager.RecordingListener {

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
    private var recorder: RecordingManager? = null

    private var webRtcCapturerObserver: CapturerObserver? = null
    private var isBackCamera = true
    private var isStreaming = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var exec: ExecutorService? = null
    private var torchEnabled = false
    private var currentZoom = 1f
    private var boundCamera: androidx.camera.core.Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityCameraBinding.inflate(layoutInflater)
            setContentView(binding.root)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.e(TAG, "Binding: ${e.message}"); finish(); return
        }

        roomCode      = intent.getStringExtra(ConnectionActivity.EXTRA_ROOM_CODE) ?: ""
        connectionType = intent.getStringExtra(ConnectionActivity.EXTRA_CONNECTION_TYPE) ?: ConnectionActivity.TYPE_WEBRTC
        serverUrl     = intent.getStringExtra(ConnectionActivity.EXTRA_SERVER_URL) ?: ""
        isBackCamera  = AppPreferences.useBackCamera

        exec = Executors.newSingleThreadExecutor()
        setupUI()

        if (!PermissionHelper.hasAllPermissions(this)) {
            PermissionHelper.requestPermissions(this)
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ init() }, 200)
        }
    }

    private fun init() {
        updateStatus("Starting camera...", false)
        try {
            val si = Intent(this, CameraStreamingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si) else startService(si)
        } catch (e: Exception) { Log.e(TAG, "Service: ${e.message}") }

        try { ai = AIProcessor(this, this).also { it.initialize() } } catch (e: Exception) { Log.e(TAG, "AI: ${e.message}") }
        try { night = NightModeHelper(this) } catch (e: Exception) { Log.e(TAG, "Night: ${e.message}") }
        try { recorder = RecordingManager(this, this) } catch (e: Exception) { Log.e(TAG, "Recorder: ${e.message}") }

        startCamera()

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (connectionType == ConnectionActivity.TYPE_WEBRTC) initWebRTC() else initWS()
            } catch (e: Exception) {
                Log.e(TAG, "Connection: ${e.message}")
                updateStatus("⚠️ ${e.message?.take(50)}", false)
            }
        }, 800)
    }

    private fun startCamera() {
        val execLocal = exec ?: return
        ProcessCameraProvider.getInstance(this).also { fut ->
            fut.addListener({
                try {
                    cameraProvider = fut.get()
                    val preview = Preview.Builder()
                        .setTargetResolution(Size(1920, 1080))
                        .build().also { it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider) }

                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also { ia ->
                            ia.setAnalyzer(execLocal) { proxy ->
                                try {
                                    val bmp = proxy.toBitmap()
                                    ai?.processFrame(bmp)
                                    night?.analyzeFrameBrightness(bmp)
                                    if (connectionType == ConnectionActivity.TYPE_WEBSOCKET && isStreaming) wsStream?.sendFrame(bmp)
                                    val obs = webRtcCapturerObserver
                                    if (connectionType == ConnectionActivity.TYPE_WEBRTC && obs != null) {
                                        try {
                                            val nv21 = imageProxyToNV21(proxy)
                                            val buf = NV21Buffer(nv21, proxy.width, proxy.height, null)
                                            val frame = VideoFrame(buf, proxy.imageInfo.rotationDegrees, System.nanoTime())
                                            obs.onFrameCaptured(frame); frame.release()
                                        } catch (e: Exception) { Log.e(TAG, "WebRTC frame: ${e.message}") }
                                    }
                                } catch (e: Exception) { Log.e(TAG, "Frame: ${e.message}") }
                                finally { proxy.close() }
                            }
                        }

                    // Build VideoCapture for recording
                    val videoCapture = recorder?.buildVideoCapture()

                    val selector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    cameraProvider?.unbindAll()

                    val cam = if (videoCapture != null) {
                        cameraProvider?.bindToLifecycle(this, selector, preview, analysis, videoCapture)
                    } else {
                        cameraProvider?.bindToLifecycle(this, selector, preview, analysis)
                    }

                    boundCamera = cam
                    cam?.let {
                        night?.attachCamera(it)
                        // Apply current zoom
                        it.cameraControl.setZoomRatio(currentZoom)
                        binding.btnTorch.setOnClickListener { _ ->
                            torchEnabled = !torchEnabled
                            cam.cameraControl.enableTorch(torchEnabled)
                            binding.btnTorch.alpha = if (torchEnabled) 1f else 0.5f
                            sendCameraEvent(CommandChannel.evtTorchState(torchEnabled))
                        }
                    }

                    updateStatus("📷 Ready — waiting for viewer...", false)
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind: ${e.message}")
                    runOnUiThread { updateStatus("❌ Camera error: ${e.message?.take(60)}", false) }
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun imageProxyToNV21(proxy: androidx.camera.core.ImageProxy): ByteArray {
        val w = proxy.width; val h = proxy.height
        val yP = proxy.planes[0]; val uP = proxy.planes[1]; val vP = proxy.planes[2]
        val nv21 = ByteArray(w * h * 3 / 2)
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        var offset = 0
        for (row in 0 until h) { yBuf.position(row * yP.rowStride); yBuf.get(nv21, offset, w); offset += w }
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val idx = row * uP.rowStride + col * uP.pixelStride
                vBuf.position(idx); nv21[offset++] = vBuf.get()
                uBuf.position(idx); nv21[offset++] = uBuf.get()
            }
        }
        return nv21
    }

    private fun setupUI() {
        binding.tvRoomCode.text = "Room: $roomCode"
        binding.tvConnectionType.text = if (connectionType == ConnectionActivity.TYPE_WEBRTC) "WEBRTC" else "RELAY"
        updateStatus("Initializing...", false)

        binding.btnSwitchCamera.setOnClickListener {
            isBackCamera = !isBackCamera
            AppPreferences.useBackCamera = isBackCamera
            startCamera()
            sendCameraEvent(CommandChannel.switchCamera())
        }
        binding.btnMute.setOnClickListener {
            val muted = binding.btnMute.alpha < 0.7f
            webRTCManager?.setAudioEnabled(muted)
            binding.btnMute.alpha = if (muted) 1f else 0.4f
        }
        binding.btnVideoOff.setOnClickListener {
            val visible = binding.btnVideoOff.alpha > 0.7f
            binding.cameraPreviewView.visibility = if (visible) View.INVISIBLE else View.VISIBLE
            webRTCManager?.setVideoEnabled(!visible)
            binding.btnVideoOff.alpha = if (visible) 0.4f else 1f
        }
        binding.btnNightMode.setOnClickListener {
            night?.toggleNightMode()
            sendCameraEvent(CommandChannel.evtNightState(night?.isNightModeActive() ?: false))
        }
        binding.btnEndStream.setOnClickListener {
            AlertDialog.Builder(this).setTitle("End Stream?")
                .setPositiveButton("End") { _, _ -> finish() }
                .setNegativeButton("Cancel", null).show()
        }
        binding.motionBar.max = 100
    }

    /** Handle DataChannel commands from viewer */
    private fun handleViewerCommand(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                CommandChannel.CMD_ZOOM -> {
                    val ratio = obj.optDouble("value", 1.0).toFloat()
                    currentZoom = ratio
                    boundCamera?.cameraControl?.setZoomRatio(ratio)
                    Log.d(TAG, "Zoom set to $ratio")
                }
                CommandChannel.CMD_NIGHT_MODE -> {
                    val on = obj.optBoolean("on", false)
                    night?.applyNightMode(on)
                    sendCameraEvent(CommandChannel.evtNightState(on))
                }
                CommandChannel.CMD_TORCH -> {
                    val on = obj.optBoolean("on", false)
                    torchEnabled = on
                    boundCamera?.cameraControl?.enableTorch(on)
                    binding.btnTorch.alpha = if (on) 1f else 0.5f
                    sendCameraEvent(CommandChannel.evtTorchState(on))
                }
                CommandChannel.CMD_SWITCH_CAMERA -> {
                    isBackCamera = !isBackCamera
                    runOnUiThread { startCamera() }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "handleViewerCommand: ${e.message}") }
    }

    private fun sendCameraEvent(json: String) {
        webRTCManager?.sendCommand(json)
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
                runOnUiThread { updateStatus("❌ Connection failed", false) }
            }
            override fun onRemoteVideoReceived() {}
            override fun onDataChannelMessage(json: String) {
                runOnUiThread { handleViewerCommand(json) }
            }
        })
        webRTCManager?.initialize()
        webRtcCapturerObserver = webRTCManager?.createCameraXVideoSource()

        signalingClient = SignalingClient(serverUrl, roomCode, true, object : SignalingClient.SignalingListener {
            override fun onConnected() { runOnUiThread { updateStatus("⏳ Waiting for viewer...", false) } }
            override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected", false) } }
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
            override fun onPeerJoined() { runOnUiThread { isStreaming = true; updateStatus("🟢 Viewer Connected", true) } }
            override fun onPeerLeft() { runOnUiThread { isStreaming = false; updateStatus("⏳ Viewer disconnected", false) } }
            override fun onFrameReceived(d: ByteArray) {}
            override fun onStreamInfo(w: Int, h: Int) {}
            override fun onMotionEventReceived(t: Long) {}
            override fun onAiEventReceived(l: String, c: Float) {}
            override fun onError(m: String) { runOnUiThread { updateStatus("⚠️ $m", false) } }
        })
        wsStream?.connect()
    }

    // AI callbacks
    override fun onMotionAlert(score: Float, regions: List<MotionDetector.MotionRegion>) {
        runOnUiThread {
            binding.tvMotionAlert.visibility = View.VISIBLE
            binding.tvMotionAlert.postDelayed({ binding.tvMotionAlert.visibility = View.GONE }, 3000)
        }
        try { NotificationHelper.showMotionAlert(this, score) } catch (e: Exception) {}
        wsStream?.sendMotionEvent()
        sendCameraEvent(CommandChannel.evtMotion(score))
        recorder?.onMotionScore(score, AppPreferences.getRecordingDirectory(), AppPreferences.autoRecordOnMotion)
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
        sendCameraEvent(CommandChannel.evtObject(label, confidence))
    }
    override fun onFacesUpdate(faces: List<FaceDetectionManager.DetectedFace>) {
        runOnUiThread {
            if (faces.isEmpty()) binding.tvFaceLabel.visibility = View.GONE
            else { binding.tvFaceLabel.text = "👤 ${faces.size}"; binding.tvFaceLabel.visibility = View.VISIBLE }
        }
    }
    override fun onFaceAlert(count: Int) {
        try { NotificationHelper.showFaceAlert(this, count) } catch (e: Exception) {}
    }
    override fun onFaceRecognised(name: String, confidence: Float) {
        runOnUiThread {
            binding.tvFaceLabel.text = "✅ $name"
            binding.tvFaceLabel.visibility = View.VISIBLE
        }
        sendCameraEvent(CommandChannel.evtFace(name, true))
    }
    override fun onUnknownFace() {
        runOnUiThread {
            binding.tvFaceLabel.text = "❓ Unknown"
            binding.tvFaceLabel.visibility = View.VISIBLE
        }
        try { NotificationHelper.showUnknownFaceAlert(this) } catch (e: Exception) {}
        sendCameraEvent(CommandChannel.evtFace("Unknown", false))
    }
    override fun onNightModeChanged(isNight: Boolean) {
        runOnUiThread {
            binding.tvNightMode.visibility = if (isNight) View.VISIBLE else View.GONE
            binding.btnNightMode.alpha = if (isNight) 1f else 0.5f
        }
    }

    // RecordingManager callbacks
    override fun onRecordingStarted(path: String) {
        runOnUiThread {
            binding.tvRecordingIndicator.visibility = View.VISIBLE
            Toast.makeText(this, "🔴 Recording started", Toast.LENGTH_SHORT).show()
        }
        sendCameraEvent(CommandChannel.evtRecording(true))
    }
    override fun onRecordingStopped(path: String) {
        runOnUiThread {
            binding.tvRecordingIndicator.visibility = View.GONE
            Toast.makeText(this, "✅ Saved: ${path.substringAfterLast('/')}", Toast.LENGTH_LONG).show()
        }
        sendCameraEvent(CommandChannel.evtRecording(false))
    }
    override fun onRecordingError(msg: String) {
        runOnUiThread { Toast.makeText(this, "⚠️ Recording: $msg", Toast.LENGTH_SHORT).show() }
    }

    private fun updateStatus(msg: String, connected: Boolean) {
        runOnUiThread {
            binding.tvStatus.text = msg
            binding.statusDot.setBackgroundResource(
                if (connected) android.R.drawable.presence_online else android.R.drawable.presence_busy
            )
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == PermissionHelper.REQUEST_CODE) {
            if (PermissionHelper.hasAllPermissions(this))
                Handler(Looper.getMainLooper()).postDelayed({ init() }, 200)
            else Toast.makeText(this, "Camera & audio permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcCapturerObserver = null
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        try { ai?.release() } catch (e: Exception) {}
        try { night?.release() } catch (e: Exception) {}
        try { recorder?.release() } catch (e: Exception) {}
        try { webRTCManager?.release() } catch (e: Exception) {}
        try { signalingClient?.disconnect() } catch (e: Exception) {}
        try { wsStream?.disconnect() } catch (e: Exception) {}
        try { exec?.shutdown() } catch (e: Exception) {}
        try { stopService(Intent(this, CameraStreamingService::class.java)) } catch (e: Exception) {}
    }
}
