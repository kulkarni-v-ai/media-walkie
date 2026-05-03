package com.mediawalkie

import android.app.Application
import android.util.Log
import org.webrtc.PeerConnectionFactory

class WalkieApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize hardware later if possible, but try here defensively
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            Log.d("WalkieApplication", "WebRTC Native Engine Loaded")
        } catch (t: Throwable) {
            // Use Throwable to catch UnsatisfiedLinkError
            Log.e("WalkieApplication", "WebRTC Native Engine failed to load - falling back to safety mode", t)
        }
    }
}
