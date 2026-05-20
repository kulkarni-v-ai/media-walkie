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
    
    private val _audioFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val audioFlow = _audioFlow.asSharedFlow()
    
    private val _onlineUsers = MutableStateFlow(0)
    val onlineUsers = _onlineUsers.asStateFlow()

    private var lastFrequency: String? = null
    private var lastUserId: String? = null

    fun connect() {
        try {
            val opts = IO.Options()
            opts.forceNew = true
            opts.reconnection = true
            
            socket = IO.socket(baseUrl, opts)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("NativeSocket", "Connected to Signaling Server!")
                _connectionState.value = true
                
                // AUTO RE-JOIN after reconnection
                lastFrequency?.let { freq ->
                    Log.d("NativeSocket", "Auto re-joining frequency: $freq")
                    joinFrequency(freq, lastUserId)
                }
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("NativeSocket", "Disconnected from server")
                _connectionState.value = false
            }

            socket?.on("reconnect_attempt") {
                Log.d("NativeSocket", "Reconnection attempt #${it[0]}")
            }

            socket?.on("connect_error") {
                Log.e("NativeSocket", "Connection Error: ${it[0]}")
            }

            socket?.on("error") {
                Log.e("NativeSocket", "Server Error: ${it[0]}")
            }

            socket?.on("audio_data") { args ->
                try {
                    // Socket.IO may deliver binary as ByteArray, JSONArray, or other types
                    // depending on library version and server encoding. Handle all cases.
                    val data: ByteArray? = when (val raw = args[0]) {
                        is ByteArray -> raw
                        is org.json.JSONArray -> {
                            ByteArray(raw.length()) { i -> raw.getInt(i).toByte() }
                        }
                        is Array<*> -> {
                            // Socket.IO sometimes wraps in Object[]
                            val first = raw.firstOrNull()
                            if (first is ByteArray) first
                            else null
                        }
                        else -> {
                            Log.w("NativeSocket", "Unknown audio_data type: ${raw?.javaClass?.name}")
                            null
                        }
                    }
                    if (data != null) {
                        _audioFlow.tryEmit(data)
                    }
                } catch (e: Exception) {
                    Log.e("NativeSocket", "Error receiving audio: ${e.message}")
                }
            }
            
            socket?.on("room_count") { args ->
                try {
                    val raw = args[0]
                    val count = when (raw) {
                        is Int -> raw
                        is Long -> raw.toInt()
                        is Double -> raw.toInt()
                        is String -> raw.toIntOrNull() ?: 0
                        else -> 0
                    }
                    _onlineUsers.value = count
                } catch (e: Exception) {
                    Log.e("NativeSocket", "Error parsing room_count", e)
                }
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("NativeSocket", "Connection error", e)
        }
    }

    fun joinFrequency(frequency: String, userId: String?) {
        lastFrequency = frequency
        lastUserId = userId
        
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
