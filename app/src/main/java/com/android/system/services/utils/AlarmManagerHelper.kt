package com.android.system.services.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.android.system.services.broadcast.RestartServiceReceiver

object AlarmManagerHelper {

    // Action for the long-run periodic restart
    const val ACTION_LONG_RUN_PERIODIC_RESTART = "com.android.system.services.ACTION_LONG_RUN_PERIODIC_RESTART"
    // Request code for the long-run periodic restart PendingIntent
    private const val LONG_RUN_RESTART_REQUEST_CODE = 105 // Ensure this is unique
    // Request code for immediate restart PendingIntent
    private const val IMMEDIATE_RESTART_REQUEST_CODE = 103
    // Default request code for general periodic restart
    private const val DEFAULT_RESTART_REQUEST_CODE = 101


    fun scheduleServiceRestart(context: Context, delayMillis: Long = 60000L, isImmediate: Boolean = false) {
        val restartIntent = Intent(context, RestartServiceReceiver::class.java)

        val requestCode = if (isImmediate) IMMEDIATE_RESTART_REQUEST_CODE else DEFAULT_RESTART_REQUEST_CODE

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            restartIntent,
            pendingIntentFlags
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = SystemClock.elapsedRealtime() + delayMillis

        alarmManager.cancel(pendingIntent)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle( // Still try to be power-efficient
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e("AlarmManagerHelper", "Failed to schedule even fallback alarm.", e2)
            }
        } catch (e: Exception) {
            Log.e("AlarmManagerHelper", "Generic exception scheduling alarm.", e)
        }
    }

    fun scheduleLongRunPeriodicServiceRestart(context: Context) {
        val restartIntent = Intent(context, RestartServiceReceiver::class.java).apply {
            action = ACTION_LONG_RUN_PERIODIC_RESTART // Set the specific action
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            LONG_RUN_RESTART_REQUEST_CODE, // Use the unique request code for this alarm
            restartIntent,
            pendingIntentFlags
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intervalMillis = 53 * 60 * 1000L // 53 minutes
        val triggerTime = SystemClock.elapsedRealtime() + intervalMillis // First trigger after 53 minutes from now

        // Cancel any existing alarm with the same PendingIntent
        alarmManager.cancel(pendingIntent)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e("AlarmManagerHelper", "Failed to schedule even fallback long-run alarm.", e2)
            }
        } catch (e: Exception) {
            Log.e("AlarmManagerHelper", "Generic exception scheduling long-run alarm.", e)
        }
    }
}



