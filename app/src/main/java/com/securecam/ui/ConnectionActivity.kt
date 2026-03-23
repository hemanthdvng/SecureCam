package com.securecam.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.securecam.camera.CameraActivity
import com.securecam.databinding.ActivityConnectionBinding
import com.securecam.utils.AppPreferences
import com.securecam.viewer.ViewerActivity
import java.util.UUID

class ConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionBinding
    private var mode = MODE_CAMERA
    private var connectionType = TYPE_WEBRTC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CAMERA
        val prefilledRoom = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""

        setupUI(prefilledRoom)
    }

    private fun setupUI(prefilledRoom: String) {
        binding.tvTitle.text = if (mode == MODE_CAMERA) "📷 Camera Mode" else "👁️ Viewer Mode"
        binding.tvSubtitle.text = if (mode == MODE_CAMERA)
            "This phone will stream video" else "This phone will receive video"

        // Pre-fill last room code
        val lastRoom = prefilledRoom.ifEmpty { AppPreferences.lastRoomCode }
        if (lastRoom.isNotEmpty()) binding.etRoomCode.setText(lastRoom)

        // Pre-fill server URL from Settings if saved
        val savedServer = AppPreferences.customSignalingServer
        if (savedServer.isNotEmpty()) {
            binding.etServerUrl.setText(savedServer)
        }

        binding.btnGenerate.setOnClickListener {
            binding.etRoomCode.setText(UUID.randomUUID().toString().take(8).uppercase())
        }

        binding.rgConnectionType.setOnCheckedChangeListener { _, checkedId ->
            connectionType = when (checkedId) {
                binding.rbWebRTC.id -> TYPE_WEBRTC
                binding.rbWebSocket.id -> TYPE_WEBSOCKET
                else -> TYPE_WEBRTC
            }
            updateServerUrlVisibility()
        }

        // Default WebRTC
        binding.rbWebRTC.isChecked = true
        updateServerUrlVisibility()

        binding.btnStart.setOnClickListener {
            val roomCode = binding.etRoomCode.text.toString().trim()
            if (roomCode.isEmpty()) {
                binding.etRoomCode.error = "Enter a room code"
                return@setOnClickListener
            }

            // Build server URL — priority:
            // 1. Manually typed in this screen
            // 2. Saved in Settings (AppPreferences)
            // 3. Hardcoded fallback (should never be used in production)
            val typedUrl = binding.etServerUrl.text.toString().trim()
            val savedUrl = AppPreferences.customSignalingServer
            val finalUrl = when {
                typedUrl.isNotEmpty() -> typedUrl
                savedUrl.isNotEmpty() -> savedUrl
                else -> DEFAULT_SIGNALING_URL
            }

            // WebSocket mode requires a server URL
            if (connectionType == TYPE_WEBSOCKET && finalUrl == DEFAULT_SIGNALING_URL) {
                binding.etServerUrl.error = "Enter your server URL"
                binding.etServerUrl.visibility = View.VISIBLE
                binding.tvServerUrlLabel.visibility = View.VISIBLE
                return@setOnClickListener
            }

            AppPreferences.lastRoomCode = roomCode

            // Also save the server URL if user typed one
            if (typedUrl.isNotEmpty()) {
                AppPreferences.customSignalingServer = typedUrl
            }

            val intent = if (mode == MODE_CAMERA) {
                Intent(this, CameraActivity::class.java)
            } else {
                Intent(this, ViewerActivity::class.java)
            }

            intent.putExtra(EXTRA_MODE, mode)
            intent.putExtra(EXTRA_ROOM_CODE, roomCode)
            intent.putExtra(EXTRA_CONNECTION_TYPE, connectionType)
            intent.putExtra(EXTRA_SERVER_URL, finalUrl)

            startActivity(intent)
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun updateServerUrlVisibility() {
        val isWS = connectionType == TYPE_WEBSOCKET
        binding.tvServerUrlLabel.visibility = if (isWS) View.VISIBLE else View.GONE
        binding.etServerUrl.visibility = if (isWS) View.VISIBLE else View.GONE
        binding.tvSignalingNote.visibility = if (isWS) View.GONE else View.VISIBLE

        // For WebRTC, show the saved URL as a hint so user knows it's being used
        if (!isWS) {
            val saved = AppPreferences.customSignalingServer
            if (saved.isNotEmpty()) {
                binding.tvSignalingNote.text = "ℹ️ Using server: $saved"
            } else {
                binding.tvSignalingNote.text = "ℹ️ Go to Settings and enter your server URL"
            }
        }
    }

    companion object {
        const val MODE_CAMERA = "camera"
        const val MODE_VIEWER = "viewer"
        const val TYPE_WEBRTC = "webrtc"
        const val TYPE_WEBSOCKET = "websocket"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_ROOM_CODE = "extra_room_code"
        const val EXTRA_CONNECTION_TYPE = "extra_connection_type"
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val DEFAULT_SIGNALING_URL = "wss://your-signaling-server.com"
    }
}
