package com.promenar.nexara.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.jobs.BackgroundJob
import com.promenar.nexara.domain.jobs.BackgroundJobExitAction
import com.promenar.nexara.domain.jobs.BackgroundJobRunner
import com.promenar.nexara.domain.jobs.BackgroundJobState
import com.promenar.nexara.domain.schedule.ScheduleTrigger
import com.promenar.nexara.domain.schedule.ScheduledTask
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class AgentActivityFormattingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 23, 23, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `运行中的任务优先，没有运行时只显示近期结束的最新一个`() {
        val running = job("a", BackgroundJobState.RUNNING, endedAt = null)
        val old = job("b", BackgroundJobState.EXITED, endedAt = now - 30 * 60_000)
        val recent = job("c", BackgroundJobState.EXITED, endedAt = now - 60_000)
        val recentest = job("d", BackgroundJobState.FAILED, endedAt = now - 1_000)

        val withRunning = summarizeAgentActivity(listOf(running, recent), emptyList(), now)
        assertThat(withRunning.runningJobs).isEqualTo(1)
        assertThat(withRunning.latestFinishedJob).isNull()

        val finishedOnly = summarizeAgentActivity(listOf(old, recent, recentest), emptyList(), now)
        assertThat(finishedOnly.latestFinishedJob?.id).isEqualTo("d")
        assertThat(summarizeAgentActivity(listOf(old), emptyList(), now).visible).isFalse()
    }

    @Test
    fun `只统计启用的定时任务并取最早的下次触发`() {
        val summary = summarizeAgentActivity(
            emptyList(),
            listOf(
                schedule("a", enabled = true, next = now + 3_600_000),
                schedule("b", enabled = true, next = now + 600_000),
                schedule("c", enabled = false, next = now + 60_000),
            ),
            now,
        )
        assertThat(summary.enabledSchedules).isEqualTo(2)
        assertThat(summary.nextFireAtMillis).isEqualTo(now + 600_000)
        assertThat(summary.visible).isTrue()
    }

    @Test
    fun `触发日期按本地日期区分今天、明天与更晚`() {
        assertThat(fireDay(now + 30 * 60_000, now, zone)).isEqualTo(FireDay.TODAY)
        assertThat(fireDay(now + 2 * 3_600_000, now, zone)).isEqualTo(FireDay.TOMORROW)
        assertThat(fireDay(now + 26 * 3_600_000, now, zone)).isEqualTo(FireDay.LATER)
    }

    @Test
    fun `间隔与时长换算`() {
        assertThat(intervalUnit(30)).isEqualTo(IntervalUnit.Minutes(30))
        assertThat(intervalUnit(90)).isEqualTo(IntervalUnit.Minutes(90))
        assertThat(intervalUnit(120)).isEqualTo(IntervalUnit.Hours(2))
        assertThat(intervalUnit(2_880)).isEqualTo(IntervalUnit.Days(2))
        assertThat(compactDuration(3_725_000)).isEqualTo(CompactDuration(1, 2, 5))
        assertThat(compactDuration(-5)).isEqualTo(CompactDuration(0, 0, 0))
    }

    private fun job(id: String, state: BackgroundJobState, endedAt: Long?) = BackgroundJob(
        id = id,
        sessionId = "s",
        runner = BackgroundJobRunner.SHELL,
        summary = "sleep 90",
        state = state,
        exitCode = null,
        startedAtMillis = now - 3_600_000,
        endedAtMillis = endedAt,
        maxRuntimeMinutes = 120,
        onExit = BackgroundJobExitAction.NOTIFY,
        logWorkspacePath = "jobs/$id.log",
        liveOutputKey = "job:$id",
    )

    private fun schedule(id: String, enabled: Boolean, next: Long) = ScheduledTask(
        id = id,
        sessionId = "s",
        name = id,
        prompt = "检查",
        trigger = ScheduleTrigger.Interval(30),
        exactRequested = false,
        exactEffective = false,
        enabled = enabled,
        nextFireAtMillis = next,
        runCount = 0,
        maxRuns = null,
        expiresAtMillis = null,
        lastFiredAtMillis = null,
        lastResult = null,
        createdAtMillis = now,
    )
}
