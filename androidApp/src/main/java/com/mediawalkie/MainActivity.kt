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
import kotlinx.coroutines.launch
import com.mediawalkie.routing.RoutingManager
import com.mediawalkie.service.WalkieService
import com.mediawalkie.ui.screens.MainScreen
import com.mediawalkie.data.SessionManager
import com.mediawalkie.data.api.WalkieApi
import com.mediawalkie.ui.screens.AuthScreen
import com.mediawalkie.ui.theme.MediaWalkieTheme

class MainActivity : ComponentActivity() {

    private var routingManager: RoutingManager? = null
    private val repository by lazy { com.mediawalkie.network.WalkieRepository() }
    private val sessionManager by lazy { SessionManager(this) }
    private val walkieApi by lazy { WalkieApi.create() }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // GLOBAL CRASH BUSTER: Catch every silent error
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRASH_BUSTER", "Fatal crash on thread ${thread.name}", throwable)
        }

        // Initialize early to match working APK logic
        routingManager = RoutingManager(this, repository)
        
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
                        AuthScreen(sessionManager = sessionManager, api = walkieApi) {
                            // Recomposes on success
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
                        
                        // Single source of truth for startup
                        LaunchedEffect(permissionState.allPermissionsGranted, userId) {
                            if (permissionState.allPermissionsGranted && userId?.isNotEmpty() == true) {
                                try {
                                    // Check if Location is actually ON in system settings
                                    val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                                    val isLocationEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                                            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                                    
                                    if (!isLocationEnabled) {
                                        Log.e("MainActivity", "CRITICAL: Location is OFF in system settings. Mesh will FAIL.")
                                    }

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
                                    Log.e("MainActivity", "Unified startup failed", e)
                                }
                            } else if (!permissionState.allPermissionsGranted) {
                                permissionState.launchMultiplePermissionRequest()
                            }
                        }

                        val scope = rememberCoroutineScope()
                        
                        MainScreen(
                            routingManager = routingManager, 
                            userName = userName ?: "User", 
                            api = walkieApi, 
                            userId = userId ?: "",
                            onLogout = {
                                scope.launch {
                                    routingManager?.stop()
                                    sessionManager.clearSession()
                                }
                            }
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
