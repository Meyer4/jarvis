package com.jarvis.ai.gesture

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.jarvis.ai.AppSettings
import com.jarvis.ai.JarvisApp
import com.jarvis.ai.MainActivity
import com.jarvis.ai.service.JarvisForegroundService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Hosts the front-camera gesture pipeline and non-intercepting cursor overlay as a camera FGS. */
class AirTouchService : LifecycleService() {
    private val serviceStopping = AtomicBoolean(false)
    private var lastPinchHintAt = 0L
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var gestureEngine: GestureEngine? = null
    private var cursorView: GestureCursorView? = null
    private var overlayAdded = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopAirTouch()
            return START_NOT_STICKY
        }
        serviceStopping.set(false)
        try {
            startAsForeground()
        } catch (error: Exception) {
            fail("Android could not start the camera foreground service. Check camera access.")
            return START_NOT_STICKY
        }
        if (gestureEngine != null) return START_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("Camera permission is required for Air Touch. Open JarvisAI to grant it.")
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            fail("Allow JarvisAI to display over other apps before enabling the Air Touch cursor.")
            return START_NOT_STICKY
        }

        val executor = Executors.newSingleThreadExecutor { Thread(it, "jarvis-hand-tracking") }
        analysisExecutor = executor
        val engine = GestureEngine(
            context = this,
            onFrame = { frame -> cursorView?.update(frame) },
            onFailure = { message -> updateStatus(message) },
        )
        gestureEngine = engine
        executor.execute {
            val initialized = runCatching { engine.initialize() }
            if (!serviceStopping.get()) {
                ContextCompat.getMainExecutor(this).execute {
                    if (!serviceStopping.get() && !isDestroyed) {
                        initialized.onSuccess { bindCamera() }
                            .onFailure { error -> fail(error.message ?: "Could not load the hand model.") }
                    }
                }
            }
        }
        AppSettings.setAirTouchEnabled(this, true)
        updateStatus("Air Touch is active. Open palm shows the cursor; fist hides it; pinch starts voice input.")
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        serviceStopping.set(true)
        releaseCameraAndOverlay()
        closeGestureEngine()
        AppSettings.setAirTouchEnabled(this, false)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun bindCamera() {
        if (isDestroyed || gestureEngine == null) return
        if (!Settings.canDrawOverlays(this)) {
            fail("Overlay permission was not granted. Air Touch has been stopped.")
            return
        }
        val overlay = GestureCursorView(this, ::handlePinchTap)
        cursorView = overlay
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(overlay, params)
            overlayAdded = true
        } catch (error: Exception) {
            fail("Could not show the cursor overlay: ${error.message.orEmpty()}")
            return
        }

        val cameraFuture = ProcessCameraProvider.getInstance(this)
        cameraFuture.addListener({
            if (serviceStopping.get() || isDestroyed) return@addListener
            try {
                val provider = cameraFuture.get()
                cameraProvider = provider
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(analysisExecutor!!, gestureEngine!!::process)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analyzer)
            } catch (error: Exception) {
                fail("Could not start the front camera: ${error.message.orEmpty()}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handlePinchTap() {
        if (AppSettings.assistantEnabled(this)) {
            sendBroadcast(
                Intent(JarvisForegroundService.ACTION_GESTURE_PINCH)
                    .setPackage(packageName),
            )
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPinchHintAt >= 5_000L) {
            lastPinchHintAt = now
            updateStatus("Start Jarvis listening from the dashboard to use pinch-to-talk.")
        }
    }

    private fun stopAirTouch() {
        serviceStopping.set(true)
        AppSettings.setAirTouchEnabled(this, false)
        releaseCameraAndOverlay()
        closeGestureEngine()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        serviceStopping.set(true)
        AppSettings.setAirTouchEnabled(this, false)
        updateStatus(message)
        releaseCameraAndOverlay()
        closeGestureEngine()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Serializes native MediaPipe cleanup behind any active analyzer or model-load task. */
    private fun closeGestureEngine() {
        val engine = gestureEngine
        gestureEngine = null
        val executor = analysisExecutor
        analysisExecutor = null
        if (executor == null) {
            runCatching { engine?.close() }
            return
        }
        try {
            executor.execute { runCatching { engine?.close() } }
            executor.shutdown()
        } catch (_: Exception) {
            runCatching { engine?.close() }
            executor.shutdownNow()
        }
    }

    private fun releaseCameraAndOverlay() {
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        if (overlayAdded) {
            runCatching {
                cursorView?.let { view ->
                    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
                }
            }
            overlayAdded = false
        }
        cursorView = null
    }

    private fun updateStatus(status: String) {
        AppSettings.setStatus(this, status)
        sendBroadcast(
            Intent(JarvisForegroundService.ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(JarvisForegroundService.EXTRA_STATUS, status),
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            3,
            Intent(this, AirTouchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_ASSISTANT)
            .setSmallIcon(com.jarvis.ai.R.drawable.ic_notification)
            .setContentTitle("Jarvis Air Touch is active")
            .setContentText("Front-camera hand tracking is running. Tap Stop to turn it off.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(com.jarvis.ai.R.drawable.ic_notification, "Stop Air Touch", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.ai.gesture.STOP_AIR_TOUCH"
        const val NOTIFICATION_ID = 4102
    }
}
