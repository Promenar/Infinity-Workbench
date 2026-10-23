package com.promenar.nexara.domain.schedule

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CronExpressionTest {
    @Test
    fun `下一次执行严格晚于输入且对齐分钟`() {
        val cron = CronExpression.parse("* * * * *")

        assertEquals(millis("2025-01-01T00:01:00Z"), cron.nextAfter(millis("2025-01-01T00:00:00Z"), "UTC"))
        assertEquals(millis("2025-01-01T00:01:00Z"), cron.nextAfter(millis("2025-01-01T00:00:59.999Z"), "UTC"))
    }

    @Test
    fun `允许字段两侧与字段之间的空白`() {
        val cron = CronExpression.parse("  30\t9  *  * *  ")

        assertEquals(millis("2025-01-01T01:30:00Z"), cron.nextAfter(millis("2025-01-01T00:00:00Z"), "Asia/Shanghai"))
    }

    @Test
    fun `数字列表范围与步长组合按实际时刻排序并去重`() {
        val cron = CronExpression.parse("5,10-20/5,10 8-9 1,15 1-3 *")

        assertEquals(
            listOf("2025-01-01T08:05:00Z", "2025-01-01T08:10:00Z", "2025-01-01T08:15:00Z", "2025-01-01T08:20:00Z", "2025-01-01T09:05:00Z").map(::millis),
            cron.nextTimes(millis("2025-01-01T08:00:00Z"), "UTC", 5),
        )
    }

    @Test
    fun `星号步长与数字起点步长分别从字段下界和指定数字起算`() {
        assertEquals(
            millis("2025-01-01T02:00:00Z"),
            CronExpression.parse("*/15 */2 * * *").nextAfter(millis("2025-01-01T00:50:00Z"), "UTC"),
        )
        assertEquals(
            millis("2025-01-01T00:20:00Z"),
            CronExpression.parse("5/15 * * * *").nextAfter(millis("2025-01-01T00:05:00Z"), "UTC"),
        )
    }

    @Test
    fun `周日的零与七以及包含七的范围都可匹配`() {
        val after = millis("2025-01-04T12:00:00Z")
        val expected = millis("2025-01-05T00:00:00Z")

        listOf("0", "7", "5-7", "0,7").forEach { dayOfWeek ->
            assertEquals(expected, CronExpression.parse("0 0 * * $dayOfWeek").nextAfter(after, "UTC"), dayOfWeek)
        }
    }

    @Test
    fun `日字段单独受限时不会被任意星期覆盖`() {
        val cron = CronExpression.parse("0 0 15 * *")

        assertEquals(millis("2025-01-15T00:00:00Z"), cron.nextAfter(millis("2025-01-01T00:00:00Z"), "UTC"))
    }

    @Test
    fun `星期字段单独受限时只在指定星期执行`() {
        val cron = CronExpression.parse("0 0 * * 1")

        assertEquals(millis("2025-01-06T00:00:00Z"), cron.nextAfter(millis("2025-01-01T00:00:00Z"), "UTC"))
    }

    @Test
    fun `日与星期同时受限时任意一个匹配即可但月份始终受限`() {
        val cron = CronExpression.parse("0 0 15 2 1")

        assertEquals(
            listOf("2025-02-03T00:00:00Z", "2025-02-10T00:00:00Z", "2025-02-15T00:00:00Z", "2025-02-17T00:00:00Z").map(::millis),
            cron.nextTimes(millis("2025-01-01T00:00:00Z"), "UTC", 4),
        )
    }

    @Test
    fun `日字段的星号步长构成日期限制并与星期取或`() {
        val cron = CronExpression.parse("0 0 */2 * 1")

        assertEquals(millis("2025-01-06T00:00:00Z"), cron.nextAfter(millis("2025-01-05T00:00:00Z"), "UTC"))
    }

    @Test
    fun `月末跳过不存在的日期并跨年`() {
        val cron = CronExpression.parse("0 9 31 * *")

        assertEquals(millis("2025-03-31T09:00:00Z"), cron.nextAfter(millis("2025-01-31T09:00:00Z"), "UTC"))
        assertEquals(millis("2026-01-31T09:00:00Z"), cron.nextAfter(millis("2025-12-31T09:00:00Z"), "UTC"))
    }

    @Test
    fun `闰日可跨越普通年份与不闰的整百年`() {
        val cron = CronExpression.parse("0 0 29 2 *")

        assertEquals(millis("2028-02-29T00:00:00Z"), cron.nextAfter(millis("2025-01-01T00:00:00Z"), "UTC"))
        assertEquals(millis("2104-02-29T00:00:00Z"), cron.nextAfter(millis("2096-02-29T00:00:00Z"), "UTC"))
    }

    @Test
    fun `搜索窗内不存在的日期返回空值`() {
        val cron = CronExpression.parse("0 0 31 2 *")

        assertNull(cron.nextAfter(millis("2025-01-01T00:00:00Z"), "UTC"))
        assertEquals(emptyList<Long>(), cron.nextTimes(millis("2025-01-01T00:00:00Z"), "UTC", 3))
    }

    @Test
    fun `夏令时前跳造成的缺失本地时间被跳过`() {
        val cron = CronExpression.parse("30 2 * * *")

        assertEquals(millis("2025-03-10T06:30:00Z"), cron.nextAfter(millis("2025-03-09T05:00:00Z"), "America/New_York"))
    }

    @Test
    fun `夏令时回拨的同一本地时间执行两次`() {
        val cron = CronExpression.parse("30 1 * * *")

        assertEquals(
            listOf("2025-11-02T05:30:00Z", "2025-11-02T06:30:00Z", "2025-11-03T06:30:00Z").map(::millis),
            cron.nextTimes(millis("2025-11-02T04:00:00Z"), "America/New_York", 3),
        )
    }

    @Test
    fun `回拨时可以返回比当前本地分钟更早的候选`() {
        val cron = CronExpression.parse("*/15 1 * * *")

        assertEquals(millis("2025-11-02T06:00:00Z"), cron.nextAfter(millis("2025-11-02T05:50:00Z"), "America/New_York"))
        assertEquals(millis("2025-11-02T05:15:00Z"), cron.nextAfter(millis("2025-11-02T05:10:00Z"), "America/New_York"))
    }

    @Test
    fun `非整小时回拨也保留第二个偏移`() {
        val cron = CronExpression.parse("45 1 * * *")

        assertEquals(millis("2025-04-05T15:15:00Z"), cron.nextAfter(millis("2025-04-05T14:45:00Z"), "Australia/Lord_Howe"))
    }

    @Test
    fun `跨午夜的历史回拨能够返回前一本地日期的第二次执行`() {
        val cron = CronExpression.parse("0 23 29 10 *")

        assertEquals(millis("1988-10-30T02:30:00Z"), cron.nextAfter(millis("1988-10-30T01:30:30Z"), "America/St_Johns"))
    }

    @Test
    fun `毫秒时间戳边界不会溢出`() {
        val cron = CronExpression.parse("* * * * *")

        assertNull(cron.nextAfter(Long.MAX_VALUE, "UTC"))
        assertEquals((Math.floorDiv(Long.MIN_VALUE, 60_000L) + 1L) * 60_000L, cron.nextAfter(Long.MIN_VALUE, "UTC"))
    }

    @Test
    fun `无效字段数量范围列表步长与扩展语法均明确拒绝`() {
        listOf(
            "", "* * * *", "* * * * * *", "60 * * * *", "-1 * * * *", "* 24 * * *",
            "* * 0 * *", "* * 32 * *", "* * * 0 *", "* * * 13 *", "* * * * 8",
            "1,,2 * * * *", "1, * * * *", ",1 * * * *", "3-1 * * * *", "0-60 * * * *",
            "*/0 * * * *", "*/-1 * * * *", "*/ * * * *", "1/2/3 * * * *", "1-2-3 * * * *",
            "2147483648 * * * *", "*/2147483648 * * * *", "0.5 * * * *", "? * * * *",
            "0 0 L * *", "0 0 * * MON", "@daily",
        ).forEach { expression ->
            assertThrows(IllegalArgumentException::class.java, { CronExpression.parse(expression) }, expression)
        }
    }

    @Test
    fun `预览允许零个结果但拒绝负数并拒绝未知时区`() {
        val cron = CronExpression.parse("* * * * *")

        assertEquals(emptyList<Long>(), cron.nextTimes(0L, "UTC", 0))
        assertThrows(IllegalArgumentException::class.java) { cron.nextTimes(0L, "UTC", -1) }
        assertThrows(IllegalArgumentException::class.java) { cron.nextAfter(0L, "Unknown/Zone") }
    }

    private fun millis(value: String): Long = Instant.parse(value).toEpochMilli()
}
