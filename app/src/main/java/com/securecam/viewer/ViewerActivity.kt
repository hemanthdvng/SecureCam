package com.securecam.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.securecam.databinding.ActivityViewerBinding
import com.securecam.ui.ConnectionActivity
import com.securecam.utils.AppPreferences
import com.securecam.utils.NotificationHelper
import com.securecam.webrtc.CommandChannel
import com.securecam.webrtc.SignalingClient
import com.securecam.webrtc.WebRTCManager
import com.securecam.websocket.WebSocketStreamManager
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class ViewerActivity : AppCompatActivity() {

    private val TAG = "ViewerActivity"
    private lateinit var binding: ActivityViewerBinding

    private var roomCode = ""
    private var connectionType = ""
    private var serverUrl = ""

    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null
    private var wsStreamManager: WebSocketStreamManager? = null

    private var isFullscreen    = false
    private var cameraConnected = false
    private var nightModeOn     = false
    private var torchOn         = false
    private var currentZoom     = 1f
    private var isViewerRecording = false

    // Camera recording server info (set when EVT_RECORDING_SERVER received)
    private var cameraRecordingIp: String?   = null
    private var cameraHttpPort: Int          = 8765
    private var cameraRecordingFiles: JSONArray? = null

    private var currentTab = TAB_LIVE

    companion object {
        const val TAB_LIVE       = "live"
        const val TAB_RECORDINGS = "recordings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppPreferences.init(this)

        roomCode       = intent.getStringExtra(ConnectionActivity.EXTRA_ROOM_CODE) ?: ""
        connectionType = intent.getStringExtra(ConnectionActivity.EXTRA_CONNECTION_TYPE) ?: ConnectionActivity.TYPE_WEBRTC
        serverUrl      = intent.getStringExtra(ConnectionActivity.EXTRA_SERVER_URL) ?: ""

        setupUI()
        if (connectionType == ConnectionActivity.TYPE_WEBRTC) initWebRTC() else initWebSocket()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        binding.tvRoomCode.text      = "Room: $roomCode"
        binding.tvConnectionType.text = connectionType.uppercase()
        updateStatus("Connecting…", false)

        binding.btnTabLive.setOnClickListener       { switchTab(TAB_LIVE) }
        binding.btnTabRecordings.setOnClickListener { switchTab(TAB_RECORDINGS) }

        listOf<View>(binding.remoteVideoView, binding.wsFrameView).forEach { v ->
            v.setOnClickListener {
                val vis = binding.controlsOverlay.visibility == View.VISIBLE
                binding.controlsOverlay.visibility = if (vis) View.GONE else View.VISIBLE
            }
        }

        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }
        binding.btnDisconnect.setOnClickListener { finish() }

        binding.btnNightMode.setOnClickListener {
            nightModeOn = !nightModeOn
            binding.btnNightMode.alpha = if (nightModeOn) 1f else 0.5f
            binding.tvNightModeLabel.visibility = if (nightModeOn) View.VISIBLE else View.GONE
            sendCommand(CommandChannel.nightMode(nightModeOn))
        }

        binding.btnTorch.setOnClickListener {
            torchOn = !torchOn
            binding.btnTorch.alpha = if (torchOn) 1f else 0.5f
            sendCommand(CommandChannel.torch(torchOn))
        }

        binding.btnSwitchCamera.setOnClickListener {
            sendCommand(CommandChannel.switchCamera())
            Toast.makeText(this, "Switching camera…", Toast.LENGTH_SHORT).show()
        }

        // Snapshot button — saves a still frame on the camera phone
        binding.btnSnapshot.setOnClickListener {
            if (!cameraConnected) {
                Toast.makeText(this, "Not connected to camera", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendCommand(CommandChannel.snapshot())
            Toast.makeText(this, "📸 Requesting snapshot…", Toast.LENGTH_SHORT).show()
        }

        // Manual record toggle
        binding.btnRecord.setOnClickListener {
            isViewerRecording = !isViewerRecording
            binding.btnRecord.alpha = if (isViewerRecording) 1f else 0.5f
            binding.tvRecordingIndicator.visibility = if (isViewerRecording) View.VISIBLE else View.GONE
            sendCommand(CommandChannel.recordToggle(isViewerRecording))
            Toast.makeText(
                this,
                if (isViewerRecording) "⏺ Recording started" else "⏹ Recording stopped",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.seekZoom.max = 40
        binding.seekZoom.progress = 0
        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentZoom = 1f + progress / 10f
                binding.tvZoomLabel.text = "%.1f×".format(currentZoom)
                sendCommand(CommandChannel.zoom(currentZoom))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.tvZoomLabel.text = "1.0×"

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
                    binding.tvPttLabel.visibility = View.GONE
                }
            }
            true
        }

        switchTab(TAB_LIVE)
    }

    // ── Tab switching ─────────────────────────────────────────────────────────

    private fun switchTab(tab: String) {
        currentTab = tab
        val liveActive = tab == TAB_LIVE

        binding.btnTabLive.alpha       = if (liveActive) 1f else 0.45f
        binding.btnTabRecordings.alpha = if (!liveActive) 1f else 0.45f
        binding.livePanel.visibility       = if (liveActive) View.VISIBLE else View.GONE
        binding.recordingsPanel.visibility = if (!liveActive) View.VISIBLE else View.GONE

        if (!liveActive) {
            if (cameraConnected && connectionType == ConnectionActivity.TYPE_WEBRTC) {
                // Request recordings from camera
                binding.tvRecordingSource.text = "📡 Camera Recordings"
                binding.tvRecordingSource.visibility = View.VISIBLE
                sendCommand(CommandChannel.listRecordings())
                binding.tvNoRecordings.text = "Requesting recordings from camera…"
                binding.tvNoRecordings.visibility = View.VISIBLE
                binding.rvRecordings.visibility = View.GONE
            } else {
                binding.tvRecordingSource.text = "💾 Local Recordings"
                binding.tvRecordingSource.visibility = View.VISIBLE
                loadLocalRecordings()
            }
        }
    }

    // ── Recordings: local fallback ────────────────────────────────────────────

    private fun loadLocalRecordings() {
        val dir   = AppPreferences.getRecordingDirectory()
        val files = dir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            binding.tvNoRecordings.text = "📭\n\nNo recordings yet."
            binding.tvNoRecordings.visibility = View.VISIBLE
            binding.rvRecordings.visibility   = View.GONE
            return
        }
        binding.tvNoRecordings.visibility = View.GONE
        binding.rvRecordings.visibility   = View.VISIBLE
        binding.rvRecordings.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvRecordings.adapter = LocalRecordingsAdapter(files) { file -> playLocalFile(file) }
    }

    // ── Recordings: camera streaming ──────────────────────────────────────────

    private fun loadCameraRecordings(filesJson: String) {
        try {
            val arr = JSONArray(filesJson)
            if (arr.length() == 0) {
                binding.tvNoRecordings.text = "📭 No recordings on camera."
                binding.tvNoRecordings.visibility = View.VISIBLE
                binding.rvRecordings.visibility   = View.GONE
                return
            }
            val items = mutableListOf<RemoteRecordingItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(RemoteRecordingItem(
                    name     = obj.getString("name"),
                    sizeMb   = obj.optString("sizeMb", "?"),
                    modified = obj.optLong("modified", 0L)
                ))
            }
            binding.tvNoRecordings.visibility = View.GONE
            binding.rvRecordings.visibility   = View.VISIBLE
            binding.rvRecordings.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            binding.rvRecordings.adapter = RemoteRecordingsAdapter(items) { item -> playRemoteRecording(item.name) }
        } catch (e: Exception) {
            binding.tvNoRecordings.text = "⚠️ Failed to parse recording list"
            binding.tvNoRecordings.visibility = View.VISIBLE
        }
    }

    private fun playLocalFile(file: File) {
        try {
            val player = ExoPlayer.Builder(this).build()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
            showPlayerDialog(file.name, player)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playRemoteRecording(fileName: String) {
        val ip = cameraRecordingIp
        if (ip == null) {
            Toast.makeText(this, "Camera server address not received yet", Toast.LENGTH_SHORT).show()
            return
        }
        val encodedName = URLEncoder.encode(fileName, "UTF-8")
        val url = "http://$ip:$cameraHttpPort/recording/$encodedName"
        try {
            val player = ExoPlayer.Builder(this).build()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
            showPlayerDialog(fileName, player)
        } catch (e: Exception) {
            // Fallback: open in external app
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { type = "video/mp4" })
            } catch (ex: Exception) {
                Toast.makeText(this, "No player available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showPlayerDialog(title: String, player: ExoPlayer) {
        val playerView = PlayerView(this)
        playerView.player = player
        AlertDialog.Builder(this)
            .setTitle(title.take(40))
            .setView(playerView)
            .setOnDismissListener { player.release() }
            .setNegativeButton("✕ Close") { d, _ -> d.dismiss() }
            .show()
    }

    // ── WebRTC event handler ──────────────────────────────────────────────────

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
                    val name  = obj.optString("label", "")
                    val known = obj.optBoolean("known", false)
                    if (known) {
                        showAlert(binding.tvAiAlert, "✅ $name", 3000)
                        NotificationHelper.showFaceRecognisedAlert(this, name)
                    } else {
                        showAlert(binding.tvAiAlert, "❓ Unknown person!", 5000)
                        NotificationHelper.showUnknownFaceAlert(this)
                    }
                }
                CommandChannel.EVT_OBJECT -> {
                    val label = obj.optString("label", "")
                    val conf  = obj.optDouble("confidence", 0.0).toFloat()
                    val group = obj.optString("group", "object")
                    showAlert(binding.tvAiAlert, "${groupIcon(group)} $label (${"%.0f".format(conf * 100)}%)", 3000)
                }
                CommandChannel.EVT_RECORDING -> {
                    val active = obj.optBoolean("active", false)
                    binding.tvRecordingIndicator.visibility = if (active) View.VISIBLE else View.GONE
                    if (!active && currentTab == TAB_RECORDINGS) {
                        // Refresh list after recording finishes
                        sendCommand(CommandChannel.listRecordings())
                    }
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
                CommandChannel.EVT_SNAPSHOT_SAVED -> {
                    Toast.makeText(this, "📸 Snapshot saved on camera", Toast.LENGTH_SHORT).show()
                }
                CommandChannel.EVT_RECORDING_SERVER -> {
                    cameraRecordingIp   = obj.optString("ip", null)
                    cameraHttpPort      = obj.optInt("port", 8765)
                }
                CommandChannel.EVT_RECORDING_LIST -> {
                    val filesJson = obj.optString("files", "[]")
                    cameraRecordingFiles = JSONArray(filesJson)
                    if (currentTab == TAB_RECORDINGS) {
                        loadCameraRecordings(filesJson)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun groupIcon(group: String) = when (group) {
        "person"  -> "🧍"; "vehicle" -> "🚗"; "package" -> "📦"; "animal" -> "🐾"; else -> "🔍"
    }

    private fun showAlert(view: View, text: String, durationMs: Long) {
        if (view is android.widget.TextView) view.text = text
        view.visibility = View.VISIBLE
        view.removeCallbacks(null)
        view.postDelayed({ view.visibility = View.GONE }, durationMs)
    }

    private fun sendCommand(json: String) { webRTCManager?.sendCommand(json) }

    // ── WebRTC init ───────────────────────────────────────────────────────────

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
                    // Enable snapshot/record buttons
                    binding.btnSnapshot.isEnabled = true
                    binding.btnRecord.isEnabled   = true
                    binding.btnSnapshot.alpha     = 1f
                    binding.btnRecord.alpha       = 0.5f
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
                    binding.tvWaiting.visibility    = View.GONE
                    binding.remoteVideoView.visibility = View.VISIBLE
                }
            }
            override fun onDataChannelMessage(json: String) { runOnUiThread { handleCameraEvent(json) } }
        })
        webRTCManager?.initialize()
        webRTCManager?.initRemoteRenderer(binding.remoteVideoView)
        webRTCManager?.createViewerMicTrack()

        signalingClient = SignalingClient(serverUrl, roomCode, false, object : SignalingClient.SignalingListener {
            override fun onConnected()    { runOnUiThread { updateStatus("⏳ Waiting for camera…", false) } }
            override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected", false) } }
            override fun onPeerJoined()   { runOnUiThread { updateStatus("Camera found — connecting…", false) } }
            override fun onPeerLeft()     {
                runOnUiThread {
                    cameraConnected = false
                    updateStatus("⏳ Camera disconnected…", false)
                    binding.tvWaiting.visibility = View.VISIBLE
                }
            }
            override fun onOfferReceived(sdp: String) {
                webRTCManager?.createPeerConnection(binding.remoteVideoView)
                webRTCManager?.setRemoteOffer(sdp) { answer -> signalingClient?.sendAnswer(answer.description) }
            }
            override fun onAnswerReceived(sdp: String) {}
            override fun onIceCandidateReceived(c: String, m: String, i: Int) { webRTCManager?.addIceCandidate(c, m, i) }
            override fun onError(message: String) { runOnUiThread { updateStatus("⚠️ $message", false) } }
        })
        signalingClient?.connect()
    }

    // ── WebSocket relay init ──────────────────────────────────────────────────

    private fun initWebSocket() {
        wsStreamManager = WebSocketStreamManager(serverUrl, roomCode, false,
            object : WebSocketStreamManager.StreamListener {
                override fun onConnected()    { runOnUiThread { updateStatus("⏳ Waiting…", false) } }
                override fun onDisconnected() { runOnUiThread { updateStatus("Disconnected", false) } }
                override fun onPeerJoined()   { runOnUiThread { updateStatus("Camera found…", false) } }
                override fun onPeerLeft()     {
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
                        binding.tvWaiting.visibility   = View.GONE
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

    // ── Fullscreen ────────────────────────────────────────────────────────────

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        binding.controlsOverlay.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (isFullscreen)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun updateStatus(msg: String, connected: Boolean) {
        binding.tvStatus.text = msg
        binding.statusDot.setBackgroundResource(
            if (connected) android.R.drawable.presence_online else android.R.drawable.presence_busy
        )
    }

    override fun onResume() {
        super.onResume()
        if (currentTab == TAB_RECORDINGS) switchTab(TAB_RECORDINGS)
    }

    override fun onDestroy() {
        super.onDestroy()
        webRTCManager?.release()
        signalingClient?.disconnect()
        wsStreamManager?.disconnect()
    }
}

// ── Data models ────────────────────────────────────────────────────────────────

data class RemoteRecordingItem(val name: String, val sizeMb: String, val modified: Long)

// ── Local recordings adapter ──────────────────────────────────────────────────

class LocalRecordingsAdapter(
    private val files: List<File>,
    private val onPlay: (File) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<LocalRecordingsAdapter.VH>() {

    class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvName: android.widget.TextView = view.findViewById(com.securecam.R.id.tvRecFileName)
        val tvMeta: android.widget.TextView = view.findViewById(com.securecam.R.id.tvRecMeta)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        VH(android.view.LayoutInflater.from(parent.context)
            .inflate(com.securecam.R.layout.item_recording, parent, false))

    override fun getItemCount() = files.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f    = files[position]
        val date = SimpleDateFormat("MMM dd  HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
        val mb   = "%.1f MB".format(f.length() / 1_048_576.0)
        holder.tvName.text = f.name
        holder.tvMeta.text = "$date  ·  $mb"
        holder.itemView.setOnClickListener { onPlay(f) }
    }
}

// ── Remote (camera) recordings adapter ───────────────────────────────────────

class RemoteRecordingsAdapter(
    private val items: List<RemoteRecordingItem>,
    private val onPlay: (RemoteRecordingItem) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<RemoteRecordingsAdapter.VH>() {

    class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvName: android.widget.TextView = view.findViewById(com.securecam.R.id.tvRecFileName)
        val tvMeta: android.widget.TextView = view.findViewById(com.securecam.R.id.tvRecMeta)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        VH(android.view.LayoutInflater.from(parent.context)
            .inflate(com.securecam.R.layout.item_recording, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val date = if (item.modified > 0)
            SimpleDateFormat("MMM dd  HH:mm", Locale.getDefault()).format(Date(item.modified))
        else "—"
        holder.tvName.text = item.name
        holder.tvMeta.text = "$date  ·  ${item.sizeMb} MB  📡"
        holder.itemView.setOnClickListener { onPlay(item) }
    }
}
