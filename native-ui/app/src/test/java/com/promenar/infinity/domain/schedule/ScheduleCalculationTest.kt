package com.promenar.infinity.domain.schedule

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScheduleCalculationTest {
    private val now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
    private fun args(interval: Int, exact: Boolean = false) = ScheduleCreateArgs("检查", "检查状态", ScheduleTrigger.Interval(interval), exact)

    @Test fun `前台间隔下限为一分钟且不受精确能力影响`() {
        for (exact in listOf(false, true)) {
            assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.validate(args(0, exact), now, exact) }
            ScheduleCalculation.validate(args(1, exact), now, exact)
            assertEquals(now + 60_000, ScheduleCalculation.nextAfter(ScheduleTrigger.Interval(1), now, exact))
        }
    }
    @Test fun `渠道或权限不足仍按请求的前台节奏预览`() {
        val raw = Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"interval","every_minutes":1},"exact":true}""").jsonObject
        for ((channel, permission) in listOf(false to true, true to false, true to true)) {
            assertEquals(listOf(1L, 2L, 3L).map { now + it * 60_000 }, previewScheduleTriggers(raw, now, "UTC", channel, permission))
        }
    }
    @Test fun `单次时间必须未来且不能落入夏令时缺口`() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.validate(args(15).copy(trigger = ScheduleTrigger.Once("2026-10-22T09:00", "UTC")), now, false) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.resolveLocal("2026-03-08T02:30", "America/New_York") }
    }
    @Test fun `单次时间带Z或偏移时换算为设备本地时间`() {
        fun once(at: String) = ScheduleCalculation.parse(
            Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"once","at":"$at"}}""").jsonObject,
            "Asia/Shanghai", now,
        ).trigger
        assertEquals(ScheduleTrigger.Once("2026-10-23T19:36", "Asia/Shanghai"), once("2026-10-23T19:36"))
        assertEquals(ScheduleTrigger.Once("2026-10-23T19:36:30", "Asia/Shanghai"), once("2026-10-23T19:36:30"))
        assertEquals(ScheduleTrigger.Once("2026-10-23T19:36", "Asia/Shanghai"), once("2026-10-23T11:36:00Z"))
        assertEquals(ScheduleTrigger.Once("2026-10-23T19:36", "Asia/Shanghai"), once("2026-10-23T20:36:00+09:00"))
        assertEquals(ScheduleTrigger.Once("明天", "Asia/Shanghai"), once("明天"))
    }
    @Test fun `cron每分钟表达式按前台节奏预览`() {
        val raw = Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"cron","expr":"* * * * *"},"exact":true}""").jsonObject
        assertEquals(listOf(1L, 2L, 3L).map { now + it * 60_000 }, previewScheduleTriggers(raw, now, "UTC", false, false))
    }
    @Test fun `倒计时预览使用设备时间并保留到秒`() {
        val raw = Json.parseToJsonElement("""{"name":"检查","prompt":"检查状态","trigger":{"type":"once","in_minutes":2}}""").jsonObject
        assertEquals(listOf(now + 150_000), previewScheduleTriggers(raw, now + 30_987, "Asia/Shanghai", false, false))
    }
    @Test fun `过去的单次时间错误包含当前设备本地时间及倒计时建议`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ScheduleCalculation.validate(args(15).copy(trigger = ScheduleTrigger.Once("2026-10-22T09:00", "Asia/Shanghai")), now, false)
        }
        assertTrue(error.message!!.contains("2026-10-23T08:00"))
        assertTrue(error.message!!.contains("in_minutes"))
    }
    @Test fun `后台重复任务取前台计划与上次触发或创建时刻下限的较晚者`() {
        for (trigger in listOf(ScheduleTrigger.Interval(1), ScheduleTrigger.Cron("* * * * *", "UTC"))) {
            assertEquals(now + 5 * 60_000, ScheduleCalculation.backgroundFireAt(trigger, now + 60_000, null, now, true))
            assertEquals(now + 15 * 60_000, ScheduleCalculation.backgroundFireAt(trigger, now + 60_000, null, now, false))
            assertEquals(now + 25 * 60_000, ScheduleCalculation.backgroundFireAt(trigger, now + 21 * 60_000, now + 20 * 60_000, now, true))
            assertEquals(now + 35 * 60_000, ScheduleCalculation.backgroundFireAt(trigger, now + 21 * 60_000, now + 20 * 60_000, now, false))
            assertEquals(now + 60 * 60_000, ScheduleCalculation.backgroundFireAt(trigger, now + 60 * 60_000, now, now, false))
        }
    }
    @Test fun `单次后台时刻不受渠道下限约束`() {
        for (exact in listOf(false, true)) {
            assertEquals(now + 60_000, ScheduleCalculation.backgroundFireAt(
                ScheduleTrigger.Once("2026-10-23T00:01", "UTC"), now + 60_000, now, now, exact))
        }
    }
    @Test fun `倒计时边界与绝对时间互斥并拒绝错误类型`() {
        fun parse(fields: String) = ScheduleCalculation.parse(Json.parseToJsonElement(
            """{"name":"检查","prompt":"检查状态","trigger":{"type":"once"$fields}}""").jsonObject, "Asia/Shanghai", now + 30_987)
        assertEquals(ScheduleTrigger.Once("2026-10-23T08:01:30", "Asia/Shanghai"), parse(",\"in_minutes\":1").trigger)
        assertEquals(ScheduleTrigger.Once("2026-10-30T08:00:30", "Asia/Shanghai"), parse(",\"in_minutes\":10080").trigger)
        for (fields in listOf("", ",\"in_minutes\":1,\"at\":\"2026-10-23T09:00\"")) {
            val error = assertThrows(IllegalArgumentException::class.java) { parse(fields) }
            assertTrue(error.message!!.contains("at"))
            assertTrue(error.message!!.contains("in_minutes"))
        }
        for (value in listOf("0", "-1", "10081", "2147483648", "1.5", "true", "null", "\"1\"")) {
            assertThrows(IllegalArgumentException::class.java) { parse(",\"in_minutes\":$value") }
        }
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
        assertThrows(IllegalArgumentException::class.java) { ScheduleCalculation.parse(wrong, "UTC", now) }
    }
    @Test fun `达到上限或到期均耗尽`() {
        assertTrue(ScheduleCalculation.exhausted(2, 2, null, now))
        assertTrue(ScheduleCalculation.exhausted(0, null, now, now))
        assertFalse(ScheduleCalculation.exhausted(1, 2, now + 1, now))
    }
}
