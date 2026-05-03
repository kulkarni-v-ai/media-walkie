package com.mediawalkie.network

import com.mediawalkie.model.*
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import io.ktor.util.*

class WebSocketManager(
    private val client: HttpClient,
    private val baseUrl: String = "media-walkie-signaling.onrender.com"
) {
    private var session: DefaultClientWebSocketSession? = null
    private val _connectionState = MutableStateFlow(false)
    val connectionState = _connectionState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableSharedFlow<JsonElement>()
    val events = _events.asSharedFlow()
    private val _signalFlow = MutableSharedFlow<WebRTCSignal>()
    val signalFlow = _signalFlow.asSharedFlow()
    private val _onlineUsers = MutableStateFlow(0)
    val onlineUsers: StateFlow<Int> = _onlineUsers

    private val _audioFlow = MutableSharedFlow<ByteArray>()
    val audioFlow = _audioFlow.asSharedFlow()

    suspend fun sendAudioData(data: ByteArray) {
        // Socket.IO 4 format for binary: 42["audio_data", "base64_string"]
        // This is the most compatible way for Ktor to talk to Socket.IO
        val base64 = data.encodeBase64()
        val packet = "42" + json.encodeToString(listOf("audio_data", base64))
        session?.send(Frame.Text(packet))
    }

    fun connect() {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                client.webSocket(method = HttpMethod.Get, host = baseUrl, path = "/socket.io/?EIO=4&transport=websocket") {
                    session = this
                    _connectionState.value = true
                    
                    // Socket.IO Handshake
                    send(Frame.Text("40"))
                    
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            try {
                                val text = frame.data.decodeToString()
                                
                                // Handle Ping/Pong
                                if (text == "2") {
                                    send(Frame.Text("3"))
                                    continue
                                }
                                
                                if (text.startsWith("42[")) {
                                    val content = text.substring(2)
                                    val element = json.parseToJsonElement(content)
                                    if (element is JsonArray && element.size >= 2) {
                                        val event = element[0].jsonPrimitive.content
                                        val data = element[1]
                                        
                                        if (event == "webrtc_signal") {
                                            val signal = json.decodeFromJsonElement<WebRTCSignal>(data)
                                            _signalFlow.emit(signal)
                                        } else if (event == "audio_data") {
                                            // Classic Global Audio Logic
                                            if (data is JsonPrimitive && data.isString) {
                                                try {
                                                    val bytes = data.content.decodeBase64Bytes()
                                                    _audioFlow.emit(bytes)
                                                } catch (e: Exception) {
                                                    println("WebSocketManager: Base64 decode failed: ${e.message}")
                                                }
                                            }
                                        } else if (event == "room_count") {
                                            // Update global participant count
                                            val count = data.jsonPrimitive.intOrNull ?: 0
                                            _onlineUsers.value = count
                                        }
                                        _events.emit(data)
                                    }
                                } else if (frame is Frame.Binary) {
                                    // Some Socket.IO servers send binary frames for audio
                                    _audioFlow.emit(frame.data)
                                }
                            } catch (e: Exception) {
                                // Silent fail for common code
                            }
                        } else if (frame is Frame.Binary) {
                            _audioFlow.emit(frame.data)
                        }
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = false
                // Implement exponential backoff here for "Production Ready"
                delay(5000)
                connect()
            } finally {
                _connectionState.value = false
            }
        }
    }

    suspend fun joinFrequency(frequency: String, userId: String?) {
        val data = JoinFrequencyData(frequency, userId)
        sendEvent("join_frequency", json.encodeToJsonElement(data))
    }

    suspend fun sendSignal(signal: WebRTCSignal) {
        sendEvent("webrtc_signal", json.encodeToJsonElement(signal))
    }

    private suspend fun sendEvent(event: String, data: JsonElement) {
        // Socket.IO 4 format: 42["event", {data}]
        val packet = "42" + json.encodeToString(listOf(event, data))
        session?.send(io.ktor.websocket.Frame.Text(packet))
    }

    fun disconnect() {
        _connectionState.value = false
        session = null
    }
}
