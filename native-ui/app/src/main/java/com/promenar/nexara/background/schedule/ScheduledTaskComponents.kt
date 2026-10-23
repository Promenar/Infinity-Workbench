package com.promenar.nexara.background.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.backup.BackupStartupState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** 广播完成后仍由应用持有恢复协程；开机路径只登记 Job/Alarm，不启动前台服务。 */
internal object ScheduleApplicationScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
        com.promenar.nexara.utils.NexaraLogger.diagnostic("schedule.callback_failure",
            mapOf("exception" to failure.javaClass.simpleName))
    })
    fun launchReconcile(context: Context) = scope.launch {
        val app = context.applicationContext as NexaraApplication
        app.startupState.first { it == BackupStartupState.Ready }
        app.scheduledTaskController.reconcile()
    }
}

class ScheduledTaskReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        ScheduleApplicationScope.scope.launch {
            try {
                withTimeout(8_000) { ScheduleApplicationScope.launchReconcile(context).join() }
            } finally { pending.finish() }
        }
    }
    companion object {
        private val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
    }
}

class ScheduledTaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidScheduleBackend.ACTION_FIRE) return
        val id = intent.getStringExtra(AndroidScheduleBackend.EXTRA_TASK_ID) ?: return
        val pending = goAsync()
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, ScheduledTaskAlarmService::class.java).putExtra(AndroidScheduleBackend.EXTRA_TASK_ID, id))
        } catch (_: IllegalStateException) {
            fallback(context, id)
        } catch (_: SecurityException) {
            fallback(context, id)
        } finally { pending.finish() }
    }
    private fun fallback(context: Context, id: String) {
        ScheduleApplicationScope.scope.launch {
            val app = context.applicationContext as NexaraApplication
            app.startupState.first { it == BackupStartupState.Ready }
            app.scheduledTaskController.fire(id)
        }
    }
}

class ScheduledTaskJobService : JobService() {
    private data class Execution(val token: String, val job: Job)
    private val running = mutableMapOf<Int, Execution>()
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(AndroidScheduleBackend.EXTRA_TASK_ID) ?: return false
        val token = java.util.UUID.randomUUID().toString()
        val job = ScheduleApplicationScope.scope.launch(start = CoroutineStart.LAZY) {
            val app = application as NexaraApplication
            var finished = false
            try {
                app.startupState.first { it == BackupStartupState.Ready }
                app.scheduledTaskController.fire(id, reschedule = false, executionToken = token)
            } finally {
                try {
                    withContext(Dispatchers.Main.immediate + NonCancellable) {
                        if (running[params.jobId]?.token == token) {
                            running.remove(params.jobId)
                            jobFinished(params, false)
                            finished = true
                        }
                    }
                } finally {
                    // 窗口撤回后执行仍可完成；快速恢复的任务也要在领取释放后重新排期。
                    withContext(NonCancellable) {
                        app.scheduledTaskController.reconcile(
                            if (finished && params.extras.getBoolean(AndroidScheduleBackend.EXTRA_PERIODIC)) id else null,
                        )
                    }
                }
            }
        }
        running[params.jobId] = Execution(token, job)
        job.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getString(AndroidScheduleBackend.EXTRA_TASK_ID) ?: return false
        val execution = running.remove(params.jobId) ?: return false
        // 主动撤排期只释放系统窗口；待发请求由控制器撤回，已发轮次保留真实结果。
        if (params.stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP) return false
        ScheduleApplicationScope.scope.launch {
            try {
                val app = application as NexaraApplication
                app.startupState.first { it == BackupStartupState.Ready }
                if (app.scheduledTaskController.stopped(id, params.stopReason, execution.token)) {
                    app.stopSystemTurnSource(id)
                    app.scheduledTaskController.find(id)?.let { task ->
                        app.systemTriggerNotifications.failed(task.sessionId, task.name, getString(R.string.schedule_interrupted))
                    }
                }
            } finally {
                execution.job.cancel()
            }
        }
        return false
    }
    override fun onDestroy() { running.clear(); super.onDestroy() }
}

/** 精确闹钟豁免仅用于用户批准的任务；广播迅速返回，生成存活期由服务持有。 */
class ScheduledTaskAlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Execution(val token: String, val job: Job)
    private val running = mutableMapOf<String, Execution>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(AndroidScheduleBackend.EXTRA_TASK_ID) ?: run { stopSelf(startId); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.schedule_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(getString(R.string.schedule_running))
            .setOngoing(true).build()
        try { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        catch (_: RuntimeException) {
            ScheduleApplicationScope.scope.launch {
                val app = application as NexaraApplication
                app.startupState.first { it == BackupStartupState.Ready }
                app.scheduledTaskController.fire(id)
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (id !in running) {
            val token = java.util.UUID.randomUUID().toString()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val app = application as NexaraApplication
                    app.startupState.first { it == BackupStartupState.Ready }
                    app.scheduledTaskController.fire(id, executionToken = token)
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        running.remove(id)
                        if (running.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    }
                }
            }
            running[id] = Execution(token, job)
            job.start()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        val executions = running.toMap()
        ScheduleApplicationScope.scope.launch {
            val app = application as NexaraApplication
            executions.forEach { (id, execution) ->
                if (app.scheduledTaskController.stopped(id, -1, execution.token)) {
                    app.stopSystemTurnSource(id)
                    app.scheduledTaskController.find(id)?.let { task ->
                        app.systemTriggerNotifications.failed(task.sessionId, task.name, getString(R.string.schedule_interrupted))
                    }
                }
                execution.job.cancel()
            }
        }
        stopSelf()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "schedule_execution"
        private const val NOTIFICATION_ID = 8044
    }
}
