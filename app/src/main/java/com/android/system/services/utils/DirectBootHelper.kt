package com.android.system.services.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

object DirectBootHelper {

    private const val TAG = "DirectBootHelper"

    fun isDeviceLocked(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            !context.isDeviceProtectedStorage
        } else {
            false
        }
    }

    fun getContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!context.isDeviceProtectedStorage) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
        } else {
            context
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun migrateStorageIfNeeded(context: Context) {
        try {
            if (!context.isDeviceProtectedStorage) {
                context.moveSharedPreferencesFrom(
                    context.createDeviceProtectedStorageContext(),
                    "app_prefs"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Storage migration failed: ${e.message}", e)
        }
    }

    fun logStatus(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val isLocked = isDeviceLocked(context)
            val storageType = if (context.isDeviceProtectedStorage) {
                "Device Protected"
            } else {
                "Credential Protected"
            }
            
            Log.d(TAG, "Device Locked: $isLocked")
            Log.d(TAG, "Storage Type: $storageType")
        }
    }

    fun canReceiveUserUnlocked(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}