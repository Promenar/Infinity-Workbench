package com.promenar.nexara.domain.schedule

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScheduleCalculationTest {
    private val now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
    private fun args(interval: Int, exact: Boolean = false) = ScheduleCreateArgs("检查", "检查状态", ScheduleTrigger.Interval(interval), exact)

    @Test fun `非精确与精确具有各自下限`() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.validate(args(14), now, false) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.validate(args(4, true), now, true) }
        ScheduleCalculation.validate(args(15), now, false)
        ScheduleCalculation.validate(args(5, true), now, true)
    }
    @Test fun `Play或权限撤销将五分钟请求实际按十五分钟排期`() {
        val raw = Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"interval","every_minutes":5},"exact":true}""").jsonObject
        for ((channel, permission) in listOf(false to true, true to false)) {
            assertEquals(listOf(15L, 30L, 45L).map { now + it * 60_000 }, previewScheduleTriggers(raw, now, "UTC", channel, permission))
        }
        assertEquals(now + 5 * 60_000, previewScheduleTriggers(raw, now, "UTC", true, true).first())
    }
    @Test fun `单次时间必须未来且不能落入夏令时缺口`() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.validate(args(15).copy(trigger = ScheduleTrigger.Once("2026-10-22T09:00", "UTC")), now, false) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.resolveLocal("2026-03-08T02:30", "America/New_York") }
    }
    @Test fun `单次时间带Z或偏移时换算为设备本地时间`() {
        fun once(at: String) = ScheduleCalculation.parse(
            Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"once","at":"$at"}}""").jsonObject,
            "Asia/Shanghai",
        ).trigger
        assertEquals(ScheduleTrigger.Once("2026-10-23T19:36", "Asia/Shanghai"), once("2026-10-23T19:36"))
        assertEquals(ScheduleTrigger.Once("2026-10-23T19:36:30", "Asia/Shanghai"), once("2026-10-23T19:36:30"))
        assertEquals(ScheduleTrigger.Once("2026-10-23T19:36", "Asia/Shanghai"), once("2026-10-23T11:36:00Z"))
        assertEquals(ScheduleTrigger.Once("2026-10-23T19:36", "Asia/Shanghai"), once("2026-10-23T20:36:00+09:00"))
        assertEquals(ScheduleTrigger.Once("明天", "Asia/Shanghai"), once("明天"))
    }
    @Test fun `cron高频表达式拒绝且降级实际间隔至少十五分钟`() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.validate(args(15).copy(trigger = ScheduleTrigger.Cron("*/5 * * * *", "UTC")), now, false) }
        val raw = Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"cron","expr":"*/5 * * * *"},"exact":true}""").jsonObject
        val times = previewScheduleTriggers(raw, now, "UTC", false, false)
        assertTrue(times.zipWithNext().all { (a,b) -> b - a >= 15 * 60_000 })
    }
    @Test fun `预览尊重次数上限与到期时间`() {
        val raw = Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"interval","every_minutes":15},"max_runs":2,"expires_at":"2026-10-23T00:40:00Z"}""").jsonObject
        assertEquals(listOf(now + 15 * 60_000, now + 30 * 60_000), previewScheduleTriggers(raw, now, "UTC", false, false))
    }
    @Test fun `文字长度空白整数布尔类型和到期时间校验`() {
        listOf(args(15).copy(name = " "), args(15).copy(name = "名".repeat(61)), args(15).copy(prompt = "文".repeat(4001)), args(15).copy(maxRuns = 0), args(15).copy(expiresAtMillis = now)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.validate(it, now, false) }
        }
        val wrong = Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"interval","every_minutes":"15"}}""").jsonObject
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.parse(wrong, "UTC") }
    }
    @Test fun `达到上限或到期均耗尽`() {
        assertTrue(ScheduleCalculation.exhausted(2, 2, null, now))
        assertTrue(ScheduleCalculation.exhausted(0, null, now, now))
        assertFalse(ScheduleCalculation.exhausted(1, 2, now + 1, now))
    }
}
