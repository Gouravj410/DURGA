package com.durga.emergency.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.durga.emergency.data.PreferencesManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class DurgaLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long
)

interface LocationProvider {
    suspend fun getCurrentLocation(): DurgaLocation?
}

class FusedLocationProvider(private val context: Context) : LocationProvider {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val prefs: PreferencesManager = PreferencesManager(context)

    private fun resolveFromLocationManager(): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val net = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val gps = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val passive = lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            listOfNotNull(net, gps, passive).maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheRealLocation(lat: Double, lon: Double) {
        prefs.lastKnownLatitude = lat
        prefs.lastKnownLongitude = lon
    }

    private fun buildFromCache(): DurgaLocation {
        val lat = prefs.lastKnownLatitude ?: 19.4580
        val lon = prefs.lastKnownLongitude ?: 72.8090
        return DurgaLocation(lat, lon, 25.0f, System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): DurgaLocation? = suspendCancellableCoroutine { continuation ->
        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location: Location? ->
                    val resolved = location ?: resolveFromLocationManager()
                    if (resolved != null) {
                        cacheRealLocation(resolved.latitude, resolved.longitude)
                        if (continuation.isActive) {
                            continuation.resume(
                                DurgaLocation(
                                    latitude = resolved.latitude,
                                    longitude = resolved.longitude,
                                    accuracy = resolved.accuracy,
                                    timestamp = resolved.time
                                )
                            )
                        }
                    } else if (continuation.isActive) {
                        fusedClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                            val finalLoc = lastLoc ?: resolveFromLocationManager()
                            if (finalLoc != null) {
                                cacheRealLocation(finalLoc.latitude, finalLoc.longitude)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        DurgaLocation(
                                            latitude = finalLoc.latitude,
                                            longitude = finalLoc.longitude,
                                            accuracy = finalLoc.accuracy,
                                            timestamp = finalLoc.time
                                        )
                                    )
                                }
                            } else {
                                if (continuation.isActive) {
                                    continuation.resume(buildFromCache())
                                }
                            }
                        }.addOnFailureListener {
                            val lmLoc = resolveFromLocationManager()
                            if (lmLoc != null) {
                                cacheRealLocation(lmLoc.latitude, lmLoc.longitude)
                                if (continuation.isActive) {
                                    continuation.resume(
                                        DurgaLocation(
                                            latitude = lmLoc.latitude,
                                            longitude = lmLoc.longitude,
                                            accuracy = lmLoc.accuracy,
                                            timestamp = lmLoc.time
                                        )
                                    )
                                }
                            } else {
                                if (continuation.isActive) {
                                    continuation.resume(buildFromCache())
                                }
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    val lmLoc = resolveFromLocationManager()
                    if (lmLoc != null) {
                        cacheRealLocation(lmLoc.latitude, lmLoc.longitude)
                        if (continuation.isActive) {
                            continuation.resume(
                                DurgaLocation(
                                    latitude = lmLoc.latitude,
                                    longitude = lmLoc.longitude,
                                    accuracy = lmLoc.accuracy,
                                    timestamp = lmLoc.time
                                )
                            )
                        }
                    } else {
                        if (continuation.isActive) {
                            continuation.resume(buildFromCache())
                        }
                    }
                }

            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }
        } catch (e: Exception) {
            val lmLoc = resolveFromLocationManager()
            if (lmLoc != null) {
                cacheRealLocation(lmLoc.latitude, lmLoc.longitude)
                if (continuation.isActive) {
                    continuation.resume(
                        DurgaLocation(
                            latitude = lmLoc.latitude,
                            longitude = lmLoc.longitude,
                            accuracy = lmLoc.accuracy,
                            timestamp = lmLoc.time
                        )
                    )
                }
            } else {
                if (continuation.isActive) {
                    continuation.resume(buildFromCache())
                }
            }
        }
    }
}
