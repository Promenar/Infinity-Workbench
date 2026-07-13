package com.promenar.nexara.background.generation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.promenar.nexara.MainActivity
import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import com.promenar.nexara.navigation.AppIntentRouter

class GenerationNotificationFactory(
    context: Context,
    private val appIntentRouter: AppIntentRouter,
) {
    private val applicationContext = context.applicationContext
    private val notificationManager = applicationContext
        .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun create(snapshot: GenerationTaskSnapshot): Notification {
        ensureChannel()
        val stopPendingIntent = PendingIntent.getService(
            applicationContext,
            snapshot.taskId.hashCode(),
            stopIntent(snapshot.taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext,
            snapshot.taskId.hashCode(),
            openIntent(snapshot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.generation_notification_title))
            .setContentText(
                applicationContext.getString(snapshot.phase.notificationTextRes()) + " · " +
                    applicationContext.getString(
                        R.string.generation_notification_current_session,
                        snapshot.generatedChars,
                    ),
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                applicationContext.getString(R.string.generation_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    internal fun openIntent(snapshot: GenerationTaskSnapshot) =
        Intent(applicationContext, MainActivity::class.java).apply {
            val issued = appIntentRouter.issue(snapshot.taskId, snapshot.sessionId)
            action = AppIntentRouter.ACTION_OPEN_GENERATION
            putExtra(AppIntentRouter.EXTRA_SESSION_ID, issued.sessionId)
            putExtra(AppIntentRouter.EXTRA_REQUEST_ID, issued.requestId)
            data = Uri.parse("nexara://generation/open/${Uri.encode(snapshot.taskId)}")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    internal fun stopIntent(taskId: String) =
        Intent(applicationContext, GenerationForegroundService::class.java).apply {
            action = GenerationForegroundService.ACTION_STOP
            putExtra(GenerationForegroundService.EXTRA_TASK_ID, taskId)
            data = Uri.parse("nexara://generation/stop/${Uri.encode(taskId)}")
        }

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.generation_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.generation_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun GenerationPhase.notificationTextRes(): Int = when (this) {
        GenerationPhase.PREPARING,
        GenerationPhase.BUILDING_CONTEXT,
        GenerationPhase.CONNECTING -> R.string.generation_notification_preparing
        GenerationPhase.THINKING -> R.string.generation_notification_thinking
        GenerationPhase.STREAMING,
        GenerationPhase.POST_PROCESSING -> R.string.generation_notification_generating
        GenerationPhase.WAITING_APPROVAL,
        GenerationPhase.COMPLETED,
        GenerationPhase.FAILED,
        GenerationPhase.CANCELLED,
        GenerationPhase.PERSISTENCE_FAILED -> R.string.generation_notification_finishing
    }

    companion object {
        const val CHANNEL_ID = "generation_background"
        const val NOTIFICATION_ID = 0x4E58
    }
}
