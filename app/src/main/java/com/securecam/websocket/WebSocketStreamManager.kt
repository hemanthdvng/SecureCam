package com.securecam.websocket

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * WebSocket-based stream relay.
 * Camera phone encodes frames as JPEG and sends over WebSocket.
 * Viewer phone receives and decodes frames.
 */
class WebSocketStreamManager(
    private val serverUrl: String,
    private val roomCode: String,
    private val isCamera: Boolean,
    private val listener: StreamListener
) {
    private val TAG = "WebSocketStream"
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Frame send throttle: max 15 FPS for WebSocket mode
    private var lastFrameTime = 0L
    private val minFrameInterval = 66L // ~15fps

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                val joinMsg = JsonObject().apply {
                    addProperty("type", "join_stream")
                    addProperty("room", roomCode)
                    addProperty("role", if (isCamera) "sender" else "receiver")
                }
                ws.send(gson.toJson(joinMsg))
                scope.launch(Dispatchers.Main) { listener.onConnected() }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Binary frame data from camera
                scope.launch(Dispatchers.Main) {
                    listener.onFrameReceived(bytes.toByteArray())
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = gson.fromJson(text, JsonObject::class.java)
                    val type = json.get("type")?.asString ?: return
                    scope.launch(Dispatchers.Main) {
                        when (type) {
                            "peer_joined" -> listener.onPeerJoined()
                            "peer_left" -> listener.onPeerLeft()
                            "stream_info" -> {
                                val width = json.get("width")?.asInt ?: 0
                                val height = json.get("height")?.asInt ?: 0
                                listener.onStreamInfo(width, height)
                            }
                            "motion_event" -> {
                                val timestamp = json.get("timestamp")?.asLong ?: 0
                                listener.onMotionEventReceived(timestamp)
                            }
                            "ai_event" -> {
                                val label = json.get("label")?.asString ?: ""
                                val confidence = json.get("confidence")?.asFloat ?: 0f
                                listener.onAiEventReceived(label, confidence)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                scope.launch(Dispatchers.Main) { listener.onDisconnected() }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                scope.launch(Dispatchers.Main) {
                    listener.onError("Connection failed: ${t.message}")
                }
            }
        })
    }

    /**
     * Send a camera frame as compressed JPEG binary
     */
    fun sendFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < minFrameInterval) return
        lastFrameTime = now

        scope.launch {
            try {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                val bytes = baos.toByteArray()
                webSocket?.send(bytes.toByteString())
            } catch (e: Exception) {
                Log.e(TAG, "Frame send error: ${e.message}")
            }
        }
    }

    /**
     * Send motion detection event to viewer
     */
    fun sendMotionEvent() {
        val msg = JsonObject().apply {
            addProperty("type", "motion_event")
            addProperty("room", roomCode)
            addProperty("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(gson.toJson(msg))
    }

    /**
     * Send AI detection result to viewer
     */
    fun sendAiEvent(label: String, confidence: Float) {
        val msg = JsonObject().apply {
            addProperty("type", "ai_event")
            addProperty("room", roomCode)
            addProperty("label", label)
            addProperty("confidence", confidence)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        scope.cancel()
    }

    interface StreamListener {
        fun onConnected()
        fun onDisconnected()
        fun onPeerJoined()
        fun onPeerLeft()
        fun onFrameReceived(frameData: ByteArray)
        fun onStreamInfo(width: Int, height: Int)
        fun onMotionEventReceived(timestamp: Long)
        fun onAiEventReceived(label: String, confidence: Float)
        fun onError(message: String)
    }
}
