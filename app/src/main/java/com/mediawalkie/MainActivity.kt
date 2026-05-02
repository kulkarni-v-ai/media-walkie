package com.mediawalkie

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mediawalkie.routing.RoutingManager
import com.mediawalkie.service.WalkieService
import com.mediawalkie.ui.screens.MainScreen
import com.mediawalkie.ui.theme.MediaWalkieTheme

class MainActivity : ComponentActivity() {

    private lateinit var routingManager: RoutingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        routingManager = RoutingManager(this)
        routingManager.start("104.5")

        // Start Foreground Service
        val serviceIntent = Intent(this, WalkieService::class.java)
        serviceIntent.putExtra("FREQUENCY", "104.5")
        startForegroundService(serviceIntent)

        setContent {
            MediaWalkieTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(routingManager = routingManager)
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
