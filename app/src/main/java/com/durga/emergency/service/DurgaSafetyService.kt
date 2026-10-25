package com.durga.emergency.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.durga.emergency.R
import com.durga.emergency.data.PreferencesManager
import com.durga.emergency.data.api.DurgaApiClient
import com.durga.emergency.location.FusedLocationProvider
import com.durga.emergency.location.LocationProvider
import com.durga.emergency.sensors.CustomGestureDetector
import com.durga.emergency.sensors.GestureRepository
import com.durga.emergency.sensors.GestureType
import com.durga.emergency.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DURGA Persistent Background Safety Service.
 * Provides a dependable Lock-Screen & Background SOS Action Trigger
 * that bypasses Android 10-14 background execution limits.
 * Also provides continuous background proximity alerting and motion gesture detection.
 */
class DurgaSafetyService : Service() {

    companion object {
        private const val TAG = "DURGA_SAFETY_SVC"
        const val CHANNEL_ID = "durga_safety_service_channel"
        const val ALERT_CHANNEL_ID = "durga_proximity_alert_channel"
        const val NOTIFICATION_ID = 9001
        const val ALERT_NOTIFICATION_ID = 9002

        const val ACTION_START_SERVICE = "com.durga.emergency.START_SAFETY_SERVICE"
        const val ACTION_STOP_SERVICE = "com.durga.emergency.STOP_SAFETY_SERVICE"
        const val ACTION_TRIGGER_SOS = "com.durga.emergency.TRIGGER_SOS_FROM_NOTIFICATION"

        fun startService(context: Context) {
            val intent = Intent(context, DurgaSafetyService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DurgaSafetyService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PreferencesManager
    private lateinit var apiClient: DurgaApiClient
    private lateinit var locationProvider: LocationProvider

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var gestureDetector: CustomGestureDetector? = null
    private lateinit var gestureRepo: GestureRepository

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        apiClient = DurgaApiClient.getInstance(this)
        locationProvider = FusedLocationProvider(this)

        createNotificationChannels()
        startBackgroundProximityMonitor()
        initGestureMonitoring()
    }

    private fun initGestureMonitoring() {
        gestureRepo = GestureRepository.getInstance(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val primary = gestureRepo.getPrimaryGesture()
        gestureDetector = CustomGestureDetector(
            config = primary,
            onGestureMatched = { matchedConfig, confidence ->
                Log.i(TAG, "🚨 [BACKGROUND GESTURE DETECTED] Triggering SOS: ${matchedConfig.name}")
                val sosIntent = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_TRIGGER_SOS
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(sosIntent)
            }
        )

        if (gestureRepo.isGestureActivationEnabled && primary.type == GestureType.MOTION_SHAKE_SEQUENCE) {
            accelerometer?.let {
                sensorManager?.registerListener(gestureDetector, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stopping DurgaSafetyService")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildPersistentNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d(TAG, "DurgaSafetyService running in foreground")
                return START_STICKY
            }
        }
    }

    private fun startBackgroundProximityMonitor() {
        serviceScope.launch {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            while (true) {
                try {
                    if (prefs.isLoggedIn) {
                        val loc = locationProvider.getCurrentLocation()
                        val result = apiClient.getNearbyIncidents(
                            latitude = loc?.latitude,
                            longitude = loc?.longitude,
                            radiusKm = 1.0,
                            excludeUserId = prefs.userId
                        )

                        if (result.isSuccess) {
                            val incidents = result.getOrNull() ?: emptyList()
                            val active = incidents.firstOrNull {
                                it.status in listOf("ACTIVE", "REPEAT_ACTIVE", "ACKNOWLEDGED", "RESPONDING")
                            }

                            if (active != null) {
                                val alertNotif = buildProximityAlertNotification(active.userName, active.emergencyContactPhone)
                                notificationManager.notify(ALERT_NOTIFICATION_ID, alertNotif)
                            } else {
                                notificationManager.cancel(ALERT_NOTIFICATION_ID)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background proximity check note: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    private fun buildProximityAlertNotification(victimName: String, contactPhone: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("🚨 NEARBY CITIZEN SOS ALERT!")
            .setContentText("Emergency triggered by $victimName ($contactPhone) within 1.0 km! Tap to view.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildPersistentNotification(): Notification {
        // Tap on notification body opens MainActivity
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action button to immediately trigger SOS
        val sosTriggerIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_TRIGGER_SOS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sosPendingIntent = PendingIntent.getActivity(
            this,
            1,
            sosTriggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.status_protected))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_dialog_alert,
                getString(R.string.notif_service_action_sos),
                sosPendingIntent
            )
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Persistent Service Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(serviceChannel)

            // High Priority Proximity Alert Channel
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "DURGA Proximity Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent alerts when a nearby citizen within 1.0 km triggers SOS"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        sensorManager?.unregisterListener(gestureDetector)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(ALERT_NOTIFICATION_ID)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
