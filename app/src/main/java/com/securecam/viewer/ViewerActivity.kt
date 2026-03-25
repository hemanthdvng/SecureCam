package com.securecam.viewer

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.securecam.databinding.ActivityViewerBinding
import com.securecam.ui.ConnectionActivity
import com.securecam.utils.NotificationHelper
import com.securecam.webrtc.CommandChannel
import com.securecam.webrtc.SignalingClient
import com.securecam.webrtc.WebRTCManager
import com.securecam.websocket.WebSocketStreamManager
import org.json.JSONObject
import org.webrtc.IceCandidate

class ViewerActivity : AppCompatActivity() {

    private val TAG = "ViewerActivity"
    private lateinit var binding: ActivityViewerBinding

    private var roomCode = ""
    private var connectionType = ""
    private var serverUrl = ""

    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null
    private var wsStreamManager: WebSocketStreamManager? = null

    private var isFullscreen = false
    private var cameraConnected = false
    private var nightModeOn = false
    private var torchOn = false
    private var currentZoom = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        roomCode       = intent.getStringExtra(ConnectionActivity.EXTRA_ROOM_CODE) ?: ""
        connectionType = intent.getStringExtra(ConnectionActivity.EXTRA_CONNECTION_TYPE) ?: ConnectionActivity.TYPE_WEBRTC
        serverUrl      = intent.getStringExtra(ConnectionActivity.EXTRA_SERVER_URL) ?: ""

        setupUI()

        if (connectionType == ConnectionActivity.TYPE_WEBRTC) initWebRTC() else initWebSocket()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        binding.tvRoomCode.text = "Room: $roomCode"
        binding.tvConnectionType.text = connectionType.uppercase()
        updateStatus("Connecting…", false)

