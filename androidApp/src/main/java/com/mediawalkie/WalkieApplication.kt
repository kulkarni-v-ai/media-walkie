package com.mediawalkie

import android.app.Application
import android.util.Log
import org.webrtc.PeerConnectionFactory

class WalkieApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Initialize WebRTC at the application level for maximum hardware compatibility
            val options = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            Log.d("WalkieApplication", "WebRTC Initialized successfully")
        } catch (e: Exception) {
            Log.e("WalkieApplication", "WebRTC Initialization failed", e)
        }
    }
}
