package com.durga.emergency

import android.app.Application
import android.util.Log

class DurgaApplication : Application() {

    companion object {
        const val TAG = "DURGA"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[DURGA] Application initialized.")
        createEmergencyNotificationChannel()
    }

    private fun createEmergencyNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channelId = "durga_emergency_alerts"
            val channelName = "DURGA Emergency Alerts"
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority emergency alerts and verified test notifications"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                setSound(
                    soundUri,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "[DURGA] Emergency notification channel initialized.")
        }
    }
}
