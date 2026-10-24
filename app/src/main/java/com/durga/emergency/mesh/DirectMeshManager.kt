package com.durga.emergency.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.durga.emergency.data.model.EmergencyEvent
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DURGA Direct Device-to-Device Mesh Manager.
 * Enables zero-server, zero-API emergency alerts between adjacent physical Android phones
 * over local Wi-Fi, portable mobile hotspots, and ad-hoc networks via UDP broadcast.
 */
class DirectMeshManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DURGA_DIRECT_MESH"
        const val MESH_PORT = 8888

        @Volatile
        private var instance: DirectMeshManager? = null

        fun getInstance(context: Context): DirectMeshManager {
            return instance ?: synchronized(this) {
                instance ?: DirectMeshManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenJob: Job? = null
    private var activeBroadcastJob: Job? = null

    private var alertCallback: ((EmergencyEvent) -> Unit)? = null
    private var cancelCallback: ((String) -> Unit)? = null

    init {
        acquireMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("durga_direct_mesh_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Wi-Fi Multicast Lock acquired for Direct Mesh")
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock note: ${e.message}")
        }
    }

    /**
     * Start listening for direct emergency broadcasts from nearby phones.
     */
    fun startListening(
        myUserId: String,
        myLat: Double? = null,
        myLon: Double? = null,
        onAlert: (EmergencyEvent) -> Unit,
        onCancel: (String) -> Unit
    ) {
        alertCallback = onAlert
        cancelCallback = onCancel

        if (listenJob?.isActive == true) return

        listenJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(MESH_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }
                Log.i(TAG, "🟢 Direct Mesh Receiver active on UDP port $MESH_PORT")

                val buffer = ByteArray(4096)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val jsonStr = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    Log.d(TAG, "Received Mesh Packet from ${packet.address.hostAddress}: $jsonStr")

                    try {
                        val json = gson.fromJson(jsonStr, JsonObject::class.java)
                        val action = json.get("action")?.asString ?: ""
                        val senderUserId = json.get("userId")?.asString ?: ""

                        // Ignore broadcasts sent by self
                        if (senderUserId == myUserId) {
                            continue
                        }

                        when (action) {
                            "EMERGENCY_ALERT" -> {
                                val victimName = json.get("userName")?.asString ?: "Adjacent Citizen"
                                val victimPhone = json.get("userPhone")?.asString ?: ""
                                val victimLat = if (json.has("latitude") && !json.get("latitude").isJsonNull) json.get("latitude").asDouble else 18.5204
                                val victimLon = if (json.has("longitude") && !json.get("longitude").isJsonNull) json.get("longitude").asDouble else 73.8567
                                val distressScore = if (json.has("distressScore") && !json.get("distressScore").isJsonNull) json.get("distressScore").asFloat else 0.85f
                                val distressClass = json.get("distressClass")?.asString ?: "HIGH_DISTRESS"
                                val incidentId = json.get("incidentId")?.asString ?: ("INC-MESH-" + System.currentTimeMillis().toString().takeLast(6))

                                // Calculate distance in km
                                val distanceKm = if (myLat != null && myLon != null) {
                                    haversineDistance(myLat, myLon, victimLat, victimLon)
                                } else {
                                    0.05 // ~50 meters (adjacent)
                                }

                                val event = EmergencyEvent(
                                    eventId = incidentId,
                                    userId = senderUserId,
                                    userName = victimName,
                                    timestamp = System.currentTimeMillis(),
                                    latitude = victimLat,
                                    longitude = victimLon,
                                    locationAccuracy = 10.0f,
                                    distressScore = distressScore,
                                    distressClassification = distressClass,
                                    emergencyLevel = "HIGH",
                                    audioUrl = "",
                                    emergencyContactPhone = victimPhone,
                                    nearestPoliceStationName = "Nearby Emergency Unit",
                                    nearestPoliceStationDistanceKm = distanceKm,
                                    status = "ACTIVE"
                                )

                                withContext(Dispatchers.Main) {
                                    alertCallback?.invoke(event)
                                }
                            }

                            "EMERGENCY_CANCEL" -> {
                                withContext(Dispatchers.Main) {
                                    cancelCallback?.invoke(senderUserId)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing mesh packet: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mesh listener socket closed or error: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    /**
     * Broadcast emergency alert packet over local Wi-Fi / Hotspot to all adjacent phones.
     * Continuously transmits every 2 seconds while SOS is active.
     */
    fun startEmergencyBroadcast(
        incidentId: String,
        userId: String,
        userName: String,
        userPhone: String,
        latitude: Double,
        longitude: Double,
        distressScore: Float,
        distressClass: String
    ) {
        activeBroadcastJob?.cancel()
        activeBroadcastJob = scope.launch {
            val payload = JsonObject().apply {
                addProperty("action", "EMERGENCY_ALERT")
                addProperty("incidentId", incidentId)
                addProperty("userId", userId)
                addProperty("userName", userName)
                addProperty("userPhone", userPhone)
                addProperty("latitude", latitude)
                addProperty("longitude", longitude)
                addProperty("distressScore", distressScore)
                addProperty("distressClass", distressClass)
                addProperty("timestamp", System.currentTimeMillis())
            }.toString()

            Log.i(TAG, "📡 Starting Direct Mesh SOS Broadcast: $payload")
            while (isActive) {
                sendUdpBroadcast(payload)
                delay(2000)
            }
        }
    }

    /**
     * Stop repeating emergency broadcast and send cancellation signal to all nearby phones.
     */
    fun broadcastEmergencyCancellation(userId: String, userName: String) {
        activeBroadcastJob?.cancel()
        activeBroadcastJob = null

        scope.launch {
            val payload = JsonObject().apply {
                addProperty("action", "EMERGENCY_CANCEL")
                addProperty("userId", userId)
                addProperty("userName", userName)
                addProperty("timestamp", System.currentTimeMillis())
            }.toString()

            Log.i(TAG, "📡 Broadcasting Direct Mesh Cancellation: $payload")
            // Send 3 times to ensure delivery over UDP
            repeat(3) {
                sendUdpBroadcast(payload)
                delay(200)
            }
        }
    }

    /**
     * Transmit UDP packet to 255.255.255.255 and all local interface broadcast addresses.
     */
    private fun sendUdpBroadcast(message: String) {
        try {
            val socket = DatagramSocket().apply {
                broadcast = true
            }
            val data = message.toByteArray(Charsets.UTF_8)

            val broadcastTargets = getBroadcastAddresses()
            for (target in broadcastTargets) {
                try {
                    val packet = DatagramPacket(data, data.size, target, MESH_PORT)
                    socket.send(packet)
                } catch (e: Exception) {
                    // Ignore per-interface transmission errors
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "UDP send error: ${e.message}")
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            // Global standard broadcast
            list.add(InetAddress.getByName("255.255.255.255"))

            // Enumerate network interfaces for subnet broadcasts
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue

                for (ifAddr in netIf.interfaceAddresses) {
                    val broadcast = ifAddr.broadcast
                    if (broadcast != null) {
                        list.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return list.distinct()
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun stop() {
        listenJob?.cancel()
        activeBroadcastJob?.cancel()
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
