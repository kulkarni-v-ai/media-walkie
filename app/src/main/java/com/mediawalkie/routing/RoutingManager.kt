package com.mediawalkie.routing

import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
import com.mediawalkie.audio.AudioEngine
import com.mediawalkie.network.MeshManager
import com.mediawalkie.network.WebRTCEngine
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*

class RoutingManager(private val context: Context) {

    private val TAG = "RoutingManager"
    
    val audioEngine = AudioEngine(context)
    val meshManager = MeshManager(context)
    val webRTCEngine = WebRTCEngine(context)

    private var activeFrequency: String = "104.5"
    private var isInternetAvailable = false
    var connectedMeshPeers by mutableStateOf(0)
        private set
    private var isPttActive = false
    var currentSpeaker by mutableStateOf<String?>(null)
        private set

    init {
        checkInternet()
        // Wire up MeshManager callbacks
        meshManager.onPayloadReceived = { payload ->
            Log.d(TAG, "Received payload from Mesh. Playing...")
            audioEngine.queueAudioForPlayback(payload)
            
            // GATEWAY LOGIC: Forward mesh audio to the global internet (WebRTC)
            if (isInternetAvailable) {
                Log.d(TAG, "Acting as Gateway: Forwarding Mesh -> WebRTC")
                webRTCEngine.sendAudioData(payload)
            }
        }

        meshManager.onPeerCountChanged = { count ->
            connectedMeshPeers = count
            Log.d(TAG, "Mesh peer count: $count")
        }

        // Wire up WebRTCEngine callbacks
        webRTCEngine.onAudioDataReceived = { payload ->
            Log.d(TAG, "Received payload from WebRTC. Playing...")
            audioEngine.queueAudioForPlayback(payload)
            
            // GATEWAY LOGIC: Forward global audio to the local mesh
            if (connectedMeshPeers > 0) {
                Log.d(TAG, "Acting as Gateway: Forwarding WebRTC -> Mesh")
                meshManager.sendAudio(payload)
            }
        }

        // Wire up AudioEngine to Routing Manager
        audioEngine.onAudioDataCaptured = { payload ->
            routeOutboundAudio(payload)
        }

        audioEngine.onSpeakerChanged = { name ->
            currentSpeaker = name
        }

        // Periodic internet check to handle on-the-fly network changes
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val previousState = isInternetAvailable
                checkInternet()
                if (isInternetAvailable && !previousState) {
                    Log.d(TAG, "Internet restored! Reconnecting WebRTC...")
                    webRTCEngine.initialize()
                    webRTCEngine.connectToSignalingServer("https://media-walkie-signaling.onrender.com", activeFrequency)
                }
                delay(5000)
            }
        }
    }

    fun start(frequency: String, userId: String? = null) {
        activeFrequency = frequency
        audioEngine.startPlayback() // Always listen
        
        checkInternet() // Refresh status on start
        
        // Start offline mesh discovery/advertising
        meshManager.startAdvertising(frequency)
        meshManager.startDiscovery(frequency)

        if (isInternetAvailable) {
            webRTCEngine.initialize()
            // Connect to local or remote Node.js signaling server
            webRTCEngine.connectToSignalingServer("https://media-walkie-signaling.onrender.com", frequency, userId)
        }
    }

    private fun checkInternet() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            isInternetAvailable = capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
            Log.d(TAG, "Internet check: available = $isInternetAvailable")
        } catch (e: Exception) {
            isInternetAvailable = false
        }
    }

    fun stop() {
        audioEngine.stopPlayback()
        audioEngine.stopCapture()
        meshManager.stopAll()
        webRTCEngine.disconnect()
    }

    fun setPttActive(active: Boolean, name: String = "User") {
        isPttActive = active
        audioEngine.userName = name
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
