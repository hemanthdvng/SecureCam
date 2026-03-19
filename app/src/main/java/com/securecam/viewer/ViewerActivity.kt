package com.securecam.viewer

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.securecam.databinding.ActivityViewerBinding
import com.securecam.ui.ConnectionActivity
import com.securecam.utils.NotificationHelper
import com.securecam.webrtc.SignalingClient
import com.securecam.webrtc.WebRTCManager
import com.securecam.websocket.WebSocketStreamManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        roomCode = intent.getStringExtra(ConnectionActivity.EXTRA_ROOM_CODE) ?: ""
        connectionType = intent.getStringExtra(ConnectionActivity.EXTRA_CONNECTION_TYPE)
            ?: ConnectionActivity.TYPE_WEBRTC
        serverUrl = intent.getStringExtra(ConnectionActivity.EXTRA_SERVER_URL) ?: ""

        setupUI()

        if (connectionType == ConnectionActivity.TYPE_WEBRTC) {
            initWebRTC()
        } else {
            initWebSocket()
        }
    }

    private fun setupUI() {
        binding.tvRoomCode.text = "Room: $roomCode"
        binding.tvConnectionType.text = connectionType.uppercase()
        updateStatus("Connecting…", false)

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        binding.btnDisconnect.setOnClickListener {
            finish()
        }

        // Tap on video to show/hide controls
        binding.remoteVideoView.setOnClickListener {
            val visible = binding.controlsOverlay.visibility == View.VISIBLE
            binding.controlsOverlay.visibility = if (visible) View.GONE else View.VISIBLE
        }
    }

    private fun initWebRTC() {
        webRTCManager = WebRTCManager(this, false, object : WebRTCManager.WebRTCListener {
            override fun onLocalIceCandidate(candidate: IceCandidate) {
                signalingClient?.sendIceCandidate(
                    candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
                )
            }
            override fun onConnectionEstablished() {
                runOnUiThread {
                    cameraConnected = true
                    updateStatus("🟢 Live Stream", true)
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
        })

        webRTCManager?.initialize()
        webRTCManager?.initRemoteRenderer(binding.remoteVideoView)

        signalingClient = SignalingClient(serverUrl, roomCode, false, object : SignalingClient.SignalingListener {
            override fun onConnected() {
                runOnUiThread { updateStatus("⏳ Waiting for camera…", false) }
            }
            override fun onDisconnected() {
                runOnUiThread { updateStatus("Disconnected", false) }
            }
            override fun onPeerJoined() {
                runOnUiThread { updateStatus("Camera found — connecting…", false) }
            }
            override fun onPeerLeft() {
                runOnUiThread {
                    cameraConnected = false
                    updateStatus("⏳ Camera disconnected — waiting…", false)
                    binding.tvWaiting.visibility = View.VISIBLE
                }
            }
            override fun onOfferReceived(sdp: String) {
                val pc = webRTCManager?.createPeerConnection(binding.remoteVideoView)
                webRTCManager?.setRemoteOffer(sdp) { answer ->
                    signalingClient?.sendAnswer(answer.description)
                }
            }
            override fun onAnswerReceived(sdp: String) {}
            override fun onIceCandidateReceived(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                webRTCManager?.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
            }
            override fun onError(message: String) {
                runOnUiThread { updateStatus("⚠️ $message", false) }
            }
        })
        signalingClient?.connect()
    }

    private fun initWebSocket() {
        wsStreamManager = WebSocketStreamManager(
            serverUrl, roomCode, false,
            object : WebSocketStreamManager.StreamListener {
                override fun onConnected() {
                    runOnUiThread { updateStatus("⏳ Waiting for camera…", false) }
                }
                override fun onDisconnected() {
                    runOnUiThread { updateStatus("Disconnected", false) }
                }
                override fun onPeerJoined() {
                    runOnUiThread { updateStatus("Camera found — streaming…", false) }
                }
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
                        if (!cameraConnected) {
                            cameraConnected = true
                            updateStatus("🟢 Live (Relay)", true)
                        }
                    }
                }
                override fun onStreamInfo(width: Int, height: Int) {
                    runOnUiThread {
                        binding.tvResolution.text = "${width}×${height}"
                        binding.tvResolution.visibility = View.VISIBLE
                    }
                }
                override fun onMotionEventReceived(timestamp: Long) {
                    runOnUiThread {
                        binding.tvMotionAlert.text = "⚠️ Motion Detected!"
                        binding.tvMotionAlert.visibility = View.VISIBLE
                        binding.tvMotionAlert.postDelayed({
                            binding.tvMotionAlert.visibility = View.GONE
                        }, 4000)
                    }
                    NotificationHelper.showMotionAlert(this@ViewerActivity, 0.6f)
                }
                override fun onAiEventReceived(label: String, confidence: Float) {
                    runOnUiThread {
                        binding.tvAiAlert.text = "🤖 $label (${"%.0f".format(confidence * 100)}%)"
                        binding.tvAiAlert.visibility = View.VISIBLE
                        binding.tvAiAlert.postDelayed({
                            binding.tvAiAlert.visibility = View.GONE
                        }, 3000)
                    }
                    NotificationHelper.showObjectDetectionAlert(this@ViewerActivity, label, confidence)
                }
                override fun onError(message: String) {
                    runOnUiThread { updateStatus("⚠️ $message", false) }
                }
            }
        )
        wsStreamManager?.connect()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            binding.controlsOverlay.visibility = View.GONE
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        } else {
            binding.controlsOverlay.visibility = View.VISIBLE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun updateStatus(msg: String, connected: Boolean) {
        binding.tvStatus.text = msg
        binding.statusDot.setBackgroundResource(
            if (connected) android.R.drawable.presence_online
            else android.R.drawable.presence_busy
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        webRTCManager?.release()
        signalingClient?.disconnect()
        wsStreamManager?.disconnect()
    }
}
