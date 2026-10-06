package com.durga.emergency.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): DurgaLocation? = suspendCancellableCoroutine { continuation ->
        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location: Location? ->
                    if (location != null && continuation.isActive) {
                        continuation.resume(
                            DurgaLocation(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracy = location.accuracy,
                                timestamp = location.time
                            )
                        )
                    } else if (continuation.isActive) {
                        // Fallback to last location
                        fusedClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                            if (continuation.isActive) {
                                if (lastLoc != null) {
                                    continuation.resume(
                                        DurgaLocation(
                                            latitude = lastLoc.latitude,
                                            longitude = lastLoc.longitude,
                                            accuracy = lastLoc.accuracy,
                                            timestamp = lastLoc.time
                                        )
                                    )
                                } else {
                                    continuation.resume(null)
                                }
                            }
                        }.addOnFailureListener {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }

            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
