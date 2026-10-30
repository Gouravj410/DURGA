package com.durga.emergency.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.durga.emergency.data.PreferencesManager
import com.durga.emergency.data.api.DurgaApiClient
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages Firebase Cloud Messaging registration token lifecycle:
 * retrieval, persistence, rotation, and truthful synchronization with the central backend.
 */
object FcmTokenHelper {
    private const val TAG = "DURGA_FCM_HELPER"

    @Volatile
    private var isSyncInProgress = false

    @Volatile
    private var lastSyncedToken: String? = null

    @Volatile
    private var lastSyncedAuthToken: String? = null

    @Volatile
    private var lastSyncedDeviceId: String? = null

    /**
     * Coroutine-based retrieval of the Firebase registration token.
     */
    private suspend fun getFirebaseToken(): String = suspendCancellableCoroutine { continuation ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        continuation.resume(token)
                    } else {
                        continuation.resumeWithException(IllegalStateException("Empty or null FCM token received from Firebase"))
                    }
                } else {
                    continuation.resumeWithException(task.exception ?: IllegalStateException("FCM token retrieval failed"))
                }
            }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    /**
     * Truthfully retrieves and synchronizes device registration with the backend.
     * Fails if unauthenticated, if Firebase token cannot be obtained, or if backend registration fails.
     */
    suspend fun syncRegistrationTokenSuspend(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val prefs = PreferencesManager(context)
        try {
            val token = getFirebaseToken()
            prefs.fcmToken = token
            Log.i(TAG, "Retrieved genuine FCM token: ${token.take(12)}... (len: ${token.length})")

            if (!prefs.isLoggedIn) {
                Log.d(TAG, "FCM token saved, but user not logged in; deferred backend registration.")
                return@withContext Result.failure(IllegalStateException("User is not authenticated; backend registration deferred."))
            }

            val currentAuthToken = prefs.authToken
            val currentDeviceId = prefs.deviceId

            // Idempotency check: don't hammer the backend if identical token, auth, and deviceId are registered
            if (token == lastSyncedToken &&
                currentAuthToken == lastSyncedAuthToken &&
                currentDeviceId == lastSyncedDeviceId
            ) {
                Log.d(TAG, "Backend registration already up-to-date for device $currentDeviceId")
                return@withContext Result.success(token)
            }

            val loc = try {
                com.durga.emergency.location.FusedLocationProvider(context).getCurrentLocation()
            } catch (e: Exception) { null }

            val apiClient = DurgaApiClient.getInstance(context)
            val result = apiClient.registerDevice(
                fcmToken = token,
                deviceId = currentDeviceId,
                deviceModel = Build.MODEL,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                accuracy = loc?.accuracy
            )

            if (result.isSuccess) {
                lastSyncedToken = token
                lastSyncedAuthToken = currentAuthToken
                lastSyncedDeviceId = currentDeviceId
                Log.i(TAG, "Backend registration succeeded for device $currentDeviceId")
                Result.success(token)
            } else {
                val err = result.exceptionOrNull() ?: Exception("Backend registration rejected")
                Log.e(TAG, "Backend registration FAILED: ${err.message}", err)
                Result.failure(err)
            }
        } catch (e: Exception) {
            Log.e(TAG, "FCM token synchronization exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Retrieves the genuine FCM registration token from Firebase SDK,
     * stores it in PreferencesManager, and synchronizes with the central backend.
     */
    fun syncRegistrationToken(context: Context, onComplete: ((String?) -> Unit)? = null) {
        val prefs = PreferencesManager(context)
        CoroutineScope(Dispatchers.IO).launch {
            val result = syncRegistrationTokenSuspend(context)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    onComplete?.invoke(result.getOrNull())
                } else {
                    val token = prefs.fcmToken
                    if (!prefs.isLoggedIn && !token.isNullOrBlank()) {
                        // Return token locally even if backend registration deferred
                        onComplete?.invoke(token)
                    } else {
                        onComplete?.invoke(null)
                    }
                }
            }
        }
    }

    /**
     * Extended sync callback that reports truthful backend registration outcome.
     */
    fun syncRegistrationTokenWithResult(
        context: Context,
        onResult: (isRegisteredOnBackend: Boolean, fcmToken: String?, error: Throwable?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = syncRegistrationTokenSuspend(context)
            val prefs = PreferencesManager(context)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    onResult(true, result.getOrNull(), null)
                } else {
                    val token = prefs.fcmToken
                    onResult(false, token, result.exceptionOrNull())
                }
            }
        }
    }

    /**
     * Revokes the local FCM token and notifies the central backend to clear it.
     */
    fun revokeToken(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val prefs = PreferencesManager(context)
        val token = prefs.fcmToken
        val deviceId = prefs.deviceId
        val authToken = prefs.authToken
        prefs.fcmToken = null
        lastSyncedToken = null
        lastSyncedAuthToken = null
        lastSyncedDeviceId = null

        if (prefs.isLoggedIn && !authToken.isNullOrEmpty()) {
            val apiClient = DurgaApiClient.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = apiClient.revokeDeviceToken(deviceId, token, authToken)
                    val success = result.isSuccess
                    if (success) {
                        Log.i(TAG, "Revoked FCM token for device $deviceId")
                    } else {
                        Log.w(TAG, "Failed to revoke token on backend: ${result.exceptionOrNull()?.message}")
                    }
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(success)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unexpected error revoking token on backend: ${e.message}")
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false)
                    }
                }
            }
        } else {
            onComplete?.invoke(true)
        }
    }
}
