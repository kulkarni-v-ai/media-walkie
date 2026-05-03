package com.mediawalkie.data.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// Data classes for API requests/responses
data class RegisterRequest(
    val name: String, 
    val pin: String, 
    val deviceId: String,
    val email: String? = null,
    val phone: String? = null
)
data class AuthResponse(val message: String?, val error: String?, val user: User?)
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

data class GroupRequest(val name: String, val frequency: String, val pin: String? = null, val rangeDescription: String? = null)
data class GroupResponse(val message: String?, val error: String?, val group: Group?)
data class Group(val name: String, val frequency: String, val pin: String? = null, val rangeDescription: String? = "Standard Range")

interface WalkieApi {
    @POST("/api/auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("/api/auth/verify")
    suspend fun verify(@Body request: RegisterRequest): AuthResponse

    @GET("/api/groups")
    suspend fun getGroups(@retrofit2.http.Query("userId") userId: String): List<Group>

    @POST("/api/groups")
    suspend fun createGroup(@Body request: GroupRequest): GroupResponse

    companion object {
        private const val BASE_URL = "https://media-walkie-signaling.onrender.com"

        fun create(baseUrl: String = BASE_URL): WalkieApi {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            return retrofit.create(WalkieApi::class.java)
        }
    }
}
