package com.mediawalkie.routing

import android.content.Context
import android.util.Log
import com.mediawalkie.audio.AudioEngine
import com.mediawalkie.network.MeshManager
import com.mediawalkie.network.WebRTCEngine

class RoutingManager(private val context: Context) {

    private val TAG = "RoutingManager"
    
    val audioEngine = AudioEngine()
    val meshManager = MeshManager(context)
    val webRTCEngine = WebRTCEngine(context)

    private var activeFrequency: String = "104.5"
    private var isInternetAvailable = false
    private var connectedMeshPeers = 0

    init {
        // Wire up MeshManager callbacks
        meshManager.onPayloadReceived = { payload ->
            Log.d(TAG, "Received payload from Mesh. Playing...")
            audioEngine.queueAudioForPlayback(payload)
            // TODO: Multi-hop logic: forward to WebRTC if Gateway Node
        }

        meshManager.onPeerCountChanged = { count ->
            connectedMeshPeers = count
            Log.d(TAG, "Mesh peer count: $count")
        }

        // Wire up WebRTCEngine callbacks
        webRTCEngine.onAudioDataReceived = { payload ->
            Log.d(TAG, "Received payload from WebRTC. Playing...")
            audioEngine.queueAudioForPlayback(payload)
            // TODO: Multi-hop logic: forward to Mesh if Gateway Node
        }

        // Wire up AudioEngine to Routing Manager
        audioEngine.onAudioDataCaptured = { payload ->
            routeOutboundAudio(payload)
        }
    }

    fun start(frequency: String) {
        activeFrequency = frequency
        audioEngine.startPlayback() // Always listen
        
        // Start offline mesh discovery/advertising
        meshManager.startAdvertising(frequency)
        meshManager.startDiscovery(frequency)

        // Assume check internet available (mocked for now)
        isInternetAvailable = true 
        if (isInternetAvailable) {
            webRTCEngine.initialize()
            // Connect to local or remote Node.js signaling server
            webRTCEngine.connectToSignalingServer("https://media-walkie-signaling.onrender.com", frequency)
        }
    }

    fun stop() {
        audioEngine.stopPlayback()
        audioEngine.stopCapture()
        meshManager.stopAll()
        webRTCEngine.disconnect()
    }

    fun setPttActive(active: Boolean) {
        if (active) {
            audioEngine.startCapture()
        } else {
            audioEngine.stopCapture()
        }
    }

    private fun routeOutboundAudio(payload: ByteArray) {
        // "Anti-Gravity" Core Routing Logic
        
        // 1. If we have local mesh peers, prioritize Mesh (Offline First, Zero Data)
        if (connectedMeshPeers > 0) {
            meshManager.sendAudio(payload)
        }

        // 2. If we have internet, send via WebRTC (DataChannels)
        // Note: In a true mesh, we might send to BOTH if we are acting as the Gateway Node!
        if (isInternetAvailable) {
            webRTCEngine.sendAudioData(payload)
        }

        // 3. If neither are available, we could buffer (store and forward)
        if (connectedMeshPeers == 0 && !isInternetAvailable) {
            // Buffer logic would go here
        }
    }
}
