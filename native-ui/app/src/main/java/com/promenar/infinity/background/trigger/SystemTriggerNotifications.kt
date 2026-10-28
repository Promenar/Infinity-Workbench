package com.promenar.infinity.background.trigger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.promenar.infinity.R
import com.promenar.infinity.domain.trigger.SystemTurnOutcome
import com.promenar.infinity.domain.trigger.SystemTurnRequest
import com.promenar.infinity.navigation.SessionOpenIntentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SystemTriggerNotifications(
    private val context: Context,
    private val sessionOpen: SessionOpenIntentFactory,
    private val visibleSession: () -> String?,
) {
    suspend fun result(request: SystemTurnRequest, outcome: SystemTurnOutcome, reply: String?) {
        when (outcome) {
            SystemTurnOutcome.NeedsUser -> needsUser(request.sessionId, request.title)
            SystemTurnOutcome.Completed -> post(request.sessionId, context.getString(R.string.schedule_notification_completed, request.title),
                reply?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(80)
                    ?: context.getString(R.string.schedule_notification_reply), high = false)
            is SystemTurnOutcome.Failed -> failed(request.sessionId, request.title, outcome.message)
            is SystemTurnOutcome.Skipped -> Unit
        }
    }

    suspend fun failed(sessionId: String, title: String, reason: String) =
        post(sessionId, context.getString(R.string.schedule_notification_failed, title), reason.take(80), true)

    suspend fun needsUser(sessionId: String, title: String) = post(
        sessionId, context.getString(R.string.schedule_notification_needs_user, title),
        context.getString(R.string.schedule_notification_needs_user_text), true,
    )

    private suspend fun post(sessionId: String, title: String, text: String, high: Boolean) {
        val inSession = withContext(Dispatchers.Main.immediate) {
            visibleSession() == sessionId && ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (!high && inSession) return
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@withContext
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                context.getString(R.string.schedule_notification_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.schedule_notification_channel_description)
            })
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title.take(80)).setContentText(text)
                .setContentIntent(sessionOpen.create(sessionId, "trigger:$sessionId"))
                .setAutoCancel(true).setSilent(!high)
                .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            try { NotificationManagerCompat.from(context).notify("agent_trigger:$sessionId", 0, notification) }
            catch (_: SecurityException) { /* 撤销通知权限后，等待状态仍保存在会话中。 */ }
        }
    }

    companion object { const val CHANNEL_ID = "agent_trigger" }
}
