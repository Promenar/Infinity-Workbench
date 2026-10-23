package com.promenar.nexara.domain.schedule

import kotlinx.coroutines.flow.Flow

sealed interface ScheduleTrigger {
    /** 本地日期时间（ISO，不含时区）按 [zoneId] 解释。 */
    data class Once(val localDateTime: String, val zoneId: String) : ScheduleTrigger

    data class Interval(val everyMinutes: Int) : ScheduleTrigger

    /** 5 段 cron 表达式，按 [zoneId] 解释。 */
    data class Cron(val expression: String, val zoneId: String) : ScheduleTrigger
}

enum class ScheduleRunResult {
    COMPLETED,
    NEEDS_USER,
    SKIPPED_BUSY,
    SKIPPED_OTHER,
    /** 生成失败，或被系统在执行窗口结束时中断。 */
    FAILED,
}

data class ScheduledTask(
    val id: String,
    val sessionId: String,
    val name: String,
    val prompt: String,
    val trigger: ScheduleTrigger,
    /** 创建时要求精确调度。 */
    val exactRequested: Boolean,
    /** 当前实际按精确调度排期（渠道支持且已授权）。 */
    val exactEffective: Boolean,
    val enabled: Boolean,
    val nextFireAtMillis: Long?,
    val runCount: Int,
    val maxRuns: Int?,
    val expiresAtMillis: Long?,
    val lastFiredAtMillis: Long?,
    val lastResult: ScheduleRunResult?,
    val createdAtMillis: Long,
)

/** 界面使用的定时任务入口。 */
interface ScheduledTaskController {
    /** [sessionId] 为 null 时返回全部会话的定时任务。 */
    fun observe(sessionId: String?): Flow<List<ScheduledTask>>

    suspend fun pause(id: String)

    suspend fun resume(id: String)

    suspend fun delete(id: String)
}
