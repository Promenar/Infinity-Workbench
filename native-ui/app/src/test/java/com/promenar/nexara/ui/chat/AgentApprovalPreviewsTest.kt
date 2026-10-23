package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.jobs.BackgroundJobExitAction
import com.promenar.nexara.domain.schedule.ScheduleTrigger
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class AgentApprovalPreviewsTest {
    private val zone = "Asia/Shanghai"
    private val now = LocalDateTime.of(2026, 10, 23, 10, 0).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `间隔任务预览给出接下来三次触发`() {
        val preview = scheduleApprovalPreview(
            json("""{"name":"检查 CI","prompt":"看构建","trigger":{"type":"interval","every_minutes":30}}"""),
            now, zone, exactAlarmAvailable = true, canScheduleExact = true,
        )!!
        assertThat(preview.trigger).isEqualTo(ScheduleTrigger.Interval(30))
        assertThat(preview.upcoming).containsExactly(now + 30 * 60_000L, now + 60 * 60_000L, now + 90 * 60_000L).inOrder()
        assertThat(preview.invalid).isFalse()
        assertThat(preview.exactEffective).isFalse()
    }

    @Test
    fun `请求精确但未授权时保留前台节奏预览并按次数上限截断`() {
        val preview = scheduleApprovalPreview(
            json("""{"name":"轮询","prompt":"看部署","trigger":{"type":"interval","every_minutes":5},"exact":true,"max_runs":2}"""),
            now, zone, exactAlarmAvailable = true, canScheduleExact = false,
        )!!
        assertThat(preview.exactRequested).isTrue()
        assertThat(preview.exactEffective).isFalse()
        assertThat(preview.upcoming).containsExactly(now + 5 * 60_000L, now + 10 * 60_000L).inOrder()
    }

    @Test
    fun `一分钟间隔与倒计时使用传入时钟生成审批预览`() {
        val interval = scheduleApprovalPreview(
            json("""{"name":"轮询","prompt":"状态","trigger":{"type":"interval","every_minutes":1}}"""),
            now, zone, false, false,
        )!!
        assertThat(interval.invalid).isFalse()
        assertThat(interval.countdownMinutes).isNull()
        assertThat(interval.upcoming).containsExactly(now + 60_000L, now + 120_000L, now + 180_000L).inOrder()
        val once = scheduleApprovalPreview(
            json("""{"name":"提醒","prompt":"状态","trigger":{"type":"once","in_minutes":2}}"""),
            now + 30_987L, zone, false, false,
        )!!
        assertThat(once.trigger).isEqualTo(ScheduleTrigger.Once("2026-10-23T10:02:30", zone))
        assertThat(once.invalid).isFalse()
        assertThat(once.upcoming).containsExactly(now + 150_000L)
        assertThat(once.countdownMinutes).isEqualTo(2)
    }

    @Test
    fun `校验失败标记为无效，无法解析时不提供结构化预览`() {
        val past = scheduleApprovalPreview(
            json("""{"name":"提醒","prompt":"续费","trigger":{"type":"once","at":"2026-10-22T09:00"}}"""),
            now, zone, exactAlarmAvailable = false, canScheduleExact = false,
        )!!
        assertThat(past.invalid).isTrue()
        assertThat(past.upcoming).isEmpty()
        assertThat(past.countdownMinutes).isNull()
        assertThat(scheduleApprovalPreview(json("""{"name":"x"}"""), now, zone, false, false)).isNull()
    }

    @Test
    fun `仅 background 为真时给出后台运行预览并规范默认值`() {
        assertThat(backgroundRunPreview(json("""{"command":"ls"}"""), keepAliveAvailable = true)).isNull()
        assertThat(backgroundRunPreview(json("""{"command":"make","background":true}"""), keepAliveAvailable = false))
            .isEqualTo(BackgroundRunPreview(120, BackgroundJobExitAction.NOTIFY, keepAliveAvailable = false))
        assertThat(backgroundRunPreview(json("""{"background":true,"max_runtime_minutes":5000,"on_exit":"wake"}"""), true))
            .isEqualTo(BackgroundRunPreview(1_440, BackgroundJobExitAction.WAKE, keepAliveAvailable = true))
    }
}
