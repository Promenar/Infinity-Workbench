package com.promenar.infinity.data.linux

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneOffsetTransitionRule.TimeDefinition
import java.time.zone.ZoneRules
import java.util.Locale

class LinuxLaunchTimezoneTest {
    private val winter = Instant.parse("2026-01-15T12:00:00Z")
    private val summer = Instant.parse("2026-07-15T12:00:00Z")
    private fun tz(id: String, now: Instant = winter) = LinuxLaunchTimezone.posix(ZoneId.of(id).rules, now)

    @ParameterizedTest
    @CsvSource(
        "Asia/Shanghai, <+08>-8", "America/Phoenix, <-07>7", "Asia/Kolkata, <+0530>-5:30",
        "America/St_Johns, 'STD3:30DST2:30,M3.2.0/2,M11.1.0/2'",
        "Asia/Kathmandu, <+0545>-5:45", "UTC, UTC0", "Etc/GMT+12, <-12>12", "-03:30, <-0330>3:30",
    )
    fun `固定偏移符号和分钟精度`(id: String, expected: String) = assertEquals(expected, tz(id))

    @Test fun `北半球规则在冬夏保持相同且UTC时刻转换为切换前墙钟`() {
        assertEquals("STD5DST4,M3.2.0/2,M11.1.0/2", tz("America/New_York"))
        assertEquals(tz("America/New_York"), tz("America/New_York", summer))
        assertEquals("STD-1DST-2,M3.5.0/2,M10.5.0/3", tz("Europe/Berlin"))
    }

    @Test fun `南半球标准时间定义与半小时夏令时`() {
        assertEquals("STD-10DST-11,M10.1.0/2,M4.1.0/3", tz("Australia/Sydney"))
        assertEquals(tz("Australia/Sydney"), tz("Australia/Sydney", summer))
        assertEquals("STD-10:30DST-11,M10.1.0/2,M4.1.0/2", tz("Australia/Lord_Howe"))
        assertEquals("STD-12:45DST-13:45,M9.5.0/2:45,M4.1.0/3:45", tz("Pacific/Chatham"))
    }

    @Test fun `缺少年循环规则时使用当前偏移`() {
        assertEquals("<+01>-1", tz("Africa/Casablanca", summer))
        assertEquals("<-05>5", tz("America/New_York", Instant.parse("1900-07-01T00:00:00Z")))
    }

    private fun rules(day: Int, weekday: DayOfWeek? = DayOfWeek.SUNDAY,
                      definition: TimeDefinition = TimeDefinition.WALL, endOfDay: Boolean = false): ZoneRules {
        val standard = ZoneOffset.ofHours(2)
        val daylight = ZoneOffset.ofHours(3)
        val start = ZoneOffsetTransitionRule.of(Month.MARCH, day, weekday,
            if (endOfDay) LocalTime.MIDNIGHT else LocalTime.of(2, 15, 30), endOfDay, definition, standard, standard, daylight)
        val end = ZoneOffsetTransitionRule.of(Month.OCTOBER, -1, DayOfWeek.SUNDAY,
            LocalTime.of(3, 0), false, TimeDefinition.WALL, standard, daylight, standard)
        return ZoneRules.of(standard, standard, emptyList(),
            listOf(start.createTransition(2020), end.createTransition(2020)), listOf(start, end))
    }

    @Test fun `不可表达的星期窗口与固定日期退回当前偏移`() {
        assertEquals("<+02>-2", LinuxLaunchTimezone.posix(rules(16), winter))
        assertEquals("<+03>-3", LinuxLaunchTimezone.posix(rules(16), summer))
        assertEquals("<+03>-3", LinuxLaunchTimezone.posix(rules(8, weekday = null), summer))
    }

    @Test fun `最后星期与秒精度以及日末午夜`() {
        assertEquals("STD-2DST-3,M3.5.0/2:15:30,M10.5.0/3", LinuxLaunchTimezone.posix(rules(-1), winter))
        assertEquals("STD-2DST-3,M3.2.0/4:15:30,M10.5.0/3", LinuxLaunchTimezone.posix(rules(8, definition = TimeDefinition.UTC), winter))
        assertEquals("STD-2DST-3,M3.2.0/24,M10.5.0/3", LinuxLaunchTimezone.posix(rules(8, endOfDay = true), winter))
    }

    @Test fun `输出不受设备数字区域设置影响`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("<+0530>-5:30", tz("Asia/Kolkata"))
        } finally { Locale.setDefault(original) }
    }
}
