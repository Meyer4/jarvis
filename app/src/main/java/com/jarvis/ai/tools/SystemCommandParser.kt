package com.jarvis.ai.tools

import java.util.Locale

/** Parses only the explicit duration and clock forms supported by native alarm tools. */
internal object SystemCommandParser {
    /** A validated wall-clock time in 24-hour format. */
    data class AlarmTime(val hour24: Int, val minute: Int)

    private val timerPattern = Regex(
        "\\btimer\\s+(?:for\\s+|in\\s+)?(\\d+)\\s*(seconds?|minutes?|hours?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val alarmPattern = Regex(
        "\\b(?:set\\s+)?alarm\\s+(?:(?:for|at)\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b",
        RegexOption.IGNORE_CASE,
    )

    fun timerDurationSeconds(command: String): Int? {
        val match = timerPattern.find(command) ?: return null
        val amount = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val seconds = when {
            unit.startsWith("hour") -> amount * 3_600L
            unit.startsWith("minute") -> amount * 60L
            else -> amount
        }
        return seconds.takeIf { it in 1..86_400 }?.toInt()
    }

    fun alarmTime(command: String): AlarmTime? {
        val match = alarmPattern.find(command) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3].lowercase(Locale.ROOT)
        if (minute !in 0..59) return null
        if (meridiem.isNotEmpty() && hour !in 1..12) return null
        if (meridiem.isEmpty() && hour !in 0..23) return null
        if (meridiem == "pm" && hour in 1..11) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        return AlarmTime(hour, minute)
    }
}
