package com.android.system.services

import android.app.Application
import android.util.Log

/**
 * Application Class برای initialize کردن Firebase به صورت manual
 */
class MyApplication : Application() {
    
    companion object {
        private const val TAG = "MyApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Application onCreate - Package: $packageName")
        
        // 🔥 Initialize Firebase به صورت manual
        val firebaseInitialized = FirebaseConfigManager.initialize(this)
        
        if (firebaseInitialized) {
            Log.d(TAG, "Firebase initialized successfully")
            
            // Initialize ServerConfig بعد از Firebase
            try {
                ServerConfig.initialize(this)
                Log.d(TAG, "ServerConfig initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ServerConfig: ${e.message}", e)
            }
        } else {
            Log.e(TAG, "Firebase initialization failed")
        }
    }
}
