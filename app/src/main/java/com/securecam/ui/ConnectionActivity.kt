package com.securecam.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.securecam.camera.CameraActivity
import com.securecam.databinding.ActivityConnectionBinding
import com.securecam.utils.AppPreferences
import com.securecam.viewer.ViewerActivity
import java.util.UUID

class ConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionBinding
    private var mode: String = MODE_CAMERA
    private var connectionType: String = TYPE_WEBRTC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CAMERA
        val prefilledRoom = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""

        setupUI(prefilledRoom)
    }

    private fun setupUI(prefilledRoom: String) {
        // Title based on mode
        binding.tvTitle.text = if (mode == MODE_CAMERA) "📷 Camera Mode" else "👁️ Viewer Mode"
        binding.tvSubtitle.text = if (mode == MODE_CAMERA)
            "This phone will stream video" else "This phone will receive video"

        // Pre-fill room code if available
        if (prefilledRoom.isNotEmpty()) {
            binding.etRoomCode.setText(prefilledRoom)
        }

        // Generate random room code button
        binding.btnGenerate.setOnClickListener {
            val code = UUID.randomUUID().toString().take(8).uppercase()
            binding.etRoomCode.setText(code)
        }

        // Connection type toggle
        binding.rgConnectionType.setOnCheckedChangeListener { _, checkedId ->
            connectionType = when (checkedId) {
                binding.rbWebRTC.id -> TYPE_WEBRTC
                binding.rbWebSocket.id -> TYPE_WEBSOCKET
                else -> TYPE_WEBRTC
            }
            updateServerUrlVisibility()
        }

        // Default to WebRTC
        binding.rbWebRTC.isChecked = true
        updateServerUrlVisibility()

        // Start button
        binding.btnStart.setOnClickListener {
            val roomCode = binding.etRoomCode.text.toString().trim()
            val serverUrl = binding.etServerUrl.text.toString().trim()

            if (roomCode.isEmpty()) {
                binding.etRoomCode.error = "Enter a room code"
                return@setOnClickListener
            }

            if (connectionType == TYPE_WEBSOCKET && serverUrl.isEmpty()) {
                binding.etServerUrl.error = "Enter server URL for WebSocket mode"
                return@setOnClickListener
            }

            AppPreferences.lastRoomCode = roomCode

            val intent = if (mode == MODE_CAMERA) {
                Intent(this, CameraActivity::class.java)
            } else {
                Intent(this, ViewerActivity::class.java)
            }

            intent.apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_ROOM_CODE, roomCode)
                putExtra(EXTRA_CONNECTION_TYPE, connectionType)
                putExtra(EXTRA_SERVER_URL, serverUrl.ifEmpty {
                    DEFAULT_SIGNALING_URL
                })
            }

            startActivity(intent)
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun updateServerUrlVisibility() {
        binding.tvServerUrlLabel.visibility =
            if (connectionType == TYPE_WEBSOCKET) View.VISIBLE else View.GONE
        binding.etServerUrl.visibility =
            if (connectionType == TYPE_WEBSOCKET) View.VISIBLE else View.GONE
        binding.tvSignalingNote.visibility =
            if (connectionType == TYPE_WEBRTC) View.VISIBLE else View.GONE
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

        // Default signaling server — replace with your deployed server URL
        const val DEFAULT_SIGNALING_URL = "wss://your-signaling-server.com"
    }
}
