package com.mediawalkie.network

import com.mediawalkie.model.*
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

class WalkieRepository(
    private val baseUrl: String = "https://media-walkie-signaling.onrender.com"
) {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json { ignoreUnknownKeys = true })
        }
    }

    private val wsBaseUrl = baseUrl.removePrefix("https://").removePrefix("http://")
    val webSocketManager = WebSocketManager(client, wsBaseUrl)
    val versionManager = VersionManager(client)

    val connectionState: StateFlow<Boolean> = webSocketManager.connectionState
    val audioFlow = webSocketManager.audioFlow
    val signalFlow = webSocketManager.signalFlow
    val onlineUsers = webSocketManager.onlineUsers

    fun connect() {
        webSocketManager.connect()
    }

    suspend fun sendAudioData(data: ByteArray) {
        webSocketManager.sendAudioData(data)
    }

    suspend fun sendSignal(signal: WebRTCSignal) {
        webSocketManager.sendSignal(signal)
    }

    suspend fun checkUpdate(): VersionInfo? {
        return versionManager.checkVersion(baseUrl)
    }

    suspend fun joinChannel(frequency: String, userId: String? = null) {
        webSocketManager.joinFrequency(frequency, userId)
    }

    // Additional methods for REST API (Auth, Groups) can be added here
}
