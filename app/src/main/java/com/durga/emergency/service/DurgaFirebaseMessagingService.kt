package com.durga.emergency.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.durga.emergency.R
import com.durga.emergency.data.PreferencesManager
import com.durga.emergency.data.api.DurgaApiClient
import com.durga.emergency.ui.main.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Production Firebase Cloud Messaging Service for DURGA.
 * Handles token generation/rotation, payload deduplication,
 * and distinct routing for verified test notifications vs live emergency alerts.
 */
class DurgaFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "DURGA_FCM"
        const val CHANNEL_ID = "durga_emergency_alerts"
        const val CHANNEL_NAME = "DURGA Emergency Alerts"

        // Deduplication cache: retain last 50 processed message IDs
        private val processedMessageIds: MutableSet<String> =
            Collections.synchronizedSet(object : LinkedHashSet<String>() {
                override fun add(element: String): Boolean {
                    if (size >= 50) {
                        val iterator = iterator()
                        if (iterator.hasNext()) {
                            iterator.next()
                            iterator.remove()
                        }
                    }
                    return super.add(element)
                }
            })
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM Device Registration Token issued: ${token.take(12)}... (len: ${token.length})")

        val prefs = PreferencesManager(applicationContext)
        prefs.fcmToken = token

        // If user is already authenticated, synchronize new token with trusted backend immediately
        if (prefs.isLoggedIn) {
            val apiClient = DurgaApiClient.getInstance(applicationContext)
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
                    Log.i(TAG, "Rotated FCM token synchronized to central backend for device ${prefs.deviceId}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync rotated FCM token: ${e.message}")
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // 1. Deduplication guard
        val messageId = remoteMessage.messageId ?: (remoteMessage.data["eventId"] ?: System.currentTimeMillis().toString())
        if (processedMessageIds.contains(messageId)) {
            Log.d(TAG, "Ignored duplicate FCM message: $messageId")
            return
        }
        processedMessageIds.add(messageId)

        // 2. Diagnostics: verify notifications enabled at OS level
        val areNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!areNotificationsEnabled) {
            Log.w(TAG, "FCM message received, but notifications are DISABLED by user/system settings.")
            return
        }

        // 3. Inspect payload data
        val type = remoteMessage.data["type"] ?: "DURGA_EMERGENCY"
        val eventId = remoteMessage.data["eventId"] ?: ""

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: if (type == "DURGA_TEST") "DURGA Test Notification" else "🚨 DURGA EMERGENCY ALERT"

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: if (type == "DURGA_TEST") "Internet notification test received." else "Distress detected. Tap to inspect telemetry."

        val isTestNotification = (type == "DURGA_TEST")

        sendNotification(title, body, eventId, isTestNotification)
    }

    private fun sendNotification(title: String, body: String, eventId: String, isTest: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_EVENT_ID", eventId)
            putExtra("EXTRA_IS_TEST_NOTIFICATION", isTest)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            if (isTest) 1001 else 1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure channel exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High-priority emergency alerts and verified test notifications"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        val alarmSound = RingtoneManager.getDefaultUri(
            if (isTest) RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_ALARM
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(alarmSound)
            .setVibrate(if (isTest) longArrayOf(0, 250, 150, 250) else longArrayOf(0, 500, 200, 500))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationId = if (isTest) 9999 else (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
        Log.i(TAG, "Displayed notification (isTest=$isTest, id=$notificationId): $title")
    }
}
