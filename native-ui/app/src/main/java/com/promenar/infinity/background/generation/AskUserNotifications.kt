package com.promenar.infinity.background.generation

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
import com.promenar.infinity.data.repository.AskUserLedgerRepository
import com.promenar.infinity.domain.ask.PendingQuestion
import com.promenar.infinity.navigation.SessionOpenIntentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** 沿用审批提醒的渠道与外观；会话令牌持久化，通知支持冷进程打开。 */
class AskUserNotifications(
    private val context: Context,
    private val repository: AskUserLedgerRepository,
    private val openSession: SessionOpenIntentFactory,
) {
    suspend fun observe() {
        val posted = mutableSetOf<String>()
        combine(repository.observeAllPending(), ProcessLifecycleOwner.get().lifecycle.currentStateFlow) { questions, state ->
            questions to state.isAtLeast(Lifecycle.State.STARTED)
        }.collect { (questions, foreground) ->
            val current = questions.mapTo(mutableSetOf(), ::key)
            (posted - current).forEach { NotificationManagerCompat.from(context).cancel(TAG, it.hashCode()); posted.remove(it) }
            if (!foreground) questions.forEach { question ->
                val id = key(question)
                if (id !in posted && post(question, id)) posted += id
            }
        }
    }

    private suspend fun post(question: PendingQuestion, id: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.subagent_approval_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.subagent_approval_channel_description)
            })
        val open = try { withContext(Dispatchers.IO) { openSession.create(question.sessionId, "ask_user:$id") } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { return false }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ask_user_notification_title))
            .setContentText(context.getString(R.string.ask_user_notification_text))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        return try { NotificationManagerCompat.from(context).notify(TAG, id.hashCode(), notification); true }
            catch (_: SecurityException) { false }
    }

    private fun key(question: PendingQuestion) = "${question.sessionId}:${question.assistantMessageId}:${question.toolCallId}"
    private companion object {
        const val CHANNEL_ID = "subagent_approval"
        const val TAG = "ask_user"
    }
}
