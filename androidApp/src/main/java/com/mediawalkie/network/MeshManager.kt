package com.mediawalkie.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class MeshManager(private val context: Context) {

    private val TAG = "MeshManager"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.mediawalkie.MESH_SERVICE"
    private val STRATEGY = Strategy.P2P_CLUSTER

    private val connectedEndpoints = mutableSetOf<String>()
    private val pendingConnections = mutableSetOf<String>() // endpoints awaiting connection result

    var onPayloadReceived: ((ByteArray) -> Unit)? = null
    var onPeerCountChanged: ((Int) -> Unit)? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("MeshManager", "Connection initiated with $endpointId")
            // Auto accept for mesh
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnections.remove(endpointId)
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
            pendingConnections.remove(endpointId)
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

    // Stable device name persisted across app restarts so peers can reliably identify us
    private val prefs: SharedPreferences = context.getSharedPreferences("mesh_prefs", Context.MODE_PRIVATE)
    private val deviceName: String = prefs.getString("device_name", null) ?: run {
        val newName = "Walkie-${(1000..9999).random()}"
        prefs.edit().putString("device_name", newName).apply()
        newName
    }

    fun startAdvertising() {
        connectionsClient.stopAdvertising()
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        
        connectionsClient.startAdvertising(
            deviceName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d("MeshManager", "Started advertising globally as $deviceName on $SERVICE_ID")
        }.addOnFailureListener {
            Log.e("MeshManager", "Failed to start advertising", it)
        }
    }

    fun startDiscovery() {
        connectionsClient.stopDiscovery()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    val endpointName = info.endpointName ?: "Unknown"
                    Log.d(TAG, "Radar Pulse: Spotted node $endpointId ($endpointName)")
                    if (info.endpointName.startsWith("Walkie-")) {
                        // Guard: skip if already connected or connection pending
                        if (connectedEndpoints.contains(endpointId) || pendingConnections.contains(endpointId)) {
                            Log.d(TAG, "Skipping $endpointId — already connected or pending")
                            return
                        }
                        Log.d("MeshManager", "MATCH! Connecting to global mesh node $endpointName...")
                        pendingConnections.add(endpointId)
                        connectionsClient.requestConnection(deviceName, endpointId, connectionLifecycleCallback)
                            .addOnFailureListener { e ->
                                Log.e("MeshManager", "requestConnection failed for $endpointId: ${e.message}")
                                pendingConnections.remove(endpointId)
                            }
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    Log.d("MeshManager", "Endpoint lost: $endpointId")
                }
            },
            options
        ).addOnSuccessListener {
            Log.d("MeshManager", "Started global discovery on $SERVICE_ID")
        }.addOnFailureListener {
            Log.e("MeshManager", "Failed to start discovery", it)
        }
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        pendingConnections.clear()
        onPeerCountChanged?.invoke(0)
    }

    /**
     * Restarts only the advertising and discovery without disconnecting active peers.
     * Use this for periodic scan refresh to avoid killing live connections.
     */
    fun restartScanOnly() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        val advOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(deviceName, SERVICE_ID, connectionLifecycleCallback, advOptions)
            .addOnSuccessListener { Log.d(TAG, "restartScanOnly: advertising restarted") }
            .addOnFailureListener { Log.e(TAG, "restartScanOnly: advertising failed", it) }
        val discOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (info.endpointName.startsWith("Walkie-") &&
                        !connectedEndpoints.contains(endpointId) &&
                        !pendingConnections.contains(endpointId)) {
                        pendingConnections.add(endpointId)
                        connectionsClient.requestConnection(deviceName, endpointId, connectionLifecycleCallback)
                            .addOnFailureListener { e ->
                                Log.e(TAG, "restartScanOnly requestConnection failed: ${e.message}")
                                pendingConnections.remove(endpointId)
                            }
                    }
                }
                override fun onEndpointLost(endpointId: String) {
                    Log.d(TAG, "Endpoint lost during rescan: $endpointId")
                }
            },
            discOptions
        ).addOnSuccessListener { Log.d(TAG, "restartScanOnly: discovery restarted") }
         .addOnFailureListener { Log.e(TAG, "restartScanOnly: discovery failed", it) }
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
