package com.myrat.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtils {
    
    // Core permission groups
    val SMS_PERMISSIONS = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS
    )
    
    val PHONE_PERMISSIONS = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG
    )
    
    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    
    val BACKGROUND_LOCATION_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        emptyArray()
    }
    
    val STORAGE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    
    val NOTIFICATION_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }
    
    val BIOMETRIC_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        arrayOf(Manifest.permission.USE_BIOMETRIC)
    } else {
        emptyArray()
    }
    
    // Centralized permission checking
    fun hasPermissions(context: Context, vararg permissions: String): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun hasPermissionGroup(context: Context, permissionGroup: Array<String>): Boolean {
        return permissionGroup.isEmpty() || hasPermissions(context, *permissionGroup)
    }
    
    // Specific permission group checks
    fun hasAllSmsPermissions(context: Context): Boolean {
        return hasPermissionGroup(context, SMS_PERMISSIONS)
    }
    
    fun hasAllCallPermissions(context: Context): Boolean {
        return hasPermissionGroup(context, PHONE_PERMISSIONS)
    }
    
    fun hasAllLocationPermissions(context: Context): Boolean {
        val foregroundPermissions = hasPermissionGroup(context, LOCATION_PERMISSIONS)
        val backgroundPermissions = hasPermissionGroup(context, BACKGROUND_LOCATION_PERMISSIONS)
        return foregroundPermissions && backgroundPermissions
    }
    
    fun hasForegroundLocationPermissions(context: Context): Boolean {
        return hasPermissionGroup(context, LOCATION_PERMISSIONS)
    }
    
    fun hasBackgroundLocationPermissions(context: Context): Boolean {
        return hasPermissionGroup(context, BACKGROUND_LOCATION_PERMISSIONS)
    }
    
    fun hasStoragePermissions(context: Context): Boolean {
        return hasPermissionGroup(context, STORAGE_PERMISSIONS)
    }
    
    fun hasNotificationPermissions(context: Context): Boolean {
        return hasPermissionGroup(context, NOTIFICATION_PERMISSIONS)
    }
    
    fun hasBiometricPermissions(context: Context): Boolean {
        return hasPermissionGroup(context, BIOMETRIC_PERMISSIONS)
    }
    
    // Get missing permissions
    fun getMissingPermissions(context: Context, permissions: Array<String>): List<String> {
        return permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    // Get all required permissions for the app
    fun getAllRequiredPermissions(): Array<String> {
        return (SMS_PERMISSIONS + PHONE_PERMISSIONS + LOCATION_PERMISSIONS + 
                BACKGROUND_LOCATION_PERMISSIONS + STORAGE_PERMISSIONS + 
                NOTIFICATION_PERMISSIONS + BIOMETRIC_PERMISSIONS).distinct().toTypedArray()
    }
    
    // Check if all critical permissions are granted
    fun hasAllCriticalPermissions(context: Context): Boolean {
        return hasAllSmsPermissions(context) && 
               hasAllCallPermissions(context) && 
               hasAllLocationPermissions(context)
    }
}