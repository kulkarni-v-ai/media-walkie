package com.mediawalkie

import android.app.Application
import android.util.Log
import org.webrtc.PeerConnectionFactory

class WalkieApplication : Application() {
    val repository by lazy { com.mediawalkie.network.WalkieRepository() }
    val sessionManager by lazy { com.mediawalkie.data.SessionManager(this) }
    val routingManager by lazy { com.mediawalkie.routing.RoutingManager(this, repository) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize hardware later if possible, but try here defensively
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            Log.d("WalkieApplication", "WebRTC Native Engine Loaded")
        } catch (t: Throwable) {
            Log.e("WalkieApplication", "WebRTC Native Engine failed to load", t)
        }
    }

    companion object {
        lateinit var instance: WalkieApplication
            private set
    }
}
