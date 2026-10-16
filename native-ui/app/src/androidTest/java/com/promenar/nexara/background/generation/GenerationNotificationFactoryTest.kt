package com.promenar.nexara.background.generation

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.MainActivity
import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationError
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import com.promenar.nexara.navigation.AppIntentRouter
import org.junit.Test

class GenerationNotificationFactoryTest {
    @Test
    fun `通知与PendingIntent均不暴露prompt key或错误详情`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val factory = GenerationNotificationFactory(context, AppIntentRouter())
        val snapshot = GenerationTaskSnapshot(
            taskId = "task",
            sessionId = "session",
            assistantMessageId = "assistant",
            phase = GenerationPhase.PERSISTENCE_FAILED,
            generatedChars = 42,
            startedAt = 1L,
            error = GenerationError("sk-secret prompt"),
            progress = GenerationProgress(rounds = 3, calls = 7, tokens = 4096, estimated = true),
        )

        val notification = factory.create(snapshot)

        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertThat(text).doesNotContain("sk-secret")
        // 正文展示累计轮次、调用次数与 token（估算时带标记），不再展示已生成字符数。
        val expected = context.getString(R.string.p0_progress, 3, 7, 4096L, context.getString(R.string.p0_estimated))
        assertThat(text).isEqualTo(expected)
        assertThat(text).contains("4096")
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        assertThat(notification.priority).isEqualTo(Notification.PRIORITY_LOW)
        assertThat(notification.sound).isNull()
        assertThat(notification.contentIntent).isNotNull()
        assertThat(notification.actions).hasLength(1)
        assertThat(factory.openIntent(snapshot).component?.className)
            .isEqualTo(MainActivity::class.java.name)
        val stopIntent = factory.stopIntent(snapshot.taskId)
        val stopAction = notification.actions.single().actionIntent
        assertThat(stopAction.isService).isTrue()
        assertThat(stopAction.isBroadcast).isFalse()
        assertThat(stopIntent.component?.className)
            .isEqualTo(GenerationForegroundService::class.java.name)
    }

    @Test
    fun `通知渠道使用低重要性且不显示角标`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.deleteNotificationChannel(GenerationNotificationFactory.CHANNEL_ID)

        GenerationNotificationFactory(context, AppIntentRouter()).create(
            GenerationTaskSnapshot(
                taskId = "channel-task",
                sessionId = "channel-session",
                assistantMessageId = "channel-assistant",
                phase = GenerationPhase.STREAMING,
                generatedChars = 0,
                startedAt = 1L,
            ),
        )

        val channel = notificationManager.getNotificationChannel(
            GenerationNotificationFactory.CHANNEL_ID,
        )
        assertThat(channel).isNotNull()
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(channel.canShowBadge()).isFalse()
    }
}
