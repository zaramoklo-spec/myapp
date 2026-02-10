package com.android.system.services.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionHelper {

    val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE
    )

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all { hasPermission(context, it) }
    }

    fun getMissingPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter { !hasPermission(context, it) }
    }

    fun hasSmsPermissions(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.READ_SMS) &&
                hasPermission(context, Manifest.permission.RECEIVE_SMS) &&
                hasPermission(context, Manifest.permission.SEND_SMS)
    }


    fun hasPhoneStatePermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.READ_PHONE_STATE)
    }

    fun hasCallPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.CALL_PHONE)
    }
}