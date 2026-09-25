package com.jarvis.ai

import android.content.Context

/** Stores small user-controlled assistant preferences in app-private storage. */
object AppSettings {
    private const val PREFS_NAME = "jarvis_settings"
    private const val KEY_ASSISTANT_ENABLED = "assistant_enabled"
    private const val KEY_AIR_TOUCH_ENABLED = "air_touch_enabled"
    private const val KEY_STATUS = "assistant_status"
    private const val KEY_TTS_SPEED = "tts_speed"

    fun assistantEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ASSISTANT_ENABLED, false)

    fun setAssistantEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ASSISTANT_ENABLED, enabled).apply()
    }

    fun airTouchEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AIR_TOUCH_ENABLED, false)

    fun setAirTouchEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AIR_TOUCH_ENABLED, enabled).apply()
    }

    fun status(context: Context): String =
        preferences(context).getString(KEY_STATUS, "Ready when you are.").orEmpty()

    fun setStatus(context: Context, status: String) {
        preferences(context).edit().putString(KEY_STATUS, status.take(240)).apply()
    }

    fun ttsSpeed(context: Context): Float =
        preferences(context).getFloat(KEY_TTS_SPEED, 1.0f).coerceIn(0.65f, 1.35f)

    fun setTtsSpeed(context: Context, speed: Float) {
        preferences(context).edit().putFloat(KEY_TTS_SPEED, speed.coerceIn(0.65f, 1.35f)).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
