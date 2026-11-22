package de.chris.mamafon.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import de.chris.mamafon.service.RemoteControlAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

class WebRtcManager(private val context: Context) {

    val eglBase: EglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var signalingClient: MqttSignalingClient? = null
    private var dataChannel: DataChannel? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val _statusText = MutableStateFlow("Bereit")
    val statusText: StateFlow<String> = _statusText

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun getIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // Öffentlicher Test-TURN-Server als Fallback für strikte Mobilfunk-NATs
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

    fun startHostSession(sessionId: String, mediaProjectionIntent: Intent) {
        _statusText.value = "Initialisiere Freigabe..."
        createPeerConnection(isHost = true)
        setupScreenCapture(mediaProjectionIntent)

        signalingClient = MqttSignalingClient(
            sessionId = sessionId,
            isHost = true,
            onMessageReceived = { handleSignalingMessage(it, isHost = true) },
            onStatusChanged = { _statusText.value = it }
        )
        signalingClient?.connect()
    }

    fun startViewerSession(sessionId: String) {
        _statusText.value = "Verbinde mit Sitzung $sessionId..."
        createPeerConnection(isHost = false)

        signalingClient = MqttSignalingClient(
            sessionId = sessionId,
            isHost = false,
            onMessageReceived = { handleSignalingMessage(it, isHost = false) },
            onStatusChanged = { _statusText.value = it }
        )
        signalingClient?.connect()
    }

    private fun createPeerConnection(isHost: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(getIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject().apply {
                    put("type", "ICE")
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                }
                signalingClient?.sendMessage(json)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        _isConnected.value = true
                        _statusText.value = "Verbunden (Live-Übertragung aktiv)"
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> {
                        _isConnected.value = false
                        _statusText.value = "Verbindung beendet"
                    }
                    else -> {}
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    onRemoteVideoTrack?.invoke(track)
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                if (!isHost) {
                    dataChannel = dc
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
        })

        if (isHost) {
            val dcInit = DataChannel.Init()
            dataChannel = peerConnection?.createDataChannel("control", dcInit)
            dataChannel?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(p0: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val data = buffer.data
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    val command = String(bytes, Charsets.UTF_8)
                    RemoteControlAccessibilityService.instance?.executeRemoteCommand(command)
                }
            })
        }
    }

    private fun setupScreenCapture(mediaProjectionIntent: Intent) {
        videoCapturer = ScreenCapturerAndroid(
            mediaProjectionIntent,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSession()
                }
            }
        )
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(true)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        videoCapturer?.startCapture(720, 1280, 24)

        val videoTrack = peerConnectionFactory.createVideoTrack("SCREEN_TRACK", videoSource)
        peerConnection?.addTrack(videoTrack, listOf("SCREEN_STREAM"))
    }

    private fun handleSignalingMessage(json: JSONObject, isHost: Boolean) {
        when (json.optString("type")) {
            "JOIN" -> {
                if (isHost) {
                    _statusText.value = "Steuer-Gerät beigetreten, baue Stream auf..."
                    createOffer()
                }
            }
            "OFFER" -> {
                if (!isHost) {
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, json.getString("sdp"))
                    peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                    createAnswer()
                }
            }
            "ANSWER" -> {
                if (isHost) {
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"))
                    peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                }
            }
            "ICE" -> {
                val candidate = IceCandidate(
                    json.getString("sdpMid"),
                    json.getInt("sdpMLineIndex"),
                    json.getString("candidate")
                )
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                val json = JSONObject().apply {
                    put("type", "OFFER")
                    put("sdp", desc.description)
                }
                signalingClient?.sendMessage(json)
            }
        }, constraints)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                val json = JSONObject().apply {
                    put("type", "ANSWER")
                    put("sdp", desc.description)
                }
                signalingClient?.sendMessage(json)
            }
        }, constraints)
    }

    fun sendControlCommand(json: JSONObject) {
        try {
            if (dataChannel?.state() == DataChannel.State.OPEN) {
                val bytes = json.toString().toByteArray(Charsets.UTF_8)
                dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopSession() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            dataChannel?.close()
            peerConnection?.close()
            signalingClient?.disconnect()
        } catch (_: Exception) {}
        _isConnected.value = false
        _statusText.value = "Sitzung beendet"
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {}
        override fun onSetFailure(err: String?) {}
    }
}