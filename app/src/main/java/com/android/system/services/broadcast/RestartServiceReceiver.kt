package com.android.system.services.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.android.system.services.UnifiedService
import com.android.system.services.utils.AlarmManagerHelper
import androidx.core.content.ContextCompat

class RestartServiceReceiver : BroadcastReceiver() {

    private val TAG = "RestartServiceReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action

        // Start UnifiedService
        val serviceIntent = Intent(context, UnifiedService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "UnifiedService restarted")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting UnifiedService", e)
        }

        // Reschedule the appropriate alarm
        if (AlarmManagerHelper.ACTION_LONG_RUN_PERIODIC_RESTART == action) {
            AlarmManagerHelper.scheduleLongRunPeriodicServiceRestart(context)
        } else {
            AlarmManagerHelper.scheduleServiceRestart(context)
        }
    }
}