        // Show/hide controls on tap
        val tapTargets = listOf<View>(binding.remoteVideoView, binding.wsFrameView)
        tapTargets.forEach { v ->
            v.setOnClickListener {
                val vis = binding.controlsOverlay.visibility == View.VISIBLE
                binding.controlsOverlay.visibility = if (vis) View.GONE else View.VISIBLE
            }
        }

        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }
        binding.btnDisconnect.setOnClickListener { finish() }

        // Night mode toggle — sends command to camera
        binding.btnNightMode.setOnClickListener {
            nightModeOn = !nightModeOn
            binding.btnNightMode.alpha = if (nightModeOn) 1f else 0.5f
            binding.tvNightModeLabel.visibility = if (nightModeOn) View.VISIBLE else View.GONE
            sendCommand(CommandChannel.nightMode(nightModeOn))
        }

        // Torch toggle
        binding.btnTorch.setOnClickListener {
            torchOn = !torchOn
            binding.btnTorch.alpha = if (torchOn) 1f else 0.5f
            sendCommand(CommandChannel.torch(torchOn))
        }

        // Switch camera
        binding.btnSwitchCamera.setOnClickListener {
            sendCommand(CommandChannel.switchCamera())
            Toast.makeText(this, "Switching camera…", Toast.LENGTH_SHORT).show()
        }

        // Zoom seekbar (1x–5x)
        binding.seekZoom.max = 40  // 0..40 → 1x..5x
        binding.seekZoom.progress = 0
        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentZoom = 1f + progress / 10f   // 1.0 → 5.0
                binding.tvZoomLabel.text = "%.1f×".format(currentZoom)
                sendCommand(CommandChannel.zoom(currentZoom))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.tvZoomLabel.text = "1.0×"

        // PTT — send audio to camera while held
        binding.btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    webRTCManager?.setPttActive(true)
                    binding.btnPtt.alpha = 1f
                    binding.tvPttLabel.text = "🔴 Speaking"
                    binding.tvPttLabel.visibility = View.VISIBLE
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    webRTCManager?.setPttActive(false)
                    binding.btnPtt.alpha = 0.55f
                    binding.tvPttLabel.text = "Hold to Speak"
                    binding.tvPttLabel.visibility = View.GONE
                }
            }
            true
        }
    }

    private fun initWebRTC() {
        webRTCManager = WebRTCManager(this, false, object : WebRTCManager.WebRTCListener {
            override fun onLocalIceCandidate(candidate: IceCandidate) {
                signalingClient?.sendIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }
            override fun onConnectionEstablished() {
                runOnUiThread {
                    cameraConnected = true
                    updateStatus("🟢 Live", true)
                    binding.tvWaiting.visibility = View.GONE
                }
            }
            override fun onConnectionFailed() {
                runOnUiThread {
                    cameraConnected = false
                    updateStatus("❌ Stream Lost", false)
                    binding.tvWaiting.visibility = View.VISIBLE
                }
            }
            override fun onRemoteVideoReceived() {
                runOnUiThread {
                    binding.tvWaiting.visibility = View.GONE
                    binding.remoteVideoView.visibility = View.VISIBLE
                }
            }
            override fun onDataChannelMessage(json: String) {
                runOnUiThread { handleCameraEvent(json) }
            }
        })

        webRTCManager?.initialize()
        webRTCManager?.initRemoteRenderer(binding.remoteVideoView)
        webRTCManager?.createViewerMicTrack()

        signalingClient = SignalingClient(serverUrl, roomCode, false, object : SignalingClient.SignalingListener {
            override fun onConnected() { runOnUiThread { updateStatus("⏳ Waiting for camera…", false) } }
            override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected", false) } }
            override fun onPeerJoined() { runOnUiThread { updateStatus("Camera found — connecting…", false) } }
            override fun onPeerLeft() {
                runOnUiThread {
                    cameraConnected = false
                    updateStatus("⏳ Camera disconnected…", false)
                    binding.tvWaiting.visibility = View.VISIBLE
                }
            }
            override fun onOfferReceived(sdp: String) {
                webRTCManager?.createPeerConnection(binding.remoteVideoView)
                webRTCManager?.setRemoteOffer(sdp) { answer ->
                    signalingClient?.sendAnswer(answer.description)
                }
            }
            override fun onAnswerReceived(sdp: String) {}
            override fun onIceCandidateReceived(c: String, m: String, i: Int) {
                webRTCManager?.addIceCandidate(c, m, i)
            }
            override fun onError(message: String) { runOnUiThread { updateStatus("⚠️ $message", false) } }
        })
        signalingClient?.connect()
    }

    private fun handleCameraEvent(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                CommandChannel.EVT_MOTION -> {
                    val score = obj.optDouble("score", 0.0).toFloat()
                    showAlert(binding.tvMotionAlert, "⚠️ Motion: ${"%.0f".format(score * 100)}%", 3000)
                    NotificationHelper.showMotionAlert(this, score)
                }
                CommandChannel.EVT_FACE -> {
                    val name = obj.optString("label", "")
                    val known = obj.optBoolean("known", false)
                    if (known) {
                        showAlert(binding.tvAiAlert, "✅ $name", 3000)
                    } else {
                        showAlert(binding.tvAiAlert, "❓ Unknown person!", 5000)
                        NotificationHelper.showUnknownFaceAlert(this)
                    }
                }
                CommandChannel.EVT_OBJECT -> {
                    val label = obj.optString("label", "")
                    val conf = obj.optDouble("confidence", 0.0).toFloat()
                    showAlert(binding.tvAiAlert, "🤖 $label (${"%.0f".format(conf * 100)}%)", 3000)
                }
                CommandChannel.EVT_RECORDING -> {
                    val active = obj.optBoolean("active", false)
                    binding.tvRecordingIndicator.visibility = if (active) View.VISIBLE else View.GONE
                }
                CommandChannel.EVT_NIGHT_STATE -> {
                    nightModeOn = obj.optBoolean("on", false)
                    binding.btnNightMode.alpha = if (nightModeOn) 1f else 0.5f
                    binding.tvNightModeLabel.visibility = if (nightModeOn) View.VISIBLE else View.GONE
                }
                CommandChannel.EVT_TORCH_STATE -> {
                    torchOn = obj.optBoolean("on", false)
                    binding.btnTorch.alpha = if (torchOn) 1f else 0.5f
                }
            }
        } catch (e: Exception) {}
    }

    private fun showAlert(view: View, text: String, durationMs: Long) {
        if (view is android.widget.TextView) view.text = text
        view.visibility = View.VISIBLE
        view.removeCallbacks(null)
        view.postDelayed({ view.visibility = View.GONE }, durationMs)
    }

    private fun sendCommand(json: String) {
        webRTCManager?.sendCommand(json)
    }

    private fun initWebSocket() {
        wsStreamManager = WebSocketStreamManager(serverUrl, roomCode, false,
            object : WebSocketStreamManager.StreamListener {
                override fun onConnected() { runOnUiThread { updateStatus("⏳ Waiting…", false) } }
                override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected", false) } }
                override fun onPeerJoined() { runOnUiThread { updateStatus("Camera found…", false) } }
                override fun onPeerLeft() {
                    runOnUiThread {
                        updateStatus("📷 Camera disconnected", false)
                        binding.tvWaiting.visibility = View.VISIBLE
                    }
                }
                override fun onFrameReceived(frameData: ByteArray) {
                    val bmp = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
                    runOnUiThread {
                        binding.wsFrameView.setImageBitmap(bmp)
                        binding.wsFrameView.visibility = View.VISIBLE
                        binding.tvWaiting.visibility = View.GONE
                        if (!cameraConnected) { cameraConnected = true; updateStatus("🟢 Live (Relay)", true) }
                    }
                }
                override fun onStreamInfo(width: Int, height: Int) {
                    runOnUiThread { binding.tvResolution.text = "${width}×${height}"; binding.tvResolution.visibility = View.VISIBLE }
                }
                override fun onMotionEventReceived(timestamp: Long) {
                    runOnUiThread { showAlert(binding.tvMotionAlert, "⚠️ Motion Detected!", 4000) }
                    NotificationHelper.showMotionAlert(this@ViewerActivity, 0.6f)
                }
                override fun onAiEventReceived(label: String, confidence: Float) {
                    runOnUiThread { showAlert(binding.tvAiAlert, "🤖 $label (${"%.0f".format(confidence * 100)}%)", 3000) }
                }
                override fun onError(message: String) { runOnUiThread { updateStatus("⚠️ $message", false) } }
            })
        wsStreamManager?.connect()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            binding.controlsOverlay.visibility = View.GONE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        } else {
            binding.controlsOverlay.visibility = View.VISIBLE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun updateStatus(msg: String, connected: Boolean) {
        binding.tvStatus.text = msg
        binding.statusDot.setBackgroundResource(
            if (connected) android.R.drawable.presence_online else android.R.drawable.presence_busy
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        webRTCManager?.release()
        signalingClient?.disconnect()
        wsStreamManager?.disconnect()
    }
}
