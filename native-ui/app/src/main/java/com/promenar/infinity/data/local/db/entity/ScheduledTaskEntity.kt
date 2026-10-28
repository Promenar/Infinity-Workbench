package com.promenar.infinity.data.local.db.entity

import androidx.room.*
import com.promenar.infinity.domain.schedule.*

@Entity(
    tableName = "scheduled_tasks",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id"), Index(value = ["job_id"], unique = true)],
)
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val name: String,
    val prompt: String,
    @ColumnInfo(name = "trigger_type") val triggerType: String,
    @ColumnInfo(name = "trigger_value") val triggerValue: String,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "exact_requested") val exactRequested: Boolean,
    @ColumnInfo(name = "exact_effective") val exactEffective: Boolean,
    val enabled: Boolean,
    @ColumnInfo(name = "next_fire_at") val nextFireAtMillis: Long?,
    @ColumnInfo(name = "run_count") val runCount: Int,
    @ColumnInfo(name = "max_runs") val maxRuns: Int?,
    @ColumnInfo(name = "expires_at") val expiresAtMillis: Long?,
    @ColumnInfo(name = "last_fired_at") val lastFiredAtMillis: Long?,
    @ColumnInfo(name = "last_result") val lastResult: String?,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "job_id") val jobId: Int,
    @ColumnInfo(name = "claim_token") val claimToken: String? = null,
    @ColumnInfo(name = "catch_up") val catchUp: Boolean = false,
    @ColumnInfo(name = "stop_reason") val stopReason: Int? = null,
    @ColumnInfo(name = "last_run_token") val lastRunToken: String? = null,
) {
    fun trigger(): ScheduleTrigger = when (triggerType) {
        "once" -> ScheduleTrigger.Once(triggerValue, zoneId)
        "interval" -> ScheduleTrigger.Interval(triggerValue.toInt())
        "cron" -> ScheduleTrigger.Cron(triggerValue, zoneId)
        else -> error("未知定时任务类型")
    }

    fun toDomain() = ScheduledTask(id, sessionId, name, prompt, trigger(), exactRequested, exactEffective,
        enabled, nextFireAtMillis, runCount, maxRuns, expiresAtMillis, lastFiredAtMillis,
        lastResult?.let { ScheduleRunResult.valueOf(it) }, createdAtMillis)
}
