package com.mediawalkie

import android.os.Bundle
import android.content.Intent
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.mediawalkie.routing.RoutingManager
import com.mediawalkie.service.WalkieService
import com.mediawalkie.ui.screens.MainScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mediawalkie.data.SessionManager
import com.mediawalkie.data.api.WalkieApi
import com.mediawalkie.ui.screens.AuthScreen
import com.mediawalkie.ui.theme.MediaWalkieTheme

class MainActivity : ComponentActivity() {

    private lateinit var routingManager: RoutingManager
    private lateinit var sessionManager: SessionManager
    private lateinit var walkieApi: WalkieApi

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        routingManager = RoutingManager(this)
        sessionManager = SessionManager(this)
        walkieApi = WalkieApi.create()

        setContent {
            MediaWalkieTheme {
                val isVerified by sessionManager.isVerifiedFlow.collectAsState(initial = false)
                val userName by sessionManager.userNameFlow.collectAsState(initial = "")
                val userId by sessionManager.userIdFlow.collectAsState(initial = "")
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!isVerified) {
                        AuthScreen(sessionManager = sessionManager, api = walkieApi) {
                            // On verified, state will automatically recompose
                        }
                    } else {
                        val permissionsToRequest = mutableListOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                        
                        val multiplePermissionsState = com.google.accompanist.permissions.rememberMultiplePermissionsState(permissionsToRequest)
                        
                        LaunchedEffect(multiplePermissionsState.allPermissionsGranted) {
                            if (multiplePermissionsState.allPermissionsGranted) {
                                // Default frequency on startup
                                routingManager.start("104.5")
                                
                                val serviceIntent = Intent(this@MainActivity, WalkieService::class.java)
                                serviceIntent.putExtra("FREQUENCY", "104.5")
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    startForegroundService(serviceIntent)
                                } else {
                                    startService(serviceIntent)
                                }
                            } else {
                                multiplePermissionsState.launchMultiplePermissionRequest()
                            }
                        }

                        MainScreen(routingManager = routingManager, userName = userName ?: "Unknown", api = walkieApi, userId = userId ?: "")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        routingManager.stop()
        stopService(Intent(this, WalkieService::class.java))
    }
}
