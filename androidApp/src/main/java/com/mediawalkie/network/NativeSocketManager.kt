package com.mediawalkie.network

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class NativeSocketManager(private val baseUrl: String) {
    private var socket: Socket? = null
    
    private val _connectionState = MutableStateFlow(false)
    val connectionState = _connectionState.asStateFlow()
    
    private val _audioFlow = MutableSharedFlow<ByteArray>()
    val audioFlow = _audioFlow.asSharedFlow()
    
    private val _onlineUsers = MutableStateFlow(0)
    val onlineUsers = _onlineUsers.asStateFlow()

    fun connect() {
        try {
            val opts = IO.Options()
            opts.forceNew = true
            opts.reconnection = true
            
            socket = IO.socket(baseUrl, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("NativeSocket", "Connected to Signaling Server!")
                _connectionState.value = true
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("NativeSocket", "Disconnected from server")
                _connectionState.value = false
            }

            socket?.on("audio_data") { args ->
                try {
                    // Native Socket.IO handles binary automatically as ByteArray
                    val data = args[0] as ByteArray
                    _audioFlow.tryEmit(data)
                } catch (e: Exception) {
                    Log.e("NativeSocket", "Error receiving audio: ${e.message}")
                }
            }
            
            socket?.on("room_count") { args ->
                try {
                    val count = args[0] as Int
                    _onlineUsers.value = count
                } catch (e: Exception) {}
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("NativeSocket", "Connection error", e)
        }
    }

    fun joinFrequency(frequency: String, userId: String?) {
        val joinData = JSONObject()
        joinData.put("frequency", frequency)
        joinData.put("userId", userId)
        socket?.emit("join_frequency", joinData)
    }

    fun sendAudio(data: ByteArray) {
        if (socket?.connected() == true) {
            // Send RAW binary! This was the secret to the original APK.
            socket?.emit("audio_data", data)
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        _connectionState.value = false
    }
}
