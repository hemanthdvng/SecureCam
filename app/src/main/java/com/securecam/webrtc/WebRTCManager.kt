package com.securecam.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
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
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        // Add TURN servers for NAT traversal in production
    )

    fun initialize() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val audioDeviceModule: AudioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    fun startLocalCamera(surfaceView: SurfaceViewRenderer, useBackCamera: Boolean = false) {
        surfaceView.init(eglBaseContext, null)
        surfaceView.setMirror(!useBackCamera)
        surfaceView.setEnableHardwareScaler(true)

        videoCapturer = createCameraCapturer(useBackCamera)

        localSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)

        videoCapturer?.initialize(localSurfaceTextureHelper, context, videoSource.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK", videoSource)
        localVideoTrack?.addSink(surfaceView)

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK", audioSource)

        Log.d(TAG, "Local camera started")
    }

    fun initRemoteRenderer(surfaceView: SurfaceViewRenderer) {
        surfaceView.init(eglBaseContext, null)
        surfaceView.setEnableHardwareScaler(true)
    }

    fun createPeerConnection(remoteRenderer: SurfaceViewRenderer? = null): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "Signaling state: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> listener.onConnectionEstablished()
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> listener.onConnectionFailed()
                        else -> {}
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

                override fun onIceCandidate(candidate: IceCandidate) {
                    listener.onLocalIceCandidate(candidate)
                }

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}

                override fun onAddStream(stream: MediaStream) {
                    Log.d(TAG, "Stream added: ${stream.videoTracks.size} video tracks")
                    if (stream.videoTracks.isNotEmpty() && remoteRenderer != null) {
                        stream.videoTracks[0].addSink(remoteRenderer)
                        listener.onRemoteVideoReceived()
                    }
                }

                override fun onRemoveStream(stream: MediaStream) {}

                override fun onDataChannel(channel: DataChannel) {
                    Log.d(TAG, "Data channel: ${channel.label()}")
                }

                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                    val track = receiver.track()
                    if (track is VideoTrack && remoteRenderer != null) {
                        track.addSink(remoteRenderer)
                        listener.onRemoteVideoReceived()
                    }
                }
            }
        )

        // Add local tracks if this is the camera
        if (isCamera) {
            val streamId = "local_stream"
            peerConnection?.addTrack(localVideoTrack, listOf(streamId))
            peerConnection?.addTrack(localAudioTrack, listOf(streamId))
        }

        return peerConnection
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", if (isCamera) "false" else "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isCamera) "false" else "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() { callback(sdp) }
                    override fun onCreateFailure(error: String) {}
                    override fun onSetFailure(error: String) { Log.e(TAG, "setLocalDesc failed: $error") }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { Log.e(TAG, "createOffer failed: $error") }
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun setRemoteOffer(sdp: String, callback: (SessionDescription) -> Unit) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {
                val answerConstraints = MediaConstraints()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription) {}
                            override fun onSetSuccess() { callback(answer) }
                            override fun onCreateFailure(error: String) {}
                            override fun onSetFailure(error: String) {}
                        }, answer)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) { Log.e(TAG, "createAnswer failed: $error") }
                    override fun onSetFailure(error: String) {}
                }, answerConstraints)
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) { Log.e(TAG, "setRemoteOffer failed: $error") }
        }, sessionDescription)
    }

    fun setRemoteAnswer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() { Log.d(TAG, "Remote answer set") }
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) { Log.e(TAG, "setRemoteAnswer failed: $error") }
        }, sessionDescription)
    }

    fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun enableTorch(enable: Boolean) {
        (videoCapturer as? Camera2Capturer)?.let {
            // Torch control via Camera2 API
        }
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun release() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        localSurfaceTextureHelper?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory.dispose()
        eglBase.release()
    }

    private fun createCameraCapturer(useBackCamera: Boolean): CameraVideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Prefer front/back camera based on mode
        for (deviceName in deviceNames) {
            val isBack = enumerator.isBackFacing(deviceName)
            if (useBackCamera && isBack || !useBackCamera && !isBack) {
                if (enumerator.isFrontFacing(deviceName) || enumerator.isBackFacing(deviceName)) {
                    return enumerator.createCapturer(deviceName, null)
                }
            }
        }

        // Fallback to any available camera
        for (deviceName in deviceNames) {
            return enumerator.createCapturer(deviceName, null)
        }

        throw IllegalStateException("No camera found")
    }

    interface WebRTCListener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnectionEstablished()
        fun onConnectionFailed()
        fun onRemoteVideoReceived()
    }
}
