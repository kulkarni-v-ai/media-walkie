package com.mediawalkie.network

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class MeshManager(private val context: Context) {

    private val TAG = "MeshManager"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.mediawalkie.MESH_SERVICE"
    private val STRATEGY = Strategy.P2P_CLUSTER

    private val connectedEndpoints = mutableSetOf<String>()

    var onPayloadReceived: ((ByteArray) -> Unit)? = null
    var onPeerCountChanged: ((Int) -> Unit)? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("MeshManager", "Connection initiated with $endpointId")
            // Auto accept for mesh
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d("MeshManager", "Connected to $endpointId")
                    connectedEndpoints.add(endpointId)
                    onPeerCountChanged?.invoke(connectedEndpoints.size)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d("MeshManager", "Rejected connection to $endpointId")
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.d("MeshManager", "Error connecting to $endpointId")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("MeshManager", "Disconnected from $endpointId")
            connectedEndpoints.remove(endpointId)
            onPeerCountChanged?.invoke(connectedEndpoints.size)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Check if it's a byte payload (our raw PCM audio)
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()
                if (bytes != null) {
                    onPayloadReceived?.invoke(bytes)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Track transfer progress
        }
    }

    private var activeFrequency: String = ""

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val endpointName = info.endpointName ?: "Unknown"
            Log.d(TAG, "Radar Pulse: Spotted node $endpointId ($endpointName)")
            
            // Normalize and Check
            val normalizedName = endpointName.trim()
            val normalizedFreq = activeFrequency.trim()
            
            if (normalizedName.startsWith("Walkie-") && normalizedName.contains(normalizedFreq)) {
                Log.d(TAG, "MATCH! Frequency $normalizedFreq matches $normalizedName. Initiating link...")
                connectionsClient.requestConnection("Walkie-$normalizedFreq", endpointId, connectionLifecycleCallback)
            } else {
                Log.d(TAG, "SKIP: $normalizedName is not on frequency $normalizedFreq")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d("MeshManager", "Endpoint lost: $endpointId")
        }
    }

    fun startAdvertising(frequency: String) {
        activeFrequency = frequency
        connectionsClient.stopAdvertising()
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            "Walkie-$frequency",
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d("MeshManager", "Started advertising Walkie-$frequency on $SERVICE_ID")
        }.addOnFailureListener {
            Log.e("MeshManager", "Failed to start advertising", it)
        }
    }

    fun startDiscovery(frequency: String) {
        activeFrequency = frequency
        connectionsClient.stopDiscovery()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d("MeshManager", "Started discovery on $SERVICE_ID")
        }.addOnFailureListener {
            Log.e("MeshManager", "Failed to start discovery", it)
        }
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        onPeerCountChanged?.invoke(0)
    }

    fun sendAudio(payloadData: ByteArray) {
        if (connectedEndpoints.isEmpty()) {
            Log.w("MeshManager", "No peers connected. Cannot send audio.")
            return
        }
        val payload = Payload.fromBytes(payloadData)
        connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
            .addOnFailureListener { Log.e("MeshManager", "Failed to send payload", it) }
    }
}
