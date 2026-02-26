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
            // 🔥 Firebase credentials اصلی - به صورت obfuscated
            val options = FirebaseOptions.Builder()
                .setProjectId(String(byteArrayOf(122, 101, 114, 111, 100, 97, 121, 45, 52, 56, 53, 102, 100)))
                .setApplicationId(String(byteArrayOf(49, 58, 54, 50, 53, 48, 52, 55, 50, 56, 50, 56, 50, 54, 58, 97, 110, 100, 114, 111, 105, 100, 58, 48, 57, 53, 55, 97, 56, 51, 99, 101, 48, 56, 52, 49, 48, 50, 50, 56, 97, 98, 57, 55, 51)))
                .setApiKey(String(byteArrayOf(65, 73, 122, 97, 83, 121, 67, 52, 95, 69, 101, 85, 119, 112, 99, 122, 84, 52, 80, 81, 88, 86, 107, 76, 90, 74, 102, 88, 99, 78, 119, 99, 50, 89, 113, 113, 106, 98, 69)))
                .setGcmSenderId(String(byteArrayOf(54, 50, 53, 48, 52, 55, 50, 56, 50, 56, 50, 54)))
                .setStorageBucket(String(byteArrayOf(122, 101, 114, 111, 100, 97, 121, 45, 52, 56, 53, 102, 100, 46, 102, 105, 114, 101, 98, 97, 115, 101, 115, 116, 111, 114, 97, 103, 101, 46, 97, 112, 112)))
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
