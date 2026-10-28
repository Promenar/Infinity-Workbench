package com.promenar.infinity.background.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.promenar.infinity.MainActivity
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 用户终端与回环转发共用通知；服务不会自动重连或恢复已失效的转发状态。 */
class SshConnectionService : Service() {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stopping = false
    private var observedEpoch = -1L
    private var latestStartId = 0
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
        startForeground(NOTIFICATION_ID, notification())
        if (intent?.action == ACTION_STOP || intent == null) release()
        else if (!(application as InfinityApplication).sshRuntime.hasPersistentResources) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, SshConnectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.ssh_connections_title))
            .setContentText(getString(R.string.ssh_connections_body))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.ssh_connections_stop), stop)
            .build()
    }

    private fun release() {
        if (stopping) return
        stopping = true
        val expectedEpoch = observedEpoch
        val expectedStartId = latestStartId
        cleanupScope.launch {
            val runtime = (application as InfinityApplication).sshRuntime
            try { runtime.closePersistent(expectedEpoch) } finally { stopSelf(expectedStartId) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { release(); super.onTaskRemoved(rootIntent) }
    override fun onTimeout(startId: Int, fgsType: Int) { release(); stopSelf() }
    override fun onDestroy() {
        val runtime = (application as InfinityApplication).sshRuntime
        val expectedEpoch = observedEpoch
        cleanupScope.launch {
            try { runtime.closePersistent(expectedEpoch) } finally { cleanupScope.cancel() }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ssh_connections"
        private const val NOTIFICATION_ID = 8022
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
