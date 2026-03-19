package com.securecam.webrtc

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val roomCode: String,
    private val isCamera: Boolean,
    private val listener: SignalingListener
) {
    private val TAG = "SignalingClient"
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Signaling connected")
                // Join the room
                val joinMsg = JsonObject().apply {
                    addProperty("type", "join")
                    addProperty("room", roomCode)
                    addProperty("role", if (isCamera) "camera" else "viewer")
                }
                ws.send(gson.toJson(joinMsg))
                scope.launch(Dispatchers.Main) { listener.onConnected() }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Signaling message: $text")
                try {
                    val json = gson.fromJson(text, JsonObject::class.java)
                    val type = json.get("type")?.asString ?: return

                    scope.launch(Dispatchers.Main) {
                        when (type) {
                            "offer" -> listener.onOfferReceived(json.get("sdp").asString)
                            "answer" -> listener.onAnswerReceived(json.get("sdp").asString)
                            "ice_candidate" -> {
                                val candidate = json.get("candidate").asString
                                val sdpMid = json.get("sdpMid")?.asString ?: ""
                                val sdpMLineIndex = json.get("sdpMLineIndex")?.asInt ?: 0
                                listener.onIceCandidateReceived(candidate, sdpMid, sdpMLineIndex)
                            }
                            "peer_joined" -> listener.onPeerJoined()
                            "peer_left" -> listener.onPeerLeft()
                            "error" -> listener.onError(json.get("message")?.asString ?: "Unknown error")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {}

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                scope.launch(Dispatchers.Main) { listener.onDisconnected() }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling failure: ${t.message}")
                scope.launch(Dispatchers.Main) { listener.onError(t.message ?: "Connection failed") }
            }
        })
    }

    fun sendOffer(sdp: String) {
        val msg = JsonObject().apply {
            addProperty("type", "offer")
            addProperty("sdp", sdp)
            addProperty("room", roomCode)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendAnswer(sdp: String) {
        val msg = JsonObject().apply {
            addProperty("type", "answer")
            addProperty("sdp", sdp)
            addProperty("room", roomCode)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val msg = JsonObject().apply {
            addProperty("type", "ice_candidate")
            addProperty("candidate", candidate)
            addProperty("sdpMid", sdpMid)
            addProperty("sdpMLineIndex", sdpMLineIndex)
            addProperty("room", roomCode)
        }
        webSocket?.send(gson.toJson(msg))
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
    }

    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onPeerJoined()
        fun onPeerLeft()
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onError(message: String)
    }
}
