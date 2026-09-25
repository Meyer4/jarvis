package com.jarvis.ai.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.AppSettings
import com.jarvis.ai.JarvisApp
import com.jarvis.ai.MainActivity
import com.jarvis.ai.llm.LLMRouter
import com.jarvis.ai.llm.LocalLLMEngine
import com.jarvis.ai.memory.AppDatabase
import com.jarvis.ai.memory.ModelStore
import com.jarvis.ai.network.CloudLLMClient
import com.jarvis.ai.network.NetworkUtils
import com.jarvis.ai.stt.SpeechToTextEngine
import com.jarvis.ai.tools.ToolRegistry
import com.jarvis.ai.tts.TTSEngine
import com.jarvis.ai.wake.WakeWordEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the wake-word loop alive in an Android microphone foreground service. */
class JarvisForegroundService : Service() {
    private val stopping = AtomicBoolean(false)
    private val manualTurnRequested = AtomicBoolean(false)
    private var serviceJob: Job? = null
    private var serviceScope: CoroutineScope? = null
    private var wakeRecorder: AudioRecord? = null
    private var wakeEngine: WakeWordEngine? = null
    private var speechEngine: SpeechToTextEngine? = null
    private var localEngine: LocalLLMEngine? = null
    private var ttsEngine: TTSEngine? = null
    private var turnTools: ToolRegistry? = null
    private var llmRouter: LLMRouter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false

