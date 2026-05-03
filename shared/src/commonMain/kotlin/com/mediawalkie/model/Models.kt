package com.mediawalkie.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val _id: String,
    val name: String,
    val pin: String,
    val deviceId: String,
    val isVerified: Boolean,
    val isAdmin: Boolean = false,
    val email: String? = null,
    val phone: String? = null
)

@Serializable
data class Group(
    val _id: String? = null,
    val name: String,
    val frequency: String,
    val rangeDescription: String? = "Standard Range"
)

@Serializable
data class RegisterRequest(
    val name: String,
    val pin: String,
    val deviceId: String,
    val email: String? = null,
    val phone: String? = null
)

@Serializable
data class AuthResponse(
    val message: String? = null,
    val error: String? = null,
    val user: User? = null
)

@Serializable
data class GroupRequest(
    val name: String,
    val frequency: String,
    val rangeDescription: String? = null
)

@Serializable
data class GroupResponse(
    val message: String? = null,
    val error: String? = null,
    val group: Group? = null
)

// WebRTC Signaling Models
@Serializable
data class WebRTCSignal(
    val type: String, // "offer", "answer", "ice"
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val senderId: String? = null
)

@Serializable
data class JoinFrequencyData(
    val frequency: String,
    val userId: String? = null
)
