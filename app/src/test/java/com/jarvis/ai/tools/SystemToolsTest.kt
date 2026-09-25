package com.jarvis.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemToolsTest {
    @Test
    fun parsesTimerDurationsAndRejectsOutOfRangeValues() {
        assertEquals(300, SystemCommandParser.timerDurationSeconds("set a timer for 5 minutes"))
        assertEquals(7_200, SystemCommandParser.timerDurationSeconds("timer in 2 hours"))
        assertNull(SystemCommandParser.timerDurationSeconds("timer for 0 seconds"))
        assertNull(SystemCommandParser.timerDurationSeconds("tell me a story"))
    }

    @Test
    fun parsesTwelveHourAndTwentyFourHourAlarmTimes() {
        assertEquals(SystemCommandParser.AlarmTime(19, 30), SystemCommandParser.alarmTime("set an alarm for 7:30 PM"))
        assertEquals(SystemCommandParser.AlarmTime(0, 5), SystemCommandParser.alarmTime("set alarm at 12:05 AM"))
        assertEquals(SystemCommandParser.AlarmTime(23, 0), SystemCommandParser.alarmTime("alarm at 23:00"))
        assertNull(SystemCommandParser.alarmTime("set an alarm for 14 PM"))
    }
}
