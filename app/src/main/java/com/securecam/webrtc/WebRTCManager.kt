package com.securecam.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRTCManager(
    private val context: Context,
    private val isCamera: Boolean,
    private val listener: WebRTCListener
) {
    private val TAG = "WebRTCManager"
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null         // camera mic → viewer
    private var viewerAudioTrack: AudioTrack? = null        // viewer PTT → camera speaker
    private var videoCapturer: CameraVideoCapturer? = null
    private var localSurfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null

    private var dataChannel: DataChannel? = null
    private var dataChannelObserver: DataChannel.Observer? = null

    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
    )

    fun initialize() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
                .createPeerConnectionFactory()
            Log.d(TAG, "PeerConnectionFactory initialized")
        } catch (e: Exception) {
            Log.e(TAG, "initialize failed: ${e.message}")
        }
    }

    /** Camera side: creates VideoSource + VideoTrack from CameraX frames */
    fun createCameraXVideoSource(): CapturerObserver? {
        return try {
            videoSource = peerConnectionFactory.createVideoSource(false)
            localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK_0", videoSource)
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK_0", audioSource)
            Log.d(TAG, "CameraX VideoSource ready")
            videoSource?.capturerObserver
        } catch (e: Exception) {
            Log.e(TAG, "createCameraXVideoSource: ${e.message}"); null
        }
    }

    /** Viewer side: creates a mic audio track for PTT (starts disabled/muted) */
    fun createViewerMicTrack() {
        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
            }
            val audioSource = peerConnectionFactory.createAudioSource(constraints)
            viewerAudioTrack = peerConnectionFactory.createAudioTrack("VIEWER_AUDIO_0", audioSource)
            viewerAudioTrack?.setEnabled(false)  // starts muted — PTT activates it
            Log.d(TAG, "Viewer mic track created (muted)")
        } catch (e: Exception) {
            Log.e(TAG, "createViewerMicTrack: ${e.message}")
        }
    }

    /** PTT: enable/disable viewer's mic (call from UI hold/release) */
    fun setPttActive(active: Boolean) {
        viewerAudioTrack?.setEnabled(active)
        Log.d(TAG, "PTT ${if (active) "active" else "inactive"}")
    }

    /** Send a JSON command string via DataChannel */
    fun sendCommand(json: String) {
        try {
            val dc = dataChannel ?: return
            if (dc.state() == DataChannel.State.OPEN) {
                val buf = DataChannel.Buffer(
                    java.nio.ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false
                )
                dc.send(buf)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand: ${e.message}")
        }
    }

    fun initRemoteRenderer(sv: SurfaceViewRenderer) {
        try {
            sv.init(eglBaseContext, null)
            sv.setEnableHardwareScaler(true)
        } catch (e: Exception) {
            Log.e(TAG, "initRemoteRenderer: ${e.message}")
        }
    }

    fun createPeerConnection(remote: SurfaceViewRenderer? = null): PeerConnection? {
        try {
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "ICE: $s")
                        when (s) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED ->
                                listener.onConnectionEstablished()
                            PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.DISCONNECTED ->
                                listener.onConnectionFailed()
                            else -> {}
                        }
                    }
                    override fun onIceConnectionReceivingChange(b: Boolean) {}
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidate(c: IceCandidate) { listener.onLocalIceCandidate(c) }
                    override fun onIceCandidatesRemoved(a: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: MediaStream?) {
                        stream?.videoTracks?.firstOrNull()?.let { track ->
                            remote?.let { track.addSink(it); listener.onRemoteVideoReceived() }
                        }
                    }
                    override fun onRemoveStream(s: MediaStream?) {}
                    override fun onDataChannel(dc: DataChannel?) {
                        // Viewer receives camera's data channel
                        dc?.let { setupDataChannelObserver(it) }
                    }
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                        val track = receiver?.track()
                        if (track is VideoTrack && remote != null) {
                            track.addSink(remote)
                            listener.onRemoteVideoReceived()
                        }
                        // Audio track from viewer received on camera — WebRTC plays it automatically
                    }
                })

            if (isCamera) {
                // Camera creates the DataChannel
                val dcInit = DataChannel.Init().apply {
                    ordered = true; maxRetransmits = 3
                }
                dataChannel = peerConnection?.createDataChannel("securecam_cmd", dcInit)
                dataChannel?.let { setupDataChannelObserver(it) }

                // Add camera video + audio tracks
                val streamId = "cam_stream"
                localVideoTrack?.let {
                    peerConnection?.addTrack(it, listOf(streamId))
                    Log.d(TAG, "Added camera video track")
                } ?: Log.w(TAG, "Video track null — call createCameraXVideoSource() first")
                localAudioTrack?.let {
                    peerConnection?.addTrack(it, listOf(streamId))
                    Log.d(TAG, "Added camera audio track")
                }
            } else {
                // Viewer adds PTT mic track (starts disabled)
                viewerAudioTrack?.let {
                    peerConnection?.addTrack(it, listOf("viewer_stream"))
                    Log.d(TAG, "Added viewer PTT audio track (muted)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createPeerConnection failed: ${e.message}")
        }
        return peerConnection
    }

    private fun setupDataChannelObserver(dc: DataChannel) {
        dataChannel = dc
        val obs = object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel state: ${dc.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                try {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val json = String(bytes, Charsets.UTF_8)
                    listener.onDataChannelMessage(json)
                } catch (e: Exception) {
                    Log.e(TAG, "DataChannel msg: ${e.message}")
                }
            }
        }
        dataChannelObserver = obs
        dc.registerObserver(obs)
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        try {
            val constraints = MediaConstraints().apply {
                // Camera sends video, receives viewer's PTT audio
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            peerConnection?.createOffer(sdpCallback { sdp ->
                peerConnection?.setLocalDescription(sdpSetCallback { callback(sdp) }, sdp)
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "createOffer: ${e.message}")
        }
    }

    fun setRemoteOffer(sdp: String, callback: (SessionDescription) -> Unit) {
        try {
            val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(sdpSetCallback {
                val answerConstraints = MediaConstraints()
                peerConnection?.createAnswer(sdpCallback { answer ->
                    peerConnection?.setLocalDescription(sdpSetCallback { callback(answer) }, answer)
                }, answerConstraints)
            }, desc)
        } catch (e: Exception) {
            Log.e(TAG, "setRemoteOffer: ${e.message}")
        }
    }

    fun setRemoteAnswer(sdp: String) {
        try {
            peerConnection?.setRemoteDescription(sdpSetCallback {
                Log.d(TAG, "Remote answer set")
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        } catch (e: Exception) {
            Log.e(TAG, "setRemoteAnswer: ${e.message}")
        }
    }

    fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        try {
            peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        } catch (e: Exception) {
            Log.e(TAG, "addIceCandidate: ${e.message}")
        }
    }

    // SdpObserver helpers
    private fun sdpCallback(onSuccess: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onSuccess(sdp)
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String) { Log.e(TAG, "SDP create fail: $e") }
        override fun onSetFailure(e: String) { Log.e(TAG, "SDP set fail: $e") }
    }
    private fun sdpSetCallback(onSuccess: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() = onSuccess()
        override fun onCreateFailure(e: String) { Log.e(TAG, "SDP create fail: $e") }
        override fun onSetFailure(e: String) { Log.e(TAG, "SDP set fail: $e") }
    }

    fun setVideoEnabled(enabled: Boolean) { localVideoTrack?.setEnabled(enabled) }
    fun setAudioEnabled(enabled: Boolean) { localAudioTrack?.setEnabled(enabled) }

    fun release() {
        try { dataChannel?.close(); dataChannel?.dispose() } catch (e: Exception) {}
        try { videoCapturer?.stopCapture() } catch (e: Exception) {}
        try { videoCapturer?.dispose() } catch (e: Exception) {}
        try { localVideoTrack?.dispose() } catch (e: Exception) {}
        try { localAudioTrack?.dispose() } catch (e: Exception) {}
        try { viewerAudioTrack?.dispose() } catch (e: Exception) {}
        try { videoSource?.dispose() } catch (e: Exception) {}
        try { localSurfaceTextureHelper?.dispose() } catch (e: Exception) {}
        try { peerConnection?.close() } catch (e: Exception) {}
        try { peerConnection?.dispose() } catch (e: Exception) {}
        try { if (::peerConnectionFactory.isInitialized) peerConnectionFactory.dispose() } catch (e: Exception) {}
        try { eglBase.release() } catch (e: Exception) {}
    }

    interface WebRTCListener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnectionEstablished()
        fun onConnectionFailed()
        fun onRemoteVideoReceived()
        fun onDataChannelMessage(json: String)
    }
}
