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
        const val CHANNEL_ID = "durga_emergency_siren_v3"
        const val CHANNEL_NAME = "DURGA Emergency Siren Alerts"

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
                    val result = apiClient.registerDevice(
                        fcmToken = token,
                        deviceId = prefs.deviceId,
                        deviceModel = Build.MODEL,
                        latitude = null,
                        longitude = null,
                        accuracy = null
                    )
                    result.onSuccess {
                        Log.i(TAG, "Rotated FCM token synchronized to central backend for device ${prefs.deviceId}")
                    }.onFailure { error ->
                        Log.e(TAG, "Backend registration FAILED for rotated token on device ${prefs.deviceId}: ${error.message}", error)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error synchronizing rotated FCM token: ${e.message}", e)
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // 1. Deduplication guard
        val eventIdRaw = remoteMessage.data["eventId"] ?: remoteMessage.data["incidentId"]
        val messageId = remoteMessage.messageId ?: eventIdRaw ?: System.currentTimeMillis().toString()
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

        // 3. Inspect payload data with backward/forward compatibility using parser
        val parsed = com.durga.emergency.utils.NotificationPayloadParser.parse(
            data = remoteMessage.data,
            notificationTitle = remoteMessage.notification?.title,
            notificationBody = remoteMessage.notification?.body
        )

        sendNotification(parsed, remoteMessage.data)
    }

    private fun sendNotification(
        parsed: com.durga.emergency.utils.ParsedNotification,
        dataPayload: Map<String, String> = emptyMap()
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_EVENT_ID", parsed.eventId)
            putExtra("EXTRA_INCIDENT_ID", parsed.eventId)
            putExtra("EXTRA_IS_TEST_NOTIFICATION", parsed.isTest)
            putExtra("EXTRA_NOTIFICATION_TYPE", parsed.type)
            if (parsed.latitude != null) putExtra("EXTRA_LATITUDE", parsed.latitude)
            if (parsed.longitude != null) putExtra("EXTRA_LONGITUDE", parsed.longitude)
            if (parsed.accuracy != null) putExtra("EXTRA_ACCURACY", parsed.accuracy)
            putExtra("EXTRA_DISTRESS_SCORE", parsed.distressScore)
            putExtra("EXTRA_CLASSIFICATION", parsed.classification)
            putExtra("EXTRA_NEAREST_STATION", parsed.nearestStation)
            if (parsed.distanceKm != null) putExtra("EXTRA_DISTANCE_KM", parsed.distanceKm)
            putExtra("EXTRA_STATUS", parsed.status)
            putExtra("EXTRA_ALERT_TITLE", parsed.title)
            putExtra("EXTRA_ALERT_BODY", parsed.body)
            for ((key, value) in dataPayload) {
                putExtra(key, value)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            if (parsed.isTest) 1001 else 1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val customSoundUri = android.net.Uri.parse("android.resource://$packageName/${R.raw.durga_emergency_siren}")

        // Ensure channel exists with custom DURGA siren
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                notificationManager.deleteNotificationChannel("durga_emergency_alerts")
            } catch (e: Exception) {
                // ignore
            }

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority emergency alerts with custom DURGA siren"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(customSoundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(parsed.title)
            .setContentText(parsed.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(parsed.body))
            .setAutoCancel(true)
            .setSound(if (parsed.isTest) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else customSoundUri)
            .setVibrate(if (parsed.isTest) longArrayOf(0, 250, 150, 250) else longArrayOf(0, 500, 200, 500))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)

        val notificationId = if (parsed.isTest) 9999 else (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
        Log.i(TAG, "Displayed notification (isTest=${parsed.isTest}, id=$notificationId): ${parsed.title}")
    }
}
