package com.myrat.app.worker

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.myrat.app.service.CallService
import com.myrat.app.utils.Logger

class CallServiceRestartWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            val intent = Intent(applicationContext, CallService::class.java)
            applicationContext.startService(intent)
            Logger.log("CallService restarted via WorkManager")
            return Result.success()
        } catch (e: Exception) {
            Logger.error("Failed to restart CallService via WorkManager", e)
            return Result.retry()
        }
    }
}