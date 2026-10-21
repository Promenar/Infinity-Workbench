package com.promenar.nexara.background.subagent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.promenar.nexara.MainActivity
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.background.generation.GenerationNotificationFactory
import com.promenar.nexara.data.subagent.SubagentApproval
import com.promenar.nexara.data.subagent.SubagentApprovalNotifier
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * 子代理等待审批时，应用不在前台才发系统通知；回到前台由主会话的审批卡处理。
 * 点按通知回到对应会话所在的生成任务。
 */
class SubagentApprovalNotifications(
    private val app: NexaraApplication,
    private val generation: (String) -> GenerationTaskSnapshot?,
) : SubagentApprovalNotifier {
    private val pending = ConcurrentHashMap<String, SubagentApproval>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var observing = false

    override fun notify(approval: SubagentApproval) {
        pending[approval.id] = approval
        main.post {
            observeLifecycle()
            if (!isForeground()) post(approval)
        }
    }

    // 撤销与投递都在主线程按顺序执行，先处理完的审批不会再留下通知。
    override fun cancel(approval: SubagentApproval) {
        pending.remove(approval.id)
        main.post { NotificationManagerCompat.from(app).cancel(TAG, approval.id.hashCode()) }
    }

    private fun observeLifecycle() {
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                pending.values.forEach(::post)
            }
        })
    }

    private fun isForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun post(approval: SubagentApproval) {
        if (!pending.containsKey(approval.id)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = app.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, app.getString(R.string.subagent_approval_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = app.getString(R.string.subagent_approval_channel_description)
                },
            )
        }
        val intent = generation(approval.sessionId)?.let { GenerationNotificationFactory(app, app.appIntentRouter).openIntent(it) }
            ?: Intent(app, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val open = PendingIntent.getActivity(app, approval.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(app.getString(R.string.subagent_approval_notification_title))
            .setContentText(app.getString(R.string.subagent_approval_notification_text, approval.toolName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(TAG, approval.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // 用户撤销通知权限时只保留应用内审批卡。
        }
    }

    private companion object {
        const val CHANNEL_ID = "subagent_approval"
        const val TAG = "subagent_approval"
    }
}
