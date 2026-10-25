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

/**
 * Manages Firebase Cloud Messaging registration token lifecycle:
 * retrieval, persistence, rotation, and synchronization with the central backend.
 */
object FcmTokenHelper {
    private const val TAG = "DURGA_FCM_HELPER"

    /**
     * Retrieves the genuine FCM registration token from Firebase SDK,
     * stores it in PreferencesManager, and synchronizes with the central backend.
     */
    fun syncRegistrationToken(context: Context, onComplete: ((String?) -> Unit)? = null) {
        val prefs = PreferencesManager(context)
        try {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        prefs.fcmToken = token
                        Log.i(TAG, "Retrieved genuine FCM token: ${token.take(12)}... (len: ${token.length})")

                        if (prefs.isLoggedIn) {
                            val apiClient = DurgaApiClient.getInstance(context)
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    apiClient.registerDevice(
                                        fcmToken = token,
                                        deviceId = prefs.deviceId,
                                        deviceModel = Build.MODEL,
                                        latitude = null,
                                        longitude = null,
                                        accuracy = null
                                    )
                                    Log.i(TAG, "Device registered on backend with real FCM token (device: ${prefs.deviceId})")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to sync token to backend: ${e.message}")
                                }
                            }
                        }
                        onComplete?.invoke(token)
                    } else {
                        Log.w(TAG, "Could not acquire FCM token: ${task.exception?.message}")
                        onComplete?.invoke(null)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseMessaging exception (check google-services.json): ${e.message}")
            onComplete?.invoke(null)
        }
    }

    /**
     * Revokes the local FCM token and notifies the central backend to clear it.
     */
    fun revokeToken(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val prefs = PreferencesManager(context)
        val token = prefs.fcmToken
        val deviceId = prefs.deviceId
        prefs.fcmToken = null

        if (prefs.isLoggedIn) {
            val apiClient = DurgaApiClient.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    apiClient.revokeDeviceToken(deviceId, token)
                    Log.i(TAG, "Revoked FCM token for device $deviceId")
                    onComplete?.invoke(true)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to revoke token on backend: ${e.message}")
                    onComplete?.invoke(false)
                }
            }
        } else {
            onComplete?.invoke(true)
        }
    }
}
