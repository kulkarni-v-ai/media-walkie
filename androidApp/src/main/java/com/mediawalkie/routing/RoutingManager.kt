package com.mediawalkie.routing

import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
import com.mediawalkie.audio.AudioEngine
import com.mediawalkie.network.MeshManager
import com.mediawalkie.network.WebRTCEngine
import com.mediawalkie.network.WebRTCHandler
import com.mediawalkie.network.WalkieRepository
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*

class RoutingManager(private val context: Context, private val repository: WalkieRepository) {

    private val TAG = "RoutingManager"
    
    val audioEngine by lazy { AudioEngine(context) }
    val meshManager by lazy { MeshManager(context) }
    val legacyWebRTCEngine by lazy { WebRTCEngine(context) }
    private var webRTCHandler: WebRTCHandler? = null

    private var activeFrequency: String = "104.5"
    private var isInternetAvailable = false
    var connectedMeshPeers by mutableStateOf(0)
        private set
    private var isPttActive = false
    var currentSpeaker by mutableStateOf<String?>(null)
        private set

    init {
        try {
            checkInternet()
            
            // Wire up MeshManager callbacks
            meshManager.onPayloadReceived = { payload ->
                Log.d(TAG, "Received payload from Mesh. Playing and Relaying...")
                audioEngine.queueAudioForPlayback(payload)
                
                // REPEATER: Relay Mesh audio to Internet for global reach
                if (isInternetAvailable) {
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.sendAudioData(payload)
                    }
                }
            }

            meshManager.onPeerCountChanged = { count ->
                connectedMeshPeers = count
            }

            // Wire up WebSocket Global Audio callbacks
            CoroutineScope(Dispatchers.IO).launch {
                repository.audioFlow.collect { payload ->
                    Log.d(TAG, "Received global payload from Internet. Playing and Relaying...")
                    audioEngine.queueAudioForPlayback(payload)
                    
                    // REPEATER: Relay Internet audio to Mesh for local reach
                    if (connectedMeshPeers > 0) {
                        meshManager.sendAudio(payload)
                    }
                }
            }

            audioEngine.onAudioDataCaptured = { payload ->
                // Always try to broadcast locally
                if (connectedMeshPeers > 0) {
                    meshManager.sendAudio(payload)
                }
                
                // Also broadcast globally if possible
                if (isInternetAvailable) {
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.sendAudioData(payload)
                    }
                }
            }

            audioEngine.onSpeakerChanged = { name ->
                currentSpeaker = name
            }

            webRTCHandler = WebRTCHandler(context, repository) { remoteTrack ->
                Log.d(TAG, "Professional WebRTC Remote Track Received!")
            }

            CoroutineScope(Dispatchers.IO).launch {
                while (true) {
                    try {
                        val previousState = isInternetAvailable
                        checkInternet()
                        if (isInternetAvailable && !previousState) {
                            Log.d(TAG, "Internet restored! Connecting Signaling...")
                            repository.connect()
                        }
                    } catch (e: Exception) {}
                    delay(5000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RoutingManager init failed safely", e)
        }
    }

    fun start(frequency: String, userId: String? = null) {
        Log.d(TAG, "Starting RoutingManager for frequency: $frequency")
        activeFrequency = frequency
        
        // Ensure engines are ready
        audioEngine.startPlayback()
        
        // Reset and Start Mesh with safety delay to avoid Bluetooth collision
        meshManager.stopAll()
        meshManager.startAdvertising(frequency)
        scope.launch {
            delay(1500) 
            meshManager.startDiscovery(frequency)
        }

        checkInternet()
        if (isInternetAvailable) {
            repository.connect()
            scope.launch {
                delay(1000) // Small delay to ensure WebSocket is ready
                repository.joinChannel(frequency, userId)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private fun checkInternet() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            isInternetAvailable = capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        } catch (e: Exception) {
            isInternetAvailable = false
        }
    }


    fun stop() {
        audioEngine.stopPlayback()
        audioEngine.stopCapture()
        meshManager.stopAll()
        legacyWebRTCEngine.disconnect()
        webRTCHandler?.stopCall()
    }

    fun setPttActive(active: Boolean, name: String = "User") {
        if (isPttActive == active) return
        isPttActive = active
        audioEngine.userName = name
        
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        
        if (active) {
            Log.d(TAG, "PTT Pressed - MODE: ${if (isInternetAvailable) "INTERNET" else "OFFLINE MESH"}")
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
            
            // HYBRID TRANSMISSION: Always try to capture audio
            audioEngine.startCapture() 

            if (isInternetAvailable) {
                // Internet is available - Use Professional WebRTC
                webRTCHandler?.startCall()
                // Also send to mesh as secondary if peers exist
                if (connectedMeshPeers > 0) {
                    Log.d(TAG, "Internet active, but also sending to $connectedMeshPeers mesh peers")
                }
            } else {
                // Offline Mode - Mesh handles transmission via audioEngine callbacks
                Log.d(TAG, "Offline mode - Transmitting to $connectedMeshPeers mesh peers")
            }
        } else {
            Log.d(TAG, "PTT Released")
            audioEngine.stopCapture()
            webRTCHandler?.stopCall()
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
        }
    }
}
