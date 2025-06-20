package com.myrat.app.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

object WhatsAppHelper {
    
    // Enhanced view IDs for better compatibility
    private val SEARCH_IDS = listOf(
        "menuitem_search", "search", "search_button", "action_search", "search_icon"
    )
    
    private val SEARCH_INPUT_IDS = listOf(
        "search_input", "search_field", "search_edit_text", "search_src_text", "search_view"
    )
    
    private val CONTACT_RESULT_IDS = listOf(
        "conversations_row", "chat_row", "conversation_item", "chat_item", "contact_row"
    )
    
    private val CONTACT_NAME_IDS = listOf(
        "conversation_contact_name", "contact_name", "chat_title", "header_title", 
        "toolbar_title", "conversation_title", "contact_row_name"
    )
    
    private val MESSAGE_ENTRY_IDS = listOf(
        "entry", "message_entry", "input_message", "chat_input", "compose_text", "text_input"
    )
    
    private val SEND_BUTTON_IDS = listOf(
        "send", "send_button", "btn_send", "voice_note_btn", "send_text"
    )
    
    fun isWhatsAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    fun getLaunchIntent(context: Context, packageName: String): Intent? {
        return context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
    
    suspend fun findAndClickSearch(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        for (attempt in 1..5) {
            val searchButton = SEARCH_IDS.mapNotNull { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
            }.firstOrNull()
            
            if (searchButton != null && searchButton.isClickable) {
                searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("✅ Search button clicked (attempt $attempt)")
                return true
            }
            delay(1500)
        }
        return false
    }
    
    suspend fun enterSearchText(rootNode: AccessibilityNodeInfo, packageName: String, text: String): Boolean {
        for (attempt in 1..5) {
            val searchField = SEARCH_INPUT_IDS.mapNotNull { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
            }.firstOrNull()
            
            if (searchField != null) {
                // Focus and clear existing text
                searchField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                delay(500)
                
                // Select all and replace
                val selectAllArgs = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, searchField.text?.length ?: 0)
                }
                searchField.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
                delay(300)
                
                // Set new text
                val textArgs = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)
                Logger.log("✅ Search text entered: $text (attempt $attempt)")
                return true
            }
            delay(1500)
        }
        return false
    }
    
    suspend fun clickContactResult(rootNode: AccessibilityNodeInfo, packageName: String, searchText: String): Boolean {
        for (attempt in 1..8) { // Increased attempts for contact selection
            // Look for contact rows first
            val contactRows = CONTACT_RESULT_IDS.flatMap { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
            }
            
            if (contactRows.isNotEmpty()) {
                // Try to find the specific contact by name/number
                for (row in contactRows) {
                    val contactName = CONTACT_NAME_IDS.mapNotNull { id ->
                        row.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()?.text?.toString()
                    }.firstOrNull()
                    
                    // Check if this row matches our search (by name or contains the number)
                    if (contactName != null && (
                        contactName.contains(searchText, ignoreCase = true) ||
                        searchText.contains(contactName, ignoreCase = true) ||
                        isPhoneNumberMatch(contactName, searchText)
                    )) {
                        if (row.isClickable) {
                            row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Logger.log("✅ Specific contact clicked: $contactName (attempt $attempt)")
                            return true
                        }
                        // If row is not clickable, try clicking the name element
                        val nameElement = CONTACT_NAME_IDS.mapNotNull { id ->
                            row.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
                        }.firstOrNull()
                        if (nameElement?.isClickable == true) {
                            nameElement.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Logger.log("✅ Contact name clicked: $contactName (attempt $attempt)")
                            return true
                        }
                    }
                }
                
                // If no specific match found, click the first result
                val firstRow = contactRows.firstOrNull()
                if (firstRow?.isClickable == true) {
                    firstRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.log("✅ First contact result clicked (attempt $attempt)")
                    return true
                }
            }
            
            // Fallback: look for any clickable contact name elements
            val contactNames = CONTACT_NAME_IDS.flatMap { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
            }.filter { it.isClickable }
            
            if (contactNames.isNotEmpty()) {
                contactNames.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("✅ Contact name element clicked (attempt $attempt)")
                return true
            }
            
            delay(2000) // Longer delay for contact results to appear
        }
        return false
    }
    
    suspend fun enterMessage(rootNode: AccessibilityNodeInfo, packageName: String, message: String): Boolean {
        for (attempt in 1..5) {
            val messageInput = MESSAGE_ENTRY_IDS.mapNotNull { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
            }.firstOrNull()
            
            if (messageInput != null) {
                // Focus on input
                messageInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                delay(500)
                
                // Clear existing text
                val selectAllArgs = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, messageInput.text?.length ?: 0)
                }
                messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
                delay(300)
                
                // Set message
                val messageArgs = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                }
                messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, messageArgs)
                Logger.log("✅ Message entered (attempt $attempt)")
                return true
            }
            delay(1500)
        }
        return false
    }
    
    suspend fun clickSendButton(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        for (attempt in 1..5) {
            val sendButton = SEND_BUTTON_IDS.mapNotNull { id ->
                rootNode.findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull()
            }.firstOrNull()
            
            if (sendButton != null && sendButton.isClickable) {
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.log("✅ Send button clicked (attempt $attempt)")
                return true
            }
            delay(1500)
        }
        return false
    }
    
    private fun isPhoneNumberMatch(contactName: String, searchText: String): Boolean {
        // Remove all non-digit characters for comparison
        val contactDigits = contactName.replace(Regex("[^\\d]"), "")
        val searchDigits = searchText.replace(Regex("[^\\d]"), "")
        
        return when {
            contactDigits.isEmpty() || searchDigits.isEmpty() -> false
            contactDigits.length >= 10 && searchDigits.length >= 10 -> {
                // Compare last 10 digits for phone numbers
                contactDigits.takeLast(10) == searchDigits.takeLast(10)
            }
            else -> contactDigits.contains(searchDigits) || searchDigits.contains(contactDigits)
        }
    }
}