    private val pinchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_GESTURE_PINCH && !stopping.get()) {
                manualTurnRequested.set(true)
                updateStatus("Pinch detected. Listening for a voice request.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            pinchReceiver,
            IntentFilter(ACTION_GESTURE_PINCH),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            AppSettings.setAssistantEnabled(this, false)
            stopAssistant()
            return START_NOT_STICKY
        }
        if (action == ACTION_TALK_NOW) manualTurnRequested.set(true)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppSettings.setAssistantEnabled(this, false)
            updateStatus("Microphone permission is required. Open JarvisAI and allow microphone access.")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startAsForeground()
        } catch (error: Exception) {
            AppSettings.setAssistantEnabled(this, false)
            updateStatus("Android could not start microphone listening. Check microphone and notification permissions.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        AppSettings.setAssistantEnabled(this, true)
        stopping.set(false)
        if (serviceJob?.isActive != true) startAssistantLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAssistant()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(pinchReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification(AppSettings.status(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startAssistantLoop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceScope = scope
        stopping.set(false)

        serviceJob = scope.launch {
            try {
                ModelStore.installBundledAssets(this@JarvisForegroundService)
                val wake = WakeWordEngine(this@JarvisForegroundService)
                val speech = SpeechToTextEngine(this@JarvisForegroundService)
                val local = LocalLLMEngine(this@JarvisForegroundService, scope)
                val tts = TTSEngine(this@JarvisForegroundService)
                wakeEngine = wake
                speechEngine = speech
                localEngine = local
                ttsEngine = tts
                turnTools = ToolRegistry(this@JarvisForegroundService)
                llmRouter = LLMRouter(
                    localEngine = local,
                    cloudClient = CloudLLMClient(),
                    networkUtils = NetworkUtils(this@JarvisForegroundService),
                    conversationDao = AppDatabase.get(this@JarvisForegroundService).conversationDao(),
                )

                val wakeReady = wake.initialize()
                if (!wakeReady) {
                    releaseWakeLock()
                    updateStatus(wake.unavailableReason ?: "Import the wake-word models to enable always-on listening.")
                } else {
                    acquireWakeLock()
                    updateStatus("Jarvis is listening for Hey Jarvis. Tap Talk now to speak without the wake word.")
                }
                try {
                    tts.initialize()
                } catch (error: Exception) {
                    updateStatus("System speech output is unavailable. Check that an Android TTS voice is installed.")
                }

                while (isActive && !stopping.get()) {
                    if (manualTurnRequested.getAndSet(false)) {
                        handleVoiceTurn()
                        continue
                    }
                    if (!wakeReady) {
                        delay(MANUAL_POLL_MILLIS)
                        continue
                    }
                    val triggered = listenForWakeWord(wake)
                    if (triggered || manualTurnRequested.getAndSet(false)) handleVoiceTurn()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!stopping.get()) {
                    updateStatus(error.message?.take(180) ?: "Jarvis stopped after an unexpected error.")
                }
            } finally {
                closeEngine(wakeEngine)
                closeEngine(speechEngine)
                closeEngine(localEngine)
                closeEngine(ttsEngine)
                wakeEngine = null
                speechEngine = null
                localEngine = null
                ttsEngine = null
                llmRouter = null
                turnTools = null
                releaseWakeLock()
            }
        }
    }

    private suspend fun listenForWakeWord(engine: WakeWordEngine): Boolean {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "This device does not support 16 kHz microphone input." }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBuffer, WAKE_FRAME_SAMPLES * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Android could not initialize the wake-word microphone."
        }
        wakeRecorder = recorder
        val frame = ShortArray(WAKE_FRAME_SAMPLES)
        var filled = 0
        try {
            recorder.startRecording()
            while (!stopping.get() && serviceScope?.isActive == true) {
                if (manualTurnRequested.get()) return false
                val count = recorder.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) error("The wake-word microphone was disconnected.")
                    error("Wake-word microphone capture failed ($count).")
                }
                if (count == 0) continue
                filled += count
                if (filled == frame.size) {
                    if (engine.detect(frame)) return true
                    filled = 0
                }
            }
            return false
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            wakeRecorder = null
        }
    }

    private suspend fun handleVoiceTurn() {
        val speech = speechEngine ?: return
        val tts = ttsEngine
        val router = llmRouter ?: return
        val tools = turnTools ?: return
        try {
            updateStatus("Listening for your request…")
            val recognized = speech.listenForCommand()
            if (recognized.isBlank()) {
                tts?.speak("I didn't catch that. Please try again.")
                updateStatus("I didn't hear a request. Listening again.")
                return
            }
            updateStatus("You said: ${recognized.take(90)}")
            val toolResult = tools.execute(recognized)
            val reply = if (toolResult != null) {
                router.rememberToolExchange(recognized, toolResult.spokenMessage)
                toolResult.spokenMessage
            } else {
                router.respond(recognized)
            }
            tts?.speak(reply, AppSettings.ttsSpeed(this))
            updateStatus("Ready. Say Hey Jarvis or tap Talk now.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message?.take(220) ?: "I couldn't complete that request."
            updateStatus(message)
            runCatching { tts?.speak(message) }
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:jarvis-listener")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    private fun stopAssistant() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { wakeRecorder?.stop() }
        runCatching { speechEngine?.stopCapture() }
        serviceJob?.cancel()
        serviceScope?.cancel()
        serviceJob = null
        serviceScope = null
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun updateStatus(status: String) {
        AppSettings.setStatus(this, status)
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(status))
        }
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status),
        )
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val talk = PendingIntent.getService(
            this,
            4,
            Intent(this, JarvisForegroundService::class.java).setAction(ACTION_TALK_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_ASSISTANT)
            .setSmallIcon(com.jarvis.ai.R.drawable.ic_notification)
            .setContentTitle("JarvisAI is active")
            .setContentText(status.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(com.jarvis.ai.R.drawable.ic_notification, "Talk now", talk)
            .addAction(com.jarvis.ai.R.drawable.ic_notification, "Stop", stop)
            .build()
    }

    private fun closeEngine(engine: Closeable?) {
        runCatching { engine?.close() }
    }

    companion object {
        const val ACTION_START = "com.jarvis.ai.service.START_ASSISTANT"
        const val ACTION_STOP = "com.jarvis.ai.service.STOP_ASSISTANT"
        const val ACTION_TALK_NOW = "com.jarvis.ai.service.TALK_NOW"
        const val ACTION_GESTURE_PINCH = "com.jarvis.ai.service.GESTURE_PINCH"
        const val ACTION_STATUS = "com.jarvis.ai.service.STATUS"
        const val EXTRA_STATUS = "status"
        const val NOTIFICATION_ID = 4101
        private const val SAMPLE_RATE = 16_000
        private const val WAKE_FRAME_SAMPLES = 1_280
        private const val MANUAL_POLL_MILLIS = 250L
    }
}
