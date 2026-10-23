package com.promenar.nexara.background.exec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.promenar.nexara.MainActivity
import com.promenar.nexara.R
import com.promenar.nexara.data.exec.ExecNotificationModel
import com.promenar.nexara.data.exec.ExecRunKind
import com.promenar.nexara.data.exec.ExecRunRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 提供命令登记表的 Application；前台服务据此找到运行中的命令。 */
interface ExecRunRegistryOwner {
    val execRunRegistry: ExecRunRegistry
}

/**
 * 内置命令运行期间的前台服务（仅侧载渠道声明，类型 `specialUse`）：通知显示命令摘要与停止按钮，
 * 登记表清空即停止。服务不持有命令本身，进程被结束时命令随主进程结束。
 */
class ExecForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var registry: ExecRunRegistry
    private var observer: Job? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registry = (application as ExecRunRegistryOwner).execRunRegistry
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.exec_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.exec_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            intent.getStringArrayListExtra(EXTRA_RUN_IDS)?.takeIf { it.isNotEmpty() }?.let(registry::stop)
        }
        // startForegroundService 之后必须及时进入前台；即使命令已结束也先进入再停止。
        val promoted = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(registry.runs.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            true
        } catch (_: RuntimeException) {
            false
        }
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 命令在本次启动送达前已结束时，登记表不会再发出新值，需在此直接停止。
        if (registry.runs.value.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
        if (observer == null) {
            observer = scope.launch {
                registry.runs.collect { runs ->
                    if (runs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(latestStartId)
                    } else {
                        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(runs)) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        registry.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(runs: List<ExecRunRegistry.Active>): Notification {
        val model = ExecNotificationModel.from(runs)
        val latest = model.latest
        val backgroundCount = runs.count { it.info.background }
        val commandCount = runs.size - backgroundCount
        val title = if (backgroundCount > 0 && commandCount > 0) getString(R.string.jobs_mixed_running_title, backgroundCount, commandCount)
            else if (backgroundCount > 0) getString(R.string.jobs_running_title, backgroundCount)
            else if (model.count <= 1) getString(R.string.exec_notification_title) else getString(R.string.exec_notification_title_many, model.count)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.exec_notification_public))
            .build()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .apply {
                if (latest != null) {
                    val kind = getString(if (latest.kind == ExecRunKind.PYTHON) R.string.exec_notification_kind_python else R.string.exec_notification_kind_shell)
                    setContentText("$kind · ${latest.summary}")
                    setWhen(latest.startedAtMillis)
                    setUsesChronometer(true)
                    setShowWhen(true)
                }
                if (model.stopIds.isNotEmpty()) {
                    addAction(
                        0,
                        getString(if (model.count == 1) R.string.exec_notification_stop else R.string.exec_notification_stop_all),
                        PendingIntent.getService(
                            this@ExecForegroundService, 1, stopIntent(this@ExecForegroundService, model.stopIds),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "builtin_exec"
        const val NOTIFICATION_ID = 0x4E59
        const val ACTION_STOP = "com.promenar.nexara.exec.STOP"
        const val EXTRA_RUN_IDS = "run_ids"

        fun startIntent(context: Context) = Intent(context, ExecForegroundService::class.java)

        /** 只停止通知渲染时列出的命令；之后新启动的命令不受这次点击影响。 */
        fun stopIntent(context: Context, runIds: List<String>) =
            Intent(context, ExecForegroundService::class.java).apply {
                action = ACTION_STOP
                putStringArrayListExtra(EXTRA_RUN_IDS, ArrayList(runIds))
            }
    }
}
