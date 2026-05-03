package com.mediawalkie

import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.mediawalkie.routing.RoutingManager
import com.mediawalkie.service.WalkieService
import com.mediawalkie.ui.screens.MainScreen
import com.mediawalkie.data.SessionManager
import com.mediawalkie.data.api.WalkieApi
import com.mediawalkie.ui.screens.AuthScreen
import com.mediawalkie.ui.theme.MediaWalkieTheme

class MainActivity : ComponentActivity() {

    private var routingManager: RoutingManager? = null
    private lateinit var sessionManager: SessionManager
    private var walkieApi: WalkieApi? = null
    private lateinit var repository: com.mediawalkie.network.WalkieRepository

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            repository = com.mediawalkie.network.WalkieRepository()
            sessionManager = SessionManager(this)
            walkieApi = WalkieApi.create()
            // Initialize RoutingManager later to avoid startup crashes
        } catch (e: Exception) {
            Log.e("MainActivity", "Boot initialization failed", e)
        }

        setContent {
            MediaWalkieTheme {
                val isVerified by sessionManager.isVerifiedFlow.collectAsState(initial = false)
                val userName by sessionManager.userNameFlow.collectAsState(initial = "User")
                val userId by sessionManager.userIdFlow.collectAsState(initial = "")
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!isVerified) {
                        walkieApi?.let { api ->
                            AuthScreen(sessionManager = sessionManager, api = api) {
                                // Recomposes on success
                            }
                        }
                    } else {
                        // Permissions for modern Android
                        val permissionsToRequest = remember {
                            mutableListOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ).apply {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                                    add(Manifest.permission.BLUETOOTH_CONNECT)
                                    add(Manifest.permission.BLUETOOTH_SCAN)
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                }
                            }
                        }
                        
                        val permissionState = com.google.accompanist.permissions.rememberMultiplePermissionsState(permissionsToRequest)
                        
                        // Initialize RoutingManager ONLY once permissions are handled
                        LaunchedEffect(Unit) {
                            if (routingManager == null) {
                                try {
                                    routingManager = RoutingManager(this@MainActivity, repository)
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to init RoutingManager", e)
                                }
                            }
                        }

                        LaunchedEffect(permissionState.allPermissionsGranted, userId) {
                            if (permissionState.allPermissionsGranted && userId?.isNotEmpty() == true) {
                                try {
                                    routingManager?.start("104.5", userId)
                                    
                                    val serviceIntent = Intent(this@MainActivity, WalkieService::class.java).apply {
                                        putExtra("FREQUENCY", "104.5")
                                        putExtra("USER_ID", userId)
                                    }
                                    
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        startForegroundService(serviceIntent)
                                    } else {
                                        startService(serviceIntent)
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to start services", e)
                                }
                            } else if (!permissionState.allPermissionsGranted) {
                                permissionState.launchMultiplePermissionRequest()
                            }
                        }

                        MainScreen(
                            routingManager = routingManager, 
                            userName = userName ?: "User", 
                            api = walkieApi, 
                            userId = userId ?: ""
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            routingManager?.stop()
            stopService(Intent(this, WalkieService::class.java))
        } catch (e: Exception) {}
    }
}
