package com.jarvis.ai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.jarvis.ai.gesture.AirTouchService
import com.jarvis.ai.memory.ModelStore
import com.jarvis.ai.service.JarvisForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Presents permissions, model readiness, assistant controls, and setup guidance. */
class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var modelView: TextView
    private lateinit var assistantButton: MaterialButton
    private lateinit var gestureButton: MaterialButton
    private lateinit var speedValueView: TextView
    private var pendingAction: PendingAction? = null
    private var receiverRegistered = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val action = pendingAction
        val microphoneRequired = action == PendingAction.START_ASSISTANT || action == PendingAction.TALK_NOW
        val microphoneGranted = !microphoneRequired ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cameraRequired = action == PendingAction.AIR_TOUCH
        val cameraGranted = !cameraRequired ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!microphoneGranted || !cameraGranted) {
            pendingAction = null
            setStatus(
                if (!cameraGranted) "Camera permission is required for Air Touch."
                else "Microphone permission is required to use Jarvis voice features.",
            )
            refreshDashboard()
        } else {
            // Android allows a foreground service to run even when notification permission is declined.
            runPendingAction()
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (pendingAction == PendingAction.AIR_TOUCH) {
            if (Settings.canDrawOverlays(this)) runPendingAction()
            else {
                pendingAction = null
                setStatus("Air Touch needs display-over-other-apps permission for its floating cursor.")
                refreshDashboard()
            }
        }
    }

    private val importModelsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val imported = mutableListOf<String>()
            val failures = mutableListOf<String>()
            uris.forEach { uri ->
                runCatching { ModelStore.import(this@MainActivity, uri) }
                    .onSuccess { imported += it.name }
                    .onFailure { failures += (it.message ?: "Could not import file") }
            }
            refreshDashboard()
            val result = buildString {
                if (imported.isNotEmpty()) append("Imported ${imported.size} model file(s). ")
                if (failures.isNotEmpty()) append(failures.first())
            }
            Toast.makeText(this@MainActivity, result.ifBlank { "No model files imported." }, Toast.LENGTH_LONG).show()
            if (imported.isNotEmpty()) setStatus("Models imported. Start Jarvis again to load the new files.")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(JarvisForegroundService.EXTRA_STATUS)?.let(::setStatus)
            refreshDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.jarvis_background)
        window.navigationBarColor = getColor(R.color.jarvis_background)
        buildDashboard()
        lifecycleScope.launch(Dispatchers.IO) {
            ModelStore.installBundledAssets(applicationContext)
            withContext(Dispatchers.Main) { refreshDashboard() }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(JarvisForegroundService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        refreshDashboard()
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(statusReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun buildDashboard() {
        val background = getColor(R.color.jarvis_background)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(20), dp(20), dp(20), dp(28))
            addView(root)
        }
        setContentView(scroll)

        val eyebrow = label("PERSONAL ASSISTANT · ON-DEVICE FIRST", 11f, getColor(R.color.jarvis_accent)).apply {
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(eyebrow)
        root.addView(label("JARVIS", 38f, getColor(R.color.jarvis_text)).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(label("Your assistant, on your terms.", 16f, getColor(R.color.jarvis_muted)).apply {
            setPadding(0, 0, 0, dp(20))
        })

        statusView = label("Ready when you are.", 15f, getColor(R.color.jarvis_text))
        statusView.setPadding(dp(14), dp(14), dp(14), dp(14))
        root.addView(sectionCard("ASSISTANT STATUS", statusView))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(14))
        }
        assistantButton = actionButton("") { toggleAssistant() }
        gestureButton = actionButton("") { toggleAirTouch() }
        val talkButton = actionButton("Talk now · skip wake word", outlined = true) { request(PendingAction.TALK_NOW) }
        val firstRow = horizontalRow().apply {
            addWeighted(this, assistantButton, 1f)
            addWeighted(this, talkButton, 1f)
        }
        controls.addView(firstRow)
        controls.addView(gestureButton, verticalButtonParams())
        val modelButtons = horizontalRow().apply {
            addWeighted(this, actionButton("Import models") { chooseModels() }, 1f)
            addWeighted(this, actionButton("Battery settings", outlined = true) { requestBatteryExemption() }, 1f)
        }
        controls.addView(modelButtons, verticalButtonParams())
        root.addView(sectionCard("CONTROLS", controls))

        modelView = label("Checking model files…", 14f, getColor(R.color.jarvis_muted)).apply {
            setPadding(dp(14), dp(4), dp(14), dp(14))
        }
        root.addView(sectionCard("ON-DEVICE MODELS", modelView))

        val speechControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
        }
        speedValueView = label("1.00×", 14f, getColor(R.color.jarvis_accent))
        speechControls.addView(label("System voice speed", 14f, getColor(R.color.jarvis_text)))
        speechControls.addView(speedValueView)
        val speedSeekBar = SeekBar(this).apply {
            max = 70
            progress = ((AppSettings.ttsSpeed(this@MainActivity) - MIN_TTS_SPEED) * 100f).toInt().coerceIn(0, max)
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.jarvis_accent))
            thumbTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.jarvis_accent))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val speed = (MIN_TTS_SPEED + progress / 100f).coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
                    AppSettings.setTtsSpeed(this@MainActivity, speed)
                    speedValueView.text = String.format(Locale.getDefault(), "%.2f×", speed)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        speechControls.addView(speedSeekBar)
        root.addView(sectionCard("VOICE", speechControls))

        val setup = label(
            "Always-on listening needs microphone access and downloaded model files. Air Touch additionally needs camera and overlay access. Model files stay in private app storage; see README for downloads.",
            13f,
            getColor(R.color.jarvis_muted),
        ).apply { setPadding(dp(14), dp(4), dp(14), dp(14)) }
        root.addView(sectionCard("PRIVACY & SETUP", setup))
    }

    private fun refreshDashboard() {
        if (!::statusView.isInitialized) return
        statusView.text = AppSettings.status(this)
        val models = ModelStore.snapshot(this)
        modelView.text = buildString {
            appendLine("Wake word: ${if (models.wakeReady) "ready" else "needs 3 ONNX files"}")
            appendLine("Speech recognition: ${if (models.speechReady) "ready" else "needs SenseVoice or Whisper files"}")
            appendLine("Local GGUF: ${if (models.localLlmReady) "ready" else "not installed"}")
            append("Air Touch: ${if (models.gestureReady) "ready" else "needs hand_landmarker.task"}")
            if (models.installedFiles.isNotEmpty()) {
                appendLine("\n\nInstalled files:")
                append(models.installedFiles.take(12).joinToString(separator = "\n") { "• $it" })
            }
        }
        if (::assistantButton.isInitialized) {
            assistantButton.text = if (AppSettings.assistantEnabled(this)) "Stop assistant" else "Start assistant"
            gestureButton.text = if (AppSettings.airTouchEnabled(this)) "Stop Air Touch" else "Enable Air Touch"
        }
        if (::speedValueView.isInitialized) {
            speedValueView.text = String.format(Locale.getDefault(), "%.2f×", AppSettings.ttsSpeed(this))
        }
    }

    private fun toggleAssistant() {
        if (AppSettings.assistantEnabled(this)) {
            startService(
                Intent(this, JarvisForegroundService::class.java).setAction(JarvisForegroundService.ACTION_STOP),
            )
            AppSettings.setAssistantEnabled(this, false)
            setStatus("Jarvis listening is stopped.")
            refreshDashboard()
        } else {
            request(PendingAction.START_ASSISTANT)
        }
    }

    private fun toggleAirTouch() {
        if (AppSettings.airTouchEnabled(this)) {
            startService(Intent(this, AirTouchService::class.java).setAction(AirTouchService.ACTION_STOP))
            AppSettings.setAirTouchEnabled(this, false)
            setStatus("Air Touch is stopped.")
            refreshDashboard()
        } else {
            request(PendingAction.AIR_TOUCH)
        }
    }

    private fun request(action: PendingAction) {
        pendingAction = action
        val required = buildList {
            if (action == PendingAction.START_ASSISTANT || action == PendingAction.TALK_NOW) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (action == PendingAction.AIR_TOUCH) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.distinct().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (required.isNotEmpty()) permissionLauncher.launch(required.toTypedArray())
        else runPendingAction()
    }

    private fun runPendingAction() {
        val action = pendingAction ?: return
        pendingAction = null
        when (action) {
            PendingAction.START_ASSISTANT -> {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, JarvisForegroundService::class.java).setAction(JarvisForegroundService.ACTION_START),
                )
                setStatus("Starting Jarvis background listening…")
            }
            PendingAction.TALK_NOW -> {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, JarvisForegroundService::class.java).setAction(JarvisForegroundService.ACTION_TALK_NOW),
                )
                setStatus("Starting a one-time voice request…")
            }
            PendingAction.AIR_TOUCH -> beginAirTouchSetup()
        }
        refreshDashboard()
    }

    private fun beginAirTouchSetup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            request(PendingAction.AIR_TOUCH)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingAction = PendingAction.AIR_TOUCH
            overlaySettingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, AirTouchService::class.java),
        )
        setStatus("Starting front-camera hand tracking…")
        refreshDashboard()
    }

    private fun chooseModels() {
        importModelsLauncher.launch(arrayOf("*/*"))
    }

    private fun requestBatteryExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "JarvisAI is already exempt from battery optimization.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun setStatus(status: String) {
        AppSettings.setStatus(this, status)
        if (::statusView.isInitialized) statusView.text = status
    }

    private fun sectionCard(title: String, content: View): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(getColor(R.color.jarvis_surface))
            strokeWidth = dp(1)
            strokeColor = 0x223B5364
            useCompatPadding = true
            setContentPadding(dp(4), dp(6), dp(4), dp(4))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(title, 11f, getColor(R.color.jarvis_accent)).apply {
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(14), dp(8), dp(14), dp(4))
        })
        column.addView(content)
        card.addView(column)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(12)
        return card.apply { layoutParams = params }
    }

    private fun actionButton(textValue: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = textValue
            isAllCaps = false
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (outlined) getColor(R.color.jarvis_accent) else getColor(R.color.jarvis_background))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (outlined) getColor(R.color.jarvis_surface) else getColor(R.color.jarvis_accent),
            )
            if (outlined) strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.jarvis_accent))
            strokeWidth = if (outlined) dp(1) else 0
            cornerRadius = dp(12)
            minHeight = dp(48)
            setOnClickListener { onClick() }
        }

    private fun label(textValue: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        text = textValue
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        includeFontPadding = true
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun addWeighted(row: LinearLayout, view: View, weight: Float) {
        val params = LinearLayout.LayoutParams(0, dp(50), weight)
        params.marginEnd = dp(6)
        row.addView(view, params)
    }

    private fun verticalButtonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(50),
    ).apply { topMargin = dp(6) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class PendingAction { START_ASSISTANT, TALK_NOW, AIR_TOUCH }

    private companion object {
        const val MIN_TTS_SPEED = 0.65f
        const val MAX_TTS_SPEED = 1.35f
    }
}
