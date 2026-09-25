package com.jarvis.ai.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import androidx.core.content.ContextCompat

/** Implements safe, explicit system actions; it never executes arbitrary shell or model-generated code. */
class SystemTools(private val context: Context) {
    private val packageManager = context.packageManager

    fun execute(command: String): ToolResult? {
        val clean = command.trim()
        if (clean.isBlank()) return null
        return runCatching {
            when {
                BATTERY_PATTERN.containsMatchIn(clean) -> batteryStatus()
                TIMER_PATTERN.containsMatchIn(clean) -> setTimer(clean)
                ALARM_PATTERN.containsMatchIn(clean) -> setAlarm(clean)
                FLASH_ACTION_PATTERN.containsMatchIn(clean) -> setFlashlight(clean)
                VOLUME_PATTERN.containsMatchIn(clean) -> adjustVolume(clean)
                SEARCH_PATTERN.containsMatchIn(clean) -> searchWeb(clean)
                OPEN_PATTERN.containsMatchIn(clean) -> openApp(clean)
                else -> null
            }
        }.getOrElse { error ->
            ToolResult(
                spokenMessage = "I couldn't complete that action. ${error.message.orEmpty().take(100)}",
                actionName = "error",
            )
        }
    }

    private fun batteryStatus(): ToolResult {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val batteryState = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryState == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryState == BatteryManager.BATTERY_STATUS_FULL
        val message = when {
            percent == null -> "I can't read the battery level right now."
            charging -> "Battery is at $percent percent and charging."
            else -> "Battery is at $percent percent."
        }
        return ToolResult(message, "battery_status")
    }

    private fun setTimer(command: String): ToolResult {
        val seconds = SystemCommandParser.timerDurationSeconds(command) ?: return ToolResult(
            "Tell me a timer duration, for example, set a timer for 5 minutes.",
            "timer_help",
        )
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResult("Starting a timer for ${formatDuration(seconds)}.", "set_timer")
    }

    private fun setAlarm(command: String): ToolResult {
        val time = SystemCommandParser.alarmTime(command) ?: return ToolResult(
            "Tell me a time, for example, set an alarm for 7:30 AM.",
            "alarm_help",
        )
        val hour = time.hour24
        val minute = time.minute
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val suffix = if (hour < 12) "AM" else "PM"
        val displayHour = when (val value = hour % 12) { 0 -> 12; else -> value }
        return ToolResult("Opening the clock app to set an alarm for $displayHour:${minute.toString().padStart(2, '0')} $suffix.", "set_alarm")
    }

    private fun adjustVolume(command: String): ToolResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val percentMatch = PERCENT_PATTERN.find(command)
        val message = when {
            percentMatch != null -> {
                val percent = percentMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 50
                val target = (maximum * percent / 100f).toInt().coerceIn(0, maximum)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                "Media volume set to $percent percent."
            }
            UNMUTE_PATTERN.containsMatchIn(command) -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                "Media volume unmuted."
            }
            MUTE_PATTERN.containsMatchIn(command) -> {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                "Media volume muted."
            }
            VOLUME_UP_PATTERN.containsMatchIn(command) -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                "Media volume increased."
            }
            VOLUME_DOWN_PATTERN.containsMatchIn(command) -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                "Media volume decreased."
            }
            else -> "Media volume is ${if (maximum == 0) 0 else current * 100 / maximum} percent."
        }
        return ToolResult(message, "adjust_volume")
    }

    private fun setFlashlight(command: String): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult("Allow camera access in JarvisAI before using the flashlight tool.", "flashlight_permission")
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return ToolResult("This device does not expose a rear-camera flashlight.", "flashlight_unavailable")

        val preferences = context.getSharedPreferences(FLASH_PREFS, Context.MODE_PRIVATE)
        val currentlyOn = preferences.getBoolean(FLASH_STATE, false)
        val shouldTurnOn = when {
            command.contains("off", true) || command.contains("disable", true) -> false
            command.contains("on", true) || command.contains("enable", true) -> true
            else -> !currentlyOn
        }
        cameraManager.setTorchMode(cameraId, shouldTurnOn)
        preferences.edit().putBoolean(FLASH_STATE, shouldTurnOn).apply()
        return ToolResult(if (shouldTurnOn) "Flashlight turned on." else "Flashlight turned off.", "flashlight")
    }

    private fun searchWeb(command: String): ToolResult {
        val query = SEARCH_PATTERN.find(command)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (query.isBlank()) return ToolResult("What would you like me to search for?", "search_help")
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("Searching the web for $query.", "web_search")
    }

    private fun openApp(command: String): ToolResult {
        val requested = OPEN_PATTERN.find(command)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (requested.isBlank()) return ToolResult("Which app would you like me to open?", "open_app_help")
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .firstOrNull { info ->
                info.activityInfo.packageName != context.packageName &&
                    info.loadLabel(packageManager).toString().contains(requested, ignoreCase = true)
            }
        val launchIntent = match?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
            ?: return ToolResult("I couldn't find an installed app called $requested.", "app_not_found")
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("Opening ${match.loadLabel(packageManager)}.", "open_app")
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds % 3_600 == 0 -> "${seconds / 3_600} ${if (seconds == 3_600) "hour" else "hours"}"
        seconds % 60 == 0 -> "${seconds / 60} ${if (seconds == 60) "minute" else "minutes"}"
        else -> "$seconds ${if (seconds == 1) "second" else "seconds"}"
    }

    private companion object {
        const val FLASH_PREFS = "jarvis_flashlight"
        const val FLASH_STATE = "enabled"
        val TIMER_PATTERN = Regex("\\btimer\\b", RegexOption.IGNORE_CASE)
        val ALARM_PATTERN = Regex("\\balarm\\b", RegexOption.IGNORE_CASE)
        val BATTERY_PATTERN = Regex("\\b(battery|charge level|charging status)\\b", RegexOption.IGNORE_CASE)
        val FLASH_ACTION_PATTERN = Regex(
            "\\b(?:turn|switch|toggle|enable|disable)\\b.*\\b(?:flashlight|torch)\\b|\\b(?:flashlight|torch)\\s+(?:on|off)\\b",
            RegexOption.IGNORE_CASE,
        )
        val VOLUME_PATTERN = Regex("\\b(volume|louder|quieter|mute|muted|unmute|increase|decrease)\\b", RegexOption.IGNORE_CASE)
        val MUTE_PATTERN = Regex("\\bmute\\b", RegexOption.IGNORE_CASE)
        val UNMUTE_PATTERN = Regex("\\bunmute\\b", RegexOption.IGNORE_CASE)
        val VOLUME_UP_PATTERN = Regex("\\b(up|louder|increase|raise)\\b", RegexOption.IGNORE_CASE)
        val VOLUME_DOWN_PATTERN = Regex("\\b(down|quieter|decrease|lower)\\b", RegexOption.IGNORE_CASE)
        val PERCENT_PATTERN = Regex("\\b(\\d{1,3})\\s*(?:%|percent\\b)", RegexOption.IGNORE_CASE)
        val SEARCH_PATTERN = Regex("\\b(?:search(?: the web)? for|look up)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val OPEN_PATTERN = Regex("^\\s*(?:please\\s+)?open\\s+(.+)$", RegexOption.IGNORE_CASE)
    }
}
