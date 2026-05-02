package com.mediawalkie.network

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.webrtc.*

class WebRTCEngine(private val context: Context) {

    private lateinit var socket: Socket
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val dataChannels = mutableMapOf<String, DataChannel>()

    var onAudioDataReceived: ((ByteArray) -> Unit)? = null

    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    fun connectToSignalingServer(url: String, frequency: String) {
        try {
            socket = IO.socket(url)
            socket.on(Socket.EVENT_CONNECT) {
                Log.d("WebRTCEngine", "Connected to Signaling Server")
                socket.emit("join_frequency", frequency)
            }

            socket.on("peer_joined") { args ->
                val peerId = args[0] as String
                Log.d("WebRTCEngine", "Peer joined: $peerId")
                createPeerConnection(peerId, true)
            }

            // TODO: Handle offer, answer, and ice candidates in a full implementation

            socket.connect()
        } catch (e: Exception) {
            Log.e("WebRTCEngine", "Socket IO error", e)
        }
    }

    private fun createPeerConnection(peerId: String, isInitiator: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // Send to signaling server
            }
            override fun onDataChannel(dc: DataChannel) {
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (!buffer.binary) return
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        onAudioDataReceived?.invoke(data)
                    }
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {}
                })
            }
            // ... other overrides ...
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }

        val pc = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        if (pc != null) {
            peerConnections[peerId] = pc
            if (isInitiator) {
                val dcInit = DataChannel.Init()
                val dc = pc.createDataChannel("audio_channel", dcInit)
                dataChannels[peerId] = dc
            }
        }
    }

    fun sendAudioData(payload: ByteArray) {
        val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(payload), true)
        dataChannels.values.forEach { dc ->
            if (dc.state() == DataChannel.State.OPEN) {
                dc.send(buffer)
            }
        }
    }

    fun disconnect() {
        if (this::socket.isInitialized) {
            socket.disconnect()
        }
        dataChannels.values.forEach { it.dispose() }
        dataChannels.clear()
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
    }
}
