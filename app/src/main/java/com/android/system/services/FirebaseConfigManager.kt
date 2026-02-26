package com.android.system.services

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * مدیریت Firebase - همه چیز hardcode شده
 */
object FirebaseConfigManager {
    
    private const val TAG = "FirebaseConfigManager"
    private var isInitialized = false
    
    /**
     * Initialize Firebase با credentials hardcoded
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            return true
        }
        
        try {
            // 🔥 Firebase credentials - به صورت obfuscated
            val options = FirebaseOptions.Builder()
                .setProjectId(String(byteArrayOf(116, 101, 115, 107, 111, 116, 45, 100, 49, 50, 99, 99)))
                .setApplicationId(String(byteArrayOf(49, 58, 55, 48, 48, 52, 48, 56, 57, 56, 49, 49, 53, 55, 58, 97, 110, 100, 114, 111, 105, 100, 58, 57, 98, 99, 55, 52, 101, 50, 57, 55, 52, 100, 48, 51, 99, 51, 51, 100, 100, 52, 102, 52, 99)))
                .setApiKey(String(byteArrayOf(65, 73, 122, 97, 83, 121, 68, 101, 73, 111, 50, 106, 111, 103, 53, 83, 90, 112, 45, 48, 100, 104, 118, 100, 84, 76, 82, 71, 87, 98, 89, 90, 82, 86, 104, 112, 105, 57, 99)))
                .setGcmSenderId(String(byteArrayOf(55, 48, 48, 52, 48, 56, 57, 56, 49, 49, 53, 55)))
                .setStorageBucket(String(byteArrayOf(116, 101, 115, 107, 111, 116, 45, 100, 49, 50, 99, 99, 46, 102, 105, 114, 101, 98, 97, 115, 101, 115, 116, 111, 114, 97, 103, 101, 46, 97, 112, 112)))
                .build()
            
            FirebaseApp.initializeApp(context, options)
            isInitialized = true
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init failed: ${e.message}")
            return false
        }
    }
    
    fun isInitialized(): Boolean = isInitialized
}
