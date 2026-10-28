package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

/** 对话记录的时间写法、新近度加分与问题中的时间范围解析。 */
class MemoryTimelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    // 2026-10-22 是星期四，本周一为 10-19。
    private val now: ZonedDateTime = LocalDateTime.of(2026, 10, 22, 10, 5).atZone(zone)
    private val nowMs = now.toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun day(month: Int, day: Int, year: Int = 2026) = MemoryTimeRange(at(year, month, day), at(year, month, day) + 86_400_000L)

    private fun since(month: Int, day: Int) = MemoryTimeRange(at(2026, month, day), nowMs + 1)

    @Test
    fun `相对时间一小时内按分钟、当天按小时、之后按日历天`() {
        assertThat(MemoryTimeline.relative(nowMs - 30_000L, nowMs, zone)).isEqualTo("just now")
        assertThat(MemoryTimeline.relative(nowMs - 5 * 60_000L, nowMs, zone)).isEqualTo("5 min ago")
        assertThat(MemoryTimeline.relative(at(2026, 10, 22, 7, 0), nowMs, zone)).isEqualTo("3 h ago")
        // 昨晚 23 点距今不足一天，仍按日历记为昨天。
        assertThat(MemoryTimeline.relative(at(2026, 10, 21, 23, 0), nowMs, zone)).isEqualTo("yesterday")
        assertThat(MemoryTimeline.relative(at(2026, 10, 19, 14, 32), nowMs, zone)).isEqualTo("3 days ago")
        assertThat(MemoryTimeline.relative(at(2026, 10, 1), nowMs, zone)).isEqualTo("3 weeks ago")
        assertThat(MemoryTimeline.relative(at(2026, 5, 25), nowMs, zone)).isEqualTo("5 months ago")
        assertThat(MemoryTimeline.relative(at(2024, 10, 1), nowMs, zone)).isEqualTo("2 years ago")
        // 设备时钟回拨导致记录晚于当前时间时不写负数。
        assertThat(MemoryTimeline.relative(nowMs + 60_000L, nowMs, zone)).isEqualTo("just now")
    }

    @Test
    fun `时间戳与当前时间的写法`() {
        assertThat(MemoryTimeline.stamp(at(2026, 10, 19, 14, 32), nowMs, zone)).isEqualTo("2026-10-19 14:32 · 3 days ago")
        assertThat(MemoryTimeline.currentTime(nowMs, zone)).isEqualTo("2026-10-22 10:05 Thursday (Asia/Shanghai)")
    }

    @Test
    fun `新近度按名次线性加分，最早为零`() {
        assertThat(MemoryTimeline.recencyBonus(mapOf("a" to 5L))).containsExactly("a", 0f)
        val bonus = MemoryTimeline.recencyBonus(mapOf("old" to 1L, "mid" to 1_000L, "new" to 2_000_000L))
        assertThat(bonus.getValue("old")).isEqualTo(0f)
        assertThat(bonus.getValue("mid")).isWithin(1e-6f).of(MemoryTimeline.RECENCY_WEIGHT / 2)
        assertThat(bonus.getValue("new")).isWithin(1e-6f).of(MemoryTimeline.RECENCY_WEIGHT)
        // 同一时间按 id 定序，结果稳定。
        val tie = MemoryTimeline.recencyBonus(mapOf("b" to 7L, "a" to 7L))
        assertThat(tie.getValue("a")).isEqualTo(0f)
        assertThat(tie.getValue("b")).isWithin(1e-6f).of(MemoryTimeline.RECENCY_WEIGHT)
    }

    @Test
    fun `中文相对日期`() {
        assertThat(parseTimeRange("昨天说的", now)).isEqualTo(day(10, 21))
        assertThat(parseTimeRange("前天", now)).isEqualTo(day(10, 20))
        assertThat(parseTimeRange("大前天", now)).isEqualTo(day(10, 19))
        assertThat(parseTimeRange("3天前", now)).isEqualTo(day(10, 19))
        assertThat(parseTimeRange("三天之前", now)).isEqualTo(day(10, 19))
        assertThat(parseTimeRange("最近7天", now)).isEqualTo(since(10, 16))
        assertThat(parseTimeRange("这两天", now)).isEqualTo(since(10, 21))
        assertThat(parseTimeRange("最近", now)).isEqualTo(since(10, 16))
        assertThat(parseTimeRange("今天", now)).isEqualTo(since(10, 22))
    }

    @Test
    fun `中文星期、周与月`() {
        assertThat(parseTimeRange("上周二", now)).isEqualTo(day(10, 13))
        assertThat(parseTimeRange("上个星期天", now)).isEqualTo(day(10, 18))
        assertThat(parseTimeRange("周一", now)).isEqualTo(day(10, 19))
        assertThat(parseTimeRange("星期五", now)).isEqualTo(day(10, 16))
        assertThat(parseTimeRange("本周三", now)).isEqualTo(day(10, 21))
        // 本周尚未到来的日子退回整周。
        assertThat(parseTimeRange("这周五", now)).isEqualTo(since(10, 19))
        assertThat(parseTimeRange("上周", now)).isEqualTo(MemoryTimeRange(at(2026, 10, 12), at(2026, 10, 19)))
        assertThat(parseTimeRange("两周前", now)).isEqualTo(MemoryTimeRange(at(2026, 10, 5), at(2026, 10, 12)))
        assertThat(parseTimeRange("上个月", now)).isEqualTo(MemoryTimeRange(at(2026, 9, 1), at(2026, 10, 1)))
        assertThat(parseTimeRange("三个月前", now)).isEqualTo(MemoryTimeRange(at(2026, 7, 1), at(2026, 8, 1)))
        assertThat(parseTimeRange("本月", now)).isEqualTo(since(10, 1))
        assertThat(parseTimeRange("去年", now)).isEqualTo(MemoryTimeRange(at(2025, 1, 1), at(2026, 1, 1)))
    }

    @Test
    fun `具体日期，未写年份时取最近一次已过去的`() {
        assertThat(parseTimeRange("10月19日", now)).isEqualTo(day(10, 19))
        assertThat(parseTimeRange("12月1号", now)).isEqualTo(day(12, 1, year = 2025))
        assertThat(parseTimeRange("2026年3月5日", now)).isEqualTo(day(3, 5))
        assertThat(parseTimeRange("2026-10-01", now)).isEqualTo(day(10, 1))
        assertThat(parseTimeRange("2026/2/30", now)).isNull()
    }

    @Test
    fun `英文表达`() {
        assertThat(parseTimeRange("What did we discuss yesterday?", now)).isEqualTo(day(10, 21))
        assertThat(parseTimeRange("the day before yesterday", now)).isEqualTo(day(10, 20))
        assertThat(parseTimeRange("2 days ago", now)).isEqualTo(day(10, 20))
        assertThat(parseTimeRange("in the last 3 days", now)).isEqualTo(since(10, 20))
        assertThat(parseTimeRange("last Tuesday", now)).isEqualTo(day(10, 20))
        assertThat(parseTimeRange("last Thursday", now)).isEqualTo(day(10, 15))
        assertThat(parseTimeRange("on Monday", now)).isEqualTo(day(10, 19))
        assertThat(parseTimeRange("last week", now)).isEqualTo(MemoryTimeRange(at(2026, 10, 12), at(2026, 10, 19)))
        assertThat(parseTimeRange("this month", now)).isEqualTo(since(10, 1))
    }

    @Test
    fun `只有回顾过去对话的问题才按时间检索`() {
        assertThat(parseRecallTimeRange("今天天气怎么样", now)).isNull()
        assertThat(parseRecallTimeRange("帮我写一份今天的日报", now)).isNull()
        assertThat(parseRecallTimeRange("今天我们聊了什么", now)).isEqualTo(since(10, 22))
        assertThat(parseRecallTimeRange("上周二讨论的方案是什么", now)).isEqualTo(day(10, 13))
        assertThat(parseRecallTimeRange("What did we talk about last week?", now))
            .isEqualTo(MemoryTimeRange(at(2026, 10, 12), at(2026, 10, 19)))
        assertThat(parseRecallTimeRange("我们之前聊过 Kotlin 吗", now)).isNull()
        assertThat(LocalDate.of(2026, 10, 22).dayOfWeek.name).isEqualTo("THURSDAY")
    }
}
