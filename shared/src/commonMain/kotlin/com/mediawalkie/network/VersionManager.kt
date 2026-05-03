package com.mediawalkie.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.Serializable

@Serializable
data class VersionInfo(
    val latestVersion: String,
    val minSupportedVersion: String,
    val updateType: String,
    val updateUrl: String,
    val releaseNotes: String
)

class VersionManager(private val client: HttpClient) {
    suspend fun checkVersion(baseUrl: String): VersionInfo? {
        return try {
            client.get("$baseUrl/api/version").body()
        } catch (e: Exception) {
            null
        }
    }
}
