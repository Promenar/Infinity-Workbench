package com.promenar.nexara.background.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.provider.Settings
import com.promenar.nexara.data.local.db.entity.ScheduledTaskEntity
import com.promenar.nexara.data.schedule.ScheduleBackend
import com.promenar.nexara.distribution.DistributionCapabilities
import com.promenar.nexara.domain.schedule.ScheduleTrigger
import com.promenar.nexara.domain.schedule.ScheduleCalculation

fun canScheduleExact(context: Context): Boolean = DistributionCapabilities.exactAlarmAvailable &&
    context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

fun exactAlarmPermissionIntent(context: Context): Intent? = if (DistributionCapabilities.exactAlarmAvailable) {
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
} else null

class AndroidScheduleBackend(context: Context) : ScheduleBackend {
    private val context = context.applicationContext
    private val jobs = this.context.getSystemService(JobScheduler::class.java)
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    override fun canUseExact(): Boolean = canScheduleExact(context)

    override fun cancel(task: ScheduledTaskEntity) {
        jobs.cancel(task.jobId)
        alarms.cancel(alarmIntent(task))
    }

    override fun schedule(task: ScheduledTaskEntity) {
        val foregroundAt = task.nextFireAtMillis ?: return
        val next = ScheduleCalculation.backgroundFireAt(task.trigger(), foregroundAt, task.lastFiredAtMillis,
            task.createdAtMillis, task.exactEffective)
        if (!task.enabled || task.claimToken != null) return
        if (task.exactEffective && !canUseExact()) {
            ScheduleApplicationScope.launchReconcile(context)
            return
        }
        if (task.exactEffective) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(System.currentTimeMillis(), next), alarmIntent(task))
                return
            } catch (_: SecurityException) {
                // 权限可能在检查后撤销；交由控制器更新后台渠道并重新登记排期。
                ScheduleApplicationScope.launchReconcile(context)
                return
            }
        }
        val latency = (next - System.currentTimeMillis()).coerceAtLeast(0)
        val trigger = task.trigger()
        val periodic = trigger is ScheduleTrigger.Interval && task.runCount > 0 && !task.catchUp && latency > 0
        val builder = JobInfo.Builder(task.jobId, ComponentName(context, ScheduledTaskJobService::class.java))
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(PersistableBundle().apply { putString(EXTRA_TASK_ID, task.id); putBoolean(EXTRA_PERIODIC, periodic) })
        if (periodic) {
            builder.setPeriodic(maxOf(15, trigger.everyMinutes) * 60_000L)
        } else {
            builder.setMinimumLatency(latency).setOverrideDeadline(latency + 5 * 60_000L)
        }
        check(jobs.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) { "系统无法保存定时任务排期。" }
    }

    private fun alarmIntent(task: ScheduledTaskEntity): PendingIntent = PendingIntent.getBroadcast(
        context, task.jobId,
        Intent(context, ScheduledTaskAlarmReceiver::class.java).setAction(ACTION_FIRE)
            .setData(Uri.parse("nexara-schedule://task/${task.id}"))
            .putExtra(EXTRA_TASK_ID, task.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val EXTRA_TASK_ID = "scheduled_task_id"
        const val EXTRA_PERIODIC = "scheduled_task_periodic"
        const val ACTION_FIRE = "com.promenar.nexara.SCHEDULE_FIRE"
    }
}
