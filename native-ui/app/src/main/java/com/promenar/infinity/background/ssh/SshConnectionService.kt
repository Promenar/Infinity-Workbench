package com.promenar.infinity.background.ssh

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
import androidx.core.content.ContextCompat
import com.promenar.infinity.MainActivity
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.data.ssh.SshConnectionKind
import com.promenar.infinity.data.ssh.SshRuntimeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** 用户终端、回环转发与 SSH 后台命令共用通知；服务不会自动重连。 */
class SshConnectionService : Service() {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var stopping = false
    private var observedEpoch = -1L
    private var latestStartId = 0
    private var observer: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ssh_connections_title), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.hasExtra(EXTRA_EPOCH) == true) {
            observedEpoch = intent.getLongExtra(EXTRA_EPOCH, -1L)
            stopping = false
        }
        val runtime = (application as InfinityApplication).sshRuntime
        val type = if (BuildConfig.DISTRIBUTION == "sideload") ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(runtime.activity.value), type) }
        catch (_: RuntimeException) { release(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) release(all = true)
        else if (intent == null) release()
        else if (!runtime.hasPersistentResources) stopSelf()
        if (observer == null && !stopping) observer = cleanupScope.launch {
            runtime.activity.collect { activity ->
                if (!stopping) runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(activity)) }
            }
        }
        return START_NOT_STICKY
    }

    private fun openRemote(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_REMOTE)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(activity: SshRuntimeActivity): Notification {
        val connections = activity.connections.filter { it.kind != SshConnectionKind.AGENT }
        val body = connections.map { connection ->
            getString(R.string.ssh_remote_connection_row, connection.alias, getString(when (connection.kind) {
                SshConnectionKind.TERMINAL -> R.string.ssh_remote_kind_terminal
                SshConnectionKind.FORWARD -> R.string.ssh_remote_kind_forward
                else -> R.string.ssh_remote_kind_job
            }))
        }.distinct().joinToString("\n")
        val open = openRemote()
        val stop = PendingIntent.getService(this, 1, Intent(this, SshConnectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.ssh_remote_connected_title, connections.map { it.alias }.distinct().size))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.ssh_remote_open), open)
            .addAction(0, getString(R.string.ssh_remote_disconnect_all), stop)
            .build()
    }

    private fun release(all: Boolean = false) {
        if (stopping) return
        stopping = true
        val expectedEpoch = observedEpoch
        val expectedStartId = latestStartId
        cleanupScope.launch {
            val runtime = (application as InfinityApplication).sshRuntime
            try { if (all) runtime.disconnectAll() else runtime.closePersistent(expectedEpoch) } finally { stopSelf(expectedStartId) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { release(); super.onTaskRemoved(rootIntent) }
    override fun onTimeout(startId: Int, fgsType: Int) {
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (BuildConfig.DISTRIBUTION == "play") runCatching {
            getSystemService(NotificationManager::class.java).notify(TIMEOUT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.ssh_remote_timeout_title))
                    .setContentText(getString(R.string.ssh_remote_timeout_body))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.ssh_remote_timeout_body)))
                    .setContentIntent(openRemote()).setAutoCancel(true).build())
        }
    }
    override fun onDestroy() {
        val runtime = (application as InfinityApplication).sshRuntime
        val expectedEpoch = observedEpoch
        observer?.cancel()
        cleanupScope.launch {
            try { runtime.closePersistent(expectedEpoch) } finally { cleanupScope.cancel() }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ssh_connections"
        private const val NOTIFICATION_ID = 8022
        private const val TIMEOUT_NOTIFICATION_ID = 8023
        const val ACTION_OPEN_REMOTE = "com.promenar.infinity.action.OPEN_SSH_REMOTE"
        private const val ACTION_STOP = "com.promenar.infinity.STOP_SSH"
        private const val EXTRA_EPOCH = "ssh_service_epoch"
        fun update(context: Context, active: Boolean) {
            val intent = Intent(context, SshConnectionService::class.java)
            if (active) {
                val runtime = (context.applicationContext as InfinityApplication).sshRuntime
                // 发起启动前登记代际，旧实例的延迟销毁不能关闭新请求的资源。
                intent.putExtra(EXTRA_EPOCH, runtime.registerForegroundService())
                ContextCompat.startForegroundService(context, intent)
            } else context.stopService(intent)
        }
    }
}
