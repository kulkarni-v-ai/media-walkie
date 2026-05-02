package com.mediawalkie.network

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

class WebRTCEngine(private val context: Context) {

    private var socket: Socket? = null
    var onAudioDataReceived: ((ByteArray) -> Unit)? = null
    private var currentFrequency: String = ""

    fun initialize() {
        // No complex WebRTC factory needed for pure socket communication
        Log.d("WebRTCEngine", "Engine Initialized")
    }

    fun connectToSignalingServer(url: String, frequency: String) {
        currentFrequency = frequency
        try {
            val opts = IO.Options()
            opts.forceNew = true
            opts.reconnection = true
            
            socket = IO.socket(url, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("WebRTCEngine", "Connected to Signaling Server! Joining freq: $frequency")
                socket?.emit("join_frequency", frequency)
            }

            // Receive audio from the global room
            socket?.on("audio_data") { args ->
                try {
                    val data = args[0] as ByteArray
                    onAudioDataReceived?.invoke(data)
                } catch (e: Exception) {
                    Log.e("WebRTCEngine", "Error receiving audio: ${e.message}")
                }
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("WebRTCEngine", "Disconnected from server")
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("WebRTCEngine", "Socket connection error", e)
        }
    }

    fun sendAudioData(payload: ByteArray) {
        if (socket?.connected() == true) {
            // Send to global frequency room via WebSocket
            socket?.emit("audio_data", payload)
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
}
