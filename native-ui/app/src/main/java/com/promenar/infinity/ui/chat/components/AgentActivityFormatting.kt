package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.schedule.ScheduledTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 运行时长的紧凑写法，单位由界面文案决定。 */
internal data class CompactDuration(val hours: Long, val minutes: Long, val seconds: Long)

internal fun compactDuration(millis: Long): CompactDuration {
    val total = (millis.coerceAtLeast(0) / 1_000)
    return CompactDuration(total / 3_600, total % 3_600 / 60, total % 60)
}

/** 时间线上方的活动标签需要的汇总：运行中的任务数、最近结束的任务与最早的下次触发。 */
internal data class AgentActivitySummary(
    val runningJobs: Int,
    val latestFinishedJob: BackgroundJob?,
    val enabledSchedules: Int,
    val nextFireAtMillis: Long?,
) {
    val visible: Boolean get() = runningJobs > 0 || latestFinishedJob != null || enabledSchedules > 0
}

/** 结束超过 [recentWindowMillis] 的任务不再占用活动标签，仍可在面板中查看。 */
internal fun summarizeAgentActivity(
    jobs: List<BackgroundJob>,
    schedules: List<ScheduledTask>,
    nowMillis: Long,
    recentWindowMillis: Long = 10 * 60_000L,
): AgentActivitySummary {
    val running = jobs.count { !it.state.isTerminal }
    val latestFinished = if (running > 0) null else jobs
        .filter { it.state.isTerminal && (it.endedAtMillis ?: 0) >= nowMillis - recentWindowMillis }
        .maxByOrNull { it.endedAtMillis ?: 0 }
    val enabled = schedules.filter { it.enabled }
    return AgentActivitySummary(
        runningJobs = running,
        latestFinishedJob = latestFinished,
        enabledSchedules = enabled.size,
        nextFireAtMillis = enabled.mapNotNull { it.nextFireAtMillis }.minOrNull(),
    )
}

/** 下次触发在今天、明天还是更晚，决定只显示时刻还是带上日期。 */
internal enum class FireDay { TODAY, TOMORROW, LATER }

internal fun fireDay(fireAtMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): FireDay {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day: LocalDate = Instant.ofEpochMilli(fireAtMillis).atZone(zone).toLocalDate()
    return when (day) {
        today -> FireDay.TODAY
        today.plusDays(1) -> FireDay.TOMORROW
        else -> FireDay.LATER
    }
}

/** 固定间隔优先写成整小时或整天，读起来比“每 1440 分钟”自然。 */
internal sealed interface IntervalUnit {
    val value: Int
    data class Minutes(override val value: Int) : IntervalUnit
    data class Hours(override val value: Int) : IntervalUnit
    data class Days(override val value: Int) : IntervalUnit
}

internal fun intervalUnit(everyMinutes: Int): IntervalUnit = when {
    everyMinutes >= 1_440 && everyMinutes % 1_440 == 0 -> IntervalUnit.Days(everyMinutes / 1_440)
    everyMinutes >= 60 && everyMinutes % 60 == 0 -> IntervalUnit.Hours(everyMinutes / 60)
    else -> IntervalUnit.Minutes(everyMinutes)
}

/** 传输进度比例；总大小未知时返回 null（界面显示不定进度条），空文件视为已完成。 */
internal fun com.promenar.infinity.domain.jobs.SftpTransfer.progressFraction(): Float? {
    val total = totalBytes ?: return null
    if (total <= 0L) return 1f
    return (transferredBytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

/** 按当前速度估算的剩余毫秒；总大小或速度未知时返回 null。 */
internal fun com.promenar.infinity.domain.jobs.SftpTransfer.remainingMillis(): Long? {
    val total = totalBytes ?: return null
    val speed = bytesPerSecond?.takeIf { it > 0 } ?: return null
    val left = (total - transferredBytes).coerceAtLeast(0L)
    return if (left > Long.MAX_VALUE / 1_000L) null else left * 1_000L / speed
}
