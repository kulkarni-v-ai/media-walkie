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
    private val nativeSocket by lazy { com.mediawalkie.network.NativeSocketManager("https://media-walkie-signaling.onrender.com") }
    
    private var activeFrequency: String = "104.5"
    private var isInternetAvailable = false
    var connectedMeshPeers by mutableStateOf(0)
        private set
    var connectedOnlineUsers by mutableStateOf(0)
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

            // Wire up Native Socket Global Audio callbacks
            CoroutineScope(Dispatchers.Main).launch {
                nativeSocket.onlineUsers.collect { count ->
                    connectedOnlineUsers = count
                }
            }

            CoroutineScope(Dispatchers.Main).launch {
                nativeSocket.audioFlow.collect { payload ->
                    Log.d(TAG, "Received binary payload from Internet. Playing...")
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
                
                // Also broadcast globally via Native Binary Socket
                if (isInternetAvailable) {
                    nativeSocket.sendAudio(payload)
                }
            }

            audioEngine.onSpeakerChanged = { name ->
                currentSpeaker = name
            }

            CoroutineScope(Dispatchers.IO).launch {
                while (true) {
                    try {
                        val previousState = isInternetAvailable
                        checkInternet()
                        if (isInternetAvailable && !previousState) {
                            Log.d(TAG, "Internet restored! Connecting Signaling...")
                            nativeSocket.connect()
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
        
        // Reset Mesh for new frequency - No delay, just like working APK
        meshManager.stopAll()
        meshManager.startAdvertising(frequency)
        meshManager.startDiscovery(frequency)

        if (isInternetAvailable) {
            nativeSocket.connect()
            scope.launch {
                delay(1000) 
                nativeSocket.joinFrequency(frequency, userId ?: "unknown")
            }
        }

        // PERSISTENT MESH RETRY: If no peers, restart scan every 15s
        scope.launch {
            while (isActive) {
                delay(15000)
                if (connectedMeshPeers == 0) {
                    Log.d(TAG, "No peers found yet. Refreshing Mesh Radar for $activeFrequency...")
                    meshManager.stopAll()
                    meshManager.startAdvertising(activeFrequency)
                    delay(2000)
                    meshManager.startDiscovery(activeFrequency)
                }
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
            
            // CLASSIC HARDWARE IGNITION: Required for many phones to enable mic
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
            
            audioEngine.startCapture() 
        } else {
            Log.d(TAG, "PTT Released")
            audioEngine.stopCapture()
            
            // RESET HARDWARE
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
        }
    }
}
