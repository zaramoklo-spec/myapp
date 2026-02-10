package com.android.system.services.utils

import android.content.Context
import androidx.work.*
import com.android.system.services.worker.UnifiedWatchdogWorker
import java.util.concurrent.TimeUnit

object UnifiedWatchdogScheduler {
    private const val UNIQUE = "unified_watchdog"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        // Periodic (15 min minInterval; OS may flex)
        val periodic = PeriodicWorkRequestBuilder<UnifiedWatchdogWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    // Kick an immediate run (used when service dies)
    fun kickNow(context: Context) {
        val one = OneTimeWorkRequestBuilder<UnifiedWatchdogWorker>().build()
        WorkManager.getInstance(context).enqueue(one)
    }
}



