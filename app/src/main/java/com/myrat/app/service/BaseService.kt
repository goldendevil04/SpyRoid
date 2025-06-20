package com.myrat.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.myrat.app.utils.Constants
import com.myrat.app.utils.Logger
import com.myrat.app.utils.NotificationHelper

abstract class BaseService : Service() {
    
    protected var wakeLock: PowerManager.WakeLock? = null
    protected abstract val notificationId: Int
    protected abstract val channelId: String
    protected abstract val serviceName: String
    
    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        NotificationHelper.createNotificationChannels(this)
        scheduleRestart()
    }
    
    protected fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$serviceName:KeepAlive"
            )
            wakeLock?.acquire(Constants.WAKE_LOCK_TIMEOUT)
            Logger.log("Wake lock acquired for $serviceName")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock for $serviceName", e)
        }
    }
    
    protected fun startForegroundService(title: String, content: String) {
        try {
            val notification = NotificationHelper.buildServiceNotification(
                this, channelId, title, content
            )
            startForeground(notificationId, notification)
        } catch (e: Exception) {
            Logger.error("Failed to start foreground service for $serviceName", e)
        }
    }
    
    private fun scheduleRestart() {
        try {
            val alarmIntent = Intent(this, this::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000,
                Constants.SERVICE_RESTART_INTERVAL,
                pendingIntent
            )
        } catch (e: Exception) {
            Logger.error("Failed to schedule restart for $serviceName", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            Logger.log("$serviceName destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying $serviceName", e)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}