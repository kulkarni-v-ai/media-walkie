package com.mediawalkie.network

import android.content.Context
import android.util.Log
import com.mediawalkie.model.WebRTCSignal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.webrtc.*
import java.util.*

class WebRTCHandler(
    private val context: Context,
    private val repository: WalkieRepository,
    private val onRemoteStream: (AudioTrack) -> Unit
) {
    private val TAG = "WebRTCHandler"
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var isInitialized = false

    init {
        observeSignaling()
    }

    private fun ensureInitialized() {
        if (isInitialized) return
        try {
            initializeWebRTC()
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC", e)
        }
    }

    private fun initializeWebRTC() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        
        val pcOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(pcOptions)
            .createPeerConnectionFactory()
    }

    private var currentPeerId: String? = null

    private fun observeSignaling() {
        scope.launch {
            repository.webSocketManager.signalFlow.collectLatest { signal ->
                ensureInitialized()
                // Update targeted peer if signal contains senderId
                if (signal.senderId != null) {
                    currentPeerId = signal.senderId
                }
                
                when (signal.type) {
                    "offer" -> handleOffer(signal)
                    "answer" -> handleAnswer(signal)
                    "ice" -> handleIceCandidate(signal)
                }
            }
        }
    }

    private fun ensureLocalTrack() {
        if (localAudioTrack != null) return
        
        val constraints = MediaConstraints()
        localAudioSource = peerConnectionFactory?.createAudioSource(constraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", localAudioSource)
        localAudioTrack?.setEnabled(true)
        
        peerConnection?.addTrack(localAudioTrack)
    }

    fun startCall() {
        ensureInitialized()
        if (peerConnection == null) createPeerConnection()
        ensureLocalTrack()

        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                scope.launch {
                    repository.webSocketManager.sendSignal(
                        WebRTCSignal(
                            type = "offer", 
                            sdp = sdp.description,
                            targetId = currentPeerId 
                        )
                    )
                }
            }
        }, constraints)
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        ))
        // Enable unified plan for better multi-track support
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    repository.webSocketManager.sendSignal(
                        WebRTCSignal(
                            type = "ice", 
                            candidate = candidate.sdp, 
                            sdpMid = candidate.sdpMid, 
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            targetId = currentPeerId
                        )
                    )
                }
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is AudioTrack) {
                    Log.d(TAG, "Remote Audio Track Received and Enabled!")
                    track.setEnabled(true)
                    onRemoteStream(track)
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $state")
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    private fun handleOffer(signal: WebRTCSignal) {
        ensureInitialized()
        if (peerConnection == null) createPeerConnection()
        ensureLocalTrack()
        
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                        scope.launch {
                            repository.webSocketManager.sendSignal(
                                WebRTCSignal(
                                    type = "answer", 
                                    sdp = sdp.description,
                                    targetId = signal.senderId
                                )
                            )
                        }
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, signal.sdp))
    }

    private fun handleAnswer(signal: WebRTCSignal) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, signal.sdp))
    }

    private fun handleIceCandidate(signal: WebRTCSignal) {
        val candidate = IceCandidate(signal.sdpMid!!, signal.sdpMLineIndex!!, signal.candidate!!)
        peerConnection?.addIceCandidate(candidate)
    }

    fun stopCall() {
        peerConnection?.close()
        peerConnection = null
        localAudioSource?.dispose()
        localAudioSource = null
        scope.cancel()
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) {}
        override fun onSetFailure(s: String?) {}
    }
}
