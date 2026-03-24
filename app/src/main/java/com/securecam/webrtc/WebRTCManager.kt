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
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var localSurfaceTextureHelper: SurfaceTextureHelper? = null

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

    // Only called in pure WebRTC mode (not used when CameraX is handling preview)
    fun startLocalCamera(sv: SurfaceViewRenderer, useBack: Boolean = false) {
        try {
            sv.init(eglBaseContext, null)
            sv.setMirror(!useBack)

            val enumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull {
                if (useBack) enumerator.isBackFacing(it) else enumerator.isFrontFacing(it)
            } ?: enumerator.deviceNames.firstOrNull() ?: return

            videoCapturer = enumerator.createCapturer(deviceName, null) ?: return

            localSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
            val videoSource = peerConnectionFactory.createVideoSource(false)
            videoCapturer?.initialize(localSurfaceTextureHelper, context, videoSource.capturerObserver)
            videoCapturer?.startCapture(1280, 720, 30)

            localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK", videoSource)
            localVideoTrack?.addSink(sv)

            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK", audioSource)
            Log.d(TAG, "Local camera started")
        } catch (e: Exception) {
            Log.e(TAG, "startLocalCamera failed: ${e.message}")
        }
    }

    // Called on viewer side to prepare remote video renderer
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
                    override fun onIceCandidate(candidate: IceCandidate) {
                        listener.onLocalIceCandidate(candidate)
                    }
                    override fun onIceCandidatesRemoved(a: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: MediaStream?) {
                        stream?.videoTracks?.firstOrNull()?.let { track ->
                            remote?.let { track.addSink(it); listener.onRemoteVideoReceived() }
                        }
                    }
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(dc: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                        val track = receiver?.track()
                        if (track is VideoTrack && remote != null) {
                            track.addSink(remote)
                            listener.onRemoteVideoReceived()
                        }
                    }
                })

            // Only add tracks if they were created (pure WebRTC mode)
            // In CameraX mode, tracks are null — that's fine, we skip them
            if (isCamera) {
                val streamId = "securecam_stream"
                localVideoTrack?.let {
                    peerConnection?.addTrack(it, listOf(streamId))
                    Log.d(TAG, "Added video track")
                } ?: Log.d(TAG, "No video track (CameraX mode — OK)")

                localAudioTrack?.let {
                    peerConnection?.addTrack(it, listOf(streamId))
                    Log.d(TAG, "Added audio track")
                } ?: Log.d(TAG, "No audio track (CameraX mode — OK)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "createPeerConnection failed: ${e.message}")
        }
        return peerConnection
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription) {}
                        override fun onSetSuccess() { callback(sdp) }
                        override fun onCreateFailure(e: String) {}
                        override fun onSetFailure(e: String) { Log.e(TAG, "setLocal: $e") }
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String) { Log.e(TAG, "createOffer: $e") }
                override fun onSetFailure(e: String) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "createOffer failed: ${e.message}")
        }
    }

    fun setRemoteOffer(sdp: String, callback: (SessionDescription) -> Unit) {
        try {
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onSetSuccess() {
                    peerConnection?.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(answer: SessionDescription) {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(sdp: SessionDescription) {}
                                override fun onSetSuccess() { callback(answer) }
                                override fun onCreateFailure(e: String) {}
                                override fun onSetFailure(e: String) {}
                            }, answer)
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(e: String) { Log.e(TAG, "createAnswer: $e") }
                        override fun onSetFailure(e: String) {}
                    }, MediaConstraints())
                }
                override fun onCreateFailure(e: String) {}
                override fun onSetFailure(e: String) { Log.e(TAG, "setRemoteOffer: $e") }
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        } catch (e: Exception) {
            Log.e(TAG, "setRemoteOffer failed: ${e.message}")
        }
    }

    fun setRemoteAnswer(sdp: String) {
        try {
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onSetSuccess() { Log.d(TAG, "Remote answer set") }
                override fun onCreateFailure(e: String) {}
                override fun onSetFailure(e: String) { Log.e(TAG, "setRemoteAnswer: $e") }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        } catch (e: Exception) {
            Log.e(TAG, "setRemoteAnswer failed: ${e.message}")
        }
    }

    fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        try {
            peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        } catch (e: Exception) {
            Log.e(TAG, "addIceCandidate: ${e.message}")
        }
    }

    fun switchCamera() {
        try {
            (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera: ${e.message}")
        }
    }

    fun setVideoEnabled(enabled: Boolean) { localVideoTrack?.setEnabled(enabled) }
    fun setAudioEnabled(enabled: Boolean) { localAudioTrack?.setEnabled(enabled) }

    fun release() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) {}
        try { videoCapturer?.dispose() } catch (e: Exception) {}
        try { localVideoTrack?.dispose() } catch (e: Exception) {}
        try { localAudioTrack?.dispose() } catch (e: Exception) {}
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
    }
}